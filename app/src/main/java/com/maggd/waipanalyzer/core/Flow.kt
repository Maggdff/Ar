package com.maggd.waipanalyzer.core

enum class Role { WHATSAPP_SERVER, WHATSAPP_RELAY, PEER_CANDIDATE, DNS, LOCAL_NETWORK, OTHER }
enum class Confidence { HIGH, MEDIUM, LOW }

/** Machine-readable evidence for a classification; the UI maps each one to a localized sentence. */
enum class Reason {
    META_RANGE, META_HOSTNAME, META_ORG, UDP_BIDIRECTIONAL, STUN_SEEN, STUN_BIDIRECTIONAL,
    RTP_LIKE, RTP_BIDIRECTIONAL, HIGH_VOLUME, NON_META_PUBLIC, DNS_PORT, NON_PUBLIC
}

class RemoteEndpoint(
    val ip: String,
    val ipClass: IpClass,
    val role: Role,
    val confidence: Confidence,
    val reasons: List<Reason>,
    val protocols: List<String>,
    val ports: List<Int>,
    val pktsOut: Long,
    val pktsIn: Long,
    val bytesOut: Long,
    val bytesIn: Long,
    val firstTsMicros: Long,
    val lastTsMicros: Long,
    val hostnames: List<String>
) {
    val packets: Long get() = pktsOut + pktsIn
    val bytes: Long get() = bytesOut + bytesIn
    val isCallRelated: Boolean get() = role == Role.WHATSAPP_RELAY || role == Role.PEER_CANDIDATE
}

class AnalysisResult(
    val packets: Long,
    val unsupportedPackets: Long,
    val format: String?,
    val localAddress: String?,
    val selfPublicAddresses: List<String>,
    val endpoints: List<RemoteEndpoint>,
    val firstTsMicros: Long,
    val lastTsMicros: Long
) {
    val callLikely: Boolean get() = endpoints.any { it.isCallRelated }
    val durationMs: Long get() = if (lastTsMicros > firstTsMicros) (lastTsMicros - firstTsMicros) / 1000 else 0
    companion object {
        val EMPTY = AnalysisResult(0, 0, null, null, emptyList(), emptyList(), 0, 0)
    }
}

/**
 * Accumulates per-direction flow statistics from raw IP packets (live VPN capture or an imported PCAP) and
 * turns them into per-remote-address findings. Thread-safe. Nothing here ever sends data anywhere.
 */
class FlowTracker(@Volatile var localAddress: String? = null) {

    private class Half(val src: String, val sp: Int, val dst: String, val dp: Int, val t: Transport) {
        var pkts = 0L
        var bytes = 0L
        var first = 0L
        var last = 0L
        var stun = 0L
        var rtp = 0L
    }

    private class Agg(val ip: String) {
        var pktsOut = 0L; var pktsIn = 0L; var bytesOut = 0L; var bytesIn = 0L
        var first = Long.MAX_VALUE; var last = 0L
        val ports = java.util.TreeSet<Int>()
        val protocols = java.util.TreeSet<String>()
        var udpOut = 0L; var udpIn = 0L
        var stunOut = 0L; var stunIn = 0L
        var rtpOut = 0L; var rtpIn = 0L
    }

    private val halves = HashMap<String, Half>()
    private val peers = HashMap<String, HashSet<String>>()
    private val pktCount = HashMap<String, Long>()
    private val hostnames = HashMap<String, LinkedHashSet<String>>()
    private val mapped = LinkedHashSet<String>()
    private var packets = 0L
    private var unsupported = 0L
    private var format: String? = null
    private var firstTs = Long.MAX_VALUE
    private var lastTs = 0L

    val packetCount: Long @Synchronized get() = packets

    @Synchronized
    fun setFormat(f: String?, unsupportedCount: Long) { format = f; unsupported += unsupportedCount }

    @Synchronized
    fun reset() {
        halves.clear(); peers.clear(); pktCount.clear(); hostnames.clear(); mapped.clear()
        packets = 0; unsupported = 0; format = null; firstTs = Long.MAX_VALUE; lastTs = 0
    }

