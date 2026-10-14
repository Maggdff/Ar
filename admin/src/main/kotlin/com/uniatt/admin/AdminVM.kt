package com.uniatt.admin

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.uniatt.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AdminVM(app: Application) : AndroidViewModel(app) {
    private val store = AdminStore(app)
    val state = MutableStateFlow(store.load())
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    val syncing = MutableStateFlow(false)
    val syncInfo = MutableStateFlow("")
    /** كشوف PDF المقروءة وتنتظر موافقة المسؤول (شاشة المعاينة). */
    val importPreview = MutableStateFlow<ImportPreview?>(null)
    private var syncAgain = false

    /** يطبّق تعديلًا على الأحدث من القرص (الواجهة وعامل الخلفية يكتبان معًا) ثم يحدّث الشاشة. */
    private suspend fun mutate(f: (AdminState) -> AdminState) {
        state.value = withContext(Dispatchers.IO) { store.update(f) }
    }

    /** عند الرجوع للتطبيق: قد يكون عامل الخلفية غيّر الحالة (حضور جديد مثلًا). */
    fun refresh() { state.value = store.load() }

    // ---- المزامنة التلقائية مع الدكاترة عبر الترحيل ----
    private fun fmtNow() = SimpleDateFormat("HH:mm", Locale.US).format(Date())

    private fun describe(r: AdminSync.Result): String = when {
        r.relayError != null -> "⚠ ${r.relayError}"
        r.noRelay -> "عنوان القاعدة غير صالح"
        r.offline -> "لا اتصال بالإنترنت — تتم المزامنة تلقائيًا عند توفّره"
        else -> "آخر مزامنة ${fmtNow()}: نُشر ${r.pushed} كشف، ${r.studentsPushed} طالب (بيانات/جدول)، ${r.newRecords} تسجيل حضور جديد" +
            if (r.errors.isNotEmpty()) "\n⚠ " + r.errors.joinToString("\n⚠ ") else ""
    }

    /** [manual] = المستخدم ضغط الزر، فنعرض النتيجة في نافذة؛ وإلا تبقى في سطر الحالة فقط. */
    fun syncNow(manual: Boolean = false) {
        viewModelScope.launch {
            if (syncing.value) { syncAgain = true; return@launch }
            syncing.value = true
            do {
                syncAgain = false
                val r = try { AdminSync.run(store) } catch (e: Exception) { AdminSync.Result(errors = listOf(e.message ?: "خطأ")) }
                refresh()
                syncInfo.value = describe(r)
                if (manual) message.value = syncInfo.value
            } while (syncAgain)
            syncing.value = false
        }
    }

    /** نص رمز الـQR الذي يمسحه الدكتور مرة واحدة. */
    fun pairingText(doctorId: String): String? {
        val st = state.value
        val d = st.doctors.firstOrNull { it.doctorId == doctorId } ?: return null
        val url = Pairing.normalizeUrl(st.effectiveRelay) ?: return null
        if (d.mailbox.isBlank()) return null
        return Pairing.format(Pairing.Info(url, d.mailbox, d.doctorId, d.code))
    }

    private fun courseKey(c: Course) = c.importKey

    /** مادة جديدة بشعبة من قائمة الإعدادات؛ إن وُجدت (نفس الرمز والشعبة) يُحدَّث اسمها فقط. */
    fun addCourse(code: String, name: String, section: String) = viewModelScope.launch {
        var err: AdminOps.Err? = null; var renamed = false
        val before = state.value.courses.firstOrNull { it.code.equals(code.trim(), true) && it.section.equals(section, true) }
        mutate { cur -> AdminOps.addCourse(cur, code, name, section).let { (ns, e) -> err = e; ns } }
        if (err != null) { message.value = err!!.msg; return@launch }
        renamed = before != null
        syncNow()
        if (renamed) message.value = "تم تحديث اسم المادة إلى «${name.trim()}»"
    }

    /**
     * مادة بالاسم فقط لتخصص ونوع: نظري ← شعبة أو أكثر، عملي ← مجموعة أو أكثر (تُختار مما أضفته في الإعدادات).
     * يُنشأ سجل لكل شعبة/مجموعة.
     */
    fun addCourseForm(name: String, major: String, practical: Boolean, targets: List<String>) = viewModelScope.launch {
        var res: AdminOps.AddCoursesResult? = null
        mutate { cur -> AdminOps.addCourses(cur, name, major, practical, targets).also { res = it }.state }
        val r = res ?: return@launch
        r.err?.let { message.value = it.msg; return@launch }
        syncNow()
        message.value = "تمت إضافة «${name.trim()}» ($major — ${if (practical) "عملي" else "نظري"}): ${r.added} " +
            (if (practical) "مجموعة" else "شعبة") + (if (r.skipped > 0) "\n(${r.skipped} كانت موجودة فتُخطّيت)" else "")
    }

    // ---- الإعدادات: التخصصات والشعب ----
    private fun settingOp(f: (AdminState) -> Pair<AdminState, AdminOps.Err?>) = viewModelScope.launch {
        var err: AdminOps.Err? = null
        mutate { cur -> f(cur).let { (ns, e) -> err = e; ns } }
        err?.let { message.value = it.msg }
    }
    fun addMajor(name: String) = settingOp { AdminOps.addMajor(it, name) }
    fun removeMajor(name: String) = viewModelScope.launch { mutate { AdminOps.removeMajor(it, name) } }
    fun addSectionName(name: String) = settingOp { AdminOps.addSectionName(it, name) }
    fun removeSectionName(name: String) = viewModelScope.launch { mutate { AdminOps.removeSectionName(it, name) } }
    fun addGroupName(name: String) = settingOp { AdminOps.addGroupName(it, name) }
    fun removeGroupName(name: String) = viewModelScope.launch { mutate { AdminOps.removeGroupName(it, name) } }
    fun addLevelName(name: String) = settingOp { AdminOps.addLevelName(it, name) }
    fun removeLevelName(name: String) = viewModelScope.launch { mutate { AdminOps.removeLevelName(it, name) } }

    // ---- جداول التخصصات (تصل للطلاب عند اتصالهم بالإنترنت) ----
    fun addTimetableEntry(
        major: String, level: String, day: Int, start: String, end: String, title: String, kind: Int, practical: Boolean,
        section: String, group: String, teacher: String, room: String, note: String
    ) = viewModelScope.launch {
        val a = Schedule.parseTime(start); val b = Schedule.parseTime(end)
        if (a == null || b == null) { message.value = "الوقت بصيغة 08:30 (ساعتان:دقيقتان)"; return@launch }
        val e = TEntry(0, major, level, day, a, if (b == 0) 1440 else b, title, kind, practical, section, group, teacher, room, note)
        var err: AdminOps.Err? = null
        var warn = emptyList<String>()
        mutate { cur ->
            AdminOps.addTimetableEntry(cur, e).let { (ns, er) ->
                err = er
                if (er == null) warn = AdminOps.timetableWarnings(cur, e)
                ns
            }
        }
        if (err != null) { message.value = err!!.msg; return@launch }
        syncNow()
        if (warn.isNotEmpty()) message.value = "تمت إضافة الخانة.\n" + warn.distinct().joinToString("\n")
    }

    fun deleteTimetableEntry(id: Long) = viewModelScope.launch {
        mutate { AdminOps.deleteTimetableEntry(it, id) }
        syncNow()
    }

    // ---- الدكاترة: بالاسم فقط، والمواد من قائمة ----
    fun saveDoctor(id: String?, name: String, courseIds: List<Long>) = viewModelScope.launch {
        var saved: Doctor? = null
        mutate { cur -> AdminOps.saveDoctor(cur, id, name, courseIds, { Codes.random() }, { Pairing.newMailbox() }).let { (ns, d) -> saved = d; ns } }
        if (saved == null) { message.value = "اكتب اسم الدكتور"; return@launch }
        syncNow()
    }

    // ---- الطلاب: بالاسم والتخصص والشعبة والمواد ----
    fun addStudentForm(idInput: String, name: String, major: String, section: String, level: String, courseIds: List<Long>, group: String = "") = viewModelScope.launch {
        if (name.isBlank()) { message.value = "اكتب اسم الطالب"; return@launch }
        busy.value = true
        val tags = state.value.students.map { it.tag }.toHashSet()
        val code = Codes.newUnique(tags)
        val key = withContext(Dispatchers.Default) { Hex.enc(Kdf.studentKey(code)) }
        var err: AdminOps.Err? = null
        mutate { cur -> AdminOps.addStudent(cur, idInput, name, major, section, level, courseIds, code, key, group).let { (ns, e) -> err = e; ns } }
        busy.value = false
        if (err != null) { message.value = err!!.msg; return@launch }
        syncNow()
        message.value = "تمت إضافة ${name.trim()}\nكوده: ${Codes.format(code)}\n(سلّمه الكود ليكتبه مرة واحدة في تطبيقه — يلزمه إنترنت عند أول تفعيل. يمكنك نسخ الكود من تبويب «الطلاب»)"
    }

    fun editStudentForm(id: String, name: String, major: String, section: String, level: String, courseIds: List<Long>, group: String = "") = viewModelScope.launch {
        var err: AdminOps.Err? = null
        mutate { cur -> AdminOps.editStudent(cur, id, name, major, section, level, courseIds, group).let { (ns, e) -> err = e; ns } }
        if (err != null) { message.value = err!!.msg; return@launch }
        syncNow()
    }

    /** يحذف المادة مع مواعيدها ويشيلها من الطلاب والدكاترة. */
    fun deleteCourse(id: Long) = viewModelScope.launch {
        mutate { s -> s.copy(
            courses = s.courses.filter { it.id != id },
            slots = s.slots.filter { it.courseId != id },
            students = s.students.map { it.copy(courseIds = it.courseIds - id) },
            doctors = s.doctors.map { it.copy(courseIds = it.courseIds - id) }) }
        syncNow()
    }

    // ---- الجدول الأسبوعي ----
    fun addSlot(courseId: Long, day: Int, start: String, end: String, room: String) = viewModelScope.launch {
        val a = Schedule.parseTime(start); val b = Schedule.parseTime(end)
        if (a == null || b == null) { message.value = "الوقت بصيغة 08:30 (ساعتان:دقيقتان)"; return@launch }
        val slot = RSlot(courseId, day, a, if (b == 0) 1440 else b, room.trim())
        if (!Schedule.valid(slot)) { message.value = "وقت النهاية يجب أن يكون بعد البداية"; return@launch }
        val cur = state.value
        if (cur.slots.any { it.courseId == courseId && it.day == day && it.startMin == a && it.endMin == slot.endMin }) {
            message.value = "هذا الموعد موجود"; return@launch
        }
        // تنبيه (لا منع): دكتور عنده محاضرتان متداخلتان، أو نفس القاعة مشغولة
        val warn = ArrayList<String>()
        val docs = cur.doctors.filter { courseId in it.courseIds }
        for (d in docs) for (o in cur.slots.filter { it.courseId in d.courseIds && it.courseId != courseId })
            if (Schedule.overlaps(slot, RSlot(o.courseId, o.day, o.startMin, o.endMin)))
                warn.add("⚠ ${d.name} عنده محاضرة أخرى متداخلة: ${Schedule.describe(RSlot(o.courseId, o.day, o.startMin, o.endMin), false)}")
        if (slot.room.isNotBlank()) for (o in cur.slots.filter { it.room.equals(slot.room, true) && it.courseId != courseId })
            if (Schedule.overlaps(slot, RSlot(o.courseId, o.day, o.startMin, o.endMin)))
                warn.add("⚠ القاعة ${slot.room} مشغولة في هذا الوقت")
        mutate { s -> val id = s.seq + 1; s.copy(seq = id, slots = s.slots + Slot(id, courseId, day, a, slot.endMin, slot.room)) }
        syncNow()
        if (warn.isNotEmpty()) message.value = "تمت إضافة الموعد.\n" + warn.distinct().joinToString("\n")
    }

    fun deleteSlot(id: Long) = viewModelScope.launch {
        mutate { s -> s.copy(slots = s.slots.filter { it.id != id }) }
        syncNow()
    }

    /** وصف جدول مادة (للعرض). */
    fun scheduleText(courseId: Long): String =
        Schedule.describeAll(state.value.slots.map { RSlot(it.courseId, it.day, it.startMin, it.endMin, it.room) }, courseId)

    // ---- حذف طالب/دكتور ----
    fun deleteStudent(id: String) = viewModelScope.launch {
        mutate { s ->
            val old = s.students.firstOrNull { it.studentId == id }
            s.copy(students = s.students.filter { it.studentId != id }, bindings = s.bindings.filter { it.studentId != id },
                pendingWipes = if (old != null && old.keyHex.length == 64) (s.pendingWipes + StudentBox.boxId(old.keyHex)).distinct() else s.pendingWipes)
        }
        syncNow()
    }

    fun deleteDoctor(id: String) = viewModelScope.launch {
        val cur = state.value
        val d0 = cur.doctors.firstOrNull { it.doctorId == id } ?: return@launch
        val url = Pairing.normalizeUrl(cur.effectiveRelay)
        if (url != null && d0.mailbox.isNotBlank())
            withContext(Dispatchers.IO) { runCatching { Mailbox(Relay(url), d0.mailbox).wipe() } }
        mutate { s -> s.copy(doctors = s.doctors.filter { it.doctorId != id }) }
    }

    private fun lines(text: String) = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    private fun cols(line: String) = line.split(Regex("[,\t]")).map { it.trim() }
    private fun keys(s: String?) = (s ?: "").split(";", "؛").map { it.trim() }.filter { it.isNotEmpty() }

    /** يحوّل مفاتيح مثل CS101-A إلى معرّفات؛ المفقود يُعاد منفصلًا. */
    private fun resolve(courses: List<Course>, ks: List<String>): Pair<List<Long>, List<String>> {
        val ids = ArrayList<Long>(); val missing = ArrayList<String>()
        for (k in ks) {
            val c = courses.firstOrNull { courseKey(it) == k.lowercase() }
            if (c != null) ids.add(c.id) else missing.add(k)
        }
        return ids to missing
    }

    private class Row(val s: Student, val needsKey: Boolean)

    /** كل سطر: الرقم الجامعي,الاسم,الكلية,التخصص,المستوى,الشعبة,المواد(CS101-A;MATH1-B) */
    fun addStudents(text: String) = viewModelScope.launch {
        busy.value = true
        val cur = state.value
        val errors = ArrayList<String>()
        val rows = ArrayList<Row>()
        val tags = cur.students.map { it.tag }.toHashSet()
        lines(text).forEachIndexed { i, l ->
            val c = cols(l)
            val id = c.getOrElse(0) { "" }; val name = c.getOrElse(1) { "" }
            if (id.isBlank() || name.isBlank()) { errors.add("سطر ${i + 1}: الرقم الجامعي والاسم مطلوبان"); return@forEachIndexed }
            val (ids, missing) = resolve(cur.courses, keys(c.getOrNull(6)))
            if (missing.isNotEmpty()) { errors.add("$id: مواد غير موجودة: ${missing.joinToString(", ")}"); return@forEachIndexed }
            val old = cur.students.firstOrNull { it.studentId == id } ?: rows.firstOrNull { it.s.studentId == id }?.s
            val code = old?.code ?: Codes.newUnique(tags).also { tags.add(Codes.tag(it)) }
            rows.add(Row(Student(id, name, c.getOrElse(2) { "" }, c.getOrElse(3) { "" }, c.getOrElse(4) { "" },
                c.getOrElse(5) { "" }, ids, code, old?.keyHex ?: ""), needsKey = old == null || old.keyHex.isBlank()))
        }
        // اشتقاق مفاتيح الطلاب الجدد بالتوازي (PBKDF2 بطيء عمدًا)
        val done = withContext(Dispatchers.Default) {
            rows.map { r -> async { if (r.needsKey) r.s.copy(keyHex = Hex.enc(Kdf.studentKey(r.s.code))) else r.s } }.awaitAll()
        }
        mutate { s ->
            val byId = LinkedHashMap(s.students.associateBy { it.studentId })
            done.forEach { byId[it.studentId] = it }
            s.copy(students = byId.values.toList())
        }
        busy.value = false
        syncNow()
        message.value = "تمت إضافة/تحديث ${done.size} طالب" + if (errors.isNotEmpty()) "\nأخطاء:\n" + errors.joinToString("\n") else ""
    }

    /** كل سطر: معرف الدكتور,الاسم,المواد(CS101-A;MATH1-B) */
    fun addDoctors(text: String) = viewModelScope.launch {
        val cur = state.value
        val errors = ArrayList<String>()
        val made = LinkedHashMap<String, Doctor>()
        lines(text).forEachIndexed { i, l ->
            val c = cols(l)
            val id = c.getOrElse(0) { "" }; val name = c.getOrElse(1) { "" }
            if (id.isBlank() || name.isBlank()) { errors.add("سطر ${i + 1}: معرف الدكتور والاسم مطلوبان"); return@forEachIndexed }
            val (ids, missing) = resolve(cur.courses, keys(c.getOrNull(2)))
            if (missing.isNotEmpty()) { errors.add("$id: مواد غير موجودة: ${missing.joinToString(", ")}"); return@forEachIndexed }
            if (id.contains('|')) { errors.add("$id: معرف الدكتور لا يحتوي على |"); return@forEachIndexed }
            val old = cur.doctors.firstOrNull { it.doctorId == id }
            made[id] = old?.copy(name = name, courseIds = ids)
                ?: Doctor(id, name, ids, Codes.random(), mailbox = Pairing.newMailbox())
        }
        mutate { s ->
            val byId = LinkedHashMap(s.doctors.associateBy { it.doctorId })
            byId.putAll(made)
            s.copy(doctors = byId.values.toList())
        }
        syncNow()
        message.value = "تمت إضافة/تحديث ${made.size} دكتور" + if (errors.isNotEmpty()) "\nأخطاء:\n" + errors.joinToString("\n") else ""
    }

    // ---- استيراد كشف شعب أو مجموعات عملي من PDF (لتخصص ومستوى مختارين) ----
    fun parsePdf(uri: Uri, kind: AdminOps.ImportKind, major: String, level: String) = viewModelScope.launch {
        busy.value = true
        val pages = withContext(Dispatchers.IO) { runCatching { PdfText.pages(getApplication(), uri) } }
        busy.value = false
        val list = pages.getOrElse { message.value = "تعذّرت قراءة ملف PDF: ${it.message ?: it.javaClass.simpleName}"; return@launch }
        val sheets = GroupSheets.parse(list)
        if (sheets.isEmpty()) {
            message.value = "لم أجد قوائم طلاب في هذا الملف.\nالمطلوب كشف «حضور وغياب الطلاب» (ملف نصي، وليس صورة ممسوحة) وفيه سطر «بكالوريوس … المستوى» وأسماء مرقّمة."
            return@launch
        }
        importPreview.value = ImportPreview(sheets, kind, major, level)
    }

    /** يضيف طلاب الكشوف الجدد بأكوادهم ويضبط شعبة/مجموعة الموجودين (المطابقة بالاسم فلا يتكرر طالب)، ثم يزامن. */
    fun confirmImport(groups: List<AdminOps.ImportGroup>, kind: AdminOps.ImportKind, major: String, level: String) = viewModelScope.launch {
        importPreview.value = null
        busy.value = true
        val cur = state.value
        val plan = AdminOps.planGroupImport(cur, groups, kind)
        val tags = cur.students.map { it.tag }.toHashSet()
        val codes = LinkedHashMap<String, String>()
        for ((name, _) in plan.add) codes[name] = Codes.newUnique(tags).also { tags.add(Codes.tag(it)) }
        // اشتقاق مفاتيح الطلاب الجدد بالتوازي (PBKDF2 بطيء عمدًا)
        val creds = withContext(Dispatchers.Default) {
            codes.entries.map { (n, c) -> async { n to (c to Hex.enc(Kdf.studentKey(c))) } }.awaitAll().toMap()
        }
        mutate { s -> AdminOps.applyGroupImport(s, plan, major, level, "", creds, kind) }
        busy.value = false
        syncNow()
        val what = if (kind == AdminOps.ImportKind.SECTION) "شعبتهم" else "مجموعتهم"
        message.value = buildString {
            append("تم الاستيراد ($major — $level):\n• طلاب جدد: ${plan.add.size}\n• موجودون ضُبطت $what: ${plan.update.size}")
            if (plan.same > 0) append("\n• موجودون ب$what نفسها: ${plan.same}")
            if (plan.duplicates > 0) append("\n• أسماء مكررة في الملف تُجوهلت: ${plan.duplicates}")
            if (plan.add.isNotEmpty()) append("\n\nأكواد الطلاب الجدد في تبويب «الطلاب» (زر «نسخ الكود» أو «تصدير الأكواد»).")
        }
    }

    /** يسجّل كل طلاب (تخصص، مستوى) في المواد المختارة (المطابقة بالشعبة/المجموعة عند تعدد السجلات)، ثم يزامن. */
    fun assignCourses(major: String, level: String, courseIds: List<Long>) = viewModelScope.launch {
        if (courseIds.isEmpty()) { message.value = "اختر مادة واحدة على الأقل"; return@launch }
        var report: AdminOps.AssignReport? = null
        mutate { s -> AdminOps.assignCoursesToLevel(s, major, level, courseIds).let { (ns, r) -> report = r; ns } }
        val r = report ?: return@launch
        syncNow()
        message.value = buildString {
            append("طلاب $major — $level: ${r.targets}\n• تسجيلات جديدة في المواد: ${r.enrolled}")
            if (r.already > 0) append("\n• كانوا مسجَّلين أصلًا: ${r.already}")
            if (r.unmatched.isNotEmpty()) {
                append("\n\n⚠ لم يُسجَّل بعض الطلاب لأن شعبتهم (للنظري) أو مجموعتهم (للعملي) لا تطابق أيًّا من سجلات المادة المختارة:")
                r.unmatched.forEach { (code, n) -> append("\n• $code: $n طالب — حدّد شعبتهم/مجموعتهم أو سجّلهم يدويًا") }
            }
        }
    }

    /** كود جديد لطالب (فقد هاتفه مثلًا): يُلغى القديم عند وصول كشف محدَّث للدكاترة. */
    fun resetStudent(id: String) = viewModelScope.launch {
        val s0 = state.value.students.firstOrNull { it.studentId == id } ?: return@launch
        busy.value = true
        val tags = state.value.students.map { it.tag }.toHashSet()
        val code = Codes.newUnique(tags)
        val key = withContext(Dispatchers.Default) { Hex.enc(Kdf.studentKey(code)) }
        mutate { s -> s.copy(
            students = s.students.map { if (it.studentId == id) it.copy(code = code, keyHex = key) else it },
            bindings = s.bindings.filter { it.studentId != id },
            pendingWipes = if (s0.keyHex.length == 64) (s.pendingWipes + StudentBox.boxId(s0.keyHex)).distinct() else s.pendingWipes) }
        busy.value = false
        syncNow()
        message.value = "الكود الجديد لـ ${s0.name}: ${Codes.format(code)}\nسيصل الكشف المحدَّث لدكاترته تلقائيًا فيُلغى ربط الجهاز القديم."
    }

    /** كود جديد + صندوق بريد جديد: ينقطع الهاتف القديم ويحتاج الدكتور مسح QR جديد. */
    fun resetDoctor(id: String) = viewModelScope.launch {
        val cur = state.value
        val d0 = cur.doctors.firstOrNull { it.doctorId == id } ?: return@launch
        val url = Pairing.normalizeUrl(cur.effectiveRelay)
        if (url != null && d0.mailbox.isNotBlank()) {
            withContext(Dispatchers.IO) { runCatching { Mailbox(Relay(url), d0.mailbox).wipe() } }   // محاولة أفضل جهد لمحو القديم
        }
        val code = Codes.random()
        mutate { s -> s.copy(doctors = s.doctors.map {
            if (it.doctorId == id) it.copy(code = code, mailbox = Pairing.newMailbox(), rosterHash = "", attHash = "", pushedEpoch = 0, ackEpoch = 0)
            else it
        }) }
        syncNow()
        message.value = "تم إلغاء ربط ${d0.name}. اعرض له QR جديدًا من تبويب الدكاترة ليمسحه."
    }

    private fun exportsDir() = File(getApplication<Application>().cacheDir, "exports").apply { mkdirs() }

    /** طلاب ظهر لهم مفتاحا جهاز مختلفان عند دكاترة مختلفين (كود مشارَك أو مكرّر). */
    fun conflicts(): List<Pair<Student, List<String>>> {
        val s = state.value
        return s.bindings.groupBy { it.studentId }
            .filter { (_, v) -> v.map { it.pubHex }.distinct().size > 1 }
            .mapNotNull { (id, v) ->
                val st = s.students.firstOrNull { it.studentId == id } ?: return@mapNotNull null
                st to v.map { b -> s.doctors.firstOrNull { it.doctorId == b.doctorId }?.name ?: b.doctorId }
            }
    }

    // ---- كشوف الطلاب (تخصص + مستوى + شعبة/مجموعة) ----
    fun rosterStudents(major: String, level: String, practical: Boolean, target: String) =
        AdminOps.rosterStudents(state.value, major, level, practical, target)
    fun rosterCourses(major: String, practical: Boolean, target: String) =
        AdminOps.rosterCourses(state.value, major, practical, target)
    fun rosterSheet(students: List<Student>, courseId: Long?) = AdminOps.rosterSheet(state.value, students, courseId)

    // ---- التقارير ----
    fun courseSummary(): List<String> = AdminOps.courseSummary(state.value)
    fun courseSheet(courseId: Long) = AdminOps.courseSheet(state.value, courseId)
    fun attendanceCsv(): String = AdminOps.attendanceCsv(state.value)
    private fun q(x: String) = "\"" + x.replace("\"", "\"\"") + "\""

    fun studentsCsv(): String = "studentId,name,code,device_seen,major,level,section,group\n" + state.value.students.joinToString("\n") { st ->
        "${st.studentId},${q(st.name)},${Codes.format(st.code)},${state.value.bindings.any { it.studentId == st.studentId }}," +
            "${q(st.major)},${q(st.level)},${q(st.section)},${q(st.group)}"
    }

    // ---- نسخة احتياطية مشفّرة (المسؤول هو المرجع الوحيد، فضياع هاتفه = ضياع كل الأكواد) ----
    suspend fun backup(): Pair<File, String>? {
        val pass = Codes.random()
        busy.value = true
        val blob = withContext(Dispatchers.Default) {
            Box.seal(Box.MAGIC_BACKUP, "admin", StateJson.toJson(state.value).toByteArray(Charsets.UTF_8), pass)
        }
        val f = withContext(Dispatchers.IO) { File(exportsDir(), "uniatt_backup_${System.currentTimeMillis() / 1000}.uab").also { it.writeBytes(blob) } }
        busy.value = false
        return f to pass
    }

    fun restore(bytes: ByteArray?, passInput: String) = viewModelScope.launch {
        val pass = Codes.normalize(passInput) ?: run { message.value = "كود النسخة غير صحيح"; return@launch }
        if (bytes == null) { message.value = "تعذّرت قراءة الملف"; return@launch }
        busy.value = true
        val restored = withContext(Dispatchers.Default) {
            Box.open(Box.MAGIC_BACKUP, bytes, pass)?.let { runCatching { StateJson.fromJson(String(it, Charsets.UTF_8)) }.getOrNull() }
        }
        busy.value = false
        if (restored == null) { message.value = "تعذّرت الاستعادة: الكود خاطئ أو الملف تالف"; return@launch }
        mutate { restored }
        syncNow()
        message.value = "تمت الاستعادة: ${restored.students.size} طالب، ${restored.doctors.size} دكتور"
    }
}

/** كشف PDF مقروء ينتظر الموافقة: نوعه (شعب/مجموعات) والتخصص والمستوى اللذان اختارهما المسؤول. */
class ImportPreview(val sheets: List<GroupSheet>, val kind: AdminOps.ImportKind, val major: String, val level: String)
