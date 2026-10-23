import com.uniatt.core.*

var passed = 0; var failed = 0
fun ok(c: Boolean, name: String) { if (c) { passed++; println("  PASS  $name") } else { failed++; println("  FAIL  $name") } }
fun at(day: Int, hour: Int): Long = java.util.Calendar.getInstance().apply { clear(); set(2026, 9, day, hour, 0, 0) }.timeInMillis

fun main() {
    println("[1] محاضرتان متتاليتان في اليوم نفسه")
    val sessions = listOf("a" to at(12, 8), "b" to at(12, 10), "c" to at(13, 8), "a2" to at(12, 8) + 600_000)
    val d = AttendanceSheet.build(sessions, listOf("x" to "س", "y" to "ص"), setOf("a" to "x", "a2" to "y", "b" to "y", "c" to "x"), mapOf("b" to 1))
    ok(d.total == 3, "ثلاث محاضرات: الأولى (جلستا استئناف) + الثانية المتتالية + اليوم التالي")
    ok(d.lectures[0].sessionIds == listOf("a", "a2") && d.lectures[1].sessionIds == listOf("b"), "جلسات الاستئناف تبقى في محاضرتها")
    ok(d.lectures.map { it.part } == listOf(0, 1, 0) && d.lectures.map { it.tag } == listOf("", "(2)", ""), "وسم (2) للمتتالية")
    ok(d.rows[0].present == listOf(true, false, true) && d.rows[1].present == listOf(true, true, false), "حضور كل طالب حسب العمود")
    ok(AttendanceSheet.build(sessions, emptyList(), emptySet()).total == 2, "بلا parts: السلوك القديم (محاضرة = يوم)")
    // الجلسة المتتالية الأقدم زمنيًا لا تسبق الأولى
    val d2 = AttendanceSheet.build(listOf("p1" to at(5, 14), "p0" to at(5, 9)), emptyList(), emptySet(), mapOf("p1" to 1))
    ok(d2.lectures.map { it.sessionIds[0] } == listOf("p0", "p1"), "الترتيب: اليوم ثم رقم المحاضرة")
    val d3 = AttendanceSheet.build(listOf("p1" to at(5, 9), "p0" to at(5, 14)), emptyList(), emptySet(), mapOf("p1" to 1))
    ok(d3.lectures.map { it.part } == listOf(0, 1), "الجزء 0 قبل 1 حتى لو بدأ الثاني زمنيًا قبل (ساعة الجهاز)")

    println("[2] صيغة الملف")
    val f = AttendanceFile("D1", 5, listOf(ASession("s1", 1, 1000, 15), ASession("s2", 1, 2000, 15, 38, 1), ASession("s3", 1, 3000, 15, -1, 2), ASession("s4", 1, 4000, 15, 9)), emptyList(), emptyList())
    val b = AttendanceFile.parse(f.toText())!!
    ok(b.sessions.map { it.part } == listOf(0, 1, 2, 0) && b.sessions.map { it.counted } == listOf(-1, 38, -1, 9), "part وcounted يُحفظان ويُقرآن")
    ok(f.toText().lines().first { it.startsWith("S\ts1") }.split('\t').size == 5, "بلا part ولا counted: الصيغة القديمة نفسها (5 حقول)")
    ok(AttendanceFile.parse("UAT1\nD\tD1\t5\nS\ts1\t1\t1000\t15\t7\n")!!.sessions[0].part == 0, "ملف قديم بستة حقول يُقرأ")
    ok(AttendanceFile.open(f.seal("code1"), "code1")?.sessions?.get(1)?.part == 1, "التشفير والفتح")

    println("[3] تخطيط PDF")
    val rp = SheetLayout.rowsPerPage
    ok(rp in 20..40, "صفوف الصفحة $rp")
    ok(SheetLayout.plan(0, 0).size == 1 && SheetLayout.plan(rp, 16).size == 1 && SheetLayout.plan(rp + 1, 16).size == 2 && SheetLayout.plan(rp, 17).size == 2, "حدود الصفحات")
    val p = SheetLayout.plan(60, 20)
    ok(p.size == 2 * ((60 + rp - 1) / rp), "60 طالبًا × 20 محاضرة: ${p.size} صفحة")
    ok(p.filter { it.lecturePage == 0 }.sumOf { it.rowTo - it.rowFrom } == 60 && p.filter { it.lecturePage == 1 }.sumOf { it.rowTo - it.rowFrom } == 60, "كل الطلاب في كل شريحة أعمدة")
    ok(p.all { it.rowTo - it.rowFrom <= rp } && p.map { it.lectureFrom to it.lectureTo }.toSet() == setOf(0 to 16, 16 to 20), "نطاقات الأعمدة")
    val c = SheetLayout.columns(true)
    ok(c.no.x1 <= SheetLayout.PAGE_W - SheetLayout.MARGIN + 0.01f && c.notes!!.x0 >= SheetLayout.MARGIN - 0.01f && c.lectures.size == 16 &&
        c.lectures.zipWithNext().all { (a, b) -> kotlin.math.abs(a.x0 - b.x1) < 0.01f }, "الأعمدة متلاصقة من اليمين لليسار داخل الهوامش")
    ok(SheetLayout.fileName("attendance_رياضيات 1/أ") == "attendance_رياضيات_1_أ.pdf" && SheetLayout.fileName("///") == "sheet.pdf", "اسم الملف")

    println("[4] اكتشاف المحاضرة المتتالية من الجدول")
    val sl = listOf(RSlot(1, 0, 480, 570), RSlot(1, 0, 570, 660), RSlot(2, 0, 480, 570), RSlot(1, 2, 600, 690))
    fun nx(c: Long, day: Int, last: Int, now: Int) = Schedule.isNextLecture(sl, c, day, last, now)
    ok(nx(1, 0, 485, 575), "بدأ الأولى 08:05 والآن 09:35 (الموعد الثاني) ⇒ محاضرة جديدة")
    ok(!nx(1, 0, 485, 530), "إيقاف ثم تشغيل داخل الموعد نفسه ⇒ استئناف")
    ok(nx(1, 0, 485, 565 + 10) && !nx(1, 0, 575, 600), "بعد الموعد الثاني نفسه لا جديدة")
    ok(!nx(2, 0, 485, 575), "مادة لها موعد واحد: تبقى المحاضرة نفسها")
    ok(!nx(3, 0, 485, 575) && !nx(1, 1, 485, 575), "لا جدول لها/اليوم ⇒ يدويًا فقط")
    ok(nx(1, 0, 470, 570), "بدأ قبل الموعد بـ10 دقائق (مبكرًا) والآن في الثاني")

    println("\nنجح $passed، فشل $failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
