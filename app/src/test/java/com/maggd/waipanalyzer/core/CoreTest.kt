package com.maggd.waipanalyzer.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun ip(s: String): ByteArray = IpUtil.toBytes(s)!!

private fun stunResponse(mappedIp: String, port: Int): ByteArray {
    val txid = ByteArray(12) { (it + 1).toByte() }
    val cookie = byteArrayOf(0x21, 0x12, 0xA4.toByte(), 0x42)
    val mask = cookie + txid
    val addr = ip(mappedIp)
    val b = ByteArray(20 + 12)
    b[0] = 0x01; b[1] = 0x01; b[2] = 0; b[3] = 12
    System.arraycopy(cookie, 0, b, 4, 4); System.arraycopy(txid, 0, b, 8, 12)
    b[20] = 0x00; b[21] = 0x20; b[22] = 0; b[23] = 8
    b[25] = 1
    val xp = port xor 0x2112
    b[26] = (xp shr 8).toByte(); b[27] = xp.toByte()
    for (i in 0..3) b[28 + i] = (addr[i].toInt() xor mask[i].toInt()).toByte()
    return b
}

private fun stunRequest(): ByteArray {
    val b = ByteArray(20)
    b[1] = 0x01
    b[4] = 0x21; b[5] = 0x12; b[6] = 0xA4.toByte(); b[7] = 0x42
    return b
}

private fun rtp(seq: Int): ByteArray {
    val b = ByteArray(60) { (it * 7).toByte() }
    b[0] = 0x80.toByte(); b[1] = 0x6F; b[2] = (seq shr 8).toByte(); b[3] = seq.toByte()
    return b
}

private fun udp(src: String, dst: String, sp: Int, dp: Int, payload: ByteArray) =
    PacketBuilder.udp(ip(src), ip(dst), sp, dp, payload, 0, payload.size)

class NetTest {
    @Test fun formatsIpv4AndIpv6() {
        assertEquals("1.2.3.4", IpUtil.format(byteArrayOf(1, 2, 3, 4)))
        assertEquals("2001:db8::1", IpUtil.format(ip("2001:db8::1")))
        assertEquals("::1", IpUtil.format(ip("::1")))
    }

    @Test fun rejectsNonIpLiterals() {
        assertNull(IpUtil.toBytes("example.com"))
        assertNull(IpUtil.toBytes("../etc"))
    }

    @Test fun classifiesAddresses() {
        assertEquals(IpClass.PRIVATE, IpClassifier.classify(ip("192.168.1.10")))
        assertEquals(IpClass.PRIVATE, IpClassifier.classify(ip("172.20.0.1")))
        assertEquals(IpClass.PUBLIC, IpClassifier.classify(ip("172.32.0.1")))
        assertEquals(IpClass.CGNAT, IpClassifier.classify(ip("100.72.1.1")))
        assertEquals(IpClass.LOOPBACK, IpClassifier.classify(ip("127.0.0.1")))
        assertEquals(IpClass.LINK_LOCAL, IpClassifier.classify(ip("169.254.1.1")))
        assertEquals(IpClass.PUBLIC, IpClassifier.classify(ip("8.8.8.8")))
        assertEquals(IpClass.PRIVATE, IpClassifier.classify(ip("fd00::1")))
        assertEquals(IpClass.PUBLIC, IpClassifier.classify(ip("2a03:2880:f000::1")))
    }

    @Test fun recognisesMetaRangesAndHosts() {
        assertTrue(WhatsAppRanges.isMeta(ip("157.240.22.54")))
        assertTrue(WhatsAppRanges.isMeta(ip("2a03:2880:f21f::1")))
        assertFalse(WhatsAppRanges.isMeta(ip("8.8.8.8")))
        assertTrue(WhatsAppRanges.isWhatsAppHost("mmg.whatsapp.net."))
        assertFalse(WhatsAppRanges.isWhatsAppHost("evilwhatsapp.net"))
        assertTrue(WhatsAppRanges.isMetaOrgName("Facebook, Inc."))
    }
}

class PacketTest {
    @Test fun udpRoundTrip() {
        val pkt = udp("10.0.0.2", "85.1.2.3", 40000, 3478, byteArrayOf(1, 2, 3, 4, 5))
        assertEquals(0, PacketBuilder.checksum(pkt, 0, 20)) // valid IPv4 header checksum
        val p = PacketParser.parse(pkt)!!
        assertEquals(Transport.UDP, p.transport)
        assertEquals("10.0.0.2", p.srcStr)
        assertEquals("85.1.2.3", p.dstStr)
        assertEquals(40000, p.srcPort)
        assertEquals(3478, p.dstPort)
        assertEquals(5, p.payloadLength)
    }

