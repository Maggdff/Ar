package com.uniatt.core

import java.util.Calendar
import java.util.TimeZone

/**
 * سياسة «لا تحضير إلا حسب الجدول» (نسختها بالمنطق نفسه في pwa/core.js):
 *  - الدكتور: لا تبدأ جلسة لمادة إلا داخل موعدها في الجدول، وتُقصّ مدتها عند نهاية الموعد.
 *  - الطالب: لا يُجهَّز ردّ حضور إلا إن وقع وقت التحدّي (وقت هاتف الدكتور الموثوق) داخل محاضرة من جدوله.
 * الوقت المُمرَّر لهذه الدوال يجب أن يكون الوقت الموثوق (انظر TrustedClock) لا ساعة الهاتف الخام.
 */
object AttendancePolicy {
    /** كم دقيقة قبل بدء الموعد يُسمح بفتح الجلسة. 0 = لا تُفتح إلا داخل الموعد نفسه. */
    const val EARLY_START_MIN = 0

    /** أقصى فرق بين ساعة الهاتف والوقت الموثوق قبل اعتبار الساعة معبوثًا بها (للتنبيه فقط؛ المعتمد دائمًا الوقت الموثوق). */
    const val CLOCK_TOLERANCE_MS = 120_000L

    enum class Reason { NO_SLOTS, OUTSIDE_SLOT, TOO_LATE, NO_TIMETABLE, OUTSIDE_LECTURE, CLOCK_NEEDS_SYNC }

    /** [minutes] = المدة المسموحة للجلسة (بعد القصّ عند نهاية الموعد) عند [ok] للدكتور. */
    class Decision(val ok: Boolean, val minutes: Int = 0, val reason: Reason? = null, val message: String = "")

    const val MSG_NEEDS_SYNC = "تعذّر التأكد من الوقت الحقيقي لهذا الهاتف. اتصل بالإنترنت مرة واحدة ثم أعد المحاولة."

    class LocalTime(val day: Int, val secondOfDay: Int) { val minute get() = secondOfDay / 60 }

    /** يوم الأسبوع (0 = الأحد) وثانية اليوم بتوقيت [tz]. */
    fun localTime(ms: Long, tz: TimeZone = TimeZone.getDefault()): LocalTime {
        val c = Calendar.getInstance(tz).apply { timeInMillis = ms }
        val sod = c.get(Calendar.HOUR_OF_DAY) * 3600 + c.get(Calendar.MINUTE) * 60 + c.get(Calendar.SECOND)
        return LocalTime(c.get(Calendar.DAY_OF_WEEK) - 1, sod)
    }

    private fun inside(s: RSlot, lt: LocalTime) =
        s.day == lt.day && lt.secondOfDay >= (s.startMin - EARLY_START_MIN) * 60 && lt.secondOfDay < s.endMin * 60

    /** الدقائق الكاملة المتبقية من الموعد (تُقرَّب للأسفل كي لا تتجاوز الجلسة نهاية الموعد). */
    fun remainingMinutes(slot: RSlot, nowMs: Long, tz: TimeZone = TimeZone.getDefault()): Int =
        (slot.endMin * 60 - localTime(nowMs, tz).secondOfDay).coerceAtLeast(0) / 60

    /** الموعد الجاري الآن لأي مادة (للبطاقة «حسب الجدول الآن»)، وإلا null. */
    fun currentSlot(slots: List<RSlot>, nowMs: Long, tz: TimeZone = TimeZone.getDefault()): RSlot? {
        val lt = localTime(nowMs, tz)
        return slots.filter { inside(it, lt) }.minByOrNull { it.startMin }
    }

    /** هل يجوز للدكتور بدء جلسة لمادة [courseId] الآن؟ ومدتها بعد القصّ عند نهاية الموعد. */
    fun checkDoctorStart(slots: List<RSlot>, courseId: Long, nowMs: Long, requestedMin: Int, tz: TimeZone = TimeZone.getDefault()): Decision {
        val mine = slots.filter { it.courseId == courseId }
        if (mine.isEmpty()) return Decision(false, reason = Reason.NO_SLOTS,
            message = "لا يوجد موعد لهذه المادة في الجدول، فلا يمكن بدء الحضور. راجع المسؤول.")
        val lt = localTime(nowMs, tz)
        val slot = mine.filter { inside(it, lt) }.minByOrNull { it.startMin }
            ?: return Decision(false, reason = Reason.OUTSIDE_SLOT,
                message = "ليس الآن موعد هذه المادة. مواعيدها: ${Schedule.describeAll(mine, courseId, false)}. لا يبدأ الحضور إلا داخل الموعد.")
        val left = remainingMinutes(slot, nowMs, tz)
        if (left < 1) return Decision(false, reason = Reason.TOO_LATE, message = "بقي أقل من دقيقة على نهاية موعد المحاضرة.")
        return Decision(true, minutes = requestedMin.coerceAtLeast(1).coerceAtMost(left))
    }

    /** هل يجوز للطالب إرسال حضور في الوقت [timeMs] (وقت التحدّي من هاتف الدكتور)؟ الاختبارات ليست محاضرات. */
    fun checkStudent(timetable: List<TEntry>, timeMs: Long, tz: TimeZone = TimeZone.getDefault()): Decision {
        val lectures = timetable.filter { it.kind != TKind.EXAM }
        if (lectures.isEmpty()) return Decision(false, reason = Reason.NO_TIMETABLE,
            message = "لا يوجد جدول عندك بعد، فلا يُسجَّل الحضور. حدّث جدولك بالإنترنت ثم أعد المحاولة.")
        val lt = localTime(timeMs, tz)
        val ok = lectures.any { it.day == lt.day && lt.secondOfDay >= (it.startMin - EARLY_START_MIN) * 60 && lt.secondOfDay < it.endMin * 60 }
        return if (ok) Decision(true)
        else Decision(false, reason = Reason.OUTSIDE_LECTURE, message = "ليس الآن وقت محاضرة في جدولك، فلا يُسجَّل الحضور.")
    }
}
