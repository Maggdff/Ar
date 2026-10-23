package com.uniatt.core

import kotlin.math.abs

/**
 * ساعة موثوقة لا تتأثر بتغيير الدكتور لساعة هاتفه: وقت الخادم (Firebase) عند آخر مزامنة + المدة المنقضية على ساعة
 * رتيبة (monotonic) لا يغيّرها المستخدم (على أندرويد SystemClock.elapsedRealtime).
 *  - الرتيبة تُصفَّر عند إعادة تشغيل الهاتف، فالمرساة (anchor) تحمل معرّف الإقلاع [bootId]؛ بعد إعادة التشغيل
 *    تصير القراءة NEEDS_SYNC حتى تتم مزامنة جديدة مع الخادم (لا نثق بالساعة الخام لأنها قابلة للتعديل).
 *  - [Reading.skewMs] = ساعة الهاتف − الوقت الموثوق؛ إن تجاوز [AttendancePolicy.CLOCK_TOLERANCE_MS] فالساعة معبوث بها.
 */
class TrustedClock(
    private val wall: () -> Long = { System.currentTimeMillis() },
    private val mono: () -> Long,
    private val bootId: () -> String = { "" }
) {
    data class Anchor(val serverMs: Long, val monoMs: Long, val wallMs: Long, val boot: String)

    enum class State { TRUSTED, NEEDS_SYNC }

    class Reading(val state: State, val ms: Long = 0L, val skewMs: Long = 0L) {
        val trusted get() = state == State.TRUSTED
        val tampered get() = trusted && abs(skewMs) > AttendancePolicy.CLOCK_TOLERANCE_MS
    }

    @Volatile var anchor: Anchor? = null
        private set

    /** يضبط المرساة على وقت خادم معلوم الآن. */
    fun anchorNow(serverMs: Long) { anchor = Anchor(serverMs, mono(), wall(), bootId()) }

    /**
     * يزامن مع الخادم: [fetchServerMs] يعيد وقت الخادم؛ نُعوّض نصف زمن الرحلة (الاستجابة وصلت بعد نصف الرحلة من لحظة ختم الخادم).
     * أي استثناء من [fetchServerMs] (انقطاع…) يمرّ لمن استدعى ولا تتغير المرساة.
     */
    fun syncWith(fetchServerMs: () -> Long) {
        val t0 = mono()
        val s = fetchServerMs()
        val t1 = mono()
        anchor = Anchor(s + (t1 - t0).coerceAtLeast(0L) / 2, t1, wall(), bootId())
    }

    fun read(): Reading {
        val a = anchor ?: return Reading(State.NEEDS_SYNC)
        val m = mono()
        if (m < a.monoMs || a.boot != bootId()) return Reading(State.NEEDS_SYNC)      // إعادة تشغيل: الرتيبة بدأت من جديد
        val t = a.serverMs + (m - a.monoMs)
        return Reading(State.TRUSTED, t, wall() - t)
    }

    // ---- حفظ المرساة (يحفظها التطبيق في SharedPreferences) ----
    fun encode(): String? = anchor?.let { "TC1|${it.boot.replace('|', '_')}|${it.serverMs}|${it.monoMs}|${it.wallMs}" }

    fun restore(text: String?) {
        val p = text?.split('|') ?: return
        if (p.size != 5 || p[0] != "TC1") return
        val s = p[2].toLongOrNull() ?: return
        val m = p[3].toLongOrNull() ?: return
        val w = p[4].toLongOrNull() ?: return
        anchor = Anchor(s, m, w, p[1])
    }
}
