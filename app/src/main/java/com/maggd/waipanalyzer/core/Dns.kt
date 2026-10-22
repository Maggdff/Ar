package com.maggd.waipanalyzer.core
class DnsAnswer(val ip: ByteArray, val name: String)
object Dns {
    /** Returns A/AAAA answers mapped to the *queried* name (so CNAME chains still resolve to the original host). */
    fun parseResponse(b: ByteArray, off: Int, len: Int): List<DnsAnswer> {
        try {
            if (len < 12) return emptyList()
            val flags = PacketParser.u16(b, off + 2)
            if ((flags and 0x8000) == 0 || (flags and 0x000F) != 0) return emptyList()
            val qd = PacketParser.u16(b, off + 4)
            val an = PacketParser.u16(b, off + 6)
            if (qd < 1 || an < 1 || qd > 4 || an > 64) return emptyList()
            val end = off + len
            var p = off + 12
            var queryName = ""
            repeat(qd) { i ->
                val (n, np) = readName(b, p, off, end)
                if (i == 0) queryName = n
                p = np + 4
            }
            val out = ArrayList<DnsAnswer>()
            repeat(an) {
                val (_, np) = readName(b, p, off, end)
                p = np
                if (p + 10 > end) return out
                val type = PacketParser.u16(b, p)
                val rdlen = PacketParser.u16(b, p + 8)
                p += 10
                if (p + rdlen > end) return out
                if ((type == 1 && rdlen == 4) || (type == 28 && rdlen == 16)) {
                    out.add(DnsAnswer(b.copyOfRange(p, p + rdlen), queryName))
                }
                p += rdlen
            }
            return out
        } catch (e: IndexOutOfBoundsException) {
            return emptyList()
        }
    }
    private fun readName(b: ByteArray, start: Int, base: Int, end: Int): Pair<String, Int> {
        val sb = StringBuilder()
        var p = start
        var next = -1
        var jumps = 0
        while (true) {
            if (p >= end) throw IndexOutOfBoundsException()
            val l = PacketParser.u8(b, p)
            if (l == 0) { p++; break }
            if ((l and 0xC0) == 0xC0) {
                if (next < 0) next = p + 2
                p = base + (((l and 0x3F) shl 8) or PacketParser.u8(b, p + 1))
                if (++jumps > 10) throw IndexOutOfBoundsException()
                continue
            }
            if (p + 1 + l > end) throw IndexOutOfBoundsException()
            if (sb.isNotEmpty()) sb.append('.')
            for (i in 1..l) sb.append((b[p + i].toInt() and 0xFF).toChar())
            p += 1 + l
        }
        return Pair(sb.toString().lowercase(), if (next >= 0) next else p)
    }
}
