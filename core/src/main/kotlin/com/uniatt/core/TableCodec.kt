package com.uniatt.core

/**
 * ترميز جداول نصّية بسيط للنسخ الاحتياطية (بلا اعتماد على org.json): ترويسة (مفتاح/قيمة) ثم صفوف لكل جدول.
 *   UATB1
 *   H<TAB>مفتاح<TAB>قيمة
 *   R<TAB>جدول<TAB>حقل1<TAB>حقل2…
 * الحقول تُهرَّب (\\ و\t و\n و\r) فلا يكسرها أي نص. يُشفَّر الناتج بعد ذلك بـ [Box] بكود عشوائي كنسخة المسؤول.
 */
object TableCodec {
    const val MAGIC = "UATB1"

    class Decoded(val meta: Map<String, String>, val tables: Map<String, List<List<String>>>) {
        fun table(name: String): List<List<String>> = tables[name] ?: emptyList()
    }

    private fun esc(s: String) = buildString {
        for (ch in s) when (ch) {
            '\\' -> append("\\\\"); '\t' -> append("\\t"); '\n' -> append("\\n"); '\r' -> append("\\r")
            else -> append(ch)
        }
    }

    private fun unesc(s: String): String? {
        val sb = StringBuilder(); var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\') { sb.append(c); i++; continue }
            if (i + 1 >= s.length) return null
            when (s[i + 1]) { '\\' -> sb.append('\\'); 't' -> sb.append('\t'); 'n' -> sb.append('\n'); 'r' -> sb.append('\r'); else -> return null }
            i += 2
        }
        return sb.toString()
    }

    fun encode(meta: Map<String, String>, tables: Map<String, List<List<String>>>): String = buildString {
        append(MAGIC).append('\n')
        meta.forEach { (k, v) -> append("H\t").append(esc(k)).append('\t').append(esc(v)).append('\n') }
        tables.forEach { (name, rows) -> rows.forEach { r -> append("R\t").append(esc(name)); r.forEach { append('\t').append(esc(it)) }; append('\n') } }
    }

    /** null = ليس ملف جداول صالحًا. */
    fun decode(text: String): Decoded? {
        val lines = text.split('\n').filter { it.isNotEmpty() }
        if (lines.firstOrNull() != MAGIC) return null
        val meta = LinkedHashMap<String, String>(); val tables = LinkedHashMap<String, MutableList<List<String>>>()
        for (line in lines.drop(1)) {
            val f = line.split('\t').map { unesc(it) ?: return null }
            when (f[0]) {
                "H" -> { if (f.size != 3) return null; meta[f[1]] = f[2] }
                "R" -> { if (f.size < 2) return null; tables.getOrPut(f[1]) { ArrayList() }.add(f.drop(2)) }
                else -> return null
            }
        }
        return Decoded(meta, tables)
    }
}
