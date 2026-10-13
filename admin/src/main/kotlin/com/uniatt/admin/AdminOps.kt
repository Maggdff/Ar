package com.uniatt.admin

import com.uniatt.core.*

/**
 * عمليات الإدارة كدوال نقية على الحالة (قابلة للاختبار): التخصصات والشعب (الإعدادات)، الدكاترة، الطلاب.
 * الأخطاء تُعاد نصًا عربيًا مفهومًا للمستخدم.
 */
object AdminOps {
    class Err(val msg: String)

    private fun List<String>.has(x: String) = any { it.equals(x, ignoreCase = true) }

    // ---- الإعدادات: التخصصات والشعب ----
    fun addMajor(s: AdminState, name: String): Pair<AdminState, Err?> {
        val n = name.trim()
        if (n.isEmpty()) return s to Err("اكتب اسم التخصص")
        if (s.majors.has(n)) return s to Err("التخصص موجود")
        return s.copy(majors = s.majors + n) to null
    }
    fun removeMajor(s: AdminState, name: String) = s.copy(majors = s.majors.filter { it != name })

    fun addSectionName(s: AdminState, name: String): Pair<AdminState, Err?> {
        val n = name.trim()
        if (n.isEmpty()) return s to Err("اكتب اسم الشعبة")
        if (n.contains('-') || n.contains(';') || n.contains('؛') || n.contains(',')) return s to Err("اسم الشعبة بلا - ; ،")
        if (s.sectionNames.has(n)) return s to Err("الشعبة موجودة")
        return s.copy(sectionNames = s.sectionNames + n) to null
    }
    fun removeSectionName(s: AdminState, name: String) = s.copy(sectionNames = s.sectionNames.filter { it != name })

    /** مجموعات العملي (مج1، مج2…) والمستويات: قوائم من الإعدادات تُختار منها عند إضافة الطلاب والجداول. */
    private fun validName(n: String) = !(n.contains('-') || n.contains(';') || n.contains('؛') || n.contains(',') || n.contains('\t') || n.contains('\n'))

    fun addGroupName(s: AdminState, name: String): Pair<AdminState, Err?> {
        val n = name.trim()
        if (n.isEmpty()) return s to Err("اكتب اسم المجموعة")
        if (!validName(n)) return s to Err("اسم المجموعة بلا - ; ،")
        if (s.groupNames.has(n)) return s to Err("المجموعة موجودة")
        return s.copy(groupNames = s.groupNames + n) to null
    }
    fun removeGroupName(s: AdminState, name: String) = s.copy(groupNames = s.groupNames.filter { it != name })

    fun addLevelName(s: AdminState, name: String): Pair<AdminState, Err?> {
        val n = name.trim()
        if (n.isEmpty()) return s to Err("اكتب اسم المستوى")
        if (!validName(n)) return s to Err("اسم المستوى بلا - ; ،")
        if (s.levelNames.has(n)) return s to Err("المستوى موجود")
        return s.copy(levelNames = s.levelNames + n) to null
    }
    fun removeLevelName(s: AdminState, name: String) = s.copy(levelNames = s.levelNames.filter { it != name })

    // ---- الدكاترة: بالاسم فقط (المعرّف يُولَّد)، والمواد تُختار من قائمة ----
    /** [id] = null لدكتور جديد. يعيد الحالة والدكتور. */
    fun saveDoctor(s: AdminState, id: String?, name: String, courseIds: List<Long>, newCode: () -> String, newMailbox: () -> String): Pair<AdminState, Doctor?> {
        val n = name.trim()
        if (n.isEmpty()) return s to null
        val valid = courseIds.filter { c -> s.courses.any { it.id == c } }.distinct()
        if (id != null) {
            val old = s.doctors.firstOrNull { it.doctorId == id } ?: return s to null
            val d = old.copy(name = n, courseIds = valid)
            return s.copy(doctors = s.doctors.map { if (it.doctorId == id) d else it }) to d
        }
        var seq = s.seq
        var newId: String
        do { seq++; newId = "D$seq" } while (s.doctors.any { it.doctorId == newId })
        val d = Doctor(newId, n, valid, newCode(), mailbox = newMailbox())
        return s.copy(seq = seq, doctors = s.doctors + d) to d
    }