    @Synchronized
    fun onPacket(ts: Long, data: ByteArray, off: Int, len: Int) {
        val p = PacketParser.parse(data, off, len) ?: run { unsupported++; return }
        packets++
        if (ts < firstTs) firstTs = ts
        if (ts > lastTs) lastTs = ts
        val src = p.srcStr
        val dst = p.dstStr
        pktCount[src] = (pktCount[src] ?: 0L) + 1
        pktCount[dst] = (pktCount[dst] ?: 0L) + 1
        peers.getOrPut(src) { HashSet() }.let { if (it.size < 256) it.add(dst) }
        peers.getOrPut(dst) { HashSet() }.let { if (it.size < 256) it.add(src) }

        var stun = false
        var rtp = false
        if (p.transport == Transport.UDP && p.payloadLength > 0) {
            if (p.srcPort == 53) {
                for (a in Dns.parseResponse(data, p.payloadOffset, p.payloadLength)) {
                    val set = hostnames.getOrPut(IpUtil.format(a.ip)) { LinkedHashSet() }
                    if (set.size < 8 && hostnames.size < 20000) set.add(a.name)
                }
            }
            val s = Stun.parse(data, p.payloadOffset, p.payloadLength)
            if (s != null) {
                stun = true
                if (s.messageClass == 0x0100) {
                    for (a in s.addresses) if (a.kind == StunAddrKind.MAPPED || a.kind == StunAddrKind.XOR_MAPPED) {
                        if (mapped.size < 16) mapped.add(IpUtil.format(a.ip))
                    }
                }
            } else if (Rtp.ssrcIfRtpLike(data, p.payloadOffset, p.payloadLength) != null) {
                rtp = true
            }
        }
        if (p.transport == Transport.OTHER && p.protocol != 1 && p.protocol != 58) return
        val key = "${p.transport.ordinal}|$src|${p.srcPort}|$dst|${p.dstPort}"
        var h = halves[key]
        if (h == null) {
            if (halves.size >= 200_000) return
            h = Half(src, p.srcPort, dst, p.dstPort, p.transport)
            h.first = ts
            halves[key] = h
        }
        h.pkts++
        h.bytes += p.totalLength
        h.last = ts
        if (stun) h.stun++
        if (rtp) h.rtp++
    }

    @Synchronized
    private fun inferLocal(): String? {
        localAddress?.let { return it }
        var best: String? = null
        var bestScore = -1L
        for ((ip, set) in peers) {
            val b = IpUtil.toBytes(ip) ?: continue
            val lan = if (IpClassifier.isLanLike(IpClassifier.classify(b))) 1L else 0L
            val score = lan * 1_000_000_000_000L + set.size * 1_000_000L + minOf(pktCount[ip] ?: 0L, 999_999L)
            if (score > bestScore) { bestScore = score; best = ip }
        }
        return best
    }

    /** Builds the report. [orgOf] optionally supplies registry organisation names (from an RDAP lookup). */
    @Synchronized
    fun finish(orgOf: (String) -> String? = { null }): AnalysisResult {
        val local = inferLocal()
        val aggs = LinkedHashMap<String, Agg>()
        for (h in halves.values) {
            val outbound = h.src == local
            val inbound = h.dst == local
            if (!outbound && !inbound) continue
            val remote = if (outbound) h.dst else h.src
            val remotePort = if (outbound) h.dp else h.sp
            val a = aggs.getOrPut(remote) { Agg(remote) }
            val name = when (h.t) { Transport.TCP -> "TCP"; Transport.UDP -> "UDP"; Transport.ICMP -> "ICMP"; else -> "OTHER" }
            a.protocols.add(name)
            if (remotePort != 0 && a.ports.size < 32) a.ports.add(remotePort)
            if (h.first < a.first) a.first = h.first
            if (h.last > a.last) a.last = h.last
            if (outbound) {
                a.pktsOut += h.pkts; a.bytesOut += h.bytes
                if (h.t == Transport.UDP) a.udpOut += h.pkts
                a.stunOut += h.stun; a.rtpOut += h.rtp
            } else {
                a.pktsIn += h.pkts; a.bytesIn += h.bytes
                if (h.t == Transport.UDP) a.udpIn += h.pkts
                a.stunIn += h.stun; a.rtpIn += h.rtp
            }
        }
        val self = mapped.toList()
        val endpoints = aggs.values
            .filter { it.ip !in self }
            .map { classify(it, hostnames[it.ip]?.toList() ?: emptyList(), orgOf(it.ip)) }
            .sortedWith(
                compareByDescending<RemoteEndpoint> { it.isCallRelated }
                    .thenByDescending { it.confidence == Confidence.HIGH }
                    .thenByDescending { it.bytes }
            )
        return AnalysisResult(
            packets, unsupported, format, local, self, endpoints,
            if (firstTs == Long.MAX_VALUE) 0 else firstTs, lastTs
        )
    }

