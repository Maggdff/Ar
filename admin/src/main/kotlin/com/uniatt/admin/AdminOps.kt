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

    // ---- استيراد كشوف مجموعات العملي من PDF ----
    class ImportGroup(val groupName: String, val names: List<String>)

    /** add: (الاسم، المجموعة) لطلاب جدد. update: (الرقم الداخلي، المجموعة) لطلاب موجودين. same: موجودون بالمجموعة نفسها. duplicates: مكرر داخل الملف. */
    class ImportPlan(val add: List<Pair<String, String>>, val update: List<Pair<String, String>>, val same: Int, val duplicates: Int)

    /** اقتراح اسم مجموعة عندك لحرف المجموعة في الملف (أ، ب، ج…): نفس الاسم، أو «مج1/مج2…» حسب ترتيب الحرف، وإلا الحرف نفسه. */
    fun suggestGroupName(label: String, existing: List<String>): String {
        val l = label.trim()
        existing.firstOrNull { it.equals(l, true) }?.let { return it }
        val idx = "أبجدهوزحط".indexOf(l.firstOrNull() ?: ' ') + 1
        if (idx > 0) existing.firstOrNull { it.replace(" ", "").equals("مج$idx", true) || it.replace(" ", "").equals("مجموعة$idx", true) }?.let { return it }
        return l
    }

    /** يطابق الطلاب الموجودين بالاسم (يتجاهل نقص الحروف ا/ل/ه في الملف) فلا يتكرر طالب. */
    fun planGroupImport(s: AdminState, groups: List<ImportGroup>): ImportPlan {
        val byKey = HashMap<String, Student>()
        s.students.forEach { byKey.putIfAbsent(NameFix.key(it.name), it) }
        val seen = HashSet<String>()
        val add = ArrayList<Pair<String, String>>(); val upd = ArrayList<Pair<String, String>>()
        var same = 0; var dup = 0
        for (g in groups) for (n in g.names) {
            val k = NameFix.key(n)
            if (k.isEmpty() || !seen.add(k)) { dup++; continue }
            val ex = byKey[k]
            when {
                ex == null -> add.add(n.trim() to g.groupName.trim())
                ex.group.equals(g.groupName.trim(), true) -> same++
                else -> upd.add(ex.studentId to g.groupName.trim())
            }
        }
        return ImportPlan(add, upd, same, dup)
    }

    /**
     * يطبّق الخطة: يضيف الجدد (بكود ومفتاح من [creds] حسب الاسم) ويضبط مجموعة الموجودين.
     * التخصص/المستوى/الشعبة تُملأ للجدد، وللموجودين فقط إن كانت فارغة. وتُنشأ المجموعات الناقصة في الإعدادات.
     */
    fun applyGroupImport(
        s: AdminState, plan: ImportPlan, major: String, level: String, section: String, creds: Map<String, Pair<String, String>>
    ): AdminState {
        var seq = s.seq
        val students = ArrayList(s.students)
        for ((name, group) in plan.add) {
            val (code, key) = creds[name] ?: continue
            var id: String
            do { seq++; id = "S$seq" } while (students.any { it.studentId == id })
            students.add(Student(id, name, "", major.trim(), level.trim(), section.trim(), emptyList(), code, key, group))
        }
        val upd = plan.update.toMap()
        val done = students.map { st ->
            val g = upd[st.studentId] ?: return@map st
            st.copy(group = g, major = st.major.ifBlank { major.trim() }, level = st.level.ifBlank { level.trim() }, section = st.section.ifBlank { section.trim() })
        }
        var groups = s.groupNames
        (plan.add.map { it.second } + plan.update.map { it.second }).distinct().filter { it.isNotBlank() }.forEach { g ->
            if (groups.none { it.equals(g, true) }) groups = groups + g
        }
        return s.copy(seq = seq, students = done, groupNames = groups)
    }
}
