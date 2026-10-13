import com.uniatt.admin.*
import com.uniatt.core.*
import kotlinx.coroutines.*
import java.io.File
import java.security.SecureRandom

var passed = 0; var failed = 0
fun ok(c: Boolean, name: String) { if (c) { passed++; println("  PASS  $name") } else { failed++; println("  FAIL  $name") } }

const val CY = "الأمن السيبراني"

fun mk(id: String, name: String, major: String, level: String, section: String, group: String, courseIds: List<Long>, rnd: SecureRandom): Student {
    val code = Codes.random(rnd)
    return Student(id, name, "الهندسة", major, level, section, courseIds, code, Hex.enc(Kdf.studentKey(code)), group)
}

fun te(id: Long, title: String, day: Int = 6, a: Int = 480, b: Int = 600, major: String = CY, level: String = "الأول",
       sec: String = "", grp: String = "", kind: Int = TKind.NORMAL, prac: Boolean = false) =
    TEntry(id, major, level, day, a, b, title, kind, prac, sec, grp)

fun main(args: Array<String>) = runBlocking {
    val url = args[0]; val deadUrl = args[1]; val rnd = SecureRandom()

    println("[1] مطابقة الجدول مع الطالب (تخصص/مستوى/شعبة/مجموعة)")
    val all = listOf(
        te(1, "الرياضيات1"),                                                   // للجميع
        te(2, "الرياضيات المتقطعة", a = 600, b = 720, sec = "أ"),
        te(3, "الرياضيات المتقطعة ب", a = 600, b = 720, sec = "ب"),
        te(4, "مهارات الحاسوب", day = 1, grp = "مج3", prac = true),
        te(5, "سمية", day = 0, grp = "مج3", kind = TKind.MAKEUP),
        te(6, "اختبار", day = 6, kind = TKind.EXAM),
        te(7, "تخصص آخر", major = "علوم الحاسب"),
        te(8, "مستوى آخر", level = "الثاني"),
        te(9, "بلا مستوى", level = "")                                         // خانة بلا مستوى تخص كل مستويات التخصص
    )
    val a = Timetable.forStudent(all, CY, "الأول", "أ", "مج3").map { it.id }
    ok(a.toSet() == setOf(1L, 2L, 4L, 5L, 6L, 9L), "طالب (أ، مج3) يرى: العامة + شعبته + مجموعته + التعويضية والاختبار — $a")
    ok(3L !in a && 7L !in a && 8L !in a, "لا يرى شعبة ب ولا تخصصًا آخر ولا مستوى آخر")
    val b = Timetable.forStudent(all, CY, "الأول", "ب", "مج1").map { it.id }
    ok(3L in b && 2L !in b && 4L !in b && 5L !in b && 1L in b, "طالب (ب، مج1): شعبته فقط، ولا محاضرات مج3")
    val c = Timetable.forStudent(all, CY, "الأول", "أ", "").map { it.id }
    ok(4L !in c && 5L !in c && 2L in c, "طالب بلا مجموعة لا يرى خانات المجموعات")
    ok(Timetable.forStudent(all, " الأمن السيبراني ", "الأول", "أ", "مج3").isNotEmpty(), "مقارنة التخصص تتجاهل المسافات")
    val rt = Timetable.decode(Timetable.encode(listOf(te(1, "عنوان\tبه\nتاب").copy(teacher = "د. أ", room = "قاعة/ (103)", note = "ملاحظة"))))
    ok(rt.size == 1 && rt[0].title == "عنوان به تاب" && rt[0].room == "قاعة/ (103)", "ترميز/فكّ الجدول ينظّف TAB والأسطر")
    ok(Timetable.decode("garbage\n1\t2").isEmpty(), "نص تالف لا يرمي استثناء")
    ok(!Timetable.valid(te(1, " ")) && !Timetable.valid(te(1, "x", a = 600, b = 600)), "خانة بلا عنوان أو بوقت معكوس غير صالحة")

    println("[2] عمليات المسؤول على الجدول")
    val s0 = AdminState(majors = listOf(CY), levelNames = listOf("الأول"))
    val (s1, e1) = AdminOps.addTimetableEntry(s0, te(0, "الرياضيات1", sec = "أ"))
    ok(e1 == null && s1.timetable.size == 1 && s1.timetable[0].id == 1L, "إضافة خانة بمعرّف جديد")
    ok(AdminOps.addTimetableEntry(s1, te(0, "الرياضيات1", sec = "أ")).second != null, "خانة مكررة مرفوضة")
    ok(AdminOps.addTimetableEntry(s1, te(0, "   ")).second != null, "عنوان فارغ مرفوض")
    ok(AdminOps.addTimetableEntry(s1, te(0, "س", a = 700, b = 650)).second != null, "وقت نهاية قبل البداية مرفوض")
    ok(AdminOps.addTimetableEntry(s1, te(0, "س", major = " ")).second != null, "بلا تخصص مرفوض")
    ok(AdminOps.timetableWarnings(s1, te(0, "تداخل", a = 540, b = 660, sec = "أ")).size == 1, "تنبيه تداخل لنفس الشعبة")
    ok(AdminOps.timetableWarnings(s1, te(0, "تداخل", a = 540, b = 660, sec = "ب")).isEmpty(), "لا تنبيه لشعبة أخرى")
    ok(AdminOps.timetableWarnings(s1, te(0, "تداخل", a = 540, b = 660)).size == 1, "خانة للجميع تتداخل مع خانة شعبة")
    ok(AdminOps.deleteTimetableEntry(s1, 1L).timetable.isEmpty(), "حذف خانة")
    val (g1, _) = AdminOps.addGroupName(s0, "مج1")
    ok(g1.groupNames == listOf("مج1") && AdminOps.addGroupName(g1, "مج1").second != null && AdminOps.addGroupName(g1, "").second != null, "مجموعات العملي: إضافة/تكرار/فارغ")
    ok(AdminOps.addLevelName(s0, "الثاني").first.levelNames.size == 2 && AdminOps.removeLevelName(s0, "الأول").levelNames.isEmpty(), "المستويات: إضافة/حذف")
    val (s2, _) = AdminOps.addStudent(s0, "", "طالب", CY, "أ", "الأول", emptyList(), "CODE", "KEY", "مج2")
    ok(s2.students[0].group == "مج2", "الطالب يحمل مجموعته")
    ok(AdminOps.editStudent(s2, s2.students[0].studentId, "طالب", CY, "أ", "الأول", emptyList()).first.students[0].group == "مج2", "تعديل بلا ذكر المجموعة لا يمسحها")
    ok(AdminOps.editStudent(s2, s2.students[0].studentId, "طالب", CY, "أ", "الأول", emptyList(), "مج1").first.students[0].group == "مج1", "تعديل المجموعة")

    println("[3] نشر صندوق الطالب والتحقق من الكود عبر الإنترنت")
    val dir = File.createTempFile("stu", "").also { it.delete(); it.mkdirs() }
    val store = AdminStore(dir)
    val sA = mk("1001", "محمد أحمد", CY, "الأول", "أ", "مج3", listOf(1L), rnd)
    val sB = mk("1002", "سارة علي", CY, "الأول", "ب", "مج1", listOf(1L), rnd)
    store.save(AdminState(seq = 20, relayUrl = url, majors = listOf(CY), courses = listOf(Course(1, "CS101", "برمجة", "أ")),
        slots = listOf(Slot(10, 1, 0, 480, 570, "قاعة 3")), students = listOf(sA, sB), timetable = all.filter { it.major == CY }))
    var r = AdminSync.run(store)
    ok(r.studentsPushed == 2 && !r.offline && r.relayError == null && r.errors.isEmpty(), "أول مزامنة: نُشر صندوقان")
    val fa = StudentClient.fetch(sA.keyHex, url)
    ok(fa is StudentClient.Fetch.Ok, "كود صحيح: يصل الصندوق ويُفكّ")
    val pa = (fa as StudentClient.Fetch.Ok).pub
    ok(pa.name == "محمد أحمد" && pa.major == CY && pa.level == "الأول" && pa.section == "أ" && pa.group == "مج3", "البيانات الشخصية صحيحة (شعبة + مجموعة)")
    ok(pa.timetable.map { it.id }.toSet() == Timetable.forStudent(all.filter { it.major == CY }, CY, "الأول", "أ", "مج3").map { it.id }.toSet(), "الجدول مصفّى لتخصصه وشعبته ومجموعته")
    ok(pa.timetable.any { it.kind == TKind.MAKEUP } && pa.timetable.any { it.kind == TKind.EXAM } && pa.timetable.any { it.practical }, "التعويضية والاختبار والعملي تصل بأنواعها")
    ok(pa.courses.size == 1 && pa.courses[0].startsWith("CS101 برمجة (أ)") && pa.courses[0].contains("08:00"), "المواد تصل مع مواعيدها")
    val fb = (StudentClient.fetch(sB.keyHex, url) as StudentClient.Fetch.Ok).pub
    ok(fb.group == "مج1" && fb.timetable.none { it.group == "مج3" } && fb.timetable.any { it.section == "ب" }, "الطالب الآخر يرى شعبته ومجموعته فقط")
    val wrong = Hex.enc(Kdf.studentKey(Codes.random(rnd)))
    ok(StudentClient.fetch(wrong, url) is StudentClient.Fetch.NotFound, "كود صحيح الشكل لكن غير مسجَّل عند المسؤول: مرفوض (NotFound)")
    val raw = Relay(url).getBytes(StudentBox.path(sA.keyHex))!!
    ok(!String(raw, Charsets.ISO_8859_1).contains("محمد") && StudentBox.open(sB.keyHex, raw) == null, "المحتوى مشفّر، ولا يفتحه مفتاح طالب آخر")
    ok(StudentBox.boxId(sA.keyHex) != StudentBox.boxId(sB.keyHex) && StudentBox.boxId(sA.keyHex).length == 32, "عنوان الصندوق مشتق من المفتاح (128 بت) ومختلف لكل طالب")

    r = AdminSync.run(store)
    ok(r.studentsPushed == 0, "مزامنة ثانية بلا تغيير: لا إعادة نشر")
    store.update { it.copy(timetable = it.timetable + te(50, "تعويضية جديدة", day = 3, sec = "أ", kind = TKind.MAKEUP)) }
    r = AdminSync.run(store)
    ok(r.studentsPushed == 1, "خانة لشعبة أ فقط: يُحدَّث صندوق طالب أ وحده")
    ok((StudentClient.fetch(sA.keyHex, url) as StudentClient.Fetch.Ok).pub.timetable.any { it.id == 50L } &&
        (StudentClient.fetch(sB.keyHex, url) as StudentClient.Fetch.Ok).pub.timetable.none { it.id == 50L }, "الخانة الجديدة وصلت لأ ولم تصل لـ ب")
    store.update { s -> s.copy(students = s.students.map { if (it.studentId == "1002") it.copy(group = "مج3") else it }) }
    r = AdminSync.run(store)
    ok(r.studentsPushed == 1 && (StudentClient.fetch(sB.keyHex, url) as StudentClient.Fetch.Ok).pub.timetable.any { it.group == "مج3" }, "نقل طالب لمجموعة أخرى يغيّر جدوله")

    println("[4] كود جديد / حذف طالب يُبطل الكود القديم")
    val newCode = Codes.random(rnd); val newKey = Hex.enc(Kdf.studentKey(newCode))
    store.update { s -> s.copy(students = s.students.map { if (it.studentId == "1001") it.copy(code = newCode, keyHex = newKey) else it },
        pendingWipes = s.pendingWipes + StudentBox.boxId(sA.keyHex)) }
    r = AdminSync.run(store)
    ok(r.studentsPushed == 1 && StudentClient.fetch(sA.keyHex, url) is StudentClient.Fetch.NotFound, "الكود القديم لم يعد يعمل بعد «كود جديد»")
    ok(StudentClient.fetch(newKey, url) is StudentClient.Fetch.Ok && store.load().pendingWipes.isEmpty(), "الكود الجديد يعمل، وقائمة المحو فرغت")
    store.update { s -> s.copy(students = s.students.filter { it.studentId != "1002" }, pendingWipes = s.pendingWipes + StudentBox.boxId(sB.keyHex)) }
    AdminSync.run(store)
    ok(StudentClient.fetch(sB.keyHex, url) is StudentClient.Fetch.NotFound && !store.load().pubHashes.containsKey("1002"), "طالب محذوف: صندوقه يُمحى وبصمته تُنظَّف")

    println("[5] حالات الاتصال عند الطالب")
    ok(StudentClient.fetch(newKey, "http://127.0.0.1:1") is StudentClient.Fetch.Offline, "بلا اتصال: Offline (لا تفعيل بلا إنترنت)")
    RelayLocator.candidatesProvider = { _ -> listOf(deadUrl, url) }
    ok(StudentClient.fetch(newKey, deadUrl) is StudentClient.Fetch.Ok, "عنوان قاعدة خاطئ (404): يكتشف الصحيح ويجلب")
    RelayLocator.candidatesProvider = { _ -> listOf(deadUrl) }
    val dead = StudentClient.fetch(newKey, deadUrl)
    ok(dead is StudentClient.Fetch.Error && dead.msg.contains("404"), "لا قاعدة إطلاقًا: رسالة خطأ مفهومة")
    Relay(url).putBytes(StudentBox.path(newKey), ByteArray(60) { 7 })
    ok(StudentClient.fetch(newKey, url) is StudentClient.Fetch.Error, "بيانات معدَّلة/تالفة على الخادم: خطأ (لا تفعيل)")
    ok(StudentBox.openPublic(newKey, StudentBox.seal(newKey, "USP1\nP\tx".toByteArray())) == null, "حمولة غير صالحة تُرفض")

    println("[6] الحفظ في الحالة (JSON)")
    val st = store.load()
    val back = StateJson.fromJson(StateJson.toJson(st.copy(groupNames = listOf("مج1"), levelNames = listOf("الأول"), pendingWipes = listOf("abc"))))
    ok(back.timetable == st.timetable && back.groupNames == listOf("مج1") && back.levelNames == listOf("الأول") &&
        back.pubHashes == st.pubHashes && back.pendingWipes == listOf("abc") && back.students.first().group == st.students.first().group, "الجداول والمجموعات والمستويات والبصمات تُحفظ وتُقرأ")
    ok(StateJson.fromJson("""{"v":2,"seq":0,"epoch":0,"courses":[],"students":[{"id":"1","name":"n","faculty":"","major":"","level":"","section":"","courses":[],"code":"c","key":"k"}],"doctors":[],"bindings":[],"sessions":[],"records":[]}""")
        .let { it.students[0].group == "" && it.timetable.isEmpty() && it.pubHashes.isEmpty() }, "حالة قديمة (v2) بلا جداول ولا مجموعات تُقرأ بسلام")

    println("\nالنتيجة: نجح $passed، فشل $failed")
    if (failed > 0) System.exit(1)
}
