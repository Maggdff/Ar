import com.uniatt.admin.*
import com.uniatt.core.*

var passed = 0; var failed = 0
fun ok(c: Boolean, name: String) { if (c) { passed++; println("  PASS  $name") } else { failed++; println("  FAIL  $name") } }

fun main() {
    val M = "علوم الحاسب"
    fun stu(id: String, n: String, sec: String, grp: String, lvl: String = "الأول", major: String = M) =
        Student(id, n, "", major, lvl, sec, emptyList(), "C$id", "K$id", grp)
    val base = AdminState(
        seq = 10, majors = listOf(M, "نظم المعلومات"), sectionNames = listOf("أ", "ب", "ج"), groupNames = listOf("مج1", "مج2"),
        levelNames = listOf("الأول", "الثاني")
    )

    println("[1] إضافة مادة بالاسم فقط: نظري بعدة شعب، وعملي بمجموعات")
    val r1 = AdminOps.addCourses(base, "برمجة 1", M, false, listOf("أ", "ب"))
    ok(r1.err == null && r1.added == 2 && r1.state.courses.size == 2, "نظري: سجل لكل شعبة (2)")
    ok(r1.state.courses.all { it.code.isBlank() && it.major == M && !it.practical && it.name == "برمجة 1" }, "بلا رمز، بتخصصها، ونوعها نظري")
    ok(r1.state.courses.map { it.section } == listOf("أ", "ب") && r1.state.seq == 12L, "الشعب أ وب ومعرّفات جديدة")
    val r2 = AdminOps.addCourses(r1.state, "برمجة 1", M, true, listOf("مج1", "مج2"))
    ok(r2.err == null && r2.added == 2 && r2.state.courses.count { it.practical } == 2, "عملي بنفس الاسم: سجلان للمجموعتين ولا يتعارض مع النظري")

    println("[2] التحقق والتكرار")
    ok(AdminOps.addCourses(base, "  ", M, false, listOf("أ")).err != null, "اسم فارغ مرفوض")
    ok(AdminOps.addCourses(base, "x", "", false, listOf("أ")).err != null, "بلا تخصص مرفوض")
    ok(AdminOps.addCourses(base, "x", M, false, emptyList()).err?.msg == "اختر الشعبة (أو أكثر)", "نظري بلا شعبة مرفوض")
    ok(AdminOps.addCourses(base, "x", M, true, emptyList()).err?.msg?.contains("مجموعة") == true, "عملي بلا مجموعة مرفوض")
    val dup = AdminOps.addCourses(r2.state, "برمجة 1", M, false, listOf("ب", "ج"))
    ok(dup.err == null && dup.added == 1 && dup.skipped == 1 && dup.state.courses.size == 5, "شعبة موجودة تُتخطّى وغيرها تُضاف")
    ok(AdminOps.addCourses(r2.state, "برمجة 1", M, false, listOf("أ", "ب")).err != null, "كلها موجودة: خطأ بلا تغيير")
    ok(AdminOps.addCourses(r2.state, "برمجة 1", "نظم المعلومات", false, listOf("أ")).added == 1, "نفس الاسم لتخصص آخر مادة مختلفة")

    println("[3] الحفظ والاسترجاع (JSON) والتوافق مع القديم")
    val back = StateJson.fromJson(StateJson.toJson(r2.state))
    ok(back.courses == r2.state.courses, "التخصص والنوع يُحفظان ويُسترجعان")
    val legacyJson = StateJson.toJson(base.copy(courses = listOf(Course(1, "CS101", "برمجة", "A"))))
        .replace(",\"major\":\"\"", "").replace(",\"prac\":false", "")
    val legacy = StateJson.fromJson(legacyJson).courses.single()
    ok(legacy.code == "CS101" && legacy.major == "" && !legacy.practical && legacy.short == "CS101-A برمجة", "ملف قديم بلا الحقلين الجديدين يُقرأ سليمًا")

    println("[4] ما يراه الدكتور والطالب")
    val th = r2.state.courses.first { !it.practical && it.section == "أ" }
    val pr = r2.state.courses.first { it.practical && it.section == "مج1" }
    ok(th.doctorTag == "نظري" && pr.doctorTag == "عملي" && legacy.doctorTag == "CS101", "حقل الرمز عند الدكتور = النوع للجديدة")
    ok(th.short == "برمجة 1 (نظري — شعبة أ)" && pr.short == "برمجة 1 (عملي — مج1)", "وصف مختصر بلا رمز")
    val sp = AdminOps.studentPublic(r2.state.copy(students = listOf(stu("S1", "أحمد", "أ", "مج1").copy(courseIds = listOf(th.id, pr.id)))), stu("S1", "أحمد", "أ", "مج1").copy(courseIds = listOf(th.id, pr.id)))
    ok(sp.courses.containsAll(listOf("برمجة 1 (نظري — شعبة أ)", "برمجة 1 (عملي — مج1)")), "الطالب يرى اسم المادة ونوعها وشعبتها/مجموعتها")

    println("[5] تسجيل مستوى كامل: النظري بالشعبة، والعملي بالمجموعة (صارم)")
    val students = listOf(
        stu("S1", "أحمد", "أ", "مج1"), stu("S2", "بدر", "ب", "مج2"), stu("S3", "جاسم", "ج", "مج1"),
        stu("S4", "خالد", "أ", "مج2", lvl = "الثاني")
    )
    // نظري: شعبة أ فقط؛ عملي: مج1 فقط
    val only = AdminOps.addCourses(AdminOps.addCourses(base, "شبكات", M, false, listOf("أ")).state, "شبكات", M, true, listOf("مج1")).state.copy(students = students)
    val ids = only.courses.map { it.id }
    val (asg, rep) = AdminOps.assignCoursesToLevel(only, M, "الأول", ids)
    fun got(id: String) = asg.students.first { it.studentId == id }.courseIds.toSet()
    ok(rep.targets == 3, "ثلاثة طلاب في المستوى الأول")
    ok(got("S1") == ids.toSet(), "أحمد (أ + مج1) في النظري والعملي")
    ok(got("S2").isEmpty(), "بدر (ب + مج2): لا يُسجَّل خطأً في شعبة أ ولا مجموعة مج1")
    ok(got("S3").size == 1 && only.courses.first { it.id in got("S3") }.practical, "جاسم (ج + مج1): في العملي فقط")
    ok(rep.unmatched.keys.toSet() == setOf("شبكات (نظري)", "شبكات (عملي)") && rep.unmatched["شبكات (نظري)"] == 2 && rep.unmatched["شبكات (عملي)"] == 1, "غير المطابقين يُبلَّغ عنهم باسم المادة ونوعها")
    ok(asg.students.first { it.studentId == "S4" }.courseIds.isEmpty(), "طالب المستوى الثاني لا يتأثر")

    println("[6] كشوف الطلاب: تخصص + مستوى + شعبة/مجموعة")
    val s = base.copy(students = students + stu("S5", "سالم", "أ", "", major = "نظم المعلومات"))
    ok(AdminOps.rosterStudents(s, M, "الأول", false, "أ").map { it.name } == listOf("أحمد"), "نظري شعبة أ: أحمد فقط")
    ok(AdminOps.rosterStudents(s, M, "الأول", true, "مج1").map { it.name } == listOf("أحمد", "جاسم"), "عملي مج1: أحمد وجاسم مرتبان بالاسم")
    ok(AdminOps.rosterStudents(s, M, "الأول", false, "").map { it.name } == listOf("أحمد", "بدر", "جاسم"), "بلا شعبة: كل طلاب المستوى")
    ok(AdminOps.rosterStudents(s, M, "الثاني", false, "أ").map { it.name } == listOf("خالد"), "المستوى الثاني منفصل")
    ok(AdminOps.rosterStudents(s, M, "الأول", false, "شعبة أ").size == 1, "«شعبة أ» تطابق «أ»")
    ok(AdminOps.rosterStudents(s, M, "الأول", true, "مج9").isEmpty(), "مجموعة بلا طلاب: قائمة فارغة")

    println("[7] كشف الحضور الفارغ والمرتبط بمادة")
    val roster = AdminOps.rosterStudents(s, M, "الأول", true, "مج1")
    val blank = AdminOps.rosterSheet(s, roster, null)
    ok(blank.total == 0 && blank.rows.map { it.no } == listOf(1, 2) && blank.rows.all { it.present.isEmpty() }, "كشف فارغ: أسماء مرقّمة بلا محاضرات")
    val c = AdminOps.addCourses(s, "شبكات", M, true, listOf("مج1")).state
    val cid = c.courses.single().id
    val withAtt = c.copy(sessions = listOf(SessionRow("a", cid, "D1", 1_000_000L)), records = listOf(RecordRow("a", "S1", 1)))
    val sh = AdminOps.rosterSheet(withAtt, roster, cid)
    ok(sh.total == 1 && sh.rows.first { it.studentId == "S1" }.count == 1 && sh.rows.first { it.studentId == "S3" }.count == 0, "الكشف بمادة: ✓ لأحمد وغياب جاسم")
    ok(AdminOps.rosterCourses(c, M, true, "مج1").map { it.id } == listOf(cid) && AdminOps.rosterCourses(c, M, false, "مج1").isEmpty(), "مواد الكشف: بنوعه وبمجموعته")

    println("\nالنتيجة: نجح $passed، فشل $failed")
    if (failed > 0) System.exit(1)
}
