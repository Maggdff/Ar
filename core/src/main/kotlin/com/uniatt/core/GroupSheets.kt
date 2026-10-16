package com.uniatt.core

/**
 * كشف طلاب مجموعة واحدة كما يظهر في ملف «كشف حضور وغياب الطلاب» (PDF):
 * [label] اسم المجموعة كما في الملف (أ، ب، ج…)، ثم التخصص والمستوى ووقت المحاضرة، وأسماء الطلاب.
 * [fixed] = عدد الكلمات التي أُصلح فيها نقص حروف ناتج عن الملف نفسه (انظر [NameFix]).
 */
data class GroupSheet(
    val label: String, val major: String, val level: String,
    val day: Int, val startMin: Int, val endMin: Int,
    val names: List<String>, val fixed: Int
) {
    /** «السبت 08:00–10:00» أو فارغ إن لم يُعثر على الوقت. */
    val whenText: String get() = listOfNotNull(
        Schedule.DAYS.getOrNull(day),
        if (startMin >= 0 && endMin > startMin) "${Schedule.fmt(startMin)}–${Schedule.fmt(endMin % 1440)}" else null
    ).joinToString(" ")
}

/**
 * إصلاح نقص الحروف في نص PDF: خط هذه الكشوف يضع الحروف المركّبة (لا، لأ، لله) بلا رمز Unicode،
 * فيخرج «عبدالله» → «عبدا»، «هلال» → «هلل»، «الأمن» → «المن». نصلح الحالات المؤكدة فقط، والباقي يعدّله المسؤول.
 */
object NameFix {
    private val WORDS = mapOf(
        "عبدا" to "عبدالله", "ا" to "الله",
        "هلل" to "هلال", "دلل" to "دلال", "بلل" to "بلال", "جلل" to "جلال", "ولء" to "ولاء", "علء" to "علاء",
        "صلح" to "صلاح", "فلح" to "فلاح", "الهللي" to "الهلالي", "الهلل" to "الهلال",
        "عبدالسلم" to "عبدالسلام", "السلم" to "السلام", "سندريل" to "سندريلا", "رويلء" to "رويلاء", "لرين" to "لارين",
        "المن" to "الأمن", "الول" to "الأول", "الستاذ" to "الأستاذ", "المير" to "الأمير"
    )

    /** يعيد النص المصلَح وعدد الكلمات المصلَحة. */
    fun fix(name: String): Pair<String, Int> {
        var n = 0
        val out = name.trim().split(Regex("\\s+")).joinToString(" ") { w ->
            WORDS[w]?.also { n++ } ?: w
        }
        return out to n
    }

    /**
     * مفتاح مقارنة يتجاهل الحروف التي تضيع في الملف (ا ل ه) وفروق الهمزات والتاء المربوطة،
     * فيطابق «عبدا» مع «عبدالله» و«دلل» مع «دلال» عند البحث عن طالب موجود.
     */
    fun key(name: String): String = buildString {
        for (ch in name) {
            val c = when (ch) { 'أ', 'إ', 'آ', 'ٱ' -> 'ا'; 'ى' -> 'ي'; 'ة' -> 'ه'; else -> ch }
            if (c == 'ا' || c == 'ل' || c == 'ه' || c == 'ـ' || c.isWhitespace() || c in '\u064B'..'\u065F') continue
            append(c)
        }
    }
}

object GroupSheets {
    private val DEGREE = "(?:بكالوريوس|دبلوم|ماجستير|دكتوراه)"
    // PDFBox: «أبكالوريوس المن السيبراني  1» (حرف المجموعة ملتصق قبل الدرجة، والمستوى في آخر السطر)
    private val HEAD_A = Regex("^\\s*(.{0,8}?)\\s*$DEGREE\\s+(.+?)\\s+(\\d{1,2})\\s*$")
    // pdftotext -layout: «أ 1 ... بكالوريوس المن السيبراني»
    private val HEAD_B = Regex("^\\s*(\\S{1,6})\\s+(\\d{1,2})\\s+.*?$DEGREE\\s+(.+?)\\s*$")
    private val ROW_TRAIL = Regex("^(.+?)\\s+(\\d{1,3})\\s*$")
    private val ROW_LEAD = Regex("^(\\d{1,3})\\s+(.+)$")
    private val TIME = Regex("(\\d{1,2}):(\\d{2})\\D{0,6}?([صم])\\D{0,6}?(\\d{1,2}):(\\d{2})\\D{0,6}?([صم])")
    private val LEVEL = Regex("المستوى\\s+(الول|الأول|الثاني|الثالث|الرابع|الخامس|السادس|السابع|الثامن)")
    private val ORDINALS = listOf("", "الأول", "الثاني", "الثالث", "الرابع", "الخامس", "السادس", "السابع", "الثامن", "التاسع", "العاشر")
    private val BIDI = Regex("[\u200E\u200F\u202A-\u202E\u2066-\u2069\u061C]")

