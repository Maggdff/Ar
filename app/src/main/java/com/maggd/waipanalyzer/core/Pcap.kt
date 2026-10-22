package com.maggd.waipanalyzer.core
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
class PcapResult(val format: String, val packets: Long, val unsupported: Long, val truncated: Boolean)
fun interface PacketCallback {
    /** [data] is a reusable buffer: the raw IP packet is data[off, off+len). Return false to stop reading. */
    fun onPacket(tsMicros: Long, data: ByteArray, off: Int, len: Int): Boolean
}
/** Streaming reader for classic PCAP (both endiannesses, µs/ns) and PCAPNG files. */
object PcapReader {
    private const val MAX_PACKET = 1 shl 24
    private const val MAX_BLOCK = 1 shl 26
    fun read(input: InputStream, cb: PacketCallback): PcapResult {
        val pb = PushbackInputStream(input, 4)
        val magic = ByteArray(4)
        if (readFully(pb, magic, 4) < 4) throw IllegalArgumentException("Empty or too short file")
        pb.unread(magic)
        val m = (PacketParser.u32(magic, 0))
        return when (m) {
            0x0A0D0D0AL -> readNg(pb, cb)
            0xA1B2C3D4L -> readClassic(pb, cb, false, false)
            0xD4C3B2A1L -> readClassic(pb, cb, true, false)
            0xA1B23C4DL -> readClassic(pb, cb, false, true)
            0x4D3CB2A1L -> readClassic(pb, cb, true, true)
            else -> throw IllegalArgumentException("Not a PCAP/PCAPNG file")
        }
    }
    private fun readFully(i: InputStream, b: ByteArray, n: Int): Int {
        var t = 0
        while (t < n) {
            val r = i.read(b, t, n - t)
            if (r < 0) break
            t += r
        }
        return t
    }
    private fun skip(i: InputStream, n: Long): Boolean {
        var left = n
        val tmp = ByteArray(8192)
        while (left > 0) {
            val r = i.read(tmp, 0, minOf(left, tmp.size.toLong()).toInt())
            if (r < 0) return false
            left -= r
        }
        return true
    }
    private fun u32(b: ByteArray, i: Int, le: Boolean): Long =
        if (le) (b[i].toLong() and 255) or ((b[i + 1].toLong() and 255) shl 8) or
            ((b[i + 2].toLong() and 255) shl 16) or ((b[i + 3].toLong() and 255) shl 24)
        else PacketParser.u32(b, i)
    private fun u16(b: ByteArray, i: Int, le: Boolean): Int =
        if (le) (b[i].toInt() and 255) or ((b[i + 1].toInt() and 255) shl 8) else PacketParser.u16(b, i)
    /** Offset of the IP header inside a link-layer frame, or -1 when the frame is not IP / link type unsupported. */
    fun ipOffset(link: Int, d: ByteArray, len: Int): Int {
        fun ver(o: Int) = if (o < len && (d[o].toInt() shr 4 and 0xF).let { it == 4 || it == 6 }) o else -1
        return when (link) {
            1 -> {
                if (len < 14) return -1
                var o = 12
                var et = PacketParser.u16(d, o)
                var tags = 0
                while ((et == 0x8100 || et == 0x88A8 || et == 0x9100) && tags < 3) {
                    o += 4; tags++
                    if (o + 2 > len) return -1
                    et = PacketParser.u16(d, o)
                }
                if (et == 0x0800 || et == 0x86DD) ver(o + 2) else -1
            }
            0, 108 -> if (len > 4) ver(4) else -1
            101, 12, 14, 228, 229 -> ver(0)
            113 -> if (len > 16 && PacketParser.u16(d, 14).let { it == 0x0800 || it == 0x86DD }) ver(16) else -1
            276 -> if (len > 20 && PacketParser.u16(d, 0).let { it == 0x0800 || it == 0x86DD }) ver(20) else -1
            else -> -1
        }
    }
    private fun readClassic(i: InputStream, cb: PacketCallback, le: Boolean, nanos: Boolean): PcapResult {
        val gh = ByteArray(24)
        if (readFully(i, gh, 24) < 24) return PcapResult("pcap", 0, 0, true)
        val link = (u32(gh, 20, le) and 0x0FFFFFFF).toInt()
        val rh = ByteArray(16)
        var buf = ByteArray(65536)
        var n = 0L
        var unsupported = 0L
        while (true) {
            val r = readFully(i, rh, 16)
            if (r == 0) return PcapResult("pcap", n, unsupported, false)
            if (r < 16) return PcapResult("pcap", n, unsupported, true)
            val sec = u32(rh, 0, le)
            val frac = u32(rh, 4, le)
            val incl = u32(rh, 8, le)
            if (incl > MAX_PACKET) return PcapResult("pcap", n, unsupported, true)
            if (buf.size < incl) buf = ByteArray(incl.toInt())
            if (readFully(i, buf, incl.toInt()) < incl) return PcapResult("pcap", n, unsupported, true)
            n++
            val o = ipOffset(link, buf, incl.toInt())
            if (o < 0) { unsupported++; continue }
            val ts = sec * 1_000_000L + if (nanos) frac / 1000 else frac
            if (!cb.onPacket(ts, buf, o, incl.toInt() - o)) return PcapResult("pcap", n, unsupported, false)
        }
    }
    private class Iface(val link: Int, val ticksPerSec: Double)
    private fun readNg(i: InputStream, cb: PacketCallback): PcapResult {
        var le = true
        val ifaces = ArrayList<Iface>()
        val hdr = ByteArray(8)
        var buf = ByteArray(65536)
        var n = 0L
        var unsupported = 0L
        while (true) {
            val r = readFully(i, hdr, 8)
            if (r == 0) return PcapResult("pcapng", n, unsupported, false)
            if (r < 8) return PcapResult("pcapng", n, unsupported, true)
            if (PacketParser.u32(hdr, 0) == 0x0A0D0D0AL) {
                val bom = ByteArray(4)
                if (readFully(i, bom, 4) < 4) return PcapResult("pcapng", n, unsupported, true)
                le = PacketParser.u32(bom, 0) == 0x4D3C2B1AL
                if (!le && PacketParser.u32(bom, 0) != 0x1A2B3C4DL) throw IllegalArgumentException("Bad PCAPNG byte-order magic")
                val total = u32(hdr, 4, le)
                if (total < 12 || total > MAX_BLOCK || !skip(i, total - 12)) return PcapResult("pcapng", n, unsupported, true)
                ifaces.clear()
                continue
            }
            val type = u32(hdr, 0, le)
            val total = u32(hdr, 4, le)
            if (total < 12 || total > MAX_BLOCK) return PcapResult("pcapng", n, unsupported, true)
            val bodyLen = (total - 8).toInt() // includes trailing length field
            if (buf.size < bodyLen) buf = ByteArray(bodyLen)
            if (readFully(i, buf, bodyLen) < bodyLen) return PcapResult("pcapng", n, unsupported, true)
            val body = bodyLen - 4
            when (type) {
                1L -> if (body >= 8) {
                    var res = 1_000_000.0
                    var p = 8
                    while (p + 4 <= body) {
                        val code = u16(buf, p, le)
                        val l = u16(buf, p + 2, le)
                        if (code == 0) break
                        if (code == 9 && l >= 1 && p + 4 < body) {
                            val v = buf[p + 4].toInt() and 0xFF
                            res = if (v and 0x80 == 0) Math.pow(10.0, v.toDouble()) else Math.pow(2.0, (v and 0x7F).toDouble())
                        }
                        p += 4 + ((l + 3) and 3.inv())
                    }
                    ifaces.add(Iface(u16(buf, 0, le), res))
                }
                6L, 2L -> if (body >= 20) {
                    val id = if (type == 6L) u32(buf, 0, le).toInt() else u16(buf, 0, le)
                    val ticks = (u32(buf, 4, le) shl 32) or u32(buf, 8, le)
                    val cap = u32(buf, 12, le).toInt()
                    if (cap < 0 || 20 + cap > body) continue
                    n++
                    val itf = ifaces.getOrNull(id)
                    if (itf == null) { unsupported++; continue }
                    val o = ipOffset(itf.link, buf.copyOfRange(20, 20 + cap), cap)
                    if (o < 0) { unsupported++; continue }
                    val ts = (ticks * (1_000_000.0 / itf.ticksPerSec)).toLong()
                    if (!cb.onPacket(ts, buf, 20 + o, cap - o)) return PcapResult("pcapng", n, unsupported, false)
                }
                3L -> if (body >= 4) {
                    val itf = ifaces.getOrNull(0)
                    val cap = minOf(u32(buf, 0, le).toInt(), body - 4)
                    n++
                    if (itf == null) { unsupported++; continue }
                    val o = ipOffset(itf.link, buf.copyOfRange(4, 4 + cap), cap)
                    if (o < 0) { unsupported++; continue }
                    if (!cb.onPacket(0, buf, 4 + o, cap - o)) return PcapResult("pcapng", n, unsupported, false)
                }
                else -> {}
            }
        }
    }
}
/** Writes classic PCAP with LINKTYPE_RAW (101). Packets are truncated to [snaplen] to limit stored payload. */
class PcapWriter(out: OutputStream, private val snaplen: Int) : AutoCloseable {
    private val o = BufferedOutputStream(out, 1 shl 16)
    private val hdr = ByteArray(16)
    init {
        val g = ByteArray(24)
        le32(g, 0, 0xA1B2C3D4L); le16(g, 4, 2); le16(g, 6, 4); le32(g, 16, snaplen.toLong()); le32(g, 20, 101)
        o.write(g)
    }
    private fun le32(b: ByteArray, i: Int, v: Long) { for (k in 0..3) b[i + k] = (v shr (8 * k)).toByte() }
    private fun le16(b: ByteArray, i: Int, v: Int) { b[i] = v.toByte(); b[i + 1] = (v shr 8).toByte() }
    fun write(tsMicros: Long, data: ByteArray, off: Int, len: Int) {
        val inc = minOf(len, snaplen)
        le32(hdr, 0, tsMicros / 1_000_000L); le32(hdr, 4, tsMicros % 1_000_000L)
        le32(hdr, 8, inc.toLong()); le32(hdr, 12, len.toLong())
        o.write(hdr); o.write(data, off, inc)
    }
    fun flush() = o.flush()
    override fun close() = o.close()
}
