package com.uniatt.licensekit

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings
import com.uniatt.core.KeyValue
import com.uniatt.core.LicenseClient
import com.uniatt.core.LicenseKeeper
import com.uniatt.core.LicenseStatus
import com.uniatt.core.LicenseVerdict
import com.uniatt.core.RelayDefaults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private class PrefsKv(private val sp: SharedPreferences) : KeyValue {
    override fun get(key: String): String? = sp.getString(key, null)
    override fun put(key: String, value: String?) { sp.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply() }
}

/**
 * مدير الترخيص على الهاتف (واحد لكل تطبيق). يجلب الترخيص من Firebase ويحفظه ويحكم عليه بساعة موثوقة.
 *  - [verdict]: الحكم الحالي للواجهة.   - [allowed]: للخدمات الخلفية (المزامنة، البلوتوث) قبل أي عمل.
 * أي فشل في الجلب (انقطاع، قواعد لم تُحدَّث) يُبقي آخر حكم معروف؛ ولا يُقفَل أحد إلا بقرار صريح من المطوّر.
 */
object LicenseManager {
    @Volatile private var keeper: LicenseKeeper? = null
    @Volatile private var appCtx: Context? = null
    private val TAMPERED = LicenseVerdict(LicenseStatus.TAMPERED, true, "نسخة غير موثوقة",
        "تم اكتشاف أن ملف التطبيق عُدّل أو أُعيد توقيعه. ثبّت النسخة الأصلية من المطوّر. بياناتك محفوظة ولم تُحذف، ويمكنك تصدير نسخة منها.")
    @Volatile private var relayProvider: () -> String = { RelayDefaults.URL }
    private val state = MutableStateFlow(LicenseVerdict.UNMANAGED)
    private val mutex = Mutex()

    val verdict: StateFlow<LicenseVerdict> get() = state

    /** آمن للاستدعاء المتكرر ومن أي نقطة دخول (نشاط، عامل خلفية، خدمة). */
    @Synchronized
    fun ensure(ctx: Context, relayUrl: () -> String = { RelayDefaults.URL }) {
        relayProvider = relayUrl
        appCtx = ctx.applicationContext
        if (keeper == null) {
            val app = ctx.applicationContext
            val boot = try { Settings.Global.getInt(app.contentResolver, "boot_count", -1).toString() } catch (_: Exception) { "" }
            keeper = LicenseKeeper(PrefsKv(app.getSharedPreferences("uniatt_license", Context.MODE_PRIVATE)),
                mono = { SystemClock.elapsedRealtime() }, bootId = { boot })
        }
        recompute()
    }

    /** يعيد حساب الحكم (مرور الوقت: دخول فترة السماح أو الانتهاء). */
    fun recompute(): LicenseVerdict {
        val c = appCtx
        val v = if (c != null && !Integrity.ok(c)) TAMPERED else keeper?.verdict() ?: LicenseVerdict.UNMANAGED
        state.value = v
        return v
    }

    /** هل الخدمات مسموحة الآن؟ تُستدعى من العمال والخدمات. إن لم يُهيَّأ المدير بعد فالجواب نعم. */
    fun allowed(): Boolean = !recompute().blocked

    /** يجلب أحدث ترخيص من الخادم (من أي خيط). */
    suspend fun refresh(): LicenseVerdict = mutex.withLock {
        withContext(Dispatchers.IO) {
            val k = keeper ?: return@withContext LicenseVerdict.UNMANAGED
            when (val f = LicenseClient.fetch(relayProvider())) {
                is LicenseClient.Fetch.Ok -> k.onFetched(f.raw, f.serverMs)
                else -> Unit
            }
            recompute()
        }
    }
}