    // ---- الطلاب: بالاسم والتخصص والشعبة والمواد ----
    /** طالب جديد. [idInput] فارغ = رقم داخلي يُولَّد. [code]/[keyHex] يُحسبان خارجًا (PBKDF2 بطيء). */
    fun addStudent(
        s: AdminState, idInput: String, name: String, major: String, section: String, level: String,
        courseIds: List<Long>, code: String, keyHex: String, group: String = ""
    ): Pair<AdminState, Err?> {
        val n = name.trim()
        if (n.isEmpty()) return s to Err("اكتب اسم الطالب")
        var seq = s.seq
        var id = idInput.trim()
        if (id.contains(',') || id.contains('\t') || id.contains('|')) return s to Err("الرقم الجامعي لا يحتوي , أو |")
        if (id.isEmpty()) { do { seq++; id = "S$seq" } while (s.students.any { it.studentId == id }) }
        else if (s.students.any { it.studentId == id }) return s to Err("الرقم الجامعي $id موجود لطالب آخر")
        val valid = courseIds.filter { c -> s.courses.any { it.id == c } }.distinct()
        val st = Student(id, n, "", major, level.trim(), section, valid, code, keyHex, group)
        return s.copy(seq = seq, students = s.students + st) to null
    }

    fun editStudent(
        s: AdminState, id: String, name: String, major: String, section: String, level: String, courseIds: List<Long>,
        group: String? = null
    ): Pair<AdminState, Err?> {
        val n = name.trim()
        if (n.isEmpty()) return s to Err("اكتب اسم الطالب")
        if (s.students.none { it.studentId == id }) return s to Err("الطالب غير موجود")
        val valid = courseIds.filter { c -> s.courses.any { it.id == c } }.distinct()
        return s.copy(students = s.students.map {
            if (it.studentId == id) it.copy(name = n, major = major, section = section, level = level.trim(), courseIds = valid, group = group ?: it.group) else it
        }) to null
    }

    /** مادة جديدة بشعبة من قائمة الإعدادات. */
    fun addCourse(s: AdminState, code: String, name: String, section: String): Pair<AdminState, Err?> {
        val c = code.trim(); val n = name.trim()
        if (c.isEmpty() || n.isEmpty()) return s to Err("اكتب رمز المادة واسمها")
        if (section.isBlank()) return s to Err("اختر الشعبة (تُنشأ من الإعدادات)")
        if (c.contains('-') || c.contains(';') || c.contains('؛') || c.contains(',')) return s to Err("رمز المادة بلا - ; ،")
        val dup = s.courses.firstOrNull { it.code.equals(c, true) && it.section.equals(section, true) }
        if (dup != null) {
            if (dup.name == n) return s to Err("المادة/الشعبة موجودة")
            return s.copy(courses = s.courses.map { if (it.id == dup.id) it.copy(name = n) else it }) to null
        }
        val id = s.seq + 1
        return s.copy(seq = id, courses = s.courses + Course(id, c, n, section)) to null
    }

    // ---- جداول التخصصات (تظهر عند الطلاب) ----
    /** هل تتقاطع فئتا الطلاب؟ (شعبة/مجموعة فارغة = الكل). */
    private fun audienceMeets(a: TEntry, b: TEntry) =
        (a.section.isBlank() || b.section.isBlank() || a.section.equals(b.section, true)) &&
            (a.group.isBlank() || b.group.isBlank() || a.group.equals(b.group, true))

    /** تنبيهات (لا منع) عند إضافة خانة تتداخل مع خانة أخرى لنفس التخصص والمستوى ولطلاب مشتركين. */
    fun timetableWarnings(s: AdminState, e: TEntry): List<String> =
        s.timetable.filter {
            it.id != e.id && it.major.equals(e.major, true) && it.level.equals(e.level, true) && it.day == e.day &&
                it.startMin < e.endMin && e.startMin < it.endMin && audienceMeets(it, e)
        }.map { "⚠ تتداخل مع «${it.title}» (${Schedule.DAYS[it.day]} ${Timetable.timeRange(it)}، ${Timetable.audience(it)})" }