    fun ordinal(n: Int) = ORDINALS.getOrElse(n) { n.toString() }

    private fun to24(h: Int, ampm: Char): Int = when { ampm == 'م' && h < 12 -> h + 12; ampm == 'ص' && h == 12 -> 0; else -> h }

    private fun validName(s: String): Boolean {
        val t = s.trim()
        return t.length >= 3 && t.none { it.isDigit() } && t.count { it in '\u0621'..'\u064A' } >= 3 && !t.contains("بكالوريوس") &&
            !t.startsWith("معمل") && !t.startsWith("قاعة") && !t.startsWith("القاعة") && !t.startsWith("المدرج")
    }

    private class Cur(val label: String, val major: String, val level: String, val day: Int, val start: Int, val end: Int, var next: Int) {
        val names = ArrayList<String>(); var fixed = 0
    }

    /**
     * يحلّل نص صفحات الكشف (كل عنصر نص صفحة، كما يستخرجه PDFBox). كل مجموعة تبدأ بسطر «بكالوريوس… المستوى»،
     * والطلاب أسطر مرقّمة 1،2،3… بعدها. صفحة تكمل مجموعة (نفس الاسم وأرقام متتابعة) تُدمج في المجموعة نفسها.
     */
    fun parse(pages: List<String>): List<GroupSheet> {
        val sheets = ArrayList<Cur>()
        var cur: Cur? = null
        var day = -1; var start = -1; var end = -1; var levelWord = ""
        for (page in pages) for (raw in page.lines()) {
            val line = raw.replace(BIDI, "").replace('\u00A0', ' ').trim()
            if (line.isEmpty()) continue

            // ---- ترويسة المجموعة ----
            val a = HEAD_A.matchEntire(line)
            val b = if (a == null) HEAD_B.matchEntire(line) else null
            if (a != null || b != null) {
                val label = (a?.groupValues?.get(1) ?: b!!.groupValues[1]).trim()
                val majorRaw = a?.groupValues?.get(2) ?: b!!.groupValues[3]
                val lvlNum = (a?.groupValues?.get(3) ?: b!!.groupValues[2]).toIntOrNull() ?: 0
                val prev = sheets.lastOrNull { it.label == label }
                val c = Cur(label, NameFix.fix(majorRaw).first, if (lvlNum > 0) ordinal(lvlNum) else NameFix.fix(levelWord).first, day, start, end,
                    next = if (prev != null && prev.start == start) prev.next else 1)
                sheets.add(c); cur = c
                day = -1; start = -1; end = -1; levelWord = ""
                continue
            }

            // ---- بيانات عامة قبل الترويسة (الوقت، اليوم، المستوى) ----
            // ظهور بيانات الترويسة (وقت/يوم/مستوى/أستاذ) يعني أن صفحة جديدة بدأت: لا نقبل صفوفًا قبل ترويسة مجموعتها
            if (TIME.containsMatchIn(line) || LEVEL.containsMatchIn(line) || line.contains("اليوم") || line.contains("الستــاذ") || line.contains("الأستاذ")) cur = null
            TIME.find(line)?.let { m ->
                val s = to24(m.groupValues[1].toInt(), m.groupValues[3][0]) * 60 + m.groupValues[2].toInt()
                val e = to24(m.groupValues[4].toInt(), m.groupValues[6][0]) * 60 + m.groupValues[5].toInt()
                if (e > s) { start = s; end = e }
            }
            if (line.contains("اليوم")) Schedule.DAYS.indexOfFirst { line.contains(it) }.takeIf { it >= 0 }?.let { day = it }
            LEVEL.find(line)?.let { levelWord = it.groupValues[1] }

            // ---- صف طالب: «الاسم 12» أو «12 الاسم»، ويجب أن يطابق الرقم المتوقع ----
            val c = cur ?: continue
            var name: String? = null
            ROW_TRAIL.matchEntire(line)?.let { m -> if (m.groupValues[2].toInt() == c.next && validName(m.groupValues[1])) name = m.groupValues[1] }
            if (name == null) ROW_LEAD.matchEntire(line)?.let { m -> if (m.groupValues[1].toInt() == c.next && validName(m.groupValues[2])) name = m.groupValues[2] }
            name?.let {
                val (fixedName, n) = NameFix.fix(it)
                c.names.add(fixedName); c.fixed += n; c.next++
            }
        }
        // دمج الأجزاء التي لها المجموعة والوقت نفسهما (كشف طويل على أكثر من صفحة)
        val merged = ArrayList<Cur>()
        for (c in sheets) {
            val m = merged.firstOrNull { it.label == c.label && it.start == c.start && it.day == c.day }
            if (m != null) { m.names.addAll(c.names); m.fixed += c.fixed } else merged.add(c)
        }
        return merged.filter { it.names.isNotEmpty() }.map { GroupSheet(it.label, it.major, it.level, it.day, it.start, it.end, it.names.toList(), it.fixed) }
    }
}
