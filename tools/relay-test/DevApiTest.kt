import com.uniatt.core.*
import com.uniatt.devconsole.*
import java.net.HttpURLConnection
import java.net.URL

var passed = 0; var failed = 0
fun ok(c: Boolean, name: String) { if (c) { passed++; println("  PASS  $name") } else { failed++; println("  FAIL  $name") } }
class MapKV : KeyValue { val m = HashMap<String, String>(); override fun get(key: String) = m[key]; override fun put(key: String, value: String?) { if (value == null) m.remove(key) else m[key] = value } }

/** طلب خام: يعيد (رمز HTTP، النص). */
fun raw(method: String, url: String, body: String? = null): Pair<Int, String> {
    val c = URL(url).openConnection() as HttpURLConnection
    try {
        c.requestMethod = if (method == "PATCH") "POST" else method
        if (method == "PATCH") c.setRequestProperty("X-HTTP-Method-Override", "PATCH")
        if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toByteArray()) } }
        val code = c.responseCode
        return code to ((if (code in 200..299) c.inputStream else c.errorStream)?.use { String(it.readBytes()) } ?: "")
    } finally { c.disconnect() }
}

fun main(args: Array<String>) {
    val url = args[0]
    val auth = FirebaseAuthRest("TESTKEY", url, url)
    var mono = 0L
    val kv = MapKV()
    val appKeeper = LicenseKeeper(kv, { System.currentTimeMillis() }, { mono }, { "b" })     // ما يراه تطبيق الجامعة
    fun appRefresh(): LicenseVerdict {
        val f = LicenseClient.fetch(url)
        if (f is LicenseClient.Fetch.Ok) appKeeper.onFetched(f.raw, f.serverMs)
        return appKeeper.verdict()
    }
    val now = { System.currentTimeMillis() }

    println("[1] تسجيل الدخول (Firebase Authentication)")
    val badPw = runCatching { auth.signIn("dev@example.com", "wrong") }.exceptionOrNull()
    ok(badPw is AuthException && badPw.friendly.contains("غير صحيحة"), "كلمة مرور خاطئة ← رسالة عربية واضحة")
    val badKey = runCatching { FirebaseAuthRest("BAD", url, url).signIn("dev@example.com", "pass1234") }.exceptionOrNull()
    ok(badKey is AuthException, "مفتاح API خاطئ مرفوض")
    ok(runCatching { FirebaseAuthRest("", url, url).signIn("a@b.c", "x") }.exceptionOrNull() is AuthException, "بلا مفتاح API: رسالة إرشادية")
    val s = auth.signIn("dev@example.com", "pass1234")
    ok(s.uid == "UID_DEV" && s.idToken.startsWith("idt-") && s.expiresAtMs > now(), "دخول صحيح يعيد uid ورمزًا")
    val persisted = ArrayList<AuthSession>()
    val dev = DevSession(auth, s, { persisted.add(it) })
    val api = DevApi(url, dev)
    ok(api.isAuthorized("UID_DEV"), "حساب المطوّر ضمن devAdmins")
    val other = DevSession(auth, auth.signIn("other@example.com", "pass1234"))
    val apiOther = DevApi(url, other)
    ok(!apiOther.isAuthorized("UID_OTHER") && !apiOther.isAuthorized("UID_DEV"), "حساب آخر: ليس مطوّرًا ولا يقرأ مدخل غيره")

    println("[2] قبل إنشاء أي ترخيص")
    ok(api.readLicense().doc == null, "لا ترخيص بعد")
    appRefresh()
    ok(appKeeper.verdict().status == LicenseStatus.UNMANAGED && appKeeper.lastContactMs() > 0, "تطبيق الجامعة: غير مُدار ويعمل، وسُجّل اتصال بالخادم")

    println("[3] تفعيل ترخيص وتسجيله")
    val d1 = LicenseOps.setTerm(null, now(), 365, 14, "جامعة الاختبار")
    api.commit(d1, AuditEntry("activate", "365 يومًا، سماح 14", "لا ترخيص", LicenseOps.summary(d1, now()), d1.rev))
    ok(api.readLicense().doc == d1, "المستند المخزَّن يطابق ما كُتب")
    ok(appRefresh().status == LicenseStatus.ACTIVE, "تطبيق الجامعة يرى الترخيص فعّالًا")
    val logs1 = api.listLogs()
    ok(logs1.size == 1 && logs1[0].action == "activate" && logs1[0].email == "dev@example.com" && logs1[0].rev == 1L, "سجل التدقيق: سطر واحد بالبريد والإجراء")
    ok(Math.abs(logs1[0].timeMs - now()) < 10_000, "ختم زمن السجل من الخادم وقريب من الآن")

    println("[4] التعليق والاستعادة (عكس كامل)")
    val d2 = LicenseOps.suspend(d1, now(), "تأخر السداد")
    api.commit(d2, AuditEntry("suspend", "تأخر السداد", LicenseOps.summary(d1, now()), LicenseOps.summary(d2, now()), d2.rev))
    val v2 = appRefresh()
    ok(v2.status == LicenseStatus.SUSPENDED && v2.blocked && v2.message.contains("تأخر السداد"), "بعد التعليق: التطبيق متوقف ويعرض السبب")
    val d3 = LicenseOps.restore(d2, now())
    api.commit(d3, AuditEntry("restore", "", LicenseOps.summary(d2, now()), LicenseOps.summary(d3, now()), d3.rev))
    ok(appRefresh().status == LicenseStatus.ACTIVE, "بعد الاستعادة: يعمل من جديد")

    println("[5] الصيانة والإشعار")
    val d4 = LicenseOps.setMaintenance(d3, now(), true, "ترقية الخادم")
    api.commit(d4, AuditEntry("maintenance_on", "ترقية الخادم", "", "", d4.rev))
    val v4 = appRefresh()
    ok(v4.status == LicenseStatus.MAINTENANCE && v4.message.contains("ترقية الخادم"), "الصيانة تصل برسالتها")
    val d5 = LicenseOps.setNotice(LicenseOps.setMaintenance(d4, now(), false, ""), now(), "اختبارات الأسبوع القادم")
    api.commit(d5, AuditEntry("maintenance_off+notice", "", "", "", d5.rev))
    val v5 = appRefresh()
    ok(v5.status == LicenseStatus.ACTIVE && v5.banner.contains("اختبارات الأسبوع القادم"), "إنهاء الصيانة ونشر إشعار عام")

    println("[6] السجل: ترتيب وكمال")
    val logs = api.listLogs()
    ok(logs.map { it.action }.toSet().containsAll(listOf("activate", "suspend", "restore", "maintenance_on", "maintenance_off+notice")) && logs.size == 5, "كل العمليات الخمس مسجّلة")
    ok(logs.zipWithNext().all { it.first.timeMs >= it.second.timeMs }, "الأحدث أولًا")

    println("[7] الحماية: غير المصرّح لهم")
    val before = api.readLicense().raw; val nLogs = api.listLogs().size
    val dX = LicenseOps.suspend(d5, now(), "محاولة")
    val e1 = runCatching { apiOther.commit(dX, AuditEntry("suspend", "", "", "", dX.rev)) }.exceptionOrNull()
    ok(e1 is DevApiException && e1.code == 401, "حساب ليس مطوّرًا: الكتابة مرفوضة (401)")
    ok(api.readLicense().raw == before && api.listLogs().size == nLogs, "الرفض ذرّي: لا ترخيص تغيّر ولا سجل كُتب")
    ok(raw("PUT", "$url/license/doc.json", "\"UAL1|x\"").first == 401, "كتابة بلا مصادقة مرفوضة")
    ok(raw("GET", "$url/devlog.json").first == 401 && runCatching { apiOther.listLogs() }.exceptionOrNull() is DevApiException, "قراءة السجل بلا صلاحية مرفوضة")
    ok(raw("GET", "$url/license/doc.json").first == 200, "قراءة الترخيص عامة (تحتاجها التطبيقات)")

    println("[8] الذرّية وعدم قابلية التعديل (append-only)")
    val tok = dev.token(); val good = LicenseCodec.encode(LicenseOps.suspend(d5, now(), "ذرّية"))
    val badLog = "{\"license/doc\":${Json.quote(good)},\"devlog/Lbad\":{\"t\":{\".sv\":\"timestamp\"},\"uid\":\"UID_DEV\"}}"      // ينقصه action
    ok(raw("PATCH", "$url/.json?auth=$tok", badLog).first == 400, "سطر سجل ناقص يُفشل الطلب كله")
    ok(api.readLicense().raw == before, "…ولا يُطبَّق تعديل الترخيص (كله أو لا شيء)")
    val forged = "{\"license/doc\":${Json.quote(good)},\"devlog/Lforged\":{\"t\":1,\"uid\":\"UID_DEV\",\"action\":\"x\"}}"      // ختم زمن مزوّر
    ok(raw("PATCH", "$url/.json?auth=$tok", forged).first == 400 && api.readLicense().raw == before, "ختم زمن مزوّر مرفوض")
    val k = api.listLogs().first().key
    ok(raw("PUT", "$url/devlog/$k.json?auth=$tok", "{\"t\":1,\"uid\":\"UID_DEV\",\"action\":\"edited\"}").first == 401, "تعديل سطر سجل قديم مرفوض")
    ok(raw("DELETE", "$url/devlog/$k.json?auth=$tok").first == 401 && api.listLogs().size == nLogs, "حذف سطر سجل مرفوض")
    ok(raw("PUT", "$url/license/doc.json?auth=$tok", "\"hello\"").first == 400, "مستند ترخيص بصيغة غير صالحة مرفوض (validate)")

    println("[9] انتهاء رمز الدخول وتجديده تلقائيًا")
    raw("POST", "$url/__expire", "{}")
    val e = LicenseOps.setNotice(d5, now(), "بعد التجديد")
    api.commit(e, AuditEntry("notice", "", "", "", e.rev))
    ok(persisted.isNotEmpty() && api.readLicense().doc == e, "401 (منتهٍ) ← تجديد الرمز ← إعادة المحاولة بنجاح، وحُفظت الجلسة الجديدة")
    ok(api.listLogs().size == nLogs + 1, "…وسُجّلت العملية مرة واحدة فقط")

    println("[10] حالات الشبكة")
    ok(LicenseClient.fetch("http://127.0.0.1:1") is LicenseClient.Fetch.Offline, "لا اتصال ← Offline (لا قفل)")
    val before2 = appKeeper.verdict().status
    ok(before2 == LicenseStatus.ACTIVE, "آخر حكم معروف يبقى عند انقطاع الشبكة")
    ok(runCatching { auth.refresh("rft-nonexistent") }.exceptionOrNull().let { it is AuthException && it.friendly.contains("سجّل الدخول") }, "رمز تجديد غير صالح ← اطلب دخولًا جديدًا")

    println("\nالنتيجة: نجح $passed، فشل $failed")
    if (failed > 0) System.exit(1)
}
