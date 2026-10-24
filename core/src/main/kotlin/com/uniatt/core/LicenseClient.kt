package com.uniatt.core

import java.io.IOException

/**
 * جلب الترخيص من Firebase (قراءة عامة لـ `license/doc`). وقت الخادم يأتي من ترويسة Date فلا حاجة لصلاحية كتابة.
 * أي خطأ (قواعد لم تُحدَّث بعد، انقطاع…) لا يغيّر الحكم المحفوظ: التطبيق يكمل بآخر حكم معروف.
 */
object LicenseClient {
    const val PATH = "license/doc"

    sealed class Fetch {
        /** [raw] = نص المستند، أو null إن لم يُنشأ ترخيص بعد (نظام غير مُدار). */
        class Ok(val raw: String?, val serverMs: Long) : Fetch()
        object Offline : Fetch()
        class Error(val msg: String) : Fetch()
    }

    /** يُستدعى من خيط خلفي. */
    fun fetch(relayUrl: String = RelayDefaults.URL): Fetch {
        var url = Pairing.normalizeUrl(relayUrl) ?: return Fetch.Error("عنوان القاعدة غير صالح")
        return try {
            val (raw, ms) = try {
                Relay(url, timeoutMs = 12_000).getStringWithServerTime(PATH)
            } catch (e: Relay.RelayException) {
                if (e.code != 404) return Fetch.Error(e.friendly())
                url = RelayLocator.find(url) ?: return Fetch.Error(e.friendly())
                Relay(url, timeoutMs = 12_000).getStringWithServerTime(PATH)
            }
            if (ms <= 0L) Fetch.Error("لم يرسل الخادم وقته") else Fetch.Ok(raw, ms)
        } catch (e: Relay.RelayException) {
            Fetch.Error(e.friendly())
        } catch (e: IOException) {
            Fetch.Offline
        }
    }
}
