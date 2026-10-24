package com.uniatt.core

/**
 * ترخيص الجامعة: مستند واحد نصّي يكتبه المطوّر (من Developer Console فقط) في `license/doc` على Firebase،
 * وتقرؤه تطبيقات الإدارة والدكتور والطالب. القراءة عامة، والكتابة محصورة بحساب المطوّر عبر قواعد Firebase.
 *
 * ملاحظة صريحة: الفحص هنا يحدث داخل التطبيقات، فهو يمنع الاستخدام العادي ويوقف المزامنة والحضور بوضوح،
 * لكنه ليس حماية مطلقة ضد من يعدّل ملف APK نفسه. لا يحذف شيئًا ولا يتلف بيانات: التعليق قابل للعكس تمامًا.
 */
data class LicenseDoc(
    /** رقم يزداد مع كل تعديل (للتتبّع والسجل فقط). */
    val rev: Long,
    val org: String,
    /** false = معلّق من المطوّر. */
    val active: Boolean,
    val startMs: Long,
    /** نهاية الترخيص (ميلي ثانية UTC)، 0 = بلا نهاية. */
    val validUntilMs: Long,
    /** أيام السماح بعد النهاية: الخدمات تعمل مع تنبيه، ثم تتوقف. */
    val graceDays: Int,
    val maintenance: Boolean,
    val maintMsg: String,
    /** رسالة تظهر لكل المستخدمين دون إيقاف الخدمة. */
    val notice: String,
    /** سبب التعليق (يظهر للمستخدم). */
    val reason: String,
    /** أقصى مدة بلا اتصال بخادم الترخيص (أيام)، 0 = بلا حد. تُلزم الأجهزة بالتحقق دوريًا فيصلها التعليق. */
    val offlineDays: Int,
    val updatedMs: Long
)

object LicenseCodec {
    const val PREFIX = "UAL1"
    private const val FIELDS = 13

    private fun esc(s: String) = buildString {
        for (ch in s) when (ch) {
            '%' -> append("%25"); '|' -> append("%7C"); '\n' -> append("%0A"); '\r' -> append("%0D")
            else -> append(ch)
        }
    }

    private fun unesc(s: String): String? {
        val sb = StringBuilder(); var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '%') { sb.append(c); i++; continue }
            if (i + 3 > s.length) return null
            when (s.substring(i, i + 3)) {
                "%25" -> sb.append('%'); "%7C", "%7c" -> sb.append('|'); "%0A", "%0a" -> sb.append('\n'); "%0D", "%0d" -> sb.append('\r')
                else -> return null
            }
            i += 3
        }
        return sb.toString()
    }

    fun encode(d: LicenseDoc): String = listOf(
        PREFIX, d.rev, esc(d.org), if (d.active) "A" else "S", d.startMs, d.validUntilMs, d.graceDays,
        if (d.maintenance) 1 else 0, esc(d.maintMsg), esc(d.notice), esc(d.reason), d.offlineDays, d.updatedMs
    ).joinToString("|")

    /** null = نص غير صالح (لا يُعامَل كترخيص). */
    fun decode(text: String?): LicenseDoc? {
        val p = (text ?: return null).trim().split('|')
        if (p.size != FIELDS || p[0] != PREFIX) return null
        return try {
            val active = when (p[3]) { "A" -> true; "S" -> false; else -> return null }
            val maint = when (p[7]) { "1" -> true; "0" -> false; else -> return null }
            val graceDays = p[6].toInt(); val offlineDays = p[11].toInt()
            if (graceDays < 0 || offlineDays < 0) return null
            LicenseDoc(
                rev = p[1].toLong(), org = unesc(p[2]) ?: return null, active = active,
                startMs = p[4].toLong(), validUntilMs = p[5].toLong(), graceDays = graceDays,
                maintenance = maint, maintMsg = unesc(p[8]) ?: return null, notice = unesc(p[9]) ?: return null,
                reason = unesc(p[10]) ?: return null, offlineDays = offlineDays, updatedMs = p[12].toLong()
            )
        } catch (e: NumberFormatException) { null }
    }
}

