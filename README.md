# محلل IP لمكالمات واتساب — WhatsApp Call IP Analyzer

**Developed by Maggd Alosimi**

تطبيق أندرويد (Kotlin + Jetpack Compose، Android 8+، بدون Root) يحلّل اتصالات واتساب محليًا ويعرض عناوين IP والمنافذ والبروتوكولات، ويصنّف العناوين (خوادم واتساب/Meta، خوادم ترحيل المكالمات، أطراف محتملة) **مع أدلة ودرجة ثقة**، دون ادعاء كشف IP الطرف الآخر.

An Android app (Kotlin + Jetpack Compose, Android 8+, no root) that analyses WhatsApp connections on-device and lists IP addresses, ports and protocols, classifying them as WhatsApp/Meta servers, call relays or *candidate* peers — always with evidence and a confidence level, never claiming to reveal the other party's IP.

## Features
- **Live analysis** — `VpnService` local tap routed to WhatsApp only (`addAllowedApplication`); a user-space TCP/UDP forwarder (`vpn/VpnEngine.kt`) passes traffic unchanged. UDP uses one socket per local port so P2P NAT behaviour is preserved.
- **PCAP / PCAPNG import** — classic pcap (both endians, µs/ns), pcapng (SHB/IDB/EPB/SPB), Ethernet/VLAN/Linux-cooked/raw link types; streaming reader.
- **Signals** — STUN (RFC 5389) parsing incl. XOR-MAPPED addresses (used to recognise *your own* public address), RTP/SRTP heuristics, DNS answers (host names), Meta address ranges.
- **Country / ISP** — optional RDAP registry look-up (user-initiated, public IPs only); shown as *registration* data, not physical location.
- **Arabic RTL UI + English**, in-app language switch; credit line "Developed by Maggd Alosimi" at the top.
- **Privacy** — everything is processed locally; no analytics, no uploads; live PCAP keeps only the first 96 bytes per packet in the private cache.
- Export: text report, PCAP of the live session.

## Honest limits
Calls are frequently relayed by Meta servers; a direct path is only sometimes used, and a non-Meta address may be a VPN/CGNAT/relay. Results are *candidates with reasons*. Live capture is IPv4 (IPv6 bypasses the tap — import a PCAP for IPv6). The Meta range list is a snapshot.

## Build
- CI: `.github/workflows/android.yml` runs unit tests, lint and builds debug + release APKs (uploaded as artifacts). It uses `gradle/actions/setup-gradle` with Gradle 8.9, so no wrapper files are required.
- Local: Android Studio (Koala+) / JDK 17, or `gradle :app:assembleDebug` with Gradle 8.9. (Optionally run `gradle wrapper --gradle-version 8.9` to add the wrapper.)
- Unit tests: `gradle :app:testDebugUnitTest` — parser/builder round-trips, STUN/DNS, PCAP/PCAPNG, flow classification, RDAP parsing, and an end-to-end `VpnEngine` test (fake TUN ↔ real loopback TCP/UDP servers).

## Layout
```
app/src/main/java/com/maggd/waipanalyzer/
  core/  Net, PacketParser, PacketBuilder, Stun, Dns, Pcap, Flow   (pure Kotlin, JVM-testable)
  vpn/   VpnEngine (pure Kotlin), WaVpnService
  data/  CaptureRepository, Rdap
  ui/    Screens, Theme, MainViewModel
```

Use only on your own device and calls, and in line with local law and WhatsApp's terms.