    fun addTimetableEntry(s: AdminState, e: TEntry): Pair<AdminState, Err?> {
        val t = e.copy(title = e.title.trim(), major = e.major.trim(), level = e.level.trim(), section = e.section.trim(), group = e.group.trim(),
            teacher = e.teacher.trim(), room = e.room.trim(), note = e.note.trim())
        if (t.major.isEmpty()) return s to Err("اختر التخصص")
        if (t.title.isEmpty()) return s to Err("اكتب اسم المادة أو عنوان الخانة")
        if (!Timetable.valid(t.copy(id = 0))) return s to Err("وقت النهاية يجب أن يكون بعد البداية")
        if (s.timetable.any {
                it.major.equals(t.major, true) && it.level.equals(t.level, true) && it.day == t.day && it.startMin == t.startMin &&
                    it.endMin == t.endMin && it.title == t.title && it.section.equals(t.section, true) && it.group.equals(t.group, true) && it.kind == t.kind
            }) return s to Err("هذه الخانة موجودة")
        val id = s.seq + 1
        return s.copy(seq = id, timetable = s.timetable + t.copy(id = id)) to null
    }

    fun deleteTimetableEntry(s: AdminState, id: Long) = s.copy(timetable = s.timetable.filter { it.id != id })

    // ---- ما يراه الطالب عند اتصاله بالإنترنت ----
    /** بيانات الطالب ومواده (بمواعيدها) وجدوله بحسب تخصصه ومستواه وشعبته ومجموعته. */
    fun studentPublic(s: AdminState, st: Student): StudentPublic {
        val slots = s.slots.map { RSlot(it.courseId, it.day, it.startMin, it.endMin, it.room) }
        val courses = s.courses.filter { it.id in st.courseIds }.map { c ->
            val sched = Schedule.describeAll(slots, c.id, withRoom = false)
            "${c.code} ${c.name} (${c.section})" + if (sched.isNotBlank()) " — $sched" else ""
        }
        return StudentPublic(st.studentId, st.name, st.faculty, st.major, st.level, st.section, st.group, courses,
            Timetable.forStudent(s.timetable, st.major, st.level, st.section, st.group))
    }

    // ---- استيراد كشوف الشعب أو مجموعات العملي من PDF ----
    /** هدف الكشف المستورد: شعبة (نظري) أو مجموعة عملي. نفس الطالب قد يرد في كشف شعب وفي كشف مجموعات: يُطابَق بالاسم فلا يتكرر. */
    enum class ImportKind { SECTION, GROUP }

    /** [groupName] اسم الشعبة أو المجموعة عندك (حسب نوع الاستيراد). */
    class ImportGroup(val groupName: String, val names: List<String>)

    /** add: (الاسم، الشعبة/المجموعة) لطلاب جدد. update: (الرقم الداخلي، الشعبة/المجموعة) لطلاب موجودين. same: موجودون بالقيمة نفسها. duplicates: مكرر داخل الملف. */
    class ImportPlan(val add: List<Pair<String, String>>, val update: List<Pair<String, String>>, val same: Int, val duplicates: Int)

    /** اقتراح اسم مجموعة عندك لحرف المجموعة في الملف (أ، ب، ج…): نفس الاسم، أو «مج1/مج2…» حسب ترتيب الحرف، وإلا الحرف نفسه. */
    fun suggestGroupName(label: String, existing: List<String>): String {
        val l = label.trim()
        existing.firstOrNull { it.equals(l, true) }?.let { return it }
        val idx = "أبجدهوزحط".indexOf(l.firstOrNull() ?: ' ') + 1
        if (idx > 0) existing.firstOrNull { it.replace(" ", "").equals("مج$idx", true) || it.replace(" ", "").equals("مجموعة$idx", true) }?.let { return it }
        return l
    }