enum class LicenseStatus { UNMANAGED, ACTIVE, GRACE, EXPIRED, SUSPENDED, MAINTENANCE, OFFLINE_LIMIT, TAMPERED }

/**
 * [blocked] = الخدمات متوقفة (يبقى التصدير والاستعادة متاحَين). [banner] = تنبيه لا يوقف الخدمة.
 * [daysLeft] = الأيام المتبقية على نهاية الترخيص (أو على نهاية السماح عند [LicenseStatus.GRACE]).
 */
class LicenseVerdict(
    val status: LicenseStatus, val blocked: Boolean, val title: String, val message: String,
    val banner: String = "", val daysLeft: Long? = null, val org: String = ""
) {
    companion object {
        val UNMANAGED = LicenseVerdict(LicenseStatus.UNMANAGED, false, "", "")
    }
}

object LicenseGate {
    const val DAY_MS = 86_400_000L
    const val WARN_DAYS = 14L
    private const val SAFE = "بياناتك وسجلات الحضور محفوظة كما هي ولم يُحذف منها شيء، ويمكنك تصديرها الآن. ستعمل الخدمات من جديد فور استعادة الترخيص."

    private fun daysCeil(ms: Long) = if (ms <= 0) 0L else (ms + DAY_MS - 1) / DAY_MS

    /** [lastContactMs] = وقت آخر اتصال ناجح بخادم الترخيص (لحدّ [LicenseDoc.offlineDays]). */
    fun evaluate(doc: LicenseDoc?, nowMs: Long, lastContactMs: Long = nowMs): LicenseVerdict {
        doc ?: return LicenseVerdict.UNMANAGED
        val org = doc.org

        if (!doc.active) {
            val why = doc.reason.trim().let { if (it.isEmpty()) "" else "\nالسبب: $it" }
            return LicenseVerdict(LicenseStatus.SUSPENDED, true, "الترخيص معلّق",
                "أُوقفت خدمات النظام مؤقتًا من مزوّد الخدمة.$why\n\n$SAFE", org = org)
        }

        val until = doc.validUntilMs
        val graceEnd = if (until > 0) until + doc.graceDays * DAY_MS else 0L
        if (until > 0 && nowMs > graceEnd) {
            return LicenseVerdict(LicenseStatus.EXPIRED, true, "انتهى الترخيص",
                "انتهت مدة الترخيص وفترة السماح. تواصل مع مزوّد الخدمة لتجديده.\n\n$SAFE", org = org)
        }

        if (doc.maintenance) {
            val m = doc.maintMsg.trim().ifEmpty { "النظام تحت الصيانة حاليًا. سيعود للعمل قريبًا." }
            return LicenseVerdict(LicenseStatus.MAINTENANCE, true, "النظام تحت الصيانة", "$m\n\n$SAFE", org = org)
        }

        if (doc.offlineDays > 0 && nowMs - lastContactMs > doc.offlineDays * DAY_MS) {
            return LicenseVerdict(LicenseStatus.OFFLINE_LIMIT, true, "يلزم التحقق من الترخيص",
                "لم يتصل هذا الهاتف بالإنترنت للتحقق من الترخيص منذ أكثر من ${doc.offlineDays} يومًا. اتصل بالإنترنت ثم أعد المحاولة.\n\n$SAFE", org = org)
        }

        val notice = doc.notice.trim()
        if (until > 0 && nowMs > until) {
            val left = daysCeil(graceEnd - nowMs)
            val b = "انتهى الترخيص وأنت في فترة السماح: تتوقف الخدمات بعد $left يومًا. جدّد الترخيص."
            return LicenseVerdict(LicenseStatus.GRACE, false, "فترة السماح", b, banner = joinNotice(b, notice), daysLeft = left, org = org)
        }

        val left = if (until > 0) daysCeil(until - nowMs) else null
        val warn = if (left != null && left <= WARN_DAYS) "ينتهي الترخيص خلال $left يومًا." else ""
        return LicenseVerdict(LicenseStatus.ACTIVE, false, "", "", banner = joinNotice(warn, notice), daysLeft = left, org = org)
    }

