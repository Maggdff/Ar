package com.uniatt.doctor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.uniatt.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.UUID

data class ReportRow(val studentId: String, val name: String, val attended: Int, val total: Int)

@OptIn(ExperimentalCoroutinesApi::class)
class DoctorVM(app: Application) : AndroidViewModel(app) {
    val dao = AppDb.get(app).dao()
    private val prefs = app.getSharedPreferences("doctor", 0)

    val loggedIn = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    val courses = dao.courses().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val selectedCourseId = MutableStateFlow<Long?>(null)
    val active = MutableStateFlow<ActiveSession?>(null)
    val events = MutableStateFlow<List<ScanEvent>>(emptyList())
    val qrOn = MutableStateFlow(false)
    /** الحضور التلقائي بالبلوتوث أثناء الجلسة. */
    val bleOn = MutableStateFlow(true)
    val bleState = MutableStateFlow("")
    val bleClients = MutableStateFlow(0)

    // ---- تسجيل بالـQR: تحدٍّ وإجابة ----
    val evaluator = AttendanceEvaluator(dao, clock = { now() }).also { it.scheduleOf = { id -> scheduleText(id, withRoom = false) } }
    private val challenges = QrChallenges(clock = { now() })
    /** نص رمز التحدّي المعروض الآن (يتغيّر كل 10 ثوانٍ) أو null إذا كان وضع QR مغلقًا. */
    val qrChallenge = MutableStateFlow<String?>(null)
    private var qrJob: kotlinx.coroutines.Job? = null

    fun setQrMode(on: Boolean) {
        qrJob?.cancel(); qrJob = null
        if (!on) { qrChallenge.value = null; challenges.clear(); return }
        qrJob = viewModelScope.launch {
            while (true) {
                val s = active.value ?: break
                qrChallenge.value = QrProtocol.challengeText(challenges.issue(s.sessionId))
                kotlinx.coroutines.delay(10_000)
            }
            qrChallenge.value = null
        }
    }

    /** نتيجة مسح رمز إجابة طالب. */
    fun onQrReply(text: String) {
        viewModelScope.launch {
            val s = active.value ?: run { onScan(ScanEvent(false, "لا توجد جلسة نشطة")); return@launch }
            val pr = QrProtocol.parseReply(text)
            if (pr == null) { onScan(ScanEvent(false, "الرمز الممسوح ليس إجابة صالحة من تطبيق الطالب")); return@launch }
            val req = challenges.find(pr.nonce)
            if (req == null) { onScan(ScanEvent(false, "الإجابة قديمة أو لم تصدر من شاشتك — اطلب من الطالب مسح رمز الدكتور الحالي من جديد")); return@launch }
            val out = withContext(Dispatchers.IO) { evaluator.evaluate(s, pr.reply, req) }
            onScan(ScanEvent(out.status == Status.OK, AttendanceEvaluator.describe(out, pr.reply.tag)))
        }
    }
    val historySession = MutableStateFlow<String?>(null)
    val report = MutableStateFlow<List<ReportRow>>(emptyList())

