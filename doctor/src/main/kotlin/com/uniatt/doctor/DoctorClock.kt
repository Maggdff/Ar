package com.uniatt.doctor

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.uniatt.core.*

/**
 * الوقت الموثوق لهاتف الدكتور: وقت Firebase (لا ساعة الهاتف) عند آخر مزامنة + SystemClock.elapsedRealtime (لا يغيّرها المستخدم).
 * كل قرار «هل نحن داخل موعد المادة؟» وكل ختم زمني للجلسة والتحدّيات يمرّ من هنا، فتغيير ساعة الهاتف لا يفتح جلسة ولا يمدّدها.
 * بعد إعادة تشغيل الهاتف تلزم مزامنة واحدة بالإنترنت (الساعة الرتيبة تبدأ من الصفر وساعة الهاتف الخام قابلة للتعديل).
 */
object DoctorClock {
    @Volatile private var boot = ""
    private val clock = TrustedClock(wall = { System.currentTimeMillis() }, mono = { SystemClock.elapsedRealtime() }, bootId = { boot })
    @Volatile private var loaded = false
    private const val PREFS = "doctor_clock"

    @Synchronized private fun ensure(ctx: Context) {
        if (loaded) return
        boot = try { Settings.Global.getInt(ctx.applicationContext.contentResolver, "boot_count", -1).toString() } catch (_: Exception) { "" }
        clock.restore(ctx.applicationContext.getSharedPreferences(PREFS, 0).getString("anchor", null))
        loaded = true
    }

    fun read(ctx: Context): TrustedClock.Reading { ensure(ctx); return clock.read() }

    /** الوقت للتوقيع والعرض: الموثوق إن وُجد وإلا ساعة الهاتف (لا يُستعمل أبدًا لقرار بدء جلسة). */
    fun nowMs(ctx: Context): Long = read(ctx).let { if (it.trusted) it.ms else System.currentTimeMillis() }

    /** يزامن مع Firebase (من خيط خلفي). IOException/RelayException تمرّ لمن استدعى ولا تتغير المرساة. */
    fun sync(ctx: Context, relayUrl: String, mailbox: String) {
        ensure(ctx)
        clock.syncWith { Mailbox(Relay(relayUrl, timeoutMs = 8_000), mailbox).serverTimeMs() }
        ctx.applicationContext.getSharedPreferences(PREFS, 0).edit().putString("anchor", clock.encode()).apply()
    }

    /** مزامنة عند الطلب (قبل بدء جلسة): تعيد true إن نجحت. */
    fun trySync(ctx: Context): Boolean {
        val p = ctx.applicationContext.getSharedPreferences("doctor", 0)
        val url = p.getString("relay", null) ?: return false
        val mbox = p.getString("mbox", null) ?: return false
        return try { sync(ctx, url, mbox); true } catch (_: Exception) { false }
    }
}