    private fun joinNotice(a: String, b: String) = listOf(a, b).filter { it.isNotBlank() }.joinToString("\n")
}

/** تعديلات المطوّر على المستند (دوال نقية؛ كل تعديل يرفع [LicenseDoc.rev]). الأخطاء تُرمى كـ [IllegalArgumentException] برسالة عربية. */
object LicenseOps {
    const val DAY_MS = LicenseGate.DAY_MS
    const val MAX_TEXT = 300

    fun initial(org: String, nowMs: Long): LicenseDoc = LicenseDoc(
        rev = 0, org = org.trim(), active = true, startMs = nowMs, validUntilMs = 0, graceDays = 14,
        maintenance = false, maintMsg = "", notice = "", reason = "", offlineDays = 0, updatedMs = nowMs
    )

    private fun bump(prev: LicenseDoc?, nowMs: Long, org: String = ""): LicenseDoc =
        (prev ?: initial(org, nowMs)).let { it.copy(rev = it.rev + 1, updatedMs = nowMs) }

    private fun text(s: String, what: String, required: Boolean = false): String {
        val t = s.trim()
        require(!(required && t.isEmpty())) { "اكتب $what" }
        require(t.length <= MAX_TEXT) { "$what أطول من $MAX_TEXT حرفًا" }
        return t
    }

    /** يحدّد مدة الترخيص من الآن: [days] ≥ 1، و0 = بلا نهاية. يفعّل الترخيص أيضًا ويرفع أي تعليق. */
    fun setTerm(prev: LicenseDoc?, nowMs: Long, days: Int, graceDays: Int, org: String = prev?.org ?: ""): LicenseDoc {
        require(days in 0..3650) { "مدة الترخيص بين 1 و3650 يومًا (أو 0 = بلا نهاية)" }
        require(graceDays in 0..365) { "فترة السماح بين 0 و365 يومًا" }
        val o = text(org, "اسم الجامعة", required = true)
        return bump(prev, nowMs, o).copy(
            org = o, active = true, reason = "", startMs = nowMs,
            validUntilMs = if (days == 0) 0L else nowMs + days * DAY_MS, graceDays = graceDays
        )
    }

    /** يمدّد من نهاية الترخيص الحالية (أو من الآن إن انتهى/بلا نهاية). */
    fun extend(prev: LicenseDoc, nowMs: Long, days: Int): LicenseDoc {
        require(days in 1..3650) { "التمديد بين 1 و3650 يومًا" }
        val base = if (prev.validUntilMs > nowMs) prev.validUntilMs else nowMs
        return bump(prev, nowMs).copy(validUntilMs = base + days * DAY_MS)
    }

    fun setGrace(prev: LicenseDoc, nowMs: Long, graceDays: Int): LicenseDoc {
        require(graceDays in 0..365) { "فترة السماح بين 0 و365 يومًا" }
        return bump(prev, nowMs).copy(graceDays = graceDays)
    }

    fun setOfflineLimit(prev: LicenseDoc, nowMs: Long, days: Int): LicenseDoc {
        require(days in 0..365) { "الحد بين 0 (بلا حد) و365 يومًا" }
        return bump(prev, nowMs).copy(offlineDays = days)
    }

    fun suspend(prev: LicenseDoc, nowMs: Long, reason: String): LicenseDoc =
        bump(prev, nowMs).copy(active = false, reason = text(reason, "سبب التعليق", required = true))

    /** يرفع التعليق دون المساس بالمدة. إن كانت المدة منتهية فالحكم يبقى «منتهٍ» حتى يمدّدها المطوّر. */
    fun restore(prev: LicenseDoc, nowMs: Long): LicenseDoc = bump(prev, nowMs).copy(active = true, reason = "")

