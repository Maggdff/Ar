package com.uniatt.core

/**
 * محاضرة واحدة في الكشف. المحاضرة = مادة + يوم (بتوقيت الجهاز): إذا بدأ الدكتور الحضور ثم أوقفه ثم شغّله
 * في اليوم نفسه فهي محاضرة واحدة، وحضور الطالب في أيٍّ من جلساتها يُحتسب مرة واحدة فقط.
 * [sessionIds] مرتّبة من الأقدم إلى الأحدث (الأحدث هي التي يُستأنف بها الحضور).
 */
class SheetLecture(val no: Int, val dayKey: String, val firstStart: Long, val sessionIds: List<String>, val part: Int = 0) {
    /** وسم العمود: فارغ للأولى في اليوم، و«(2)» للثانية المتتالية… */
    val tag: String get() = AttendanceSheet.partTag(part)
}

/** صف طالب في الكشف: رقمه، اسمه، وحضوره في كل محاضرة بالترتيب نفسه. */
class SheetRow(val no: Int, val studentId: String, val name: String, val present: List<Boolean>) {
    val count: Int get() = present.count { it }
}

class AttendanceSheetData(val lectures: List<SheetLecture>, val rows: List<SheetRow>) {
    val total: Int get() = lectures.size
    /** عدد صفحات الأعمدة (16 محاضرة في الصفحة كالكشف الورقي). */
    val pages: Int get() = AttendanceSheet.pageCount(lectures.size)
}

object AttendanceSheet {
    /** أعمدة المحاضرات في كل صفحة من الكشف (1…16). */
    const val PAGE = 16

    fun dayKey(ms: Long): String = fmtDate(ms)

    fun pageCount(lectures: Int): Int = maxOf(1, (lectures + PAGE - 1) / PAGE)

    /**
     * @param sessions (معرّف الجلسة، وقت بدايتها) لمادة واحدة، بأي ترتيب.
     * @param students (رقم الطالب، اسمه) بترتيب العرض.
     * @param present أزواج (معرّف الجلسة، رقم الطالب) للحضور الفعلي (بلا الملغى).
     * @param parts معرّف الجلسة ← رقم المحاضرة في يومها (0 = الأولى، 1 = الثانية المتتالية…)؛ غير المذكور = 0،
     *  فالبيانات القديمة (محاضرة = يوم) تعمل كما كانت.
     */
    fun build(
        sessions: List<Pair<String, Long>>, students: List<Pair<String, String>>, present: Set<Pair<String, String>>,
        parts: Map<String, Int> = emptyMap()
    ): AttendanceSheetData {
        val byKey = LinkedHashMap<Pair<String, Int>, MutableList<Pair<String, Long>>>()
        for (s in sessions.sortedWith(compareBy({ it.second }, { it.first }))) byKey.getOrPut(dayKey(s.second) to (parts[s.first] ?: 0).coerceAtLeast(0)) { ArrayList() }.add(s)
        // الترتيب: اليوم ثم رقم المحاضرة (لا يتأثر بتأخر وقت بدء جلسة)
        val ordered = byKey.entries.sortedWith(compareBy({ it.key.first }, { it.key.second }))
        val lectures = ordered.mapIndexed { i, (k, list) -> SheetLecture(i + 1, k.first, list.first().second, list.map { it.first }, k.second) }
        val rows = students.mapIndexed { i, (id, name) ->
            SheetRow(i + 1, id, name, lectures.map { l -> l.sessionIds.any { sid -> (sid to id) in present } })
        }
        return AttendanceSheetData(lectures, rows)
    }

    /** وسم المحاضرة المتتالية: 0 → «»، 1 → «(2)»، 2 → «(3)»… */
    fun partTag(part: Int): String = if (part <= 0) "" else "(${part + 1})"

    /** نسبة الحضور المئوية (0 إن لم تُعقد محاضرات). */
    fun percent(attended: Int, total: Int): Int = if (total == 0) 0 else attended * 100 / total
}