    private fun classify(a: Agg, names: List<String>, org: String?): RemoteEndpoint {
        val bytes = IpUtil.toBytes(a.ip)
        val ipc = if (bytes != null) IpClassifier.classify(bytes) else IpClass.RESERVED
        val reasons = ArrayList<Reason>()
        val metaRange = bytes != null && WhatsAppRanges.isMeta(bytes)
        val metaHost = names.any { WhatsAppRanges.isMetaHost(it) }
        val metaOrg = WhatsAppRanges.isMetaOrgName(org)
        val udpTotal = a.udpOut + a.udpIn
        val rtpTotal = a.rtpOut + a.rtpIn
        val rtpLike = rtpTotal >= 20 && rtpTotal * 10 >= udpTotal * 7
        val rtpBoth = rtpLike && a.rtpOut >= 10 && a.rtpIn >= 10
        val stunSeen = a.stunOut + a.stunIn > 0
        val stunBoth = a.stunOut > 0 && a.stunIn > 0
        val udpBoth = a.udpOut >= 10 && a.udpIn >= 10

        var role = Role.OTHER
        var conf = Confidence.LOW
        if (ipc != IpClass.PUBLIC) {
            role = Role.LOCAL_NETWORK
            reasons.add(Reason.NON_PUBLIC)
        } else if (a.ports.all { it == 53 || it == 853 } && a.ports.isNotEmpty()) {
            role = Role.DNS
            reasons.add(Reason.DNS_PORT)
            conf = Confidence.MEDIUM
        } else if (metaRange || metaHost || metaOrg) {
            val signals = listOf(metaRange, metaHost, metaOrg).count { it }
            if (metaRange) reasons.add(Reason.META_RANGE)
            if (metaHost) reasons.add(Reason.META_HOSTNAME)
            if (metaOrg) reasons.add(Reason.META_ORG)
            conf = if (signals >= 2) Confidence.HIGH else Confidence.MEDIUM
            val media = udpTotal >= 20 && (rtpLike || stunSeen)
            role = if (media) Role.WHATSAPP_RELAY else Role.WHATSAPP_SERVER
            if (media) {
                if (stunSeen) reasons.add(Reason.STUN_SEEN)
                if (rtpLike) reasons.add(Reason.RTP_LIKE)
            }
        } else {
            reasons.add(Reason.NON_META_PUBLIC)
            if (udpBoth && (rtpBoth || stunBoth || udpTotal >= 100)) {
                role = Role.PEER_CANDIDATE
                reasons.add(Reason.UDP_BIDIRECTIONAL)
                if (stunBoth) reasons.add(Reason.STUN_BIDIRECTIONAL) else if (stunSeen) reasons.add(Reason.STUN_SEEN)
                if (rtpBoth) reasons.add(Reason.RTP_BIDIRECTIONAL) else if (rtpLike) reasons.add(Reason.RTP_LIKE)
                if (udpTotal >= 200) reasons.add(Reason.HIGH_VOLUME)
                conf = when {
                    stunBoth && rtpBoth && udpTotal >= 200 -> Confidence.HIGH
                    (rtpBoth || stunBoth) && udpTotal >= 50 -> Confidence.MEDIUM
                    else -> Confidence.LOW
                }
            }
        }
        return RemoteEndpoint(
            a.ip, ipc, role, conf, reasons, a.protocols.toList(), a.ports.toList(),
            a.pktsOut, a.pktsIn, a.bytesOut, a.bytesIn,
            if (a.first == Long.MAX_VALUE) 0 else a.first, a.last, names
        )
    }
}

/** Convenience: analyse a whole capture file. */
object CaptureAnalyzer {
    fun analyze(input: java.io.InputStream, orgOf: (String) -> String? = { null }): Pair<FlowTracker, AnalysisResult> {
        val t = FlowTracker()
        val r = PcapReader.read(input) { ts, d, o, l -> t.onPacket(ts, d, o, l); true }
        t.setFormat(r.format, r.unsupported)
        return t to t.finish(orgOf)
    }
}
