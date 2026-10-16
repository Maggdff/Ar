package com.uniatt.core

import java.io.IOException

/** عنوان قاعدة Firebase المشترك بين التطبيقات (يُغيَّر هنا مرة واحدة). */
object RelayDefaults {
    const val URL = "https://maggd-141d1-default-rtdb.europe-west1.firebasedatabase.app"
}

/**
 * جهة الطالب على الإنترنت: يجلب صندوقه المشفّر ويفكّه بمفتاحه المشتق من كوده.
 * لا يوجد «تحقق» منفصل: الكود الصحيح المسجَّل عند المسؤول هو وحده الذي يصل إلى صندوق موجود ويفتحه.
 */
object StudentClient {
    sealed class Fetch {
        class Ok(val pub: StudentPublic) : Fetch()
        /** لا صندوق لهذا الكود: كود غير مسجَّل عند المسؤول، أو لم يزامن المسؤول بعد، أو أُلغي الكود. */
        object NotFound : Fetch()
        object Offline : Fetch()
        class Error(val msg: String) : Fetch()
    }

    /** يربط كود الطالب بهاتفه (مفتاحه العام hex). يرمي IOException/RelayException عند فشل الشبكة. */
    fun claimDevice(keyHex: String, deviceHex: String, relayUrl: String = RelayDefaults.URL): DeviceClaim.Result =
        DeviceClaim.claim(Relay(relayUrl), "m/${StudentBox.boxId(keyHex)}", deviceHex)

    /** يُستدعى من خيط خلفي (شبكة + فكّ تشفير). */
    fun fetch(keyHex: String, relayUrl: String = RelayDefaults.URL): Fetch {
        var url = Pairing.normalizeUrl(relayUrl) ?: return Fetch.Error("عنوان القاعدة غير صالح")
        val path = StudentBox.path(keyHex)
        try {
            var blob: ByteArray?
            try {
                blob = Relay(url).getBytes(path)
            } catch (e: Relay.RelayException) {
                if (e.code != 404) return Fetch.Error(e.friendly())
                // عنوان قاعدة خاطئ (المنطقة): نبحث عن الصحيح مرة واحدة
                url = RelayLocator.find(url) ?: return Fetch.Error(e.friendly())
                blob = Relay(url).getBytes(path)
            }
            if (blob == null) return Fetch.NotFound
            val pub = StudentBox.openPublic(keyHex, blob) ?: return Fetch.Error("بيانات الطالب على الخادم تالفة — راجع الإدارة")
            return Fetch.Ok(pub)
        } catch (e: Relay.RelayException) {
            return Fetch.Error(e.friendly())
        } catch (e: IOException) {
            return Fetch.Offline
        }
    }
}
