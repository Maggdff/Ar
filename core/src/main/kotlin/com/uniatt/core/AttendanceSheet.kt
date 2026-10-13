package com.uniatt.core

/**
 * محاضرة واحدة في الكشف. المحاضرة = مادة + يوم (بتوقيت الجهاز): إذا بدأ الدكتور الحضور ثم أوقفه ثم شغّله
 * في اليوم نفسه فهي محاضرة واحدة، وحضور الطالب في أيٍّ من جلساتها يُحتسب مرة واحدة فقط.
 * [sessionIds] مرتّبة من الأقدم إلى الأحدث (الأحدث هي التي يُستأنف بها الحضور).
 */
class SheetLecture(val no: Int, val dayKey: String, val firstStart: Long, val sessionIds: List<String>)

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
     */
    fun build(sessions: List<Pair<String, Long>>, students: List<Pair<String, String>>, present: Set<Pair<String, String>>): AttendanceSheetData {
        val byDay = LinkedHashMap<String, MutableList<Pair<String, Long>>>()
        for (s in sessions.sortedWith(compareBy({ it.second }, { it.first }))) byDay.getOrPut(dayKey(s.second)) { ArrayList() }.add(s)
        val lectures = byDay.entries.mapIndexed { i, (day, list) -> SheetLecture(i + 1, day, list.first().second, list.map { it.first }) }
        val rows = students.mapIndexed { i, (id, name) ->
            SheetRow(i + 1, id, name, lectures.map { l -> l.sessionIds.any { sid -> (sid to id) in present } })
        }
        return AttendanceSheetData(lectures, rows)
    }

    /** نسبة الحضور المئوية (0 إن لم تُعقد محاضرات). */
    fun percent(attended: Int, total: Int): Int = if (total == 0) 0 else attended * 100 / total
}
