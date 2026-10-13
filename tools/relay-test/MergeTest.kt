import com.uniatt.admin.*
import com.uniatt.core.*

var passed = 0; var failed = 0
fun ok(c: Boolean, name: String) { if (c) { passed++; println("  PASS  $name") } else { failed++; println("  FAIL  $name") } }

fun file(did: String, sess: List<String>, recs: List<Pair<String, String>>) = AttendanceFile(
    did, 1, sess.map { ASession(it, 1, 1000, 15) }, recs.map { ARecord(it.first, it.second, 2000) }, emptyList())

fun main() {
    val s0 = AdminState()
    println("[1] الإلغاء والتحضير اليدوي ينعكسان عند المسؤول")
    val f1 = file("D1", listOf("A", "B"), listOf("A" to "s1", "A" to "s2", "A" to "s3", "B" to "s1"))
    val (s1, n1) = AdminSync.mergeAttendance(s0, f1)
    ok(n1 == 4 && s1.records.size == 4, "أول دمج: 4 تسجيلات جديدة")
    val (s1b, n1b) = AdminSync.mergeAttendance(s1, f1)
    ok(n1b == 0 && s1b.records.size == 4, "إعادة دمج نفس الملف لا تضيف شيئًا (idempotent)")
    val f2 = file("D1", listOf("A", "B"), listOf("A" to "s1", "A" to "s3", "B" to "s1"))       // الدكتور ألغى تحضير s2 في الجلسة A
    val (s2, n2) = AdminSync.mergeAttendance(s1, f2)
    ok(s2.records.none { it.sessionId == "A" && it.studentId == "s2" } && s2.records.size == 3 && n2 == 0, "إلغاء تحضير s2 يحذف تسجيله عند المسؤول")
    val f3 = file("D1", listOf("A", "B"), listOf("A" to "s1", "A" to "s3", "A" to "s4", "B" to "s1"))   // حضّر s4 يدويًا
    val (s3, n3) = AdminSync.mergeAttendance(s2, f3)
    ok(s3.records.any { it.sessionId == "A" && it.studentId == "s4" } && n3 == 1, "تحضير يدوي لـ s4 يظهر ويُحصى جديدًا")
    val f4 = file("D1", listOf("A", "B"), listOf("A" to "s1", "A" to "s2", "A" to "s3", "A" to "s4", "B" to "s1"))  // أعاد تحضير s2
    ok(AdminSync.mergeAttendance(s3, f4).let { (st, n) -> st.records.any { it.studentId == "s2" && it.sessionId == "A" } && n == 1 }, "إعادة التحضير بعد الإلغاء تعيد التسجيل")
    val f5 = file("D1", listOf("A", "B"), listOf("B" to "s1"))                                                       // ألغى كل من في A
    ok(AdminSync.mergeAttendance(s3, f5).first.records.let { r -> r.none { it.sessionId == "A" } && r.size == 1 }, "إلغاء الجميع في جلسة يفرغها (الجلسة تبقى)")
    ok(AdminSync.mergeAttendance(s3, f5).first.sessions.size == 2, "الجلسة نفسها تبقى مسجلة")

    println("[2] الحدود: دكتور آخر وجلسات غير موجودة في الملف")
    val other = file("D2", listOf("C"), listOf("C" to "x1", "C" to "x2"))
    val (m, _) = AdminSync.mergeAttendance(s3, other)
    ok(m.records.size == s3.records.size + 2, "دكتور آخر بجلسته: تُضاف تسجيلاته")
    val f6 = file("D1", listOf("A"), listOf("A" to "s1"))                                                            // لا يذكر الجلسة B ولا جلسة D2
    val (m2, _) = AdminSync.mergeAttendance(m, f6)
    ok(m2.records.count { it.sessionId == "B" } == 1 && m2.records.count { it.sessionId == "C" } == 2, "جلسة غير مذكورة في الملف وجلسات دكتور آخر لا تُمسّ")
    val hijack = file("D3", listOf("A"), listOf("A" to "evil"))                                                      // دكتور آخر يدّعي معرّف جلسة D1
    val (m3, _) = AdminSync.mergeAttendance(m2, hijack)
    ok(m3.records.count { it.sessionId == "A" } == m2.records.count { it.sessionId == "A" } && m3.sessions.first { it.sessionId == "A" }.doctorId == "D1", "ملف دكتور آخر لا يستبدل جلسة غيره")
    ok(AdminSync.mergeAttendance(s0, file("D1", listOf("A"), listOf("A" to "s1", "A" to "s1"))).first.records.size == 1, "تسجيل مكرر داخل الملف يُدمج في واحد")

    println("\nالنتيجة: نجح $passed، فشل $failed")
    if (failed > 0) System.exit(1)
}