    val liveAttendance = active.flatMapLatest { s ->
        if (s == null) flowOf(emptyList()) else dao.attendanceOf(s.sessionId.toString())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** العدد الذي أدخله الدكتور للجلسة الجارية (-1 = لم يُدخل). */
    val liveCounted = active.flatMapLatest { s ->
        if (s == null) flowOf(-1) else dao.sessionFlow(s.sessionId.toString()).map { it?.countedCount ?: -1 }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, -1)

    /** يحفظ عدّ الدكتور لطلاب القاعة (n < 0 يمسحه) ويرفعه للمسؤول مع الحضور. */
    fun setCounted(sessionId: String, n: Int) = viewModelScope.launch {
        withContext(Dispatchers.IO) { dao.setCounted(sessionId, if (n < 0) -1 else n.coerceAtMost(9999)) }
    }

    /** طلاب مادة الجلسة الجارية (لقائمة التحضير اليدوي). */
    val liveStudents = active.flatMapLatest { s ->
        if (s == null) flowOf(emptyList()) else dao.studentsOf(s.course.id)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val students = selectedCourseId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else dao.studentsOf(id)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val sessions = selectedCourseId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else dao.sessionsOf(id)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** حضور المادة المختارة الفعلي (بلا الملغى) لكشف الحضور. */
    val courseAttendance = selectedCourseId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else dao.attendanceFlowOfCourse(id)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val historyAttendance = historySession.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else dao.attendanceOf(id)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        // بعد إعادة تشغيل التطبيق: اختر أول مادة تلقائيًا
        viewModelScope.launch {
            if (selectedCourseId.value == null) selectedCourseId.value = dao.coursesOnce().firstOrNull()?.id
        }
    }

    // ---- قفل محلي بالرمز السري (لا علاقة له بكود الدكتور) ----
    private fun hash(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    fun hasAccount() = prefs.getString("pin", null) != null
    fun register(pin: String) { prefs.edit().putString("pin", hash(pin)).apply(); loggedIn.value = true }
    fun login(pin: String) {
        if (hash(pin) == prefs.getString("pin", null)) loggedIn.value = true else message.value = "رمز الدخول خاطئ"
    }

    // ---- الربط بالمسؤول (مسح QR مرة واحدة) والمزامنة التلقائية ----
    val doctorId get() = prefs.getString("id", "") ?: ""
    val doctorName get() = prefs.getString("name", "") ?: ""
    val epoch get() = prefs.getLong("epoch", -1L)
    val paired = MutableStateFlow(prefs.getString("mbox", null) != null)
    /** وصل كشف واحد على الأقل (فيه اسمي ومواد وطلاب). */
    val linked = MutableStateFlow(prefs.getLong("epoch", -1L) >= 0)
    val syncing = MutableStateFlow(false)
    val syncInfo = MutableStateFlow("")
    /** يزيد بعد كل مزامنة لإعادة رسم الشاشة (الاسم والإصدار يُقرآن من التخزين). */
    val version = MutableStateFlow(0)

    // ---- الجدول (يحدّده المسؤول) ----
    fun slots(): List<RSlot> = Schedule.decode(prefs.getString("slots", null))

    /** نص مواعيد مادة (يُرسل أيضًا للطالب مع بياناته). */
    fun scheduleText(courseId: Long, withRoom: Boolean = true): String = Schedule.describeAll(slots(), courseId, withRoom)

    /** الوقت الموثوق (وقت الخادم + ساعة رتيبة) للتوقيع والعرض؛ لا يُستعمل لقرار البدء إلا عبر [gateStart]. */
    private fun now(): Long = DoctorClock.nowMs(getApplication())

    /**
     * المحاضرة الجارية الآن حسب الجدول مع الدقائق المتبقية من موعدها، بالوقت الموثوق؛ null إن لم يكن الآن موعد أي مادة
     * (أو لم يُتأكد من الوقت بعد). لا بدء مبكر: الجلسة لا تُفتح إلا داخل الموعد.
     */
    suspend fun nowSlot(): Pair<CourseSection, Int>? {
        val r = DoctorClock.read(getApplication())
        if (!r.trusted) return null
        val sl = AttendancePolicy.currentSlot(slots(), r.ms) ?: return null
        val left = AttendancePolicy.remainingMinutes(sl, r.ms)
        if (left < 1) return null
        val c = dao.coursesOnce().firstOrNull { it.id == sl.courseId } ?: return null
        return c to left.coerceIn(1, 240)
    }

    private fun fmtNow() = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date())

    private fun describe(r: DoctorSync.Result): String = when {
        r.error != null -> "⚠ ${r.error}"
        r.offline -> "لا اتصال بالإنترنت — تتم المزامنة تلقائيًا عند توفّره"
        r.notPaired -> "امسح QR من هاتف المسؤول للربط"
        r.waitingAdmin -> "تمّ الربط. بانتظار أن ينشر المسؤول كشفك (يحتاج اتصالًا بالإنترنت عنده)"
        else -> "آخر مزامنة ${fmtNow()}" + (if (r.rosterUpdated) " — وصل كشف جديد (إصدار $epoch)" else "") +
            (if (r.uploaded) " — رُفع الحضور للمسؤول" else "")
    }

    /** [manual] = ضغط المستخدم الزر، فتظهر النتيجة في نافذة. */
    fun syncNow(manual: Boolean = false) {
        viewModelScope.launch {
            if (!paired.value) { if (manual) message.value = "امسح QR من هاتف المسؤول أولًا"; return@launch }
            // الترخيص موقوف: لا مزامنة (لا كشف جديد ولا رفع حضور) حتى يُستعاد؛ بيانات الهاتف تبقى كما هي
            if (!com.uniatt.licensekit.LicenseManager.allowed()) { if (manual) message.value = "المزامنة متوقفة: الترخيص غير فعّال حاليًا"; return@launch }
            if (syncing.value) return@launch
            syncing.value = true
            val r = DoctorSync.run(getApplication())
            syncing.value = false
            linked.value = prefs.getLong("epoch", -1L) >= 0
            version.value++
            if (selectedCourseId.value == null) selectedCourseId.value = dao.coursesOnce().firstOrNull()?.id
            syncInfo.value = describe(r)
            if (r.rosterUpdated && r.resetCount > 0) {
                message.value = "وصل كشف جديد. أُلغي ربط ${r.resetCount} جهاز بسبب أكواد جديدة من الإدارة."
            } else if (manual || r.error != null) message.value = syncInfo.value
        }
    }

    /** يُستدعى بنتيجة مسح الـQR. */
    fun pairFromQr(text: String?) {
        val info = text?.let { Pairing.parse(it) }
        if (info == null) { message.value = "هذا ليس رمز ربط صالحًا من تطبيق المسؤول"; return }
        val curId = prefs.getString("id", null)
        if (curId != null && curId != info.doctorId) {
            message.value = "هذا الهاتف مرتبط بدكتور آخر ($curId). امسح بياناته أولًا (زر «فك الارتباط ومسح البيانات»)."
            return
        }
        viewModelScope.launch {
            // رمز الربط يعمل على هاتف واحد: أول هاتف يمسحه يسجّل مفتاح جهازه على الخادم، وغيره يُرفض
            val claim = withContext(Dispatchers.IO) {
                try { DeviceClaim.claim(Relay(info.relayUrl), "m/${info.mailbox}", Hex.enc(DoctorDeviceKey.publicRaw())) }
                catch (e: java.io.IOException) { "انقطع الاتصال أثناء الربط. اتصل بالإنترنت وأعد مسح الرمز." }
                catch (e: Relay.RelayException) { e.friendly() }
                catch (e: Exception) { "تعذّر الربط على هذا الجهاز (${e.javaClass.simpleName})" }
            }
            if (claim is String) { message.value = claim; return@launch }
            if (claim is DeviceClaim.Result.Taken) { message.value = DeviceClaim.TAKEN_DOCTOR; return@launch }
            val newBox = prefs.getString("mbox", null) != info.mailbox
            prefs.edit().putString("id", info.doctorId).putString("code", info.code)
                .putString("mbox", info.mailbox).putString("relay", info.relayUrl).apply()
            if (newBox) prefs.edit().putLong("epoch", -1L).remove("ackEpoch").remove("attHash").apply()   // QR جديد بعد «إلغاء الربط»
            paired.value = true
            linked.value = prefs.getLong("epoch", -1L) >= 0
            version.value++
            syncNow(manual = true)
        }
    }

    /** فك الارتباط ومسح كل البيانات المحلية (كشف + حضور). يُبقي رمز القفل. */
    fun unpairAndWipe() {
        viewModelScope.launch {
            active.value = null
            withContext(Dispatchers.IO) {
                AppDb.get(getApplication()).clearAllTables()
                prefs.edit().remove("id").remove("name").remove("epoch").remove("slots").remove("code").remove("mbox")
                    .remove("relay").remove("ackEpoch").remove("attHash").apply()
            }
            paired.value = false; linked.value = false; selectedCourseId.value = null
            syncInfo.value = ""; version.value++
            message.value = "تم فك الارتباط ومسح البيانات."
        }
    }

    // ---- الجلسات ----
    /**
     * يبدأ الحضور. المحاضرة = مادة + يوم: إن كانت للمادة جلسة اليوم (أوقفها الدكتور) تُستأنف بمعرّفها نفسه،
     * فلا يتكرر تسجيل أي طالب ولا تُحتسب المحاضرة مرتين حتى لو أُوقف الحضور وشُغّل أكثر من مرة.
     */
    /**
     * [newLecture] = true: محاضرة متتالية جديدة في اليوم نفسه لنفس الشعبة/المجموعة (يؤكدها الدكتور صراحةً):
     * تأخذ part = (أعلى رقم اليوم)+1 وتظهر عمودًا مستقلًا في الكشف ولو وُجدت جلسة اليوم.
     */
    private class Gate(val nowMs: Long, val minutes: Int, val clockSkewed: Boolean)

    /**
     * بوابة «لا تحضير إلا حسب الجدول»: لا تبدأ جلسة إلا داخل موعد المادة بالوقت الموثوق (الخادم) لا بساعة الهاتف،
     * وتُقصّ مدتها عند نهاية الموعد. null = مرفوض (والسبب في [message]) ولا يتغير شيء.
     */
    private suspend fun gateStart(course: CourseSection, requestedMin: Int): Gate? {
        val app = getApplication<Application>()
        var r = DoctorClock.read(app)
        if (!r.trusted) {                                  // أول مرة أو بعد إعادة تشغيل الهاتف: نزامن مع الخادم الآن
            withContext(Dispatchers.IO) { DoctorClock.trySync(app) }
            r = DoctorClock.read(app)
        }
        if (!r.trusted) { message.value = AttendancePolicy.MSG_NEEDS_SYNC; return null }
        val d = AttendancePolicy.checkDoctorStart(slots(), course.id, r.ms, requestedMin)
        if (!d.ok) { message.value = d.message; return null }
        return Gate(r.ms, d.minutes, r.tampered)
    }

    /**
     * يبدأ الحضور (داخل موعد المادة فقط، ومدته مقصوصة عند نهاية الموعد). المحاضرة = مادة + يوم: إن كانت للمادة جلسة اليوم
     * (أوقفها الدكتور) تُستأنف بمعرّفها نفسه، فلا يتكرر تسجيل أي طالب ولا تُحتسب المحاضرة مرتين حتى لو أُوقف الحضور وشُغّل أكثر من مرة.
     * [newLecture] = true: محاضرة متتالية جديدة في اليوم نفسه لنفس الشعبة/المجموعة (يؤكدها الدكتور صراحةً):
     * تأخذ part = (أعلى رقم اليوم)+1 وتظهر عمودًا مستقلًا في الكشف ولو وُجدت جلسة اليوم.
     */
    fun startSession(course: CourseSection, minutes: Int, newLecture: Boolean? = null) = viewModelScope.launch {
        val gate = gateStart(course, minutes) ?: return@launch       // مرفوض: لا تُنهى الجلسة الجارية ولا يتغير شيء
        val mins = gate.minutes
        val now = gate.nowMs
        active.value?.let { dao.endSession(it.sessionId.toString(), now) }
        val dayStart = startOfDay(now)
        val top = dao.maxPartSince(course.id, dayStart)
        val last = dao.lastSessionSince(course.id, dayStart)
        // null = حسب الجدول: إن كان الآن في موعد لاحق لنفس المادة غير موعد آخر جلسة اليوم فهي محاضرة متتالية جديدة تلقائيًا
        val next = newLecture ?: (last != null && scheduleSaysNext(course, last.startedAt, now))
        val old = if (next) null else last
        val s = if (old != null) {
            val dur = (((now - old.startedAt) / 60_000L).toInt() + mins).coerceIn(1, 1440)
            dao.reopenSession(old.sessionId, dur)
            ActiveSession(UUID.fromString(old.sessionId), course, doctorId, old.startedAt, dur)
        } else {
            ActiveSession(UUID.randomUUID(), course, doctorId, now, mins).also {
                dao.addSession(SessionEntity(it.sessionId.toString(), course.id, doctorId, it.startedAt, mins, part = if (top == null) 0 else top + 1))
            }
        }
        events.value = emptyList()
        active.value = s
        if (gate.clockSkewed) onScan(ScanEvent(false, "⚠ ساعة هاتفك لا تطابق الوقت الحقيقي — يُعتمد الوقت الحقيقي من الخادم، فتغيير الساعة لا يفيد"))
        if (mins < minutes) onScan(ScanEvent(true, "قُصّت مدة الجلسة إلى $mins دقيقة لأنها تنتهي مع نهاية موعد المحاضرة"))
        if (old != null) onScan(ScanEvent(true, "استُؤنفت محاضرة اليوم نفسها — لن يتكرر تسجيل من حضر قبل الإيقاف"))
        else if (top != null) onScan(ScanEvent(true, "بدأت محاضرة متتالية جديدة (${top + 2}) — تُحتسب عمودًا مستقلًا في الكشف"))
    }

    private fun startOfDay(now: Long): Long = java.util.Calendar.getInstance().apply {
        timeInMillis = now; set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun minuteOf(ms: Long): Int = java.util.Calendar.getInstance().apply { timeInMillis = ms }.let { it.get(java.util.Calendar.HOUR_OF_DAY) * 60 + it.get(java.util.Calendar.MINUTE) }
    private fun dayOf(ms: Long): Int = java.util.Calendar.getInstance().apply { timeInMillis = ms }.get(java.util.Calendar.DAY_OF_WEEK) - 1
    private fun scheduleSaysNext(course: CourseSection, lastStartedAt: Long, now: Long): Boolean =
        Schedule.isNextLecture(slots(), course.id, dayOf(now), minuteOf(lastStartedAt), minuteOf(now))

    /** هل يقتضي الجدول الآن محاضرة متتالية جديدة لهذه المادة (بعد وجود محاضرة سابقة اليوم في موعد آخر)؟ */
    suspend fun nextByScheduleNow(course: CourseSection): Boolean = withContext(Dispatchers.IO) {
        val now = now()
        dao.lastSessionSince(course.id, startOfDay(now))?.let { scheduleSaysNext(course, it.startedAt, now) } ?: false
    }

    /** عدد محاضرات اليوم للمادة (0 = لم تبدأ محاضرة اليوم)، لإظهار زر «محاضرة متتالية جديدة». */
    suspend fun lecturesToday(course: CourseSection): Int = withContext(Dispatchers.IO) {
        dao.maxPartSince(course.id, startOfDay(now()))?.let { it + 1 } ?: 0
    }

    fun endSession() = viewModelScope.launch {
        setQrMode(false); qrOn.value = false
        active.value?.let { dao.endSession(it.sessionId.toString(), now()) }
        active.value = null
    }

    // ---- تحضير يدوي وإلغاء التحضير (جلسة جارية أو سابقة) ----
    /** يحضّر طالبًا من كشف المادة (نسي هاتفه أو تعطّل بلوتوثه…) أو يعيد من أُلغي تحضيره. لا يكرّر طالبًا حاضرًا. */
    fun markPresent(sessionId: String, course: CourseSection, st: StudentEntity) = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            val old = dao.record(sessionId, st.studentId)
            val now = now()
            when {
                old == null -> dao.addAttendance(AttendanceRecord(
                    sessionId = sessionId, studentId = st.studentId, studentName = st.name, courseSectionId = course.id,
                    courseName = "${course.code} ${course.name} (${course.section})", date = fmtDate(now), timestamp = now, status = AttStatus.MANUAL))
                old?.status == AttStatus.CANCELLED -> dao.setStatus(sessionId, st.studentId, AttStatus.MANUAL)
                else -> Unit    // حاضر أصلًا: لا تكرار
            }
        }
        onScan(ScanEvent(true, "✓ حضّرتَ ${st.name} يدويًا"))
        syncNow()
    }