    /** اقتراح اسم شعبة عندك لحرف الشعبة في الملف: نفس الاسم، أو «ش أ»/«شعبة أ» ← «أ»، وإلا الحرف نفسه. */
    fun suggestSectionName(label: String, existing: List<String>): String {
        val l = label.trim()
        fun core(x: String) = x.replace("شعبة", "").replace(" ", "").let { if (it.length > 1 && it.startsWith("ش")) it.substring(1) else it }
        existing.firstOrNull { it.equals(l, true) }?.let { return it }
        existing.firstOrNull { core(it).equals(core(l), true) }?.let { return it }
        return l
    }

    /** يطابق الطلاب الموجودين بالاسم (يتجاهل نقص الحروف ا/ل/ه في الملف) فلا يتكرر طالب. */
    fun planGroupImport(s: AdminState, groups: List<ImportGroup>, kind: ImportKind = ImportKind.GROUP): ImportPlan {
        val byKey = HashMap<String, Student>()
        s.students.forEach { byKey.putIfAbsent(NameFix.key(it.name), it) }
        val seen = HashSet<String>()
        val add = ArrayList<Pair<String, String>>(); val upd = ArrayList<Pair<String, String>>()
        var same = 0; var dup = 0
        for (g in groups) for (n in g.names) {
            val k = NameFix.key(n)
            if (k.isEmpty() || !seen.add(k)) { dup++; continue }
            val ex = byKey[k]
            val target = g.groupName.trim()
            when {
                ex == null -> add.add(n.trim() to target)
                (if (kind == ImportKind.SECTION) ex.section else ex.group).equals(target, true) -> same++
                else -> upd.add(ex.studentId to target)
            }
        }
        return ImportPlan(add, upd, same, dup)
    }

    /**
     * يطبّق الخطة: يضيف الجدد (بكود ومفتاح من [creds] حسب الاسم) ويضبط الشعبة أو المجموعة (حسب [kind]) للموجودين.
     * التخصص/المستوى تُملأ للجدد، وللموجودين فقط إن كانت فارغة. [section] شعبة عامة للجدد في استيراد المجموعات (اختياري).
     * وتُنشأ الشعب/المجموعات الناقصة في الإعدادات.
     */
    fun applyGroupImport(
        s: AdminState, plan: ImportPlan, major: String, level: String, section: String, creds: Map<String, Pair<String, String>>,
        kind: ImportKind = ImportKind.GROUP
    ): AdminState {
        var seq = s.seq
        val students = ArrayList(s.students)
        for ((name, target) in plan.add) {
            val (code, key) = creds[name] ?: continue
            var id: String
            do { seq++; id = "S$seq" } while (students.any { it.studentId == id })
            students.add(
                if (kind == ImportKind.SECTION) Student(id, name, "", major.trim(), level.trim(), target, emptyList(), code, key, "")
                else Student(id, name, "", major.trim(), level.trim(), section.trim(), emptyList(), code, key, target)
            )
        }
        val upd = plan.update.toMap()
        val done = students.map { st ->
            val t = upd[st.studentId] ?: return@map st
            val filled = st.copy(major = st.major.ifBlank { major.trim() }, level = st.level.ifBlank { level.trim() })
            if (kind == ImportKind.SECTION) filled.copy(section = t) else filled.copy(group = t, section = filled.section.ifBlank { section.trim() })
        }
        val names = (plan.add.map { it.second } + plan.update.map { it.second }).distinct().filter { it.isNotBlank() }
        fun merge(cur: List<String>): List<String> {
            var out = cur
            names.forEach { g -> if (out.none { it.equals(g, true) }) out = out + g }
            return out
        }
        return if (kind == ImportKind.SECTION) s.copy(seq = seq, students = done, sectionNames = merge(s.sectionNames))
        else s.copy(seq = seq, students = done, groupNames = merge(s.groupNames))
    }

