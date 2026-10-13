package com.uniatt.student

import com.uniatt.core.Codes
import com.uniatt.core.Hex
import com.uniatt.core.Kdf
import com.uniatt.core.StudentClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * الجزء الوحيد من تطبيق الطالب الذي يستخدم الإنترنت:
 *  - أول تفعيل: يُتحقق من الكود بجلب صندوق الطالب المشفّر من الإدارة وفكّه؛ بلا إنترنت أو بكود غير مسجَّل لا يُفعَّل التطبيق.
 *  - بعده: كلما توفّر الإنترنت يُحدَّث الجدول والبيانات تلقائيًا. الحضور نفسه (بلوتوث/QR) يبقى بلا إنترنت.
 */
object OnlineSync {
    sealed class Activation {
        class Ok(val name: String) : Activation()
        class Failed(val msg: String) : Activation()
    }

    /** يُستدعى من خيط خلفي (شبكة + PBKDF2). لا يُحفظ شيء إلا عند النجاح الكامل. */
    fun activate(store: StudentStore, code: String): Activation {
        val key = Kdf.studentKey(code)
        return when (val f = StudentClient.fetch(Hex.enc(key))) {
            is StudentClient.Fetch.Ok -> {
                try { com.uniatt.student.DeviceKey.publicRaw() } catch (e: Exception) { return Activation.Failed("تعذّر التفعيل على هذا الجهاز") }
                store.saveCredentials(Codes.tag(code), key)
                store.saveOnline(f.pub)
                Activation.Ok(f.pub.name)
            }
            is StudentClient.Fetch.NotFound ->
                Activation.Failed("هذا الكود غير مسجَّل لدى الإدارة (أو لم تتم مزامنته بعد). تأكد من كتابته وأعد المحاولة، وإن استمرت المشكلة فراجع الإدارة.")
            is StudentClient.Fetch.Offline ->
                Activation.Failed("لا يوجد اتصال بالإنترنت. أول تفعيل يحتاج إنترنت للتحقق من الكود؛ اتصل بالإنترنت ثم أعد المحاولة.")
            is StudentClient.Fetch.Error -> Activation.Failed(f.msg)
        }
    }

    /** يحدّث الجدول والبيانات؛ يعيد رسالة حالة عربية. لا يمسح شيئًا محليًا عند أي فشل. */
    fun refresh(store: StudentStore): String {
        val c = store.credentials() ?: return ""
        store.lastTry = System.currentTimeMillis()
        return when (val f = StudentClient.fetch(Hex.enc(c.key))) {
            is StudentClient.Fetch.Ok -> { store.saveOnline(f.pub); "✓ تم تحديث جدولك وبياناتك" }
            is StudentClient.Fetch.NotFound -> "لم يعد كودك مسجّلًا لدى الإدارة — راجعها (بقي آخر جدول وصلك)"
            is StudentClient.Fetch.Offline -> "لا اتصال بالإنترنت — يُحدَّث الجدول تلقائيًا عند توفّره"
            is StudentClient.Fetch.Error -> f.msg
        }
    }

    suspend fun refreshNow(store: StudentStore): String = withContext(Dispatchers.IO) { refresh(store) }
}
