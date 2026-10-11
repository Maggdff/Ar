package com.uniatt.student

import android.content.Context
import com.uniatt.core.Hex
import com.uniatt.core.StudentProfile
import com.uniatt.core.StudentPublic
import com.uniatt.core.TEntry
import com.uniatt.core.Timetable
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class HistoryItem(val time: Long, val sessionId: String, val status: Int)

/** مفتاح الطالب المشتق من كوده (الكود نفسه لا يُحفظ). */
class Credentials(val tag: String, val key: ByteArray)

/** آخر اتصال وصل من هاتف دكتور (للتشخيص): الوقت والخطوة. */
data class Contact(val time: Long, val step: String)

object ResultBus {
    val contact = MutableStateFlow<Contact?>(null)
    val ble = MutableStateFlow("")             // حالة التسجيل التلقائي بالبلوتوث
    val sync = MutableStateFlow("")            // حالة تحديث الجدول من الإنترنت
    val last = MutableStateFlow<HistoryItem?>(null)
    val profileVersion = MutableStateFlow(0)      // يزداد عند وصول بيانات الطالب من هاتف دكتور
}

class StudentStore(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("student", Context.MODE_PRIVATE)

    /** هل التسجيل التلقائي بالبلوتوث مفعّل (يعمل في الخلفية بإشعار دائم). */
    var autoBle: Boolean
        get() = sp.getBoolean("auto_ble", false)
        set(v) { sp.edit().putBoolean("auto_ble", v).apply() }

    // ---- الكود (يُدخل مرة واحدة) ----
    fun credentials(): Credentials? {
        val tag = sp.getString("tag", null) ?: return null
        val key = sp.getString("key", null) ?: return null
        return Credentials(tag, Hex.dec(key))
    }

    fun saveCredentials(tag: String, key: ByteArray) {
        sp.edit().putString("tag", tag).putString("key", Hex.enc(key)).apply()
    }

    // ---- البيانات (تصل عند أول تقريب ناجح، وتُدمج مواد كل دكتور) ----
    fun profile(): StudentProfile? {
        val id = sp.getString("id", null) ?: return null
        fun g(k: String) = sp.getString(k, "") ?: ""
        val a = JSONArray(sp.getString("courses", "[]"))
        return StudentProfile(id, g("name"), g("faculty"), g("major"), g("level"), g("section"),
            (0 until a.length()).map { a.getString(it) })
    }

    @Synchronized
    fun mergeProfile(p: StudentProfile) {
        val old = JSONArray(sp.getString("courses", "[]"))
        val all = LinkedHashSet<String>()
        for (i in 0 until old.length()) all.add(old.getString(i))
        all.addAll(p.courses)
        sp.edit().putString("id", p.studentId).putString("name", p.name).putString("faculty", p.faculty)
            .putString("major", p.major).putString("level", p.level).putString("section", p.section)
            .putString("courses", JSONArray(all.toList()).toString()).apply()
        ResultBus.profileVersion.value++
    }

    // ---- الجدول والبيانات من الإنترنت (تصل عند أول تفعيل وتتحدّث كلما اتصل الطالب) ----
    /** مجموعة العملي (من الإدارة). */
    val group: String get() = sp.getString("group", "") ?: ""

    fun timetable(): List<TEntry> = Timetable.decode(sp.getString("tt", ""))

    /** وقت آخر تحديث ناجح للجدول (0 = لم يصل بعد). */
    val ttUpdated: Long get() = sp.getLong("tt_at", 0L)

    /** وقت آخر محاولة تحديث (لتفادي تكرارها كل مرة يُفتح التطبيق). */
    var lastTry: Long
        get() = sp.getLong("tt_try", 0L)
        set(v) { sp.edit().putLong("tt_try", v).apply() }

    /** المصدر الرسمي: بيانات الطالب ومواده وجدوله من الإدارة تحلّ محل المحفوظ (بخلاف دمج مواد الدكاترة عبر NFC). */
    @Synchronized
    fun saveOnline(p: StudentPublic) {
        sp.edit().putString("id", p.studentId).putString("name", p.name).putString("faculty", p.faculty)
            .putString("major", p.major).putString("level", p.level).putString("section", p.section)
            .putString("group", p.group).putString("courses", JSONArray(p.courses).toString())
            .putString("tt", Timetable.encode(p.timetable)).putLong("tt_at", System.currentTimeMillis()).apply()
        ResultBus.profileVersion.value++
    }

    // ---- السجل ----
    fun history(): List<HistoryItem> {
        val a = JSONArray(sp.getString("hist", "[]"))
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            HistoryItem(o.getLong("t"), o.getString("s"), o.getInt("r"))
        }.reversed()
    }

    @Synchronized
    fun addHistory(item: HistoryItem) {
        val a = JSONArray(sp.getString("hist", "[]"))
        a.put(JSONObject().put("t", item.time).put("s", item.sessionId).put("r", item.status))
        sp.edit().putString("hist", a.toString()).apply()
        ResultBus.last.value = item
    }

    /** يمسح الكود والبيانات والسجل (مفتاح Keystore يبقى). */
    fun clearAll() { sp.edit().clear().apply(); ResultBus.last.value = null; ResultBus.profileVersion.value++ }
}
