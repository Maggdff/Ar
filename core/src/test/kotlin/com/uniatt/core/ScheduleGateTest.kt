package com.uniatt.core

import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** «لا تحضير إلا حسب الجدول»: بوابة الدكتور (موعد المادة + قصّ المدة) وبوابة الطالب (جدوله) والساعة الموثوقة. */
class ScheduleGateTest {
    private val tz = TimeZone.getTimeZone("GMT+3")

    /** 2026-10-18 يوم أحد (day = 0)، فـ day = 1 الإثنين… */
    private fun at(day: Int, h: Int, m: Int, s: Int = 0): Long =
        Calendar.getInstance(tz).apply { clear(); set(2026, Calendar.OCTOBER, 18 + day, h, m, s) }.timeInMillis

    private val slots = listOf(
        RSlot(1, 0, 8 * 60, 9 * 60 + 30), RSlot(1, 0, 10 * 60, 11 * 60 + 30),   // المادة 1: الأحد 08:00–09:30 و10:00–11:30
        RSlot(2, 1, 10 * 60, 12 * 60)                                            // المادة 2: الإثنين 10:00–12:00
    )

    // ---------- الدكتور ----------
    @Test fun doctorCanStartOnlyInsideTheCourseSlot() {
        assertTrue(AttendancePolicy.checkDoctorStart(slots, 1, at(0, 8, 0, 0), 30, tz).ok)
        assertTrue(AttendancePolicy.checkDoctorStart(slots, 1, at(0, 9, 29, 0), 30, tz).ok)
        assertTrue(AttendancePolicy.checkDoctorStart(slots, 1, at(0, 10, 30), 30, tz).ok)        // الموعد الثاني

        val early = AttendancePolicy.checkDoctorStart(slots, 1, at(0, 7, 59, 59), 30, tz)
        assertFalse(early.ok); assertEquals(AttendancePolicy.Reason.OUTSIDE_SLOT, early.reason)
        assertEquals(AttendancePolicy.Reason.OUTSIDE_SLOT, AttendancePolicy.checkDoctorStart(slots, 1, at(0, 9, 30, 0), 30, tz).reason)   // النهاية غير شاملة
        assertEquals(AttendancePolicy.Reason.OUTSIDE_SLOT, AttendancePolicy.checkDoctorStart(slots, 1, at(0, 9, 45), 30, tz).reason)    // بين الموعدين
        assertEquals(AttendancePolicy.Reason.OUTSIDE_SLOT, AttendancePolicy.checkDoctorStart(slots, 1, at(1, 8, 30), 30, tz).reason)    // يوم آخر
        assertEquals(AttendancePolicy.Reason.OUTSIDE_SLOT, AttendancePolicy.checkDoctorStart(slots, 2, at(0, 10, 30), 30, tz).reason)   // موعد مادة أخرى لا يخصها
        assertTrue(AttendancePolicy.checkDoctorStart(slots, 2, at(1, 10, 30), 30, tz).ok)
    }

    @Test fun doctorWithoutAnySlotForTheCourseIsRefused() {
        val d = AttendancePolicy.checkDoctorStart(slots, 3, at(0, 8, 30), 30, tz)
        assertFalse(d.ok); assertEquals(AttendancePolicy.Reason.NO_SLOTS, d.reason)
        assertFalse(AttendancePolicy.checkDoctorStart(emptyList(), 1, at(0, 8, 30), 30, tz).ok)
    }

    @Test fun refusalMessageListsTheCourseSlots() {
        val d = AttendancePolicy.checkDoctorStart(slots, 1, at(2, 8, 30), 30, tz)
        assertTrue(d.message.contains(Schedule.describeAll(slots, 1, false)))
    }

    @Test fun sessionDurationIsCutAtTheSlotEnd() {
        assertEquals(30, AttendancePolicy.checkDoctorStart(slots, 1, at(0, 9, 0, 0), 60, tz).minutes)      // طلب 60 فبقي 30
        assertEquals(15, AttendancePolicy.checkDoctorStart(slots, 1, at(0, 9, 0, 0), 15, tz).minutes)      // الأقصر يبقى
        assertEquals(29, AttendancePolicy.checkDoctorStart(slots, 1, at(0, 9, 0, 30), 60, tz).minutes)     // تُقرَّب للأسفل: لا تتجاوز النهاية
        assertEquals(1, AttendancePolicy.checkDoctorStart(slots, 1, at(0, 9, 28, 30), 60, tz).minutes)
        assertEquals(1, AttendancePolicy.checkDoctorStart(slots, 1, at(0, 9, 0), 0, tz).minutes)           // طلب غير صالح => 1
        // لا تتجاوز الجلسة نهاية الموعد أبدًا
        for (sec in 0 until 90 * 60 step 37) {
            val now = at(0, 8, 0) + sec * 1000L
            val d = AttendancePolicy.checkDoctorStart(slots, 1, now, 240, tz)
            if (d.ok) assertTrue("sec=$sec", now + d.minutes * 60_000L <= at(0, 9, 30))
        }
    }

