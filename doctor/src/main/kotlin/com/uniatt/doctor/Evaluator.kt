package com.uniatt.doctor

import com.uniatt.core.*
import java.util.UUID

/**
 * جلسة حضور جارية. تُستأنف جلسة اليوم نفسه للمادة نفسها (معرّفها لا يتغيّر) إذا أوقفها الدكتور ثم شغّلها،
 * فلا يتكرر تسجيل أي طالب في المحاضرة الواحدة.
 */
data class ActiveSession(
    val sessionId: UUID, val course: CourseSection, val doctorId: String,
    val startedAt: Long, val durationMin: Int
) { val endsAt get() = startedAt + durationMin * 60_000L }

data class ScanEvent(val ok: Boolean, val text: String, val time: Long = System.currentTimeMillis())

/** [cancelled] = الدكتور ألغى تحضير هذا الطالب في هذه الجلسة، فلا يُعاد تسجيله تلقائيًا (يراه الطالب «مسجَّلًا مسبقًا»). */
class Outcome(val status: Int, val student: StudentEntity?, val profile: StudentProfile? = null, val cancelled: Boolean = false)

/** التحقق من إجابة طالب وتسجيل حضوره (مشترك بين البلوتوث وQR). */
class AttendanceEvaluator(private val dao: AttDao, private val clock: () -> Long = { System.currentTimeMillis() }) {
    /** نص مواعيد المادة (من جدول المسؤول) ليصل للطالب مع بياناته. */
    @Volatile var scheduleOf: (Long) -> String = { "" }

    suspend fun evaluate(s: ActiveSession, r: Protocol.AttendReply, req: Protocol.AttendRequest): Outcome {
        val now = clock()                                   // الوقت الموثوق (لا ساعة الهاتف الخام)
        if (now > s.endsAt) return Outcome(Status.EXPIRED, null)
        val st = dao.studentByTag(r.tag) ?: return Outcome(Status.UNKNOWN_STUDENT, null)

        // 1) إثبات معرفة الكود (يمنع انتحال طالب بلا كوده)
        val expected = Hmac.sha256(Hex.dec(st.keyHex), Protocol.bindMessage(r.tag, r.pub, req))
        if (!Hmac.equal(expected, r.proof)) return Outcome(Status.BAD_PROOF, st)
        // 2) إثبات حيازة مفتاح الجهاز (Keystore)
        if (!SigVerifier.verify(r.pub, Protocol.signedBytes(r.tag, r.pub, req), r.sig)) return Outcome(Status.BAD_SIGNATURE, st)
        // 3) مسجل في مادة هذه الجلسة؟ (قبل الربط، فلا يُربط جهاز طالب من مادة أخرى)
        if (dao.enrolled(st.studentId, s.course.id) == 0) return Outcome(Status.NOT_ENROLLED, st)
        // 4) ربط الجهاز عند أول مرة، وبعدها لا يُقبل جهاز مختلف
        val pubHex = Hex.enc(r.pub)
        if (st.publicKeyHex == null) dao.bindKey(st.studentId, pubHex)
        else if (st.publicKeyHex != pubHex) return Outcome(Status.KEY_MISMATCH, st)

        val rec = AttendanceRecord(
            sessionId = s.sessionId.toString(), studentId = st.studentId, studentName = st.name,
            courseSectionId = s.course.id, courseName = "${s.course.code} ${s.course.name} (${s.course.section})",
            date = fmtDate(req.timeMs), timestamp = req.timeMs, status = AttStatus.PRESENT
        )
        val cancelled = dao.record(rec.sessionId, st.studentId)?.status == AttStatus.CANCELLED
        val status = if (cancelled || dao.addAttendance(rec) == -1L) Status.DUPLICATE else Status.OK
        val courses = dao.coursesOfStudent(st.studentId).map {
            val sched = try { scheduleOf(it.id) } catch (_: Exception) { "" }
            "${it.code} ${it.name} (${it.section})" + if (sched.isNotBlank()) " — $sched" else ""
        }
        val profile = StudentProfile(st.studentId, st.name, st.faculty, st.major, st.level, st.section, courses)
        return Outcome(status, st, profile, cancelled)
    }

    companion object {
        fun describe(out: Outcome, tag: String): String {
            val name = out.student?.name ?: ""
            if (out.cancelled) return "⚠ $name — ألغيتَ تحضيره في هذه المحاضرة (يمكنك إعادته من «الحاضرون»)"
            return when (out.status) {
                Status.OK -> "✓ تم تسجيل حضور $name"
                Status.DUPLICATE -> "⚠ $name — سُجّل حضوره مسبقًا"
                Status.NOT_ENROLLED -> "✗ الطالب غير مسجل في هذه المادة ($name)"
                Status.UNKNOWN_STUDENT -> "✗ كود غير موجود في الكشف ($tag) — اضغط «مزامنة الآن» في تبويب المواد"
                Status.BAD_PROOF -> "✗ كود الطالب غير صحيح"
                Status.KEY_MISMATCH -> "✗ $name: الكود مرتبط بجهاز آخر — يحتاج كودًا جديدًا من الإدارة"
                Status.BAD_SIGNATURE -> "✗ فشل التحقق من جهاز الطالب"
                else -> "✗ انتهت مدة الجلسة"
            }
        }
    }
}
