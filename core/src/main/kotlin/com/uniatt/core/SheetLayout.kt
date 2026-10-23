package com.uniatt.core

/**
 * تخطيط كشف الحضور للطباعة/PDF (A4 أفقي، من اليمين لليسار): دالة بحتة تُختبر بلا أندرويد.
 * الصفحات: لكل 16 محاضرة «شريحة أعمدة»، وتحتها تتابع صفحات الطلاب (يتكرر الرأس في كل صفحة).
 */
object SheetLayout {
    const val PAGE_W = 842f          // A4 أفقي بالنقاط (1/72 بوصة)
    const val PAGE_H = 595f
    const val MARGIN = 28f
    const val TITLE_H = 58f          // عنوان الكشف (يتكرر في كل صفحة)
    const val HEAD_H = 34f           // رأس الجدول
    const val ROW_H = 18f
    const val NO_W = 26f
    const val NAME_W = 190f
    const val NOTE_W = 122f

    /** عدد صفوف الطلاب في الصفحة الواحدة. */
    val rowsPerPage: Int get() = ((PAGE_H - 2 * MARGIN - TITLE_H - HEAD_H) / ROW_H).toInt()

    /** صفحة واحدة من الناتج: نطاقات نصف مفتوحة [from, to). */
    data class Page(
        val lectureFrom: Int, val lectureTo: Int, val rowFrom: Int, val rowTo: Int,
        val lecturePage: Int, val lecturePages: Int, val rowPage: Int, val rowPages: Int
    )

    fun plan(students: Int, lectures: Int, rowsPer: Int = rowsPerPage): List<Page> {
        val lp = AttendanceSheet.pageCount(lectures)
        val rp = maxOf(1, (students + rowsPer - 1) / rowsPer)
        val out = ArrayList<Page>()
        for (l in 0 until lp) for (r in 0 until rp) out.add(
            Page(l * AttendanceSheet.PAGE, minOf((l + 1) * AttendanceSheet.PAGE, lectures), r * rowsPer, minOf((r + 1) * rowsPer, students), l, lp, r, rp)
        )
        return out
    }

    /** عمود: بداية x ونهايته (من اليمين: م ← الاسم ← المحاضرات 1..16 ← ملاحظات). */
    data class Col(val x0: Float, val x1: Float) { val w: Float get() = x1 - x0; val cx: Float get() = (x0 + x1) / 2 }

    class Columns(val no: Col, val name: Col, val lectures: List<Col>, val notes: Col?)

    fun columns(withNotes: Boolean): Columns {
        var x = PAGE_W - MARGIN
        fun take(w: Float): Col { val c = Col(x - w, x); x -= w; return c }
        val no = take(NO_W); val name = take(NAME_W)
        val noteW = if (withNotes) NOTE_W else 0f
        val lw = (x - MARGIN - noteW) / AttendanceSheet.PAGE
        val lec = (0 until AttendanceSheet.PAGE).map { take(lw) }
        val notes = if (withNotes) Col(MARGIN, x) else null
        return Columns(no, name, lec, notes)
    }

    /** «MM/dd» + وسم المحاضرة المتتالية، لرأس العمود. */
    fun dateLabel(l: SheetLecture): String = l.dayKey.substring(5).replace('-', '/')

    /** اسم ملف آمن: يبقي الحروف العربية واللاتينية والأرقام. */
    fun fileName(base: String): String {
        val b = base.map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '_' }.joinToString("").trim('_').ifEmpty { "sheet" }
        return "$b.pdf"
    }
}