    @Test fun tcpRoundTripWithMss() {
        val pkt = PacketBuilder.tcp(ip("1.1.1.1"), ip("10.0.0.2"), 443, 50000, 0xFFFFFFF0L, 77, PacketBuilder.SYN or PacketBuilder.ACK, 65535, mss = 1400)
        val p = PacketParser.parse(pkt)!!
        assertEquals(Transport.TCP, p.transport)
        assertEquals(443, p.srcPort)
        assertEquals(0xFFFFFFF0L, p.seq)
        assertEquals(77L, p.ack)
        assertEquals(PacketBuilder.SYN or PacketBuilder.ACK, p.tcpFlags)
        assertEquals(0, p.payloadLength)
    }

    @Test fun garbageIsRejected() {
        assertNull(PacketParser.parse(ByteArray(5)))
        assertNull(PacketParser.parse(ByteArray(40)))
    }

    @Test fun parsesStunXorMapped() {
        val s = Stun.parse(stunResponse("93.184.216.34", 54321), 0, 32)!!
        assertEquals(0x0100, s.messageClass)
        val a = s.addresses.single()
        assertEquals(StunAddrKind.XOR_MAPPED, a.kind)
        assertEquals("93.184.216.34", IpUtil.format(a.ip))
        assertEquals(54321, a.port)
        assertNull(Stun.parse(ByteArray(40), 0, 40))
    }

    @Test fun parsesDnsAnswer() {
        val q = "mmg.whatsapp.net".split(".")
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x12, 0x34, 0x81.toByte(), 0x80.toByte(), 0, 1, 0, 1, 0, 0, 0, 0))
        for (l in q) { out.write(l.length); out.write(l.toByteArray()) }
        out.write(0); out.write(byteArrayOf(0, 1, 0, 1))
        out.write(byteArrayOf(0xC0.toByte(), 12, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 157.toByte(), 240.toByte(), 1, 35))
        val d = out.toByteArray()
        val ans = Dns.parseResponse(d, 0, d.size)
        assertEquals(1, ans.size)
        assertEquals("mmg.whatsapp.net", ans[0].name)
        assertEquals("157.240.1.35", IpUtil.format(ans[0].ip))
    }
}

class PcapTest {
    @Test fun classicRoundTrip() {
        val bos = ByteArrayOutputStream()
        PcapWriter(bos, 65535).use {
            val p = udp("10.0.0.2", "8.8.8.8", 1000, 53, ByteArray(10))
            it.write(1_700_000_000_123_456L, p, 0, p.size)
            it.write(1_700_000_001_000_000L, p, 0, p.size)
        }
        var n = 0
        var firstTs = 0L
        val r = PcapReader.read(ByteArrayInputStream(bos.toByteArray())) { ts, d, o, l ->
            if (n == 0) firstTs = ts
            n++
            assertNotNull(PacketParser.parse(d, o, l))
            true
        }
        assertEquals("pcap", r.format)
        assertEquals(2, n)
        assertEquals(1_700_000_000_123_456L, firstTs)
    }

    @Test fun ethernetFramesAreUnwrapped() {
        val ipPkt = udp("10.0.0.2", "8.8.8.8", 1000, 53, ByteArray(4))
        val frame = ByteArray(14) + ipPkt
        frame[12] = 0x08; frame[13] = 0x00
        val g = ByteArray(24)
        g[0] = 0xD4.toByte(); g[1] = 0xC3.toByte(); g[2] = 0xB2.toByte(); g[3] = 0xA1.toByte()
        g[4] = 2; g[6] = 4; g[16] = 0xFF.toByte(); g[17] = 0xFF.toByte(); g[20] = 1
        val rec = ByteArray(16)
        rec[8] = frame.size.toByte(); rec[12] = frame.size.toByte()
        var count = 0
        PcapReader.read(ByteArrayInputStream(g + rec + frame)) { _, d, o, l ->
            count++
            assertEquals("10.0.0.2", PacketParser.parse(d, o, l)!!.srcStr)
            true
        }
        assertEquals(1, count)
    }

    @Test fun pcapngSimplePacket() {
        fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
        fun block(type: Int, body: ByteArray): ByteArray {
            val pad = (4 - body.size % 4) % 4
            val total = 12 + body.size + pad
            return le32(type) + le32(total) + body + ByteArray(pad) + le32(total)
        }
        val shb = block(0x0A0D0D0A, le32(0x1A2B3C4D) + byteArrayOf(1, 0, 0, 0) + ByteArray(8) { 0xFF.toByte() })
        val idb = block(1, byteArrayOf(101, 0, 0, 0) + le32(65535))
        val pkt = udp("10.0.0.2", "8.8.4.4", 1, 2, ByteArray(3))
        val epb = block(6, le32(0) + le32(0) + le32(1_000_000) + le32(pkt.size) + le32(pkt.size) + pkt)
        var n = 0
        val r = PcapReader.read(ByteArrayInputStream(shb + idb + epb)) { _, d, o, l ->
            n++
            assertEquals("8.8.4.4", PacketParser.parse(d, o, l)!!.dstStr)
            true
        }
        assertEquals("pcapng", r.format)
        assertEquals(1, n)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOtherFiles() {
        PcapReader.read(ByteArrayInputStream("hello world, not a capture".toByteArray())) { _, _, _, _ -> true }
    }
}

class FlowTest {
    private fun feed(t: FlowTracker, ts: Long, p: ByteArray) = t.onPacket(ts, p, 0, p.size)

