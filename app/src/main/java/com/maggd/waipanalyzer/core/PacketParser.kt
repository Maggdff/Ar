package com.maggd.waipanalyzer.core
enum class Transport { TCP, UDP, ICMP, OTHER }
class ParsedPacket(
    val version: Int,
    val src: ByteArray,
    val dst: ByteArray,
    val protocol: Int,
    val transport: Transport,
    val srcPort: Int,
    val dstPort: Int,
    val totalLength: Int,
    val payloadOffset: Int,
    val payloadLength: Int,
    val tcpFlags: Int,
    val seq: Long,
    val ack: Long,
    val fragment: Boolean
) {
    val srcStr: String by lazy { IpUtil.format(src) }
    val dstStr: String by lazy { IpUtil.format(dst) }
}
object PacketParser {
    fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    fun u16(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)
    fun u32(b: ByteArray, i: Int): Long = (u16(b, i).toLong() shl 16) or u16(b, i + 2).toLong()
    /** Parses a raw IPv4/IPv6 packet starting at [off]. Returns null if it is not a valid IP packet. */
    fun parse(b: ByteArray, off: Int = 0, len: Int = b.size - off): ParsedPacket? {
        if (len < 20 || off < 0 || off + len > b.size) return null
        return when (u8(b, off) shr 4) {
            4 -> parseV4(b, off, len)
            6 -> parseV6(b, off, len)
            else -> null
        }
    }
    private fun parseV4(b: ByteArray, off: Int, len: Int): ParsedPacket? {
        val ihl = (u8(b, off) and 0x0F) * 4
        if (ihl < 20 || ihl > len) return null
        var total = u16(b, off + 2)
        if (total < ihl || total > len) total = len
        val ff = u16(b, off + 6)
        val fragOff = ff and 0x1FFF
        val fragment = fragOff != 0 || (ff and 0x2000) != 0
        val proto = u8(b, off + 9)
        val src = b.copyOfRange(off + 12, off + 16)
        val dst = b.copyOfRange(off + 16, off + 20)
        return l4(b, 4, src, dst, proto, off + ihl, off + total, total, fragment, fragOff == 0)
    }
    private fun parseV6(b: ByteArray, off: Int, len: Int): ParsedPacket? {
        if (len < 40) return null
        val payloadLen = u16(b, off + 4)
        val end = minOf(off + len, off + 40 + payloadLen).let { if (payloadLen == 0) off + len else it }
        var next = u8(b, off + 6)
        var cur = off + 40
        var fragment = false
        var parseL4 = true
        var guard = 0
        while (guard++ < 8 && (next == 0 || next == 43 || next == 60 || next == 44 || next == 51)) {
            if (cur + 8 > end) return null
            val nh = u8(b, cur)
            when (next) {
                44 -> {
                    fragment = true
                    if ((u16(b, cur + 2) and 0xFFF8) != 0) parseL4 = false
                    cur += 8
                }
                51 -> cur += (u8(b, cur + 1) + 2) * 4
                else -> cur += (u8(b, cur + 1) + 1) * 8
            }
            next = nh
        }
        if (cur > end) return null
        return l4(b, 6, b.copyOfRange(off + 8, off + 24), b.copyOfRange(off + 24, off + 40), next, cur, end, end - off, fragment, parseL4)
    }
    private fun l4(
        b: ByteArray, ver: Int, src: ByteArray, dst: ByteArray, proto: Int, s: Int, end: Int,
        total: Int, fragment: Boolean, parse: Boolean
    ): ParsedPacket {
        val transport = when (proto) { 6 -> Transport.TCP; 17 -> Transport.UDP; 1, 58 -> Transport.ICMP; else -> Transport.OTHER }
        fun other(t: Transport = transport) = ParsedPacket(ver, src, dst, proto, t, 0, 0, total, s, 0, 0, 0, 0, fragment)
        if (!parse) return other()
        if (transport == Transport.TCP) {
            if (end - s < 20) return other(Transport.OTHER)
            val dataOff = (u8(b, s + 12) shr 4) * 4
            if (dataOff < 20 || s + dataOff > end) return other(Transport.OTHER)
            return ParsedPacket(
                ver, src, dst, proto, transport, u16(b, s), u16(b, s + 2), total, s + dataOff, end - (s + dataOff),
                u8(b, s + 13) and 0x3F, u32(b, s + 4), u32(b, s + 8), fragment
            )
        }
        if (transport == Transport.UDP) {
            if (end - s < 8) return other(Transport.OTHER)
            val ulen = u16(b, s + 4)
            val plen = maxOf(0, minOf(ulen - 8, end - s - 8))
            return ParsedPacket(ver, src, dst, proto, transport, u16(b, s), u16(b, s + 2), total, s + 8, plen, 0, 0, 0, fragment)
        }
        return other()
    }
}
