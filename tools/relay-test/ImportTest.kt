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

    println("\nالنتيجة: نجح $passed، فشل $failed")
    if (failed > 0) System.exit(1)
}
