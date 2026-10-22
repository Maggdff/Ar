package com.maggd.waipanalyzer.core
/** Builds raw IPv4 TCP/UDP packets (used by the VPN engine to answer the app through the TUN device). */
object PacketBuilder {
    const val FIN = 0x01
    const val SYN = 0x02
    const val RST = 0x04
    const val PSH = 0x08
    const val ACK = 0x10
    private var ipId = 0
    fun checksum(data: ByteArray, off: Int, len: Int, initial: Long = 0): Int {
        var sum = initial
        var i = off
        val end = off + len
        while (i + 1 < end) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) sum += (data[i].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }
    private fun pseudoSum(src: ByteArray, dst: ByteArray, proto: Int, len: Int): Long {
        var s = 0L
        for (i in 0 until 4 step 2) {
            s += ((src[i].toInt() and 0xFF) shl 8) or (src[i + 1].toInt() and 0xFF)
            s += ((dst[i].toInt() and 0xFF) shl 8) or (dst[i + 1].toInt() and 0xFF)
        }
        return s + proto + len
    }
    private fun put16(b: ByteArray, i: Int, v: Int) { b[i] = (v shr 8).toByte(); b[i + 1] = v.toByte() }
    private fun put32(b: ByteArray, i: Int, v: Long) {
        b[i] = (v shr 24).toByte(); b[i + 1] = (v shr 16).toByte(); b[i + 2] = (v shr 8).toByte(); b[i + 3] = v.toByte()
    }
    @Synchronized
    private fun nextId(): Int { ipId = (ipId + 1) and 0xFFFF; return ipId }
    private fun ipHeader(b: ByteArray, total: Int, proto: Int, src: ByteArray, dst: ByteArray) {
        b[0] = 0x45; b[1] = 0
        put16(b, 2, total); put16(b, 4, nextId()); put16(b, 6, 0x4000)
        b[8] = 64; b[9] = proto.toByte()
        System.arraycopy(src, 0, b, 12, 4); System.arraycopy(dst, 0, b, 16, 4)
        put16(b, 10, checksum(b, 0, 20))
    }
    fun tcp(
        src: ByteArray, dst: ByteArray, sp: Int, dp: Int, seq: Long, ack: Long, flags: Int, window: Int,
        payload: ByteArray? = null, pOff: Int = 0, pLen: Int = 0, mss: Int = 0
    ): ByteArray {
        val opt = if (mss > 0) 4 else 0
        val tcpLen = 20 + opt + pLen
        val b = ByteArray(20 + tcpLen)
        ipHeader(b, b.size, 6, src, dst)
        val t = 20
        put16(b, t, sp); put16(b, t + 2, dp)
        put32(b, t + 4, seq and 0xFFFFFFFFL); put32(b, t + 8, ack and 0xFFFFFFFFL)
        b[t + 12] = (((20 + opt) / 4) shl 4).toByte()
        b[t + 13] = flags.toByte()
        put16(b, t + 14, window.coerceIn(0, 65535))
        if (mss > 0) { b[t + 20] = 2; b[t + 21] = 4; put16(b, t + 22, mss) }
        if (payload != null && pLen > 0) System.arraycopy(payload, pOff, b, t + 20 + opt, pLen)
        put16(b, t + 16, checksum(b, t, tcpLen, pseudoSum(src, dst, 6, tcpLen)))
        return b
    }
    fun udp(src: ByteArray, dst: ByteArray, sp: Int, dp: Int, payload: ByteArray, pOff: Int, pLen: Int): ByteArray {
        val udpLen = 8 + pLen
        val b = ByteArray(20 + udpLen)
        ipHeader(b, b.size, 17, src, dst)
        put16(b, 20, sp); put16(b, 22, dp); put16(b, 24, udpLen)
        System.arraycopy(payload, pOff, b, 28, pLen)
        var c = checksum(b, 20, udpLen, pseudoSum(src, dst, 17, udpLen))
        if (c == 0) c = 0xFFFF
        put16(b, 26, c)
        return b
    }
}