    @Test fun lastMinuteOfTheSlotIsTooLate() {
        val d = AttendancePolicy.checkDoctorStart(slots, 1, at(0, 9, 29, 30), 30, tz)
        assertFalse(d.ok); assertEquals(AttendancePolicy.Reason.TOO_LATE, d.reason)
    }

    @Test fun currentSlotUsesExactBoundaries() {
        assertEquals(1L, AttendancePolicy.currentSlot(slots, at(0, 8, 0), tz)?.courseId)
        assertNull(AttendancePolicy.currentSlot(slots, at(0, 7, 59, 59), tz))
        assertNull(AttendancePolicy.currentSlot(slots, at(0, 9, 30), tz))
        assertEquals(2L, AttendancePolicy.currentSlot(slots, at(1, 11, 0), tz)?.courseId)
        assertEquals(30, AttendancePolicy.remainingMinutes(slots[0], at(0, 9, 0), tz))
    }

    // ---------- الطالب ----------
    private fun te(day: Int, a: Int, b: Int, kind: Int = TKind.NORMAL) =
        TEntry(1, "أمن", "الأول", day, a, b, "مادة", kind)

    @Test fun studentMayAttendOnlyInsideALectureOfHisTimetable() {
        val tt = listOf(te(0, 8 * 60, 10 * 60), te(0, 12 * 60, 13 * 60, TKind.EXAM), te(1, 10 * 60, 12 * 60, TKind.MAKEUP))
        assertTrue(AttendancePolicy.checkStudent(tt, at(0, 9, 0), tz).ok)
        assertTrue(AttendancePolicy.checkStudent(tt, at(0, 8, 0, 0), tz).ok)
        assertTrue(AttendancePolicy.checkStudent(tt, at(1, 10, 30), tz).ok)                                  // التعويضية محاضرة
        assertEquals(AttendancePolicy.Reason.OUTSIDE_LECTURE, AttendancePolicy.checkStudent(tt, at(0, 10, 0, 0), tz).reason)   // النهاية غير شاملة
        assertEquals(AttendancePolicy.Reason.OUTSIDE_LECTURE, AttendancePolicy.checkStudent(tt, at(0, 7, 59, 59), tz).reason)
        assertEquals(AttendancePolicy.Reason.OUTSIDE_LECTURE, AttendancePolicy.checkStudent(tt, at(0, 12, 30), tz).reason)      // الاختبار ليس محاضرة
        assertEquals(AttendancePolicy.Reason.OUTSIDE_LECTURE, AttendancePolicy.checkStudent(tt, at(2, 9, 0), tz).reason)        // يوم بلا محاضرة
    }

    @Test fun studentWithoutTimetableCannotAttend() {
        assertEquals(AttendancePolicy.Reason.NO_TIMETABLE, AttendancePolicy.checkStudent(emptyList(), at(0, 9, 0), tz).reason)
        assertEquals(AttendancePolicy.Reason.NO_TIMETABLE, AttendancePolicy.checkStudent(listOf(te(0, 8 * 60, 10 * 60, TKind.EXAM)), at(0, 9, 0), tz).reason)
    }

    // ---------- الساعة الموثوقة ----------
    private class Fake { var wall = 0L; var mono = 0L; var boot = "7" }

    private fun clock(f: Fake) = TrustedClock({ f.wall }, { f.mono }, { f.boot })

    @Test fun clockNeedsSyncBeforeFirstUse() {
        val f = Fake(); val c = clock(f)
        assertFalse(c.read().trusted)
        assertEquals(TrustedClock.State.NEEDS_SYNC, c.read().state)
    }

    @Test fun clockFollowsServerPlusMonotonicElapsedAndCompensatesHalfTheRoundTrip() {
        val f = Fake(); f.wall = 5_000_000; f.mono = 5_000
        val c = clock(f)
        c.syncWith { f.mono += 400; 1_000_000L }          // الرحلة 400 مللي ثانية ← نصفها 200
        assertEquals(1_000_200L, c.read().ms)
        f.mono += 60_000
        assertEquals(1_060_200L, c.read().ms)
    }