    fun setMaintenance(prev: LicenseDoc, nowMs: Long, on: Boolean, message: String): LicenseDoc =
        bump(prev, nowMs).copy(maintenance = on, maintMsg = if (on) text(message, "رسالة الصيانة") else "")

    fun setNotice(prev: LicenseDoc, nowMs: Long, notice: String): LicenseDoc =
        bump(prev, nowMs).copy(notice = text(notice, "الرسالة"))

    fun summary(d: LicenseDoc?, nowMs: Long): String {
        d ?: return "لا ترخيص"
        val v = LicenseGate.evaluate(d, nowMs)
        val until = if (d.validUntilMs == 0L) "بلا نهاية" else "حتى ${d.validUntilMs}"
        return "r${d.rev} ${if (d.active) "فعّال" else "معلّق"} • $until • سماح ${d.graceDays}ي • صيانة ${if (d.maintenance) "نعم" else "لا"} • حكم ${v.status}"
    }
}

/** مخزن مفتاح/قيمة تُنفّذه التطبيقات بـ SharedPreferences (وتنفّذه الاختبارات بخريطة). */
interface KeyValue {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

/**
 * حالة الترخيص على الجهاز: يحفظ آخر مستند وصل، ويحكم عليه بساعة موثوقة (وقت الخادم من ترويسة Date + عدّاد رتيب)
 * فلا تفيد تغيير ساعة الهاتف. عند انقطاع الاتصال يبقى آخر حكم معروف (لا يُغلق أحد بسبب انقطاع الشبكة).
 * إن لم تتوفر ساعة موثوقة (بعد إعادة تشغيل بلا اتصال) نأخذ الأكبر من ساعة الهاتف وآخر وقت خادم رآه الجهاز.
 */
class LicenseKeeper(
    private val kv: KeyValue,
    private val wall: () -> Long = { System.currentTimeMillis() },
    mono: () -> Long,
    bootId: () -> String = { "" }
) {
    private val clock = TrustedClock(wall, mono, bootId).also { it.restore(kv.get(K_CLOCK)) }

    private fun long(k: String) = kv.get(k)?.toLongOrNull() ?: 0L

    /** المستند المحفوظ، أو null إن لم يكن هناك ترخيص مُدار. */
    fun doc(): LicenseDoc? = LicenseCodec.decode(kv.get(K_DOC))

    /** وقت آخر اتصال ناجح بخادم الترخيص (0 = لم يحدث). */
    fun lastContactMs(): Long = long(K_CONTACT)

    fun nowMs(): Long {
        val r = clock.read()
        return if (r.trusted) r.ms else maxOf(wall(), long(K_SERVER))
    }

    /**
     * نتيجة جلب ناجحة: [raw] نص المستند (null = لا يوجد ترخيص مُدار على الخادم)، و[serverMs] وقت الخادم.
     * نص موجود لكنه غير صالح لا يستبدل المحفوظ (لا نقفل الجميع بسبب نص تالف).
     */
    fun onFetched(raw: String?, serverMs: Long) {
        if (serverMs > 0) {
            clock.anchorNow(serverMs)
            kv.put(K_CLOCK, clock.encode())
            kv.put(K_SERVER, maxOf(serverMs, long(K_SERVER)).toString())
            kv.put(K_CONTACT, serverMs.toString())
        }
        when {
            raw == null -> kv.put(K_DOC, null)
            LicenseCodec.decode(raw) != null -> kv.put(K_DOC, raw.trim())
        }
    }

    fun verdict(): LicenseVerdict {
        val now = nowMs()
        return LicenseGate.evaluate(doc(), now, lastContactMs().let { if (it == 0L) now else it })
    }

    private companion object {
        const val K_DOC = "lic_doc"; const val K_CLOCK = "lic_clk"; const val K_SERVER = "lic_srv"; const val K_CONTACT = "lic_contact"
    }
}

