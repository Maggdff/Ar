package com.maggd.waipanalyzer.vpn

import com.maggd.waipanalyzer.core.PacketBuilder
import com.maggd.waipanalyzer.core.PacketParser
import com.maggd.waipanalyzer.core.ParsedPacket
import com.maggd.waipanalyzer.core.Transport
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.CancelledKeyException
import java.nio.channels.ClosedSelectorException
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * User-space forwarder for the packets that arrive on the VPN TUN interface.
 *
 * It terminates the app's TCP connections and re-opens them from real (protected) sockets, and relays UDP
 * datagrams through one socket per local port so that peer-to-peer NAT behaviour is preserved. It has no
 * Android dependency (the service injects the TUN I/O and the socket-protect callbacks), which makes it testable on a JVM.
 *
 * IPv4 only. All packets - in both directions - are reported to [onPacket] for analysis.
 */
class VpnEngine(
    private val tunRead: (ByteArray) -> Int,
    private val tunWrite: (ByteArray, Int, Int) -> Unit,
    private val protectTcp: (Socket) -> Boolean,
    private val protectUdp: (DatagramSocket) -> Boolean,
    private val onPacket: (Long, ByteArray, Int, Int) -> Unit,
    private val clientIp: ByteArray = byteArrayOf(10, 8, 0, 2),
    private val log: (String) -> Unit = {}
) : AutoCloseable {

    private enum class St { CONNECTING, ESTABLISHED }

    private inner class TcpConn(
        val clientPort: Int, val remoteIp: ByteArray, val remotePort: Int, val ch: SocketChannel,
        val clientIsn: Long, val ourIsn: Long, val wscale: Int
    ) {
        var key: SelectionKey? = null
        var state = St.CONNECTING
        var ourSeq = add(ourIsn, 1)             // next sequence number we will send
        var clientAcked = add(ourIsn, 1)        // highest ack received from the app
        var flushedNext = add(clientIsn, 1)     // everything below this has been delivered to the remote socket
        var clientNext = add(clientIsn, 1)      // next byte we expect from the app
        var window = 65535L
        val pending = ArrayDeque<ByteBuffer>()
        var pendingBytes = 0
        var clientFinSeen = false
        var finShutdown = false
        var remoteEof = false
        var finSent = false
        var last = System.currentTimeMillis()
    }

    private class UdpSess(val port: Int, val ch: DatagramChannel) {
        var last = System.currentTimeMillis()
    }

    @Volatile private var running = false
    private var selector: Selector? = null
    private val queue = ConcurrentLinkedQueue<ByteArray>()
    private val tcp = HashMap<String, TcpConn>()
    private val udp = HashMap<Int, UdpSess>()
    private val tunLock = Any()
    private var readerThread: Thread? = null
    private var ioThread: Thread? = null
    private val rnd = java.security.SecureRandom()

    @Synchronized
    fun start() {
        if (running) return
        selector = Selector.open()
        running = true
        ioThread = Thread({ ioLoop() }, "vpn-io").also { it.isDaemon = true; it.start() }
        readerThread = Thread({ readLoop() }, "vpn-tun-reader").also { it.isDaemon = true; it.start() }
    }

    /** Stops the engine. The caller should also close the TUN descriptor so the blocking read returns. */
    @Synchronized
    override fun close() {
        running = false
        try { selector?.wakeup() } catch (_: Exception) {}
        ioThread?.let { if (it !== Thread.currentThread()) it.join(2000) }
        try { selector?.close() } catch (_: Exception) {}
        readerThread?.interrupt()
        ioThread = null; readerThread = null
    }

    // ---------------------------------------------------------------- reader thread

    private fun readLoop() {
        val buf = ByteArray(65535)
        while (running) {
            val n = try { tunRead(buf) } catch (e: Exception) { -1 }
            if (n < 0) break
            if (n == 0) continue
            val copy = buf.copyOf(n)
            try { onPacket(nowMicros(), copy, 0, n) } catch (_: Exception) {}
            queue.add(copy)
            try { selector?.wakeup() } catch (_: Exception) {}
        }
    }

    // ---------------------------------------------------------------- IO thread

    private fun ioLoop() {
        var lastClean = System.currentTimeMillis()
        val sel = selector ?: return
        while (running) {
            try {
                var count = 0
                var p = queue.poll()
                while (p != null) {
                    handlePacket(p)
                    if (++count >= 512) break
                    p = queue.poll()
                }
                sel.select(if (queue.isEmpty()) 250 else 1)
                val it = sel.selectedKeys().iterator()
                while (it.hasNext()) {
                    val k = it.next()
                    it.remove()
                    if (!k.isValid) continue
                    dispatch(k)
                }
                val now = System.currentTimeMillis()
                if (now - lastClean > 5000) { lastClean = now; cleanup(now) }
            } catch (e: ClosedSelectorException) {
                break
            } catch (e: CancelledKeyException) {
                // key closed concurrently with its own handler; ignore
            } catch (e: Exception) {
                log("io loop: $e")
            }
        }
        closeAll()
    }

    private fun dispatch(k: SelectionKey) {
        when (val a = k.attachment()) {
            is TcpConn -> try {
                if (k.isValid && k.isConnectable) onConnect(a)
                if (k.isValid && k.isReadable) readRemote(a)
                if (k.isValid && k.isWritable) flush(a)
            } catch (e: IOException) {
                reset(a)
            } catch (e: CancelledKeyException) {
                remove(a)
            }
            is UdpSess -> try { readUdp(a) } catch (e: IOException) { closeUdp(a) }
        }
    }

    private fun handlePacket(raw: ByteArray) {
        val p = PacketParser.parse(raw, 0, raw.size) ?: return
        if (p.version != 4 || p.fragment) return
        when (p.transport) {
            Transport.UDP -> handleUdp(p, raw)
            Transport.TCP -> handleTcp(p, raw)
            else -> {}
        }
    }

    // ---------------------------------------------------------------- UDP

    private fun handleUdp(p: ParsedPacket, raw: ByteArray) {
        var s = udp[p.srcPort]
        if (s == null) {
            if (udp.size >= 512) return
            val ch = DatagramChannel.open()
            try {
                ch.configureBlocking(false)
                protectUdp(ch.socket())
                ch.socket().bind(null)
                s = UdpSess(p.srcPort, ch)
                ch.register(selector, SelectionKey.OP_READ, s)
            } catch (e: Exception) {
                try { ch.close() } catch (_: Exception) {}
                return
            }
            udp[p.srcPort] = s
        }
        s.last = System.currentTimeMillis()
        try {
            s.ch.send(
                ByteBuffer.wrap(raw, p.payloadOffset, p.payloadLength),
                InetSocketAddress(InetAddress.getByAddress(p.dst), p.dstPort)
            )
        } catch (e: IOException) {
            // unreachable network etc.: UDP is best effort
        }
    }

    private fun readUdp(s: UdpSess) {
        val buf = ByteBuffer.allocate(65535)
        repeat(32) {
            buf.clear()
            val from = s.ch.receive(buf) as? InetSocketAddress ?: return
            val addr = from.address?.address ?: return
            if (addr.size != 4) return
            s.last = System.currentTimeMillis()
            emit(PacketBuilder.udp(addr, clientIp, from.port, s.port, buf.array(), 0, buf.position()))
        }
    }

    private fun closeUdp(s: UdpSess) {
        udp.remove(s.port)
        try { s.ch.close() } catch (_: Exception) {}
    }

    // ---------------------------------------------------------------- TCP

    private fun tcpKey(port: Int, ip: ByteArray, rport: Int) = "$port>${ip.joinToString(".") { (it.toInt() and 255).toString() }}:$rport"

    private fun windowOf(raw: ByteArray, conn: TcpConn?): Long {
        val tcpOff = (raw[0].toInt() and 0x0F) * 4
        val w = PacketParser.u16(raw, tcpOff + 14).toLong()
        return w shl (conn?.wscale ?: 0)
    }

    private fun wscaleOf(raw: ByteArray, p: ParsedPacket): Int {
        var i = (raw[0].toInt() and 0x0F) * 4 + 20
        val end = p.payloadOffset
        while (i < end) {
            val kind = raw[i].toInt() and 0xFF
            if (kind == 0) break
            if (kind == 1) { i++; continue }
            if (i + 1 >= end) break
            val l = raw[i + 1].toInt() and 0xFF
            if (l < 2) break
            if (kind == 3 && l == 3 && i + 2 < end) return minOf(raw[i + 2].toInt() and 0xFF, 14)
            i += l
        }
        return 0
    }

    private fun handleTcp(p: ParsedPacket, raw: ByteArray) {
        val key = tcpKey(p.srcPort, p.dst, p.dstPort)
        var c = tcp[key]
        val f = p.tcpFlags
        if ((f and PacketBuilder.RST) != 0) { c?.let { remove(it) }; return }

        if ((f and PacketBuilder.SYN) != 0 && (f and PacketBuilder.ACK) == 0) {
            if (c != null) { if (c.state == St.ESTABLISHED && p.seq == c.clientIsn) sendSynAck(c); return }
            if (tcp.size >= 1024) return
            val ch = SocketChannel.open()
            try {
                ch.configureBlocking(false)
                protectTcp(ch.socket())
                val conn = TcpConn(p.srcPort, p.dst, p.dstPort, ch, p.seq, rnd.nextInt().toLong() and 0xFFFFFFFFL, wscaleOf(raw, p))
                conn.window = windowOf(raw, null)
                tcp[key] = conn
                val connected = ch.connect(InetSocketAddress(InetAddress.getByAddress(p.dst), p.dstPort))
                conn.key = ch.register(selector, if (connected) SelectionKey.OP_READ else SelectionKey.OP_CONNECT, conn)
                if (connected) onConnect(conn, alreadyConnected = true)
            } catch (e: Exception) {
                try { ch.close() } catch (_: Exception) {}
                tcp.remove(key)
                rstFor(p)
            }
            return
        }

        if (c == null) { rstFor(p); return }
        c.last = System.currentTimeMillis()
        if (c.state != St.ESTABLISHED) return
        try {
            if ((f and PacketBuilder.ACK) != 0) {
                c.window = windowOf(raw, c)
                val inflight = sub(c.ourSeq, p.ack)
                if (inflight <= sub(c.ourSeq, c.clientAcked) && inflight < (1L shl 31)) c.clientAcked = p.ack
                if (c.finSent && c.clientAcked == c.ourSeq && c.clientFinSeen && c.pending.isEmpty()) { remove(c); return }
                updateInterest(c)
            }
            if (p.payloadLength > 0) {
                if (p.seq == c.clientNext) {
                    if (c.pendingBytes + p.payloadLength <= MAX_PENDING) {
                        c.pending.add(ByteBuffer.wrap(raw.copyOfRange(p.payloadOffset, p.payloadOffset + p.payloadLength)))
                        c.pendingBytes += p.payloadLength
                        c.clientNext = add(c.clientNext, p.payloadLength.toLong())
                        flush(c)
                    }
                    // else: drop; the app retransmits once we have flushed (back-pressure)
                } else {
                    sendAck(c)
                }
            }
            if ((f and PacketBuilder.FIN) != 0 && !c.clientFinSeen && add(p.seq, p.payloadLength.toLong()) == c.clientNext) {
                c.clientFinSeen = true
                c.clientNext = add(c.clientNext, 1)
                flush(c)
            }
        } catch (e: IOException) {
            reset(c)
        }
    }

    private fun onConnect(c: TcpConn, alreadyConnected: Boolean = false) {
        if (!alreadyConnected && !c.ch.finishConnect()) return
        c.state = St.ESTABLISHED
        sendSynAck(c)
        updateInterest(c)
    }

    private fun sendSynAck(c: TcpConn) {
        emit(PacketBuilder.tcp(c.remoteIp, clientIp, c.remotePort, c.clientPort, c.ourIsn, c.flushedNext, PacketBuilder.SYN or PacketBuilder.ACK, 65535, mss = MSS))
    }

    private fun sendAck(c: TcpConn) {
        emit(PacketBuilder.tcp(c.remoteIp, clientIp, c.remotePort, c.clientPort, c.ourSeq, c.flushedNext, PacketBuilder.ACK, 65535))
    }

    /** Writes buffered app data to the real socket; ACKs the app only for bytes the socket accepted. */
    private fun flush(c: TcpConn) {
        var progressed = false
        while (true) {
            val b = c.pending.peek() ?: break
            val n = c.ch.write(b)
            if (n > 0) {
                progressed = true
                c.pendingBytes -= n
                c.flushedNext = add(c.flushedNext, n.toLong())
            }
            if (b.hasRemaining()) break
            c.pending.poll()
        }
        if (c.pending.isEmpty() && c.clientFinSeen && !c.finShutdown) {
            c.finShutdown = true
            c.flushedNext = add(c.flushedNext, 1)
            try { c.ch.socket().shutdownOutput() } catch (_: IOException) {}
            progressed = true
        }
        if (progressed) sendAck(c)
        updateInterest(c)
    }

    private fun readRemote(c: TcpConn) {
        if (c.state != St.ESTABLISHED || c.remoteEof) return
        repeat(16) {
            val room = minOf(MSS.toLong(), c.window - sub(c.ourSeq, c.clientAcked)).toInt()
            if (room <= 0) { updateInterest(c); return }
            val buf = ByteBuffer.allocate(room)
            val n = c.ch.read(buf)
            if (n < 0) {
                c.remoteEof = true
                c.finSent = true
                emit(PacketBuilder.tcp(c.remoteIp, clientIp, c.remotePort, c.clientPort, c.ourSeq, c.flushedNext, PacketBuilder.FIN or PacketBuilder.ACK, 65535))
                c.ourSeq = add(c.ourSeq, 1)
                updateInterest(c)
                return
            }
            if (n == 0) return
            emit(PacketBuilder.tcp(c.remoteIp, clientIp, c.remotePort, c.clientPort, c.ourSeq, c.flushedNext, PacketBuilder.PSH or PacketBuilder.ACK, 65535, buf.array(), 0, n))
            c.ourSeq = add(c.ourSeq, n.toLong())
        }
        updateInterest(c)
    }

    private fun updateInterest(c: TcpConn) {
        val k = c.key ?: return
        if (!k.isValid) return
        var ops = 0
        if (c.state == St.CONNECTING) ops = SelectionKey.OP_CONNECT
        else {
            if (!c.remoteEof && c.window - sub(c.ourSeq, c.clientAcked) > 0) ops = ops or SelectionKey.OP_READ
            if (c.pending.isNotEmpty()) ops = ops or SelectionKey.OP_WRITE
        }
        if (k.interestOps() != ops) k.interestOps(ops)
    }

    private fun rstFor(p: ParsedPacket) {
        if ((p.tcpFlags and PacketBuilder.RST) != 0) return
        val pkt = if ((p.tcpFlags and PacketBuilder.ACK) != 0)
            PacketBuilder.tcp(p.dst, p.src, p.dstPort, p.srcPort, p.ack, 0, PacketBuilder.RST, 0)
        else {
            val len = p.payloadLength + (if ((p.tcpFlags and PacketBuilder.SYN) != 0) 1 else 0) + (if ((p.tcpFlags and PacketBuilder.FIN) != 0) 1 else 0)
            PacketBuilder.tcp(p.dst, p.src, p.dstPort, p.srcPort, 0, add(p.seq, len.toLong()), PacketBuilder.RST or PacketBuilder.ACK, 0)
        }
        emit(pkt)
    }

    private fun reset(c: TcpConn) {
        emit(PacketBuilder.tcp(c.remoteIp, clientIp, c.remotePort, c.clientPort, c.ourSeq, c.flushedNext, PacketBuilder.RST or PacketBuilder.ACK, 0))
        remove(c)
    }

    private fun remove(c: TcpConn) {
        tcp.remove(tcpKey(c.clientPort, c.remoteIp, c.remotePort))
        try { c.key?.cancel() } catch (_: Exception) {}
        try { c.ch.close() } catch (_: Exception) {}
    }

    private fun cleanup(now: Long) {
        tcp.values.filter { now - it.last > TCP_IDLE_MS }.forEach { remove(it) }
        udp.values.filter { now - it.last > UDP_IDLE_MS }.forEach { closeUdp(it) }
    }

    private fun closeAll() {
        tcp.values.toList().forEach { remove(it) }
        udp.values.toList().forEach { closeUdp(it) }
    }

    /** Sends a packet to the app through the TUN interface (and reports it to the analyser). */
    private fun emit(pkt: ByteArray) {
        try { onPacket(nowMicros(), pkt, 0, pkt.size) } catch (_: Exception) {}
        try {
            synchronized(tunLock) { tunWrite(pkt, 0, pkt.size) }
        } catch (e: IOException) {
            log("tun write: $e")
        }
    }

    private fun nowMicros() = System.currentTimeMillis() * 1000L

    companion object {
        const val MSS = 1400
        private const val MAX_PENDING = 256 * 1024
        private const val TCP_IDLE_MS = 120_000L
        private const val UDP_IDLE_MS = 60_000L
        private const val M = 0xFFFFFFFFL
        private fun add(a: Long, n: Long) = (a + n) and M
        private fun sub(a: Long, b: Long) = (a - b) and M
    }
}