    /** يلغي تحضير طالب: يبقى سجله «ملغى» فلا يعيد هاتفه تسجيل نفسه، ولا يُحتسب ولا يُرفع للمسؤول (فيزول عنده أيضًا). */
    fun cancelAttendance(sessionId: String, studentId: String, name: String) = viewModelScope.launch {
        withContext(Dispatchers.IO) { dao.setStatus(sessionId, studentId, AttStatus.CANCELLED) }
        onScan(ScanEvent(false, "⊘ ألغيتَ تحضير $name"))
        syncNow()
    }

    fun onScan(e: ScanEvent) { events.update { (listOf(e) + it).take(50) } }

    fun loadReport() = viewModelScope.launch {
        val cid = selectedCourseId.value ?: run { report.value = emptyList(); return@launch }
        val students = dao.studentsOfOnce(cid)
        val data = AttendanceSheet.build(
            dao.sessionsOnce(cid).map { it.sessionId to it.startedAt },
            students.map { it.studentId to it.name },
            dao.attendanceForCourse(cid).map { it.sessionId to it.studentId }.toHashSet(),
            dao.sessionsOnce(cid).associate { it.sessionId to it.part }
        )
        report.value = data.rows.map { ReportRow(it.studentId, it.name, it.count, data.total) }
    }

    fun csv(rows: List<ReportRow>): String = buildString {
        append("studentId,name,attended,sessions,percent\n")
        rows.forEach {
            val pct = if (it.total == 0) 0 else it.attended * 100 / it.total
            append("${it.studentId},\"${it.name.replace("\"", "\"\"")}\",${it.attended},${it.total},$pct\n")
        }
    }
}
