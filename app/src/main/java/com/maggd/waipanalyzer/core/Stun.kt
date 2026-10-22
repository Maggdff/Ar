package com.maggd.waipanalyzer.core
enum class StunAddrKind { MAPPED, XOR_MAPPED, XOR_PEER, XOR_RELAYED }
class StunAddress(val kind: StunAddrKind, val ip: ByteArray, val port: Int)
class StunInfo(val messageType: Int, val addresses: List<StunAddress>, val attributeTypes: List<Int>) {
    /** 0x0000 request, 0x0010 indication, 0x0100 success response, 0x0110 error response */
    val messageClass: Int get() = messageType and 0x0110
}
/** Minimal RFC 5389/8489 parser, enough to detect STUN/TURN-style signalling used by call setup. */
object Stun {
    private const val COOKIE = 0x2112A442L
    fun parse(b: ByteArray, off: Int, len: Int): StunInfo? {
        if (len < 20 || off < 0 || off + len > b.size) return null
        if ((b[off].toInt() and 0xC0) != 0) return null
        if (PacketParser.u32(b, off + 4) != COOKIE) return null
        val msgLen = PacketParser.u16(b, off + 2)
        if (20 + msgLen > len) return null
        val txid = b.copyOfRange(off + 8, off + 20)
        val addrs = ArrayList<StunAddress>()
        val types = ArrayList<Int>()
        var p = off + 20
        val end = off + 20 + msgLen
        while (p + 4 <= end) {
            val t = PacketParser.u16(b, p)
            val l = PacketParser.u16(b, p + 2)
            if (p + 4 + l > end) break
            types.add(t)
            val kind = when (t) {
                0x0001 -> StunAddrKind.MAPPED
                0x0020 -> StunAddrKind.XOR_MAPPED
                0x0012 -> StunAddrKind.XOR_PEER
                0x0016 -> StunAddrKind.XOR_RELAYED
                else -> null
            }
            if (kind != null) decodeAddress(b, p + 4, l, kind, kind != StunAddrKind.MAPPED, txid)?.let { addrs.add(it) }
            p += 4 + ((l + 3) and 3.inv())
        }
        return StunInfo(PacketParser.u16(b, off), addrs, types)
    }
    private fun decodeAddress(b: ByteArray, v: Int, l: Int, kind: StunAddrKind, xor: Boolean, txid: ByteArray): StunAddress? {
        if (l < 8) return null
        val family = b[v + 1].toInt() and 0xFF
        var port = PacketParser.u16(b, v + 2)
        if (xor) port = port xor 0x2112
        val size = when (family) { 1 -> 4; 2 -> 16; else -> return null }
        if (l < 4 + size) return null
        val ip = b.copyOfRange(v + 4, v + 4 + size)
        if (xor) {
            val mask = byteArrayOf(0x21, 0x12, 0xA4.toByte(), 0x42) + txid
            for (i in ip.indices) ip[i] = (ip[i].toInt() xor mask[i].toInt()).toByte()
        }
        return StunAddress(kind, ip, port)
    }
}
/** Heuristic RTP/SRTP header check (the RTP header stays readable in SRTP). Returns the SSRC or null. */
object Rtp {
    fun ssrcIfRtpLike(b: ByteArray, off: Int, len: Int): Int? {
        if (len < 12) return null
        val b0 = b[off].toInt() and 0xFF
        if ((b0 and 0xC0) != 0x80) return null
        val pt = b[off + 1].toInt() and 0x7F
        if (pt in 72..95) return null // RTCP range (with marker bit) - not media
        return PacketParser.u32(b, off + 8).toInt()
    }
}