    @Test fun detectsRelayedCallAndMetaServer() {
        val t = FlowTracker("192.168.1.5")
        var ts = 0L
        // Meta relay (UDP media with STUN checks)
        repeat(60) { i ->
            feed(t, ts++, udp("192.168.1.5", "157.240.9.9", 50000, 3478, rtp(i)))
            feed(t, ts++, udp("157.240.9.9", "192.168.1.5", 3478, 50000, rtp(i)))
        }
        feed(t, ts++, udp("157.240.9.9", "192.168.1.5", 3478, 50000, stunResponse("85.10.20.30", 50000)))
        // Meta chat server over TCP
        feed(t, ts++, PacketBuilder.tcp(ip("192.168.1.5"), ip("157.240.1.60"), 41000, 443, 1, 0, PacketBuilder.SYN, 65535))
        val r = t.finish()
        assertEquals("192.168.1.5", r.localAddress)
        assertTrue(r.selfPublicAddresses.contains("85.10.20.30"))
        val relay = r.endpoints.first { it.ip == "157.240.9.9" }
        assertEquals(Role.WHATSAPP_RELAY, relay.role)
        assertTrue(relay.reasons.contains(Reason.META_RANGE))
        assertEquals(Role.WHATSAPP_SERVER, r.endpoints.first { it.ip == "157.240.1.60" }.role)
        assertTrue(r.callLikely)
    }

    @Test fun directPeerIsOnlyACandidate() {
        val t = FlowTracker()
        var ts = 0L
        repeat(150) { i ->
            feed(t, ts++, udp("192.168.1.5", "85.99.1.2", 50000, 40000, rtp(i)))
            feed(t, ts++, udp("85.99.1.2", "192.168.1.5", 40000, 50000, rtp(i)))
        }
        repeat(5) {
            feed(t, ts++, udp("192.168.1.5", "85.99.1.2", 50000, 40000, stunRequest()))
            feed(t, ts++, udp("85.99.1.2", "192.168.1.5", 40000, 50000, stunRequest()))
        }
        // plus a noisy LAN host so that local inference has to work
        feed(t, ts++, udp("192.168.1.5", "192.168.1.1", 5353, 53, ByteArray(4)))
        val r = t.finish()
        assertEquals("192.168.1.5", r.localAddress)
        val peer = r.endpoints.first { it.ip == "85.99.1.2" }
        assertEquals(Role.PEER_CANDIDATE, peer.role)
        assertEquals(Confidence.HIGH, peer.confidence)
        assertTrue(peer.reasons.contains(Reason.NON_META_PUBLIC))
        assertEquals(Role.LOCAL_NETWORK, r.endpoints.first { it.ip == "192.168.1.1" }.role)
    }

    @Test fun lightTrafficIsNotACall() {
        val t = FlowTracker("192.168.1.5")
        feed(t, 1, udp("192.168.1.5", "85.99.1.2", 50000, 40000, ByteArray(30)))
        feed(t, 2, udp("85.99.1.2", "192.168.1.5", 40000, 50000, ByteArray(30)))
        val r = t.finish()
        assertFalse(r.callLikely)
        assertEquals(Role.OTHER, r.endpoints.single().role)
    }

    @Test fun orgNameMarksMetaAddresses() {
        val t = FlowTracker("192.168.1.5")
        feed(t, 1, PacketBuilder.tcp(ip("192.168.1.5"), ip("34.1.2.3"), 41000, 443, 1, 0, PacketBuilder.SYN, 65535))
        assertEquals(Role.OTHER, t.finish().endpoints.single().role)
        assertEquals(Role.WHATSAPP_SERVER, t.finish { "Meta Platforms, Inc." }.endpoints.single().role)
    }

    @Test fun ipv6OnlyCaptureInfersLocal() {
        val t = FlowTracker()
        fun v6(src: String, dst: String): ByteArray {
            val b = ByteArray(40 + 8 + 4)
            b[0] = 0x60; b[4] = 0; b[5] = 12; b[6] = 17; b[7] = 64
            System.arraycopy(ip(src), 0, b, 8, 16); System.arraycopy(ip(dst), 0, b, 24, 16)
            b[41] = 1; b[43] = 2; b[45] = 12
            return b
        }
        repeat(3) { feed(t, it.toLong(), v6("2001:db8::5", "2a03:2880:f21f::9")); feed(t, it.toLong(), v6("2001:db8::5", "2606:4700::1")) }
        assertEquals("2001:db8::5", t.finish().localAddress)
    }
}
