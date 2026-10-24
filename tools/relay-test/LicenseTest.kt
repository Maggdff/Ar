import com.uniatt.core.*

var passed = 0; var failed = 0
fun ok(c: Boolean, name: String) { if (c) { passed++; println("  PASS  $name") } else { failed++; println("  FAIL  $name") } }
fun throwsIae(f: () -> Unit): Boolean = try { f(); false } catch (e: IllegalArgumentException) { true }

const val DAY = LicenseGate.DAY_MS
class MapKV : KeyValue { val m = HashMap<String, String>(); override fun get(key: String) = m[key]; override fun put(key: String, value: String?) { if (value == null) m.remove(key) else m[key] = value } }

fun doc(until: Long = 0, grace: Int = 14, active: Boolean = true, maint: Boolean = false, offline: Int = 0, notice: String = "", reason: String = "", mmsg: String = "") =
    LicenseDoc(1, "جامعة الاختبار", active, 0, until, grace, maint, mmsg, notice, reason, offline, 0)

fun main() {
    val T0 = 1_800_000_000_000L

    println("[1] ترميز المستند: ذهاب وإياب وحماية من النص التالف")
    val tricky = LicenseDoc(7, "جامعة | الاختبار 100%", true, 5, 9_999, 30, true, "صيانة\nسطر ثانٍ | مع %7C", "تنبيه%", "سبب\r\nطويل", 3, 12345)
    val enc = LicenseCodec.encode(tricky)
    ok(!enc.contains('\n') && !enc.contains('\r') && enc.startsWith("UAL1|"), "السطر المرمَّز بلا فواصل أسطر")
    ok(LicenseCodec.decode(enc) == tricky, "ذهاب وإياب مطابق مع | و % وأسطر جديدة")
    ok(LicenseCodec.decode(LicenseCodec.encode(doc())) == doc(), "ذهاب وإياب لمستند عادي")
    ok(LicenseCodec.decode(null) == null && LicenseCodec.decode("") == null, "null/فارغ ← لا مستند")
    ok(LicenseCodec.decode("XXXX|" + enc.substringAfter('|')) == null, "ترويسة خاطئة مرفوضة")
    ok(LicenseCodec.decode(enc + "|x") == null && LicenseCodec.decode(enc.substringBeforeLast('|')) == null, "عدد حقول خاطئ مرفوض")
    ok(LicenseCodec.decode(enc.replace("|A|", "|Z|").replace("|S|", "|Z|")) == null, "حالة غير معروفة مرفوضة")
    ok(LicenseCodec.decode(LicenseCodec.encode(doc()).replace("|14|", "|abc|")) == null, "رقم تالف مرفوض")
    ok(LicenseCodec.decode(LicenseCodec.encode(doc()).replace("|14|", "|-1|")) == null, "فترة سماح سالبة مرفوضة")
    ok(LicenseCodec.decode("UAL1|1|a%ZZb|A|0|0|14|0|||0|0|0") == null && LicenseCodec.decode("UAL1|1|a%2|A|0|0|14|0|||0|0|0") == null, "هروب % تالف أو مقطوع مرفوض")

    println("[2] محرّك الحكم")
    ok(LicenseGate.evaluate(null, T0).status == LicenseStatus.UNMANAGED && !LicenseGate.evaluate(null, T0).blocked, "بلا ترخيص ← غير مُدار والخدمة تعمل")
    val a = LicenseGate.evaluate(doc(until = 0), T0)
    ok(a.status == LicenseStatus.ACTIVE && !a.blocked && a.banner == "" && a.daysLeft == null, "فعّال بلا نهاية: لا تنبيه")
    val a2 = LicenseGate.evaluate(doc(until = T0 + 100 * DAY), T0)
    ok(a2.status == LicenseStatus.ACTIVE && a2.banner == "" && a2.daysLeft == 100L, "فعّال بعيد النهاية: 100 يوم ولا تنبيه")
    val a3 = LicenseGate.evaluate(doc(until = T0 + 5 * DAY - 1), T0)
    ok(a3.status == LicenseStatus.ACTIVE && a3.daysLeft == 5L && a3.banner.contains("5"), "قبل النهاية بـ5 أيام: تنبيه ولا إيقاف")
    ok(LicenseGate.evaluate(doc(until = T0 + 14 * DAY), T0).banner.isNotEmpty() && LicenseGate.evaluate(doc(until = T0 + 15 * DAY), T0).banner.isEmpty(), "حدّ التنبيه 14 يومًا")
    val g = LicenseGate.evaluate(doc(until = T0 - DAY, grace = 14), T0)
    ok(g.status == LicenseStatus.GRACE && !g.blocked && g.daysLeft == 13L && g.banner.isNotEmpty(), "فترة السماح: تعمل مع تنبيه (13 يومًا متبقية)")
    ok(LicenseGate.evaluate(doc(until = T0 - 14 * DAY, grace = 14), T0).status == LicenseStatus.GRACE, "آخر لحظة من السماح ما زالت سماحًا")
    val e = LicenseGate.evaluate(doc(until = T0 - 14 * DAY - 1, grace = 14), T0)
    ok(e.status == LicenseStatus.EXPIRED && e.blocked && e.message.contains("محفوظة"), "بعد السماح: منتهٍ ومتوقف مع تطمين أن البيانات محفوظة")
    ok(LicenseGate.evaluate(doc(until = T0 - 1, grace = 0), T0).status == LicenseStatus.EXPIRED, "سماح 0: يتوقف فور النهاية")
    val s = LicenseGate.evaluate(doc(active = false, reason = "تأخر السداد"), T0)
    ok(s.status == LicenseStatus.SUSPENDED && s.blocked && s.message.contains("تأخر السداد") && s.message.contains("تصديرها"), "تعليق: يظهر السبب ويُذكر التصدير")
    ok(LicenseGate.evaluate(doc(active = false), T0).blocked, "تعليق بلا سبب ما زال يوقف")
    val m = LicenseGate.evaluate(doc(maint = true, mmsg = "ترقية الخادم حتى الساعة 5"), T0)
    ok(m.status == LicenseStatus.MAINTENANCE && m.blocked && m.message.contains("ترقية الخادم"), "الصيانة: توقف مع رسالة المطوّر")
    ok(LicenseGate.evaluate(doc(maint = true), T0).message.contains("تحت الصيانة"), "الصيانة بلا رسالة: رسالة افتراضية")
    ok(LicenseGate.evaluate(doc(active = false, maint = true), T0).status == LicenseStatus.SUSPENDED, "الأسبقية: التعليق قبل الصيانة")
    ok(LicenseGate.evaluate(doc(until = T0 - 99 * DAY, grace = 1, maint = true), T0).status == LicenseStatus.EXPIRED, "الأسبقية: المنتهي قبل الصيانة")
    ok(LicenseGate.evaluate(doc(until = T0 - DAY, maint = true), T0).status == LicenseStatus.MAINTENANCE, "الأسبقية: الصيانة قبل السماح")
    ok(LicenseGate.evaluate(doc(notice = "عطلة الأسبوع القادم"), T0).banner == "عطلة الأسبوع القادم" && !LicenseGate.evaluate(doc(notice = "x"), T0).blocked, "الإشعار العام: يظهر ولا يوقف")
    ok(LicenseGate.evaluate(doc(until = T0 + 3 * DAY, notice = "ن"), T0).banner.lines().size == 2, "تنبيه الانتهاء + الإشعار معًا")
    ok(LicenseGate.evaluate(doc(offline = 7), T0, lastContactMs = T0 - 8 * DAY).status == LicenseStatus.OFFLINE_LIMIT, "حدّ الاتصال: تجاوز 7 أيام ← يلزم التحقق")
    ok(LicenseGate.evaluate(doc(offline = 7), T0, lastContactMs = T0 - 6 * DAY).status == LicenseStatus.ACTIVE, "حدّ الاتصال: ضمن المدة")
    ok(LicenseGate.evaluate(doc(offline = 0), T0, lastContactMs = T0 - 900 * DAY).status == LicenseStatus.ACTIVE, "حدّ الاتصال 0 = بلا حد")

    println("[3] عمليات المطوّر")
    val d1 = LicenseOps.setTerm(null, T0, 365, 30, "جامعة س")
    ok(d1.active && d1.rev == 1L && d1.validUntilMs == T0 + 365 * DAY && d1.graceDays == 30 && d1.org == "جامعة س", "ترخيص جديد: سنة وسماح 30")
    ok(LicenseOps.setTerm(d1, T0, 0, 14).validUntilMs == 0L, "0 يوم = بلا نهاية")
    ok(throwsIae { LicenseOps.setTerm(null, T0, -1, 14, "س") } && throwsIae { LicenseOps.setTerm(null, T0, 3651, 14, "س") }, "مدة خارج النطاق مرفوضة")
    ok(throwsIae { LicenseOps.setTerm(null, T0, 30, 366, "س") } && throwsIae { LicenseOps.setTerm(null, T0, 30, -1, "س") }, "سماح خارج النطاق مرفوض")
    ok(throwsIae { LicenseOps.setTerm(null, T0, 30, 14, "  ") }, "اسم الجامعة مطلوب")
    val d2 = LicenseOps.extend(d1, T0 + 10 * DAY, 30)
    ok(d2.validUntilMs == T0 + 395 * DAY && d2.rev == 2L, "التمديد من نهاية الترخيص الحالية")
    val expired = LicenseOps.setTerm(null, T0, 10, 0, "س")
    ok(LicenseOps.extend(expired, T0 + 50 * DAY, 30).validUntilMs == T0 + 80 * DAY, "التمديد بعد الانتهاء من اليوم")
    ok(throwsIae { LicenseOps.extend(d1, T0, 0) }, "تمديد 0 مرفوض")
    ok(throwsIae { LicenseOps.suspend(d1, T0, "   ") }, "التعليق يتطلب سببًا")
    val sus = LicenseOps.suspend(d1, T0, "سبب")
    ok(!sus.active && sus.reason == "سبب" && sus.validUntilMs == d1.validUntilMs, "التعليق لا يمس المدة")
    val res = LicenseOps.restore(sus, T0 + DAY)
    ok(res.active && res.reason == "" && res.validUntilMs == d1.validUntilMs && res.rev == sus.rev + 1, "الاستعادة تعيد التفعيل وتبقي المدة")
    ok(LicenseGate.evaluate(LicenseOps.restore(LicenseOps.suspend(expired, T0, "س"), T0 + 99 * DAY), T0 + 99 * DAY).status == LicenseStatus.EXPIRED, "استعادة ترخيص منتهٍ: يبقى منتهيًا حتى التمديد")
    ok(LicenseOps.setMaintenance(d1, T0, true, "رسالة").maintenance && !LicenseOps.setMaintenance(LicenseOps.setMaintenance(d1, T0, true, "ر"), T0, false, "تجاهل").maintenance, "تشغيل/إيقاف الصيانة")
    ok(LicenseOps.setMaintenance(LicenseOps.setMaintenance(d1, T0, true, "ر"), T0, false, "تجاهل").maintMsg == "", "إيقاف الصيانة يمسح رسالتها")
    ok(throwsIae { LicenseOps.setNotice(d1, T0, "ا".repeat(301)) }, "رسالة أطول من 300 حرف مرفوضة")
    ok(LicenseOps.setNotice(d1, T0, "  مرحبا  ").notice == "مرحبا", "الإشعار يُقصّ")
    ok(LicenseOps.setGrace(d1, T0, 0).graceDays == 0 && throwsIae { LicenseOps.setGrace(d1, T0, 400) }, "ضبط السماح")
    ok(LicenseOps.setOfflineLimit(d1, T0, 7).offlineDays == 7 && throwsIae { LicenseOps.setOfflineLimit(d1, T0, -1) }, "ضبط حدّ الاتصال")
    ok(LicenseCodec.decode(LicenseCodec.encode(res)) == res, "ناتج العمليات يمرّ بالترميز سليمًا")

    println("[4] حالة الجهاز (ساعة موثوقة + حفظ)")
    var wall = T0; var mono = 1_000L; var boot = "b1"
    val kv = MapKV()
    fun keeper() = LicenseKeeper(kv, { wall }, { mono }, { boot })
    var k = keeper()
    ok(k.verdict().status == LicenseStatus.UNMANAGED, "جهاز جديد بلا اتصال: غير مُدار (لا قفل)")
    val live = LicenseCodec.encode(doc(until = T0 + 30 * DAY))
    k.onFetched(live, T0)
    ok(k.verdict().status == LicenseStatus.ACTIVE && k.doc() != null, "بعد الجلب: فعّال")
    wall = T0 + 400 * DAY                               // المستخدم يقدّم ساعة الهاتف سنة وهو لم يُعد التشغيل
    ok(k.verdict().status == LicenseStatus.ACTIVE && k.verdict().daysLeft == 30L, "تقديم ساعة الهاتف لا ينهي الترخيص (ساعة موثوقة)")
    wall = T0 - 400 * DAY
    ok(k.verdict().daysLeft == 30L, "تأخير ساعة الهاتف لا يمدّده")
    mono += 31 * DAY                                    // يمضي الوقت الحقيقي بلا اتصال
    ok(k.verdict().status == LicenseStatus.GRACE, "مضي الوقت الحقيقي بلا اتصال: ينتقل لفترة السماح")
    mono += 20 * DAY
    ok(k.verdict().status == LicenseStatus.EXPIRED && k.verdict().blocked, "ثم يتوقف بعد السماح")
    k.onFetched(LicenseCodec.encode(doc(until = T0 + 365 * DAY)), T0 + 51 * DAY)
    ok(k.verdict().status == LicenseStatus.ACTIVE && !k.verdict().blocked, "التجديد على الخادم يفتح الخدمات عند أول اتصال")
    val saved = k.doc()
    k.onFetched("UAL1|garbage", T0 + 52 * DAY)
    ok(k.doc() == saved && k.verdict().status == LicenseStatus.ACTIVE, "نص تالف على الخادم لا يستبدل المحفوظ ولا يقفل أحدًا")
    k.onFetched(LicenseCodec.encode(doc(active = false, reason = "ر")), T0 + 53 * DAY)
    ok(k.verdict().status == LicenseStatus.SUSPENDED, "التعليق يصل عند أول اتصال")
    k.onFetched(LicenseCodec.encode(doc(until = T0 + 365 * DAY)), T0 + 54 * DAY)
    ok(k.verdict().status == LicenseStatus.ACTIVE, "الاستعادة تعيد الخدمة (عكس كامل)")
    k.onFetched(null, T0 + 55 * DAY)
    ok(k.doc() == null && k.verdict().status == LicenseStatus.UNMANAGED, "حذف الترخيص من الخادم ← غير مُدار")
    // إعادة تشغيل الهاتف: الحفظ يبقى والمرساة تُهمَل
    k.onFetched(LicenseCodec.encode(doc(until = T0 + 100 * DAY)), T0 + 60 * DAY)
    boot = "b2"; mono = 5L; wall = T0 + 10 * DAY        // أعاد التشغيل وغيّر الساعة للخلف
    val k2 = keeper()
    ok(k2.doc() != null && k2.nowMs() >= T0 + 60 * DAY, "بعد إعادة التشغيل: لا رجوع لما قبل آخر وقت خادم رآه الجهاز")
    ok(k2.verdict().status == LicenseStatus.ACTIVE, "بعد إعادة التشغيل بلا اتصال: آخر حكم معروف")
    // حدّ الاتصال
    val kv3 = MapKV(); var mono3 = 0L
    val k3 = LicenseKeeper(kv3, { T0 }, { mono3 }, { "x" })
    k3.onFetched(LicenseCodec.encode(doc(offline = 5)), T0)
    mono3 += 4 * DAY; ok(k3.verdict().status == LicenseStatus.ACTIVE, "حدّ الاتصال: اليوم 4 مسموح")
    mono3 += 2 * DAY; ok(k3.verdict().status == LicenseStatus.OFFLINE_LIMIT, "حدّ الاتصال: اليوم 6 يتطلب تحققًا")
    k3.onFetched(LicenseCodec.encode(doc(offline = 5)), T0 + 6 * DAY)
    ok(k3.verdict().status == LicenseStatus.ACTIVE, "الاتصال يعيد العمل")
    val k4 = LicenseKeeper(MapKV(), { T0 }, { 0 }, { "x" })
    k4.onFetched(live, 0L)                              // خادم بلا ترويسة وقت: لا نقبل مرساة
    ok(k4.doc() != null && k4.lastContactMs() == 0L, "جلب بلا وقت خادم: المستند يُحفظ دون مرساة")

    println("[5] ترميز الجداول (النسخ الاحتياطية)")
    val meta = mapOf("app" to "doctor", "created" to "123", "note" to "سطر\tبتبويب\nوسطر")
    val tables = mapOf("session" to listOf(listOf("s1", "1", "ملاحظة\\مائلة"), listOf("s2", "", "")), "empty" to emptyList())
    val dec = TableCodec.decode(TableCodec.encode(meta, tables))
    ok(dec != null && dec.meta == meta, "الترويسة تذهب وتعود بما فيها التبويب والسطر الجديد")
    ok(dec != null && dec.table("session") == tables["session"], "الصفوف تعود كما هي (حقول فارغة وشرطة مائلة)")
    ok(dec != null && dec.table("none").isEmpty(), "جدول غير موجود = فارغ")
    ok(TableCodec.decode("garbage") == null && TableCodec.decode("UATB1\nX\ta") == null && TableCodec.decode("UATB1\nR\ta\\q") == null, "ملف تالف مرفوض")
    val blob = Box.seal(Box.MAGIC_DOCTOR_BACKUP, "d", TableCodec.encode(meta, tables).toByteArray(), "ABCD-EFGH-IJKL")
    ok(Box.open(Box.MAGIC_DOCTOR_BACKUP, blob, "ABCD-EFGH-IJKL")?.let { TableCodec.decode(String(it)) }?.meta == meta, "تشفير النسخة وفتحها بالكود")
    ok(Box.open(Box.MAGIC_DOCTOR_BACKUP, blob, "ABCD-EFGH-IJKM") == null && Box.open(Box.MAGIC_STUDENT_BACKUP, blob, "ABCD-EFGH-IJKL") == null, "كود خاطئ أو نوع نسخة خاطئ لا يُفتح")

    println("\nالنتيجة: نجح $passed، فشل $failed")
    if (failed > 0) System.exit(1)
}
