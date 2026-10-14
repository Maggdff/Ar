package com.uniatt.core

/** نوع الخانة في الجدول: عادية (الجدول الطبيعي)، تعويضية (أصفر)، اختبار (برتقالي). */
object TKind {
    const val NORMAL = 0
    const val MAKEUP = 1
    const val EXAM = 2
    fun valid(k: Int) = k in 0..2
    fun arabic(k: Int) = when (k) { MAKEUP -> "تعويضية"; EXAM -> "اختبار"; else -> "عادية" }
}

/**
 * خانة واحدة في جدول تخصص ومستوى (كجدول الكلية): اليوم والوقت والمادة ونوعها،
 * ولمن هي: [section] الشعبة («» = كل الشعب)، و[group] مجموعة العملي («» = بلا مجموعة/الكل).
 * day: 0=الأحد … 6=السبت (كما في Schedule). الوقت بالدقائق من منتصف الليل.
 */
data class TEntry(
    val id: Long, val major: String, val level: String,
    val day: Int, val startMin: Int, val endMin: Int,
    val title: String, val kind: Int = TKind.NORMAL, val practical: Boolean = false,
    val section: String = "", val group: String = "",
    val teacher: String = "", val room: String = "", val note: String = ""
)

object Timetable {
    /** الفترات المعتادة في جداول الكلية (للاختيار السريع؛ يمكن كتابة وقت آخر). */
    val PERIODS: List<Pair<Int, Int>> = listOf(8 * 60 to 10 * 60, 10 * 60 to 12 * 60, 12 * 60 to 14 * 60, 14 * 60 to 16 * 60)

    fun valid(e: TEntry) =
        e.day in 0..6 && e.startMin in 0..1439 && e.endMin in 1..1440 && e.endMin > e.startMin &&
            e.title.isNotBlank() && e.major.isNotBlank() && TKind.valid(e.kind)

    private fun same(a: String, b: String) = a.trim().equals(b.trim(), ignoreCase = true)

    /**
     * ما يخص طالبًا: نفس التخصص، والمستوى (إن حدّدته الخانة)، والشعبة (إن حدّدتها الخانة)، والمجموعة (إن حدّدتها الخانة).
     * خانة بلا شعبة تظهر لكل الشعب، وخانة بلا مجموعة تظهر لكل المجموعات.
     */
    fun forStudent(all: List<TEntry>, major: String, level: String, section: String, group: String): List<TEntry> =
        all.filter { e ->
            same(e.major, major) &&
                (e.level.isBlank() || same(e.level, level)) &&
                (e.section.isBlank() || same(e.section, section)) &&
                (e.group.isBlank() || same(e.group, group))
        }.sortedWith(compareBy({ it.day }, { it.startMin }, { it.kind }))

    /** وصف قصير لمن تخص الخانة: «ش أ — مج2» أو «كامل». */
    fun audience(e: TEntry): String {
        val p = ArrayList<String>()
        if (e.section.isNotBlank()) p.add("ش ${e.section}")
        if (e.group.isNotBlank()) p.add(e.group)
        return if (p.isEmpty()) "كامل" else p.joinToString(" — ")
    }

    fun timeRange(e: TEntry) = "${Schedule.fmt(e.startMin)}–${Schedule.fmt(if (e.endMin == 1440) 1439 else e.endMin)}"

    // ---- ترميز نصي (TAB) ----
    private fun c(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim()

    fun encode(list: List<TEntry>): String = list.joinToString("\n") {
        listOf(
            it.id, c(it.major), c(it.level), it.day, it.startMin, it.endMin, c(it.title), it.kind, if (it.practical) 1 else 0,
            c(it.section), c(it.group), c(it.teacher), c(it.room), c(it.note)
        ).joinToString("\t")
    }

    fun decode(text: String?): List<TEntry> = (text ?: "").split('\n').filter { it.isNotBlank() }.mapNotNull { l ->
        val f = l.split('\t')
        if (f.size < 14) null else runCatching {
            TEntry(f[0].toLong(), f[1], f[2], f[3].toInt(), f[4].toInt(), f[5].toInt(), f[6], f[7].toInt(), f[8] == "1",
                f[9], f[10], f[11], f[12], f[13])
        }.getOrNull()?.takeIf { valid(it) }
    }
}
