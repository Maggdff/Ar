import com.uniatt.admin.*
import com.uniatt.core.*

var passed = 0; var failed = 0
fun ok(c: Boolean, name: String) { if (c) { passed++; println("  PASS  $name") } else { failed++; println("  FAIL  $name") } }

fun main() {
    println("[1] اقتراح اسم المجموعة")
    val g3 = listOf("مج1", "مج2", "مج3")
    ok(AdminOps.suggestGroupName("أ", g3) == "مج1" && AdminOps.suggestGroupName("ب", g3) == "مج2" && AdminOps.suggestGroupName("ج", g3) == "مج3", "أ/ب/ج ← مج1/مج2/مج3")
    ok(AdminOps.suggestGroupName("أ", listOf("أ", "ب")) == "أ", "نفس الاسم إن وُجد")
    ok(AdminOps.suggestGroupName("ب", emptyList()) == "ب" && AdminOps.suggestGroupName("د", g3) == "د", "بلا مقابل: يبقى حرف الملف")

    println("[2] تخطيط الاستيراد: جدد/موجودون/مكرر")
    val ex = Student("S1", "محمد عبدالله الصلوي", "", "الأمن السيبراني", "الأول", "أ", emptyList(), "AAAAAAAAAAAA", "K1", "")
    val ex2 = Student("S2", "سارة علي احمد", "", "", "", "", emptyList(), "BBBBBBBBBBBB", "K2", "مج2")
    val s0 = AdminState(seq = 5, majors = listOf("الأمن السيبراني"), groupNames = g3, students = listOf(ex, ex2))
    val groups = listOf(
        AdminOps.ImportGroup("مج1", listOf("محمد عبدا الصلوي", "دلال وضاح علي", "دلل وضاح علي")),   // الأول موجود بنقص حروف، والثالث مكرر للثاني
        AdminOps.ImportGroup("مج2", listOf("سارة علي احمد", "خالد سعيد ناصر"))
    )
    val plan = AdminOps.planGroupImport(s0, groups)
    ok(plan.add.map { it.first } == listOf("دلال وضاح علي", "خالد سعيد ناصر") && plan.add.map { it.second } == listOf("مج1", "مج2"), "جدد: اسمان بمجموعتيهما")
    ok(plan.update == listOf("S1" to "مج1"), "الموجود (بنقص حروف في الملف) يُطابَق ويُنقل لمجموعته")
    ok(plan.same == 1 && plan.duplicates == 1, "موجود بمجموعته نفسها: يُحصى، والمكرر داخل الملف يُتجاهل")

    println("[3] تطبيق الاستيراد")
    val creds = mapOf("دلال وضاح علي" to ("CODE1" to "KEYA"), "خالد سعيد ناصر" to ("CODE2" to "KEYB"))
    val s1 = AdminOps.applyGroupImport(s0, plan, "الأمن السيبراني", "الأول", "ب", creds)
    ok(s1.students.size == 4 && s1.seq == 7L, "طالبان جديدان بمعرّفين داخليين جديدين")
    val n1 = s1.students.first { it.name == "دلال وضاح علي" }
    ok(n1.group == "مج1" && n1.major == "الأمن السيبراني" && n1.level == "الأول" && n1.section == "ب" && n1.code == "CODE1" && n1.keyHex == "KEYA", "الجديد: مجموعة + تخصص + مستوى + شعبة + كوده")
    val u1 = s1.students.first { it.studentId == "S1" }
    ok(u1.group == "مج1" && u1.section == "أ" && u1.code == "AAAAAAAAAAAA", "الموجود: المجموعة فقط، وشعبته وكوده لا يتغيران")
    val u2 = s1.students.first { it.studentId == "S2" }
    ok(u2.group == "مج2" && u2.major == "" && u2.level == "", "الموجود بنفس المجموعة لا يُمسّ")
    ok(s1.groupNames == g3, "لا تُكرَّر مجموعات الإعدادات")
    val s2 = AdminOps.applyGroupImport(s0, AdminOps.planGroupImport(s0, listOf(AdminOps.ImportGroup("مج9", listOf("طالب جديد تماما")))), "م", "", "", mapOf("طالب جديد تماما" to ("C" to "K")))
    ok(s2.groupNames.last() == "مج9" && s2.students.size == 3, "مجموعة جديدة تُضاف للإعدادات تلقائيًا")
    val s3 = AdminOps.applyGroupImport(s0, plan, "م", "", "", emptyMap())
    ok(s3.students.size == 2 && s3.students.first { it.studentId == "S1" }.group == "مج1", "بلا أكواد للجدد: يُتخطّون ولا ينهار، ويُحدَّث الموجود")
    val u3 = AdminOps.applyGroupImport(AdminState(students = listOf(ex2.copy(major = "", level = "", section = ""))), AdminOps.planGroupImport(AdminState(students = listOf(ex2.copy(major = "", level = "", section = ""))), listOf(AdminOps.ImportGroup("مج3", listOf("سارة علي احمد")))), "الأمن", "الأول", "أ", emptyMap()).students[0]
    ok(u3.group == "مج3" && u3.major == "الأمن" && u3.level == "الأول" && u3.section == "أ", "موجود بتخصص/مستوى/شعبة فارغة: تُملأ من الاستيراد")
    ok(AdminOps.studentPublic(s1, n1).group == "مج1", "ما يصل الطالب يحمل مجموعته")

    println("[4] كشف شعب ثم كشف مجموعات: الطالب نفسه لا يتكرر")
    val sec = AdminOps.ImportKind.SECTION
    val st0 = AdminState(seq = 0, majors = listOf("الأمن السيبراني"), levelNames = listOf("الأول"), sectionNames = listOf("أ", "ب"), groupNames = g3)
    val secGroups = listOf(AdminOps.ImportGroup("أ", listOf("محمد عبدالله الصلوي", "دلال وضاح علي")), AdminOps.ImportGroup("ب", listOf("سارة علي احمد")))
    val secPlan = AdminOps.planGroupImport(st0, secGroups, sec)
    ok(secPlan.add.size == 3 && secPlan.update.isEmpty(), "كشف شعب على قاعدة فارغة: 3 جدد")
    val creds4 = secPlan.add.associate { (n, _) -> n to ("C_$n" to "K_$n") }
    val st1 = AdminOps.applyGroupImport(st0, secPlan, "الأمن السيبراني", "الأول", "", creds4, sec)
    ok(st1.students.size == 3 && st1.students.first { it.name == "سارة علي احمد" }.section == "ب" && st1.students.all { it.group == "" }, "الجدد بشعبهم وبلا مجموعة")
    ok(st1.sectionNames == listOf("أ", "ب") && st1.groupNames == g3, "قوائم الإعدادات سليمة (الشعب لا تُضاف للمجموعات)")
    // الآن كشف المجموعات للطلاب أنفسهم، بأسماء ينقصها حروف كما في ملف PDF
    val grpGroups = listOf(AdminOps.ImportGroup("مج1", listOf("محمد عبدا الصلوي", "سارة علي احمد")), AdminOps.ImportGroup("مج2", listOf("دلل وضاح علي")))
    val grpPlan = AdminOps.planGroupImport(st1, grpGroups)
    ok(grpPlan.add.isEmpty() && grpPlan.update.size == 3 && grpPlan.duplicates == 0, "كشف المجموعات: لا جدد، الثلاثة موجودون فتُضبط مجموعاتهم")
    val st2 = AdminOps.applyGroupImport(st1, grpPlan, "الأمن السيبراني", "الأول", "", emptyMap())
    ok(st2.students.size == 3, "لم يتكرر أي طالب")
    ok(st2.students.first { it.name == "محمد عبدالله الصلوي" }.let { it.section == "أ" && it.group == "مج1" }, "شعبته أ ومجموعته مج1 معًا")
    ok(st2.students.first { it.name == "دلال وضاح علي" }.let { it.section == "أ" && it.group == "مج2" }, "الاسم الناقص «دلل» طابق «دلال» وضُبطت مجموعته")
    ok(AdminOps.planGroupImport(st2, grpGroups).let { it.update.isEmpty() && it.same == 3 }, "إعادة استيراد الكشف نفسه: لا تغيير")
    ok(AdminOps.suggestSectionName("أ", listOf("ش أ", "ش ب")) == "ش أ" && AdminOps.suggestSectionName("ب", listOf("أ", "ب")) == "ب" && AdminOps.suggestSectionName("ج", listOf("أ")) == "ج", "اقتراح اسم الشعبة")

    println("[5] تعيين المواد لكل طلاب تخصص ومستوى")
    val courses = listOf(
        Course(1, "MATH101", "الرياضيات", "أ"), Course(2, "MATH101", "الرياضيات", "ب"),
        Course(3, "CS110", "مهارات الحاسوب", "مج1"), Course(4, "CS110", "مهارات الحاسوب", "مج2"),
        Course(5, "ISL101", "الثقافة الإسلامية", "الكل")
    )
    val other = Student("O1", "طالب مستوى آخر", "", "الأمن السيبراني", "الثاني", "أ", emptyList(), "X1", "KX", "")
    val sa = st2.copy(courses = courses, students = st2.students + other)
    val (sb, rep) = AdminOps.assignCoursesToLevel(sa, "الأمن السيبراني", "الأول", listOf(1, 2, 3, 4, 5))
    fun idsOf(n: String) = sb.students.first { it.name == n }.courseIds.toSet()
    ok(rep.targets == 3 && rep.unmatched.isEmpty(), "ثلاثة طلاب مستهدفون ولا غير مطابق")
    ok(idsOf("محمد عبدالله الصلوي") == setOf(1L, 3L, 5L), "شعبة أ + مجموعة مج1 + مادة واحدة للكل")
    ok(idsOf("سارة علي احمد") == setOf(2L, 3L, 5L), "شعبة ب + مج1")
    ok(idsOf("دلال وضاح علي") == setOf(1L, 4L, 5L), "شعبة أ + مج2")
    ok(sb.students.first { it.studentId == "O1" }.courseIds.isEmpty(), "طالب مستوى آخر لا يتأثر")
    ok(rep.enrolled == 9 && rep.already == 0, "9 تسجيلات جديدة")
    val (sc, rep2) = AdminOps.assignCoursesToLevel(sb, "الأمن السيبراني", "الأول", listOf(1, 2, 3, 4, 5))
    ok(rep2.enrolled == 0 && rep2.already == 9 && sc.students.map { it.courseIds } == sb.students.map { it.courseIds }, "إعادة التعيين لا تكرر ولا تضيف")
    val noSec = sa.copy(students = sa.students.map { if (it.name == "سارة علي احمد") it.copy(section = "", group = "") else it })
    val (sd, rep3) = AdminOps.assignCoursesToLevel(noSec, "الأمن السيبراني", "الأول", listOf(1, 2, 3, 4, 5))
    ok(rep3.unmatched["MATH101"] == 1 && rep3.unmatched["CS110"] == 1 && sd.students.first { it.name == "سارة علي احمد" }.courseIds == listOf(5L), "طالب بلا شعبة ولا مجموعة: لا يُسجَّل خطأً في مادة متعددة السجلات، ويُبلَّغ عنه")
    ok(AdminOps.studentsIn(sa, "الأمن السيبراني", "الأول").size == 3, "عدّ طلاب التخصص/المستوى")

    println("[6] كشف الحضور: المحاضرة = يوم واحد")
    fun at(day: Int, hour: Int): Long = java.util.Calendar.getInstance().apply { clear(); set(2026, 9, day, hour, 0, 0) }.timeInMillis
    val enrolled = sb.copy(
        sessions = listOf(SessionRow("s1", 1, "D1", at(12, 9)), SessionRow("s2", 1, "D1", at(12, 11)), SessionRow("s3", 1, "D1", at(13, 9))),
        records = listOf(RecordRow("s1", sb.students[0].studentId, 1), RecordRow("s2", sb.students[0].studentId, 2), RecordRow("s3", sb.students[0].studentId, 3), RecordRow("s2", sb.students[1].studentId, 4))
    )
    val sheet = AdminOps.courseSheet(enrolled, 1)
    ok(sheet.total == 2 && sheet.rows.map { it.name }.sorted() == listOf("دلال وضاح علي", "محمد عبدالله الصلوي"), "جلستا اليوم نفسه = محاضرة واحدة، والطلاب المسجَّلون في المادة فقط")
    ok(sheet.rows.first { it.name == "محمد عبدالله الصلوي" }.count == 2, "حضر في جلستي اليوم الأول والثاني من اليوم نفسه: يُحسب مرة واحدة (2 من 2)")
    ok(AdminOps.courseSummary(enrolled).first().contains("2 محاضرة") && AdminOps.courseSummary(enrolled).first().contains("3 تسجيل"), "ملخص المادة: محاضرتان و3 تسجيلات")
    ok(AdminOps.attendanceCsv(enrolled).lines().any { it.contains("MATH101-أ,2,2,100") }, "ملف CSV: حضور 2 من 2 = 100%")

    println("\nالنتيجة: نجح $passed، فشل $failed")
    if (failed > 0) System.exit(1)
}
