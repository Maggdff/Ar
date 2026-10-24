package com.uniatt.devconsole

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** جلسة مصادقة المطوّر (رمز الهوية قصير العمر + رمز التجديد). */
class AuthSession(val idToken: String, val refreshToken: String, val uid: String, val email: String, val expiresAtMs: Long)

class AuthException(val code: String, val friendly: String) : Exception("$code: $friendly")

/**
 * Firebase Authentication عبر REST (بريد + كلمة مرور) دون SDK ودون google-services.json.
 * مفتاح الـAPI هنا مفتاح عام (ليس سرًّا)؛ الأمان في القواعد وفي قائمة devAdmins وفي تعطيل التسجيل الجديد.
 */
class FirebaseAuthRest(
    private val apiKey: String,
    private val authBase: String = "https://identitytoolkit.googleapis.com",
    private val tokenBase: String = "https://securetoken.googleapis.com",
    private val wall: () -> Long = { System.currentTimeMillis() }
) {
    private fun post(url: String, body: String, contentType: String): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.connectTimeout = 15_000; c.readTimeout = 15_000; c.doOutput = true
            c.setRequestProperty("Content-Type", contentType)
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val s = (if (code in 200..299) c.inputStream else c.errorStream)?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
            return code to s
        } finally { c.disconnect() }
    }

    private fun fail(code: Int, body: String): Nothing {
        val msg = runCatching { JSONObject(body).getJSONObject("error").getString("message") }.getOrDefault("HTTP_$code")
        throw AuthException(msg, friendly(msg))
    }

    fun signIn(email: String, password: String): AuthSession {
        if (apiKey.isBlank()) throw AuthException("NO_API_KEY", "أدخل مفتاح Firebase (Web API Key) من شاشة الإعدادات أولًا")
        try {
            val body = JSONObject().put("email", email.trim()).put("password", password).put("returnSecureToken", true).toString()
            val (code, text) = post("$authBase/v1/accounts:signInWithPassword?key=" + URLEncoder.encode(apiKey, "UTF-8"), body, "application/json")
            if (code !in 200..299) fail(code, text)
            val j = JSONObject(text)
            return AuthSession(j.getString("idToken"), j.getString("refreshToken"), j.getString("localId"),
                j.optString("email", email.trim()), wall() + j.optString("expiresIn", "3600").toLong() * 1000 - 60_000)
        } catch (e: IOException) { throw AuthException("NETWORK", "تعذّر الاتصال بالإنترنت") }
    }

    fun refresh(refreshToken: String): AuthSession {
        try {
            val body = "grant_type=refresh_token&refresh_token=" + URLEncoder.encode(refreshToken, "UTF-8")
            val (code, text) = post("$tokenBase/v1/token?key=" + URLEncoder.encode(apiKey, "UTF-8"), body, "application/x-www-form-urlencoded")
            if (code !in 200..299) fail(code, text)
            val j = JSONObject(text)
            return AuthSession(j.getString("id_token"), j.getString("refresh_token"), j.getString("user_id"),
                j.optString("email", ""), wall() + j.optString("expires_in", "3600").toLong() * 1000 - 60_000)
        } catch (e: IOException) { throw AuthException("NETWORK", "تعذّر الاتصال بالإنترنت") }
    }

    companion object {
        fun friendly(code: String): String = when {
            code.startsWith("INVALID_LOGIN_CREDENTIALS") || code.startsWith("INVALID_PASSWORD") || code.startsWith("EMAIL_NOT_FOUND") ->
                "البريد أو كلمة المرور غير صحيحة"
            code.startsWith("USER_DISABLED") -> "هذا الحساب معطَّل"
            code.startsWith("TOO_MANY_ATTEMPTS") -> "محاولات كثيرة؛ انتظر قليلًا ثم أعد المحاولة"
            code.startsWith("TOKEN_EXPIRED") || code.startsWith("INVALID_REFRESH_TOKEN") || code.startsWith("USER_NOT_FOUND") ->
                "انتهت الجلسة؛ سجّل الدخول من جديد"
            code.startsWith("API_KEY") || code.startsWith("INVALID_API_KEY") -> "مفتاح Firebase غير صحيح"
            else -> "تعذّر تسجيل الدخول ($code)"
        }
    }
}

/** يحمل الجلسة ويجدّدها تلقائيًا قبل انتهائها؛ [persist] يحفظ الجلسة المجدَّدة (مشفّرة) على الجهاز. */
class DevSession(private val auth: FirebaseAuthRest, initial: AuthSession, private val persist: (AuthSession) -> Unit = {},
                 private val wall: () -> Long = { System.currentTimeMillis() }) {
    @Volatile var current: AuthSession = initial
        private set

    @Synchronized fun token(): String {
        if (wall() >= current.expiresAtMs) forceRefresh()
        return current.idToken
    }

    @Synchronized fun forceRefresh() {
        val n = auth.refresh(current.refreshToken)
        current = AuthSession(n.idToken, n.refreshToken, n.uid.ifBlank { current.uid }, n.email.ifBlank { current.email }, n.expiresAtMs)
        persist(current)
    }
}
