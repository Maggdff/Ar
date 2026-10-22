package com.maggd.waipanalyzer.vpn

import com.maggd.waipanalyzer.core.PacketBuilder
import com.maggd.waipanalyzer.core.PacketParser
import com.maggd.waipanalyzer.core.ParsedPacket
import com.maggd.waipanalyzer.core.Transport
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Drives the engine with a fake TUN device against real loopback servers. */
class VpnEngineTest {
    private val client = byteArrayOf(10, 8, 0, 2)
    private val loop = byteArrayOf(127, 0, 0, 1)
    private val toApp = LinkedBlockingQueue<ByteArray>()
    private val fromApp = LinkedBlockingQueue<ByteArray>()
    private val captured = java.util.concurrent.atomic.AtomicInteger()

    private fun engine() = VpnEngine(
        tunRead = { buf ->
            val p = fromApp.poll(100, TimeUnit.MILLISECONDS)
            if (p == null) 0 else { System.arraycopy(p, 0, buf, 0, p.size); p.size }
        },
        tunWrite = { b, o, l -> toApp.add(b.copyOfRange(o, o + l)) },
        protectTcp = { true }, protectUdp = { true },
        onPacket = { _, _, _, _ -> captured.incrementAndGet() },
        clientIp = client
    )

    private fun next(pred: (ParsedPacket) -> Boolean = { true }): ParsedPacket {
        val end = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < end) {
            val raw = toApp.poll(200, TimeUnit.MILLISECONDS) ?: continue
            val p = PacketParser.parse(raw)!!
            if (pred(p)) { lastRaw = raw; return p }
        }
        throw AssertionError("timed out waiting for packet")
    }
    private var lastRaw = ByteArray(0)
    private fun payload(p: ParsedPacket) = lastRaw.copyOfRange(p.payloadOffset, p.payloadOffset + p.payloadLength)

    @Test fun tcpIsProxiedBothWays() {
        val server = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
        val srvThread = Thread {
            val s = server.accept()
            val buf = ByteArray(100)
            val n = s.getInputStream().read(buf)
            s.getOutputStream().write("echo:".toByteArray() + buf.copyOf(n))
            s.getOutputStream().flush()
            s.close()
        }.also { it.isDaemon = true; it.start() }
        val e = engine(); e.start()
        try {
            val port = server.localPort
            fromApp.add(PacketBuilder.tcp(client, loop, 40000, port, 1000, 0, PacketBuilder.SYN, 65535))
            val sa = next { it.transport == Transport.TCP }
            assertEquals(PacketBuilder.SYN or PacketBuilder.ACK, sa.tcpFlags)
            assertEquals(1001L, sa.ack)
            val ourNext = (sa.seq + 1) and 0xFFFFFFFFL
            val data = "hello".toByteArray()
            fromApp.add(PacketBuilder.tcp(client, loop, 40000, port, 1001, ourNext, PacketBuilder.PSH or PacketBuilder.ACK, 65535, data, 0, data.size))
            val reply = next { it.payloadLength > 0 }
            assertEquals("echo:hello", String(payload(reply)))
            assertEquals(1006L, reply.ack)
            // server closes -> FIN arrives
            val fin = next { (it.tcpFlags and PacketBuilder.FIN) != 0 }
            assertNotNull(fin)
            assertTrue(captured.get() >= 4)
        } finally { e.close(); server.close(); srvThread.join(1000) }
    }

    @Test fun closedPortGetsReset() {
        val tmp = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")); val port = tmp.localPort; tmp.close()
        val e = engine(); e.start()
        try {
            fromApp.add(PacketBuilder.tcp(client, loop, 40001, port, 5, 0, PacketBuilder.SYN, 65535))
            val r = next { it.transport == Transport.TCP }
            assertTrue((r.tcpFlags and PacketBuilder.RST) != 0)
        } finally { e.close() }
    }

    @Test fun udpKeepsOneSocketPerLocalPort() {
        val echo = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val t = Thread {
            val b = ByteArray(100)
            repeat(2) {
                val d = DatagramPacket(b, b.size)
                echo.receive(d)
                echo.send(DatagramPacket(d.data, d.length, d.socketAddress))
            }
        }.also { it.isDaemon = true; it.start() }
        val e = engine(); e.start()
        try {
            val msg = byteArrayOf(9, 8, 7)
            repeat(2) { fromApp.add(PacketBuilder.udp(client, loop, 50000, echo.localPort, msg, 0, 3)) }
            val a = next { it.transport == Transport.UDP }
            assertEquals(echo.localPort, a.srcPort)
            assertEquals(50000, a.dstPort)
            assertEquals(3, a.payloadLength)
            next { it.transport == Transport.UDP }
        } finally { e.close(); echo.close(); t.join(1000) }
    }
}
