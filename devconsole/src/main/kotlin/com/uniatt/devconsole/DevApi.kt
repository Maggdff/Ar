package com.uniatt.devconsole

import com.uniatt.core.LicenseCodec
import com.uniatt.core.LicenseDoc
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom

/** سطر في سجل التدقيق (يُكتب من الجهاز لكن ختم الزمن من خادم Firebase نفسه). */
class AuditEntry(val action: String, val detail: String, val before: String, val after: String, val rev: Long)

class LogRow(val key: String, val timeMs: Long, val email: String, val action: String, val detail: String, val before: String, val after: String, val rev: Long)

class Snapshot(val raw: String?, val doc: LicenseDoc?, val serverMs: Long)

class DevApiException(val code: Int, val friendly: String) : Exception("HTTP $code: $friendly")

/**
 * واجهة المطوّر لقاعدة Firebase (REST + رمز المصادقة):
 *  - القراءة: license/doc عامة؛ devlog وdevAdmins بحسب القواعد.
 *  - الكتابة: تعديل الترخيص وسطر التدقيق في طلب PATCH واحد على الجذر = عملية ذرّية (إمّا الاثنان أو لا شيء)،
 *    فلا يوجد تعديل في الترخيص بلا أثر في السجل.
 */
class DevApi(baseUrl: String, private val session: DevSession, private val rnd: SecureRandom = SecureRandom()) {
    private val base = baseUrl.trim().trimEnd('/')

    private fun call(method: String, path: String, query: String = "", body: String? = null, retry: Boolean = true): Pair<String, Long> {
        val q = (if (query.isBlank()) "" else "$query&") + "auth=" + URLEncoder.encode(session.token(), "UTF-8")
        val c = URL("$base/$path.json?$q").openConnection() as HttpURLConnection
        try {
            c.requestMethod = if (method == "PATCH") "POST" else method          // HttpURLConnection لا يدعم PATCH؛ Firebase يقبل X-HTTP-Method-Override
            if (method == "PATCH") c.setRequestProperty("X-HTTP-Method-Override", "PATCH")
            c.connectTimeout = 15_000; c.readTimeout = 15_000
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) } }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
            if (code == 401 && retry && text.contains("expired", ignoreCase = true)) {
                session.forceRefresh(); return call(method, path, query, body, retry = false)
            }
            if (code !in 200..299) throw DevApiException(code, when (code) {
                401, 403 -> "غير مصرّح: حسابك ليس ضمن قائمة المطوّرين المعتمدين (devAdmins) أو أن القواعد لم تُنشر بعد"
                404 -> "عنوان قاعدة البيانات غير صحيح"
                412 -> "تعارض في الكتابة؛ أعد المحاولة"
                else -> "خطأ من الخادم ($code)"
            })
            return text to c.getHeaderFieldDate("Date", 0L)
        } finally { c.disconnect() }
    }

    /** هل uid ضمن devAdmins؟ (القواعد تسمح بقراءة مدخل صاحبه فقط). */
    fun isAuthorized(uid: String): Boolean = try {
        call("GET", "devAdmins/" + URLEncoder.encode(uid, "UTF-8")).first.trim() == "true"
    } catch (e: DevApiException) { false }

    fun readLicense(): Snapshot {
        val (body, ms) = call("GET", "license/doc")
        val raw = body.trim().let { if (it == "null" || it.isEmpty()) null else JSONObject("{\"v\":$it}").getString("v") }
        return Snapshot(raw, LicenseCodec.decode(raw), ms)
    }

    private fun logNode(e: AuditEntry): Pair<String, JSONObject> {
        val key = "L" + System.currentTimeMillis() + "_" + Integer.toHexString(rnd.nextInt()).padStart(8, '0')
        val s = session.current
        return key to JSONObject().put("t", JSONObject().put(".sv", "timestamp")).put("uid", s.uid).put("email", s.email)
            .put("action", e.action).put("detail", e.detail).put("before", e.before).put("after", e.after).put("rev", e.rev).put("app", "devconsole")
    }

    /** يكتب المستند الجديد (إن وُجد) وسطر التدقيق معًا ذرّيًا. */
    fun commit(newDoc: LicenseDoc?, entry: AuditEntry) {
        val (key, node) = logNode(entry)
        val body = JSONObject().apply {
            if (newDoc != null) put("license/doc", LicenseCodec.encode(newDoc))
            put("devlog/$key", node)
        }
        call("PATCH", "", body = body.toString())
    }

    fun listLogs(limit: Int = 100): List<LogRow> {
        val (body, _) = call("GET", "devlog", "orderBy=" + URLEncoder.encode("\"t\"", "UTF-8") + "&limitToLast=$limit")
        val t = body.trim()
        if (t == "null" || t.isEmpty()) return emptyList()
        val j = JSONObject(t)
        return j.keys().asSequence().map { k ->
            val o = j.getJSONObject(k)
            LogRow(k, o.optLong("t"), o.optString("email"), o.optString("action"), o.optString("detail"), o.optString("before"), o.optString("after"), o.optLong("rev"))
        }.sortedByDescending { it.timeMs }.toList()
    }
}
