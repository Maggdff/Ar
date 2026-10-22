package com.maggd.waipanalyzer.core
import java.net.InetAddress
/** IP address helpers that never perform DNS lookups. */
object IpUtil {
    fun format(b: ByteArray): String {
        if (b.size == 4) {
            return "${b[0].toInt() and 255}.${b[1].toInt() and 255}.${b[2].toInt() and 255}.${b[3].toInt() and 255}"
        }
        if (b.size != 16) return b.joinToString("") { "%02x".format(it) }
        val g = IntArray(8) { ((b[it * 2].toInt() and 255) shl 8) or (b[it * 2 + 1].toInt() and 255) }
        var bestStart = -1
        var bestLen = 0
        var i = 0
        while (i < 8) {
            if (g[i] == 0) {
                var j = i
                while (j < 8 && g[j] == 0) j++
                if (j - i > bestLen) { bestStart = i; bestLen = j - i }
                i = j
            } else i++
        }
        if (bestLen < 2) return g.joinToString(":") { Integer.toHexString(it) }
        val head = g.take(bestStart).joinToString(":") { Integer.toHexString(it) }
        val tail = g.drop(bestStart + bestLen).joinToString(":") { Integer.toHexString(it) }
        return "$head::$tail"
    }
    /** True only for strings that look like IPv4/IPv6 literals (safe to embed in a URL path). */
    fun isIpLiteral(s: String): Boolean =
        s.length in 2..45 && s.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == '.' || it == ':' } &&
            (s.contains('.') || s.contains(':'))
    fun toBytes(ip: String): ByteArray? = if (!isIpLiteral(ip)) null else try {
        InetAddress.getByName(ip).address
    } catch (e: Exception) { null }
}
class Cidr(private val network: ByteArray, private val prefix: Int) {
    fun contains(addr: ByteArray): Boolean {
        if (addr.size != network.size) return false
        var bits = prefix
        var i = 0
        while (bits >= 8) {
            if (addr[i] != network[i]) return false
            i++; bits -= 8
        }
        if (bits > 0) {
            val mask = (0xFF shl (8 - bits)) and 0xFF
            if ((addr[i].toInt() and mask) != (network[i].toInt() and mask)) return false
        }
        return true
    }
    companion object {
        fun parse(s: String): Cidr {
            val parts = s.split("/")
            return Cidr(InetAddress.getByName(parts[0]).address, parts[1].toInt())
        }
    }
}
enum class IpClass { PUBLIC, PRIVATE, CGNAT, LINK_LOCAL, LOOPBACK, MULTICAST, RESERVED }
object IpClassifier {
    private fun l(vararg s: String) = s.map { Cidr.parse(it) }
    private val v4Private = l("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")
    private val v4Cgnat = l("100.64.0.0/10")
    private val v4LinkLocal = l("169.254.0.0/16")
    private val v4Loopback = l("127.0.0.0/8")
    private val v4Multicast = l("224.0.0.0/4")
    private val v4Reserved = l(
        "0.0.0.0/8", "240.0.0.0/4", "192.0.0.0/24", "192.0.2.0/24",
        "198.51.100.0/24", "203.0.113.0/24", "198.18.0.0/15"
    )
    private val v6Private = l("fc00::/7")
    private val v6LinkLocal = l("fe80::/10")
    private val v6Loopback = l("::1/128")
    private val v6Multicast = l("ff00::/8")
    private val v6Reserved = l("::/128", "2001:db8::/32", "::ffff:0:0/96", "64:ff9b::/96")
    fun classify(addr: ByteArray): IpClass {
        fun any(list: List<Cidr>) = list.any { it.contains(addr) }
        return if (addr.size == 4) when {
            any(v4Loopback) -> IpClass.LOOPBACK
            any(v4Private) -> IpClass.PRIVATE
            any(v4Cgnat) -> IpClass.CGNAT
            any(v4LinkLocal) -> IpClass.LINK_LOCAL
            any(v4Multicast) -> IpClass.MULTICAST
            any(v4Reserved) -> IpClass.RESERVED
            else -> IpClass.PUBLIC
        } else when {
            any(v6Loopback) -> IpClass.LOOPBACK
            any(v6Private) -> IpClass.PRIVATE
            any(v6LinkLocal) -> IpClass.LINK_LOCAL
            any(v6Multicast) -> IpClass.MULTICAST
            any(v6Reserved) -> IpClass.RESERVED
            else -> IpClass.PUBLIC
        }
    }
    fun isLanLike(c: IpClass) = c == IpClass.PRIVATE || c == IpClass.CGNAT || c == IpClass.LINK_LOCAL
}
/**
 * Static, approximate list of address blocks announced by Meta (AS32934), which hosts WhatsApp.
 * It can become stale; hostnames (from observed DNS answers) and RDAP lookups are complementary signals.
 */
object WhatsAppRanges {
    private val ranges = listOf(
        "31.13.24.0/21", "31.13.64.0/18", "45.64.40.0/22", "66.220.144.0/20", "69.63.176.0/20",
        "69.171.224.0/19", "74.119.76.0/22", "102.132.96.0/20", "103.4.96.0/22", "129.134.0.0/16",
        "147.75.208.0/20", "157.240.0.0/16", "163.70.128.0/17", "173.252.64.0/18", "179.60.192.0/22",
        "185.60.216.0/22", "185.89.218.0/23", "204.15.20.0/22",
        "2a03:2880::/32", "2620:0:1c00::/40", "2a03:83e0::/32"
    ).map { Cidr.parse(it) }
    fun isMeta(addr: ByteArray): Boolean = ranges.any { it.contains(addr) }
    fun isWhatsAppHost(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        return h == "whatsapp.net" || h.endsWith(".whatsapp.net") || h == "whatsapp.com" ||
            h.endsWith(".whatsapp.com") || h == "wa.me"
    }
    fun isMetaHost(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        return isWhatsAppHost(h) || h.endsWith(".facebook.com") || h.endsWith(".fbcdn.net") || h.endsWith(".fbsbx.com")
    }
    fun isMetaOrgName(name: String?): Boolean {
        val n = name?.lowercase() ?: return false
        return n.contains("facebook") || n.contains("meta platforms") || n.contains("whatsapp")
    }
}
