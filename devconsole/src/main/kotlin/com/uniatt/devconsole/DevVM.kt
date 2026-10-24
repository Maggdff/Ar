package com.uniatt.devconsole

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.uniatt.core.LicenseDoc
import com.uniatt.core.LicenseGate
import com.uniatt.core.LicenseOps
import com.uniatt.core.RelayDefaults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** حالة تطبيق المطوّر: الدخول، قراءة الترخيص، تنفيذ العمليات (كل عملية تُكتب مع سجل تدقيق ذرّيًا). */
class DevVM(app: Application) : AndroidViewModel(app) {
    private val sp = app.getSharedPreferences("devconsole", 0)

    val apiKey = mutableStateOf(sp.getString("apiKey", "") ?: "")
    val dbUrl = mutableStateOf(sp.getString("dbUrl", RelayDefaults.URL) ?: RelayDefaults.URL)
    val email = mutableStateOf(sp.getString("email", "") ?: "")
    val password = mutableStateOf("")

    val signedIn = mutableStateOf(false)
    val busy = mutableStateOf(false)
    val message = mutableStateOf("")
    val doc = mutableStateOf<LicenseDoc?>(null)
    val serverMs = mutableStateOf(0L)
    val logs = mutableStateOf<List<LogRow>>(emptyList())

    private var api: DevApi? = null

    init {
        val rt = sp.getString("refresh", null)
        if (rt != null && apiKey.value.isNotBlank()) restoreSession(rt)
    }

    private fun saveSettings() {
        sp.edit().putString("apiKey", apiKey.value.trim()).putString("dbUrl", dbUrl.value.trim()).putString("email", email.value.trim()).apply()
    }

    private fun open(s: AuthSession) {
        val auth = FirebaseAuthRest(apiKey.value.trim())
        val session = DevSession(auth, s, persist = { sp.edit().putString("refresh", it.refreshToken).apply() })
        api = DevApi(dbUrl.value.trim(), session)
        sp.edit().putString("refresh", s.refreshToken).apply()
    }

    private fun restoreSession(refresh: String) = viewModelScope.launch {
        busy.value = true
        try {
            val s = withContext(Dispatchers.IO) { FirebaseAuthRest(apiKey.value.trim()).refresh(refresh) }
            open(s); signedIn.value = true; reload()
        } catch (e: AuthException) { message.value = e.friendly } finally { busy.value = false }
    }

    fun signIn() = viewModelScope.launch {
        busy.value = true; message.value = ""
        try {
            saveSettings()
            val s = withContext(Dispatchers.IO) { FirebaseAuthRest(apiKey.value.trim()).signIn(email.value, password.value) }
            val ok = withContext(Dispatchers.IO) { DevApi(dbUrl.value.trim(), DevSession(FirebaseAuthRest(apiKey.value.trim()), s)).isAuthorized(s.uid) }
            if (!ok) { message.value = "هذا الحساب ليس ضمن قائمة المطوّرين المعتمدين (devAdmins)"; return@launch }
            open(s); password.value = ""; signedIn.value = true; reload()
        } catch (e: AuthException) { message.value = e.friendly }
        catch (e: DevApiException) { message.value = e.friendly }
        finally { busy.value = false }
    }

    fun signOut() { sp.edit().remove("refresh").apply(); api = null; signedIn.value = false; doc.value = null; logs.value = emptyList() }

    fun reload() = viewModelScope.launch {
        val a = api ?: return@launch
        busy.value = true
        try {
            val snap = withContext(Dispatchers.IO) { a.readLicense() }
            doc.value = snap.doc; serverMs.value = snap.serverMs
            logs.value = withContext(Dispatchers.IO) { a.listLogs() }
        } catch (e: DevApiException) { message.value = e.friendly }
        catch (e: AuthException) { message.value = e.friendly; signOut() }
        catch (e: Exception) { message.value = "تعذّر الاتصال: ${e.message}" }
        finally { busy.value = false }
    }

    fun now(): Long = if (serverMs.value > 0) serverMs.value else System.currentTimeMillis()

    /** ينفّذ عملية على المستند الحالي. [build] ترمي IllegalArgumentException برسالة عربية عند خطأ الإدخال. */
    fun apply(action: String, detail: String, build: (LicenseDoc?, Long) -> LicenseDoc) = viewModelScope.launch {
        val a = api ?: return@launch
        busy.value = true; message.value = ""
        try {
            val snap = withContext(Dispatchers.IO) { a.readLicense() }          // نقرأ الأحدث قبل التعديل لتفادي الكتابة فوق تعديل آخر
            val t = if (snap.serverMs > 0) snap.serverMs else System.currentTimeMillis()
            val n = build(snap.doc, t)
            withContext(Dispatchers.IO) {
                a.commit(n, AuditEntry(action, detail, snap.raw.orEmpty(), com.uniatt.core.LicenseCodec.encode(n), n.rev))
            }
            message.value = "تم: $action"
            reload()
        } catch (e: IllegalArgumentException) { message.value = e.message ?: "إدخال غير صالح" }
        catch (e: DevApiException) { message.value = e.friendly }
        catch (e: AuthException) { message.value = e.friendly }
        catch (e: Exception) { message.value = "تعذّر التنفيذ: ${e.message}" }
        finally { busy.value = false }
    }

    fun verdictText(): String = LicenseOps.summary(doc.value, now())
    fun verdict() = LicenseGate.evaluate(doc.value, now())
}