    @Test fun changingThePhoneClockDoesNotMoveTrustedTime() {
        val f = Fake(); f.wall = 1_000_000; f.mono = 10_000
        val c = clock(f); c.anchorNow(1_000_000)
        f.mono += 30_000
        val before = c.read()
        assertEquals(1_030_000L, before.ms); assertFalse(before.tampered)
        f.wall += 3 * 3_600_000L                           // الدكتور قدّم ساعة هاتفه 3 ساعات
        val after = c.read()
        assertEquals(1_030_000L, after.ms)                 // الوقت الموثوق لم يتحرك
        assertTrue(after.tampered); assertEquals(3 * 3_600_000L - 30_000L, after.skewMs)
        f.wall -= 6 * 3_600_000L                           // وأخّرها للخلف
        assertEquals(1_030_000L, c.read().ms); assertTrue(c.read().tampered)
    }

    @Test fun smallDriftIsNotFlaggedAsTampering() {
        val f = Fake(); f.wall = 1_000_000; f.mono = 0
        val c = clock(f); c.anchorNow(1_000_000)
        f.mono += 600_000; f.wall += 600_000 + 30_000      // انحراف 30 ثانية فقط
        assertFalse(c.read().tampered)
        f.wall += AttendancePolicy.CLOCK_TOLERANCE_MS
        assertTrue(c.read().tampered)
    }

    @Test fun rebootInvalidatesTheAnchor() {
        val f = Fake(); f.wall = 1_000_000; f.mono = 900_000
        val c = clock(f); c.anchorNow(1_000_000)
        f.mono = 1_000                                     // الرتيبة بدأت من جديد
        assertFalse(c.read().trusted)
        f.mono = 5_000_000; f.boot = "8"                   // رتيبة أكبر لكن الإقلاع تغيّر
        assertFalse(c.read().trusted)
        c.anchorNow(2_000_000)                             // مزامنة جديدة تعيد الثقة
        assertTrue(c.read().trusted)
    }

    @Test fun failedSyncKeepsTheOldAnchor() {
        val f = Fake(); f.mono = 100
        val c = clock(f)
        assertTrue(runCatching { c.syncWith { throw java.io.IOException("offline") } }.isFailure)
        assertFalse(c.read().trusted)
        c.anchorNow(500); f.mono += 10
        assertTrue(runCatching { c.syncWith { throw java.io.IOException("offline") } }.isFailure)
        assertEquals(510L, c.read().ms)
    }

    @Test fun anchorSurvivesSaveAndRestoreAndRejectsGarbage() {
        val f = Fake(); f.wall = 9; f.mono = 1_000
        val a = clock(f); a.anchorNow(777_000)
        val saved = a.encode()!!
        val b = clock(f); b.restore(saved)
        f.mono += 5_000
        assertEquals(782_000L, b.read().ms)
        val g = clock(f)
        for (bad in listOf(null, "", "x", "TC1|a|b", "TC1|7|x|1|1", "TC2|7|1|1|1")) { g.restore(bad); assertFalse("$bad", g.read().trusted) }
    }

    @Test fun gateUsesTrustedTimeNotThePhoneClock() {
        // الوقت الحقيقي 07:00 (خارج الموعد)، والدكتور ضبط ساعة هاتفه على 08:30 (داخل الموعد) ليبدأ الجلسة
        val f = Fake(); f.mono = 0; f.wall = at(0, 8, 30)
        val c = clock(f); c.anchorNow(at(0, 7, 0))
        val r = c.read()
        assertTrue(r.tampered)
        val d = AttendancePolicy.checkDoctorStart(slots, 1, r.ms, 30, tz)
        assertFalse(d.ok); assertEquals(AttendancePolicy.Reason.OUTSIDE_SLOT, d.reason)
        // ولو أخّر ساعته وهو داخل الموعد الحقيقي لا يخسر شيئًا ولا يربح شيئًا: القرار من الوقت الموثوق وحده
        f.mono += 90 * 60_000L                             // مرّت ساعة ونصف حقيقية → 08:30 حقيقيًا
        f.wall = at(0, 6, 0)
        assertTrue(AttendancePolicy.checkDoctorStart(slots, 1, c.read().ms, 30, tz).ok)
    }
}