    // ---- تعيين مواد لكل طلاب تخصص ومستوى ----
    class AssignReport(val targets: Int, val enrolled: Int, val already: Int, val unmatched: Map<String, Int>)

    private fun eqs(a: String, b: String) = a.trim().equals(b.trim(), true)
    private fun secCore(x: String) = x.replace("شعبة", "").replace(" ", "").let { if (it.length > 1 && it.startsWith("ش")) it.substring(1) else it }

    /** طلاب تخصص ومستوى معيّنين. */
    fun studentsIn(s: AdminState, major: String, level: String): List<Student> =
        s.students.filter { eqs(it.major, major) && eqs(it.level, level) }

    /**
     * يسجّل كل طلاب (تخصص، مستوى) في المواد المختارة. المادة التي لها أكثر من سجل بالرمز نفسه (شعب/مجموعات) يُختار لكل طالب
     * السجل المطابق لشعبته أو لمجموعته؛ ومن لا يطابق أيًّا منها يُذكر في [AssignReport.unmatched] ولا يُسجَّل خطأً. إضافة فقط: لا يُحذف شيء.
     */
    fun assignCoursesToLevel(s: AdminState, major: String, level: String, courseIds: List<Long>): Pair<AdminState, AssignReport> {
        val byCode = s.courses.filter { it.id in courseIds }.groupBy { it.code.trim().uppercase() }
        var enrolled = 0; var already = 0; var targets = 0
        val unmatched = LinkedHashMap<String, Int>()
        val students = s.students.map { st ->
            if (!(eqs(st.major, major) && eqs(st.level, level))) return@map st
            targets++
            val ids = ArrayList(st.courseIds)
            for ((code, recs) in byCode) {
                val pick = if (recs.size == 1) recs[0] else recs.firstOrNull { c ->
                    val k = secCore(c.section)
                    k.isNotEmpty() && (k.equals(secCore(st.section), true) || k.equals(secCore(st.group), true))
                }
                if (pick == null) { unmatched[code] = (unmatched[code] ?: 0) + 1; continue }
                if (pick.id in ids) already++ else { ids.add(pick.id); enrolled++ }
            }
            st.copy(courseIds = ids)
        }
        return s.copy(students = students) to AssignReport(targets, enrolled, already, unmatched)
    }

    // ---- كشف الحضور والتقارير (المحاضرة = مادة + يوم) ----
    /** كشف مادة: محاضراتها (يومًا يومًا) وحضور طلابها المسجَّلين. */
    fun courseSheet(s: AdminState, courseId: Long): AttendanceSheetData {
        val sess = s.sessions.filter { it.courseId == courseId }
        val ids = sess.map { it.sessionId }.toHashSet()
        val students = s.students.filter { courseId in it.courseIds }.sortedBy { it.name }
        return AttendanceSheet.build(
            sess.map { it.sessionId to it.startedAt }, students.map { it.studentId to it.name },
            s.records.filter { it.sessionId in ids }.map { it.sessionId to it.studentId }.toHashSet()
        )
    }

    fun courseSummary(s: AdminState): List<String> = s.courses.map { c ->
        val d = courseSheet(s, c.id)
        "${c.code}-${c.section} ${c.name}: ${d.total} محاضرة، ${d.rows.sumOf { it.count }} تسجيل حضور"
    }

    private fun q(x: String) = "\"" + x.replace("\"", "\"\"") + "\""

    fun attendanceCsv(s: AdminState): String {
        val sb = StringBuilder("studentId,name,course,attended,sessions,percent\n")
        val sheets = HashMap<Long, AttendanceSheetData>()
        for (st in s.students) for (cid in st.courseIds) {
            val c = s.courses.firstOrNull { it.id == cid } ?: continue
            val d = sheets.getOrPut(cid) { courseSheet(s, cid) }
            val att = d.rows.firstOrNull { it.studentId == st.studentId }?.count ?: 0
            sb.append("${st.studentId},${q(st.name)},${c.code}-${c.section},$att,${d.total},${AttendanceSheet.percent(att, d.total)}\n")
        }
        return sb.toString()
    }
}
