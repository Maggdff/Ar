import com.uniatt.core.*
import java.security.SecureRandom
import java.util.Calendar
import java.util.TimeZone

var passed = 0; var failed = 0
fun ok(c: Boolean, name: String) { if (c) { passed++; println("  PASS  $name") } else { failed++; println("  FAIL  $name") } }

/** وقت الخادم عبر Firebase ({".sv":"timestamp"}) والساعة الموثوقة والبوابة، مقابل خادم يحاكي Firebase.
 *  args[0] = خادم بلا إزاحة، args[1] = خادم وقته متقدّم ساعة كاملة (3600000 مللي ثانية). */
fun main(args: Array<String>) {
    val rnd = SecureRandom()
    val tz = TimeZone.getTimeZone("GMT+3")
    fun mono() = System.nanoTime() / 1_000_000

    println("[1] وقت الخادم من الترحيل")
    val box = Mailbox(Relay(args[0]), Pairing.newMailbox(rnd))
    val t = box.serverTimeMs()
    ok(Math.abs(t - System.currentTimeMillis()) < 5_000, "ختم الزمن من الخادم قريب من الوقت الفعلي")
    ok(runCatching { Relay(args[0]).serverTimeMs("zzz/forbidden") }.exceptionOrNull() is Relay.RelayException, "مسار خارج m/ يُرفض (401)")
    ok(runCatching { Mailbox(Relay("https://127.0.0.1:1"), "a".repeat(32)).serverTimeMs() }.isFailure, "بلا اتصال => استثناء (والمزامنة لا تغيّر المرساة)")

    println("[2] الساعة الموثوقة تتبع الخادم لا الهاتف")
    val skewed = Mailbox(Relay(args[1]), Pairing.newMailbox(rnd))
    val clock = TrustedClock(wall = { System.currentTimeMillis() }, mono = ::mono)
    ok(!clock.read().trusted, "قبل المزامنة: NEEDS_SYNC")
    clock.syncWith { skewed.serverTimeMs() }
    val r = clock.read()
    ok(r.trusted && Math.abs(r.ms - (System.currentTimeMillis() + 3_600_000L)) < 5_000, "الوقت الموثوق = وقت الخادم (ساعة بعد ساعة الهاتف)")
    ok(r.tampered && Math.abs(r.skewMs + 3_600_000L) < 5_000, "ساعة الهاتف متأخرة ساعة عن الخادم ⇒ تُعدّ معبوثًا بها")
    val clock2 = TrustedClock(wall = { System.currentTimeMillis() }, mono = ::mono)
    clock2.syncWith { box.serverTimeMs() }
    ok(!clock2.read().tampered, "خادم بلا إزاحة ⇒ الساعة سليمة")

    println("[3] البوابة تقرأ الوقت الموثوق")
    fun slotAround(ms: Long, fromMin: Int, toMin: Int): RSlot {
        val lt = AttendancePolicy.localTime(ms, tz)
        val mid = lt.minute
        return RSlot(1, lt.day, (mid + fromMin).coerceAtLeast(0), (mid + toMin).coerceAtMost(1440))
    }
    val real = clock.read().ms                                    // الوقت الحقيقي (من الخادم)
    val phone = System.currentTimeMillis()                        // ما تقوله ساعة الهاتف (ساعة متأخرة)
    // موعد يضم «وقت الهاتف» لا «الوقت الحقيقي»: الدكتور أخّر ساعته ليدخل موعدًا ليس موعده
    val trap = slotAround(phone, -10, 10).let { RSlot(it.courseId, it.day, it.startMin, it.endMin) }
    ok(AttendancePolicy.checkDoctorStart(listOf(trap), 1, phone, 30, tz).ok, "(تمهيد) لو صُدّقت ساعة الهاتف لدخل الموعد")
    ok(!AttendancePolicy.checkDoctorStart(listOf(trap), 1, real, 30, tz).ok, "بالوقت الموثوق: خارج الموعد فيُرفض")
    val right = slotAround(real, -10, 25)
    val d = AttendancePolicy.checkDoctorStart(listOf(right), 1, real, 240, tz)
    ok(d.ok && d.minutes in 24..25, "داخل الموعد الحقيقي: يُسمح وتُقصّ المدة عند نهايته (${d.minutes} دقيقة)")

    println("\nالنتيجة: نجح $passed، فشل $failed")
    if (failed > 0) System.exit(1)
}
