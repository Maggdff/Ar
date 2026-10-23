package com.uniatt.core.pdf

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.pdf.PdfDocument
import com.uniatt.core.AttendanceSheetData
import com.uniatt.core.SheetLayout
import java.io.OutputStream

/** يرسم كشف الحضور PDF (A4 أفقي، من اليمين لليسار) عبر PdfDocument — بلا مكتبات خارجية. */
object SheetPdf {
    fun fileName(base: String) = SheetLayout.fileName(base)

    /**
     * @param title السطر الأول (المادة)، [subtitle] السطر الثاني (الشعبة/المجموعة…)
     * @param notes ملاحظات كل طالب (studentId ← نص)؛ فارغ = بلا عمود ملاحظات يُملأ، ويبقى العمود فارغًا للكتابة اليدوية.
     * @param footer نص قصير يُضاف إلى سطر الصفحة (اختياري).
     */
    fun write(out: OutputStream, title: String, subtitle: String, data: AttendanceSheetData, notes: Map<String, String> = emptyMap(), footer: String = "") {
        val doc = PdfDocument()
        try {
            val cols = SheetLayout.columns(true)
            val pages = SheetLayout.plan(data.rows.size, data.total)
            val line = Paint().apply { color = 0xFF444444.toInt(); strokeWidth = 0.5f; style = Paint.Style.STROKE; isAntiAlias = true }
            val fillHead = Paint().apply { color = 0xFFD6ECD9.toInt(); style = Paint.Style.FILL }
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt(); textAlign = Paint.Align.CENTER }
            val check = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1B7F3A.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.6f; strokeCap = Paint.Cap.ROUND }

            fun fit(text: String, size: Float, w: Float, bold: Boolean = false): Paint {
                tp.isFakeBoldText = bold; tp.textSize = size
                while (tp.textSize > 6f && tp.measureText(text) > w - 4f) tp.textSize -= 0.5f
                return tp
            }
            fun cell(cv: Canvas, c: SheetLayout.Col, y: Float, h: Float, text: String, size: Float, bold: Boolean = false, right: Boolean = false) {
                if (text.isEmpty()) return
                val p = fit(text, size, c.w, bold)
                var t = text
                if (p.measureText(t) > c.w - 4f) { // لا يزال أطول: اقتطاع
                    while (t.length > 1 && p.measureText("$t…") > c.w - 4f) t = t.dropLast(1)
                    t = "$t…"
                }
                p.textAlign = if (right) Paint.Align.RIGHT else Paint.Align.CENTER
                val x = if (right) c.x1 - 3f else c.cx
                val fm = p.fontMetrics
                cv.drawText(t, x, y + h / 2 - (fm.ascent + fm.descent) / 2, p)
                p.textAlign = Paint.Align.CENTER
            }
            var n = 0
            for (pg in pages) {
                val info = PdfDocument.PageInfo.Builder(SheetLayout.PAGE_W.toInt(), SheetLayout.PAGE_H.toInt(), ++n).create()
                val page = doc.startPage(info)
                val cv = page.canvas
                // العنوان
                var y = SheetLayout.MARGIN
                val right = SheetLayout.PAGE_W - SheetLayout.MARGIN
                tp.textAlign = Paint.Align.RIGHT; tp.isFakeBoldText = true; tp.textSize = 15f
                cv.drawText(title, right, y + 14f, tp)
                tp.isFakeBoldText = false; tp.textSize = 10.5f
                cv.drawText(subtitle, right, y + 30f, tp)
                val pageNote = buildString {
                    append("المحاضرات: ${data.total}")
                    if (pg.lecturePages > 1) append(" — أعمدة ${pg.lectureFrom + 1}–${pg.lectureTo}")
                    if (pg.rowPages > 1) append(" — طلاب ${pg.rowFrom + 1}–${pg.rowTo}")
                    append(" — صفحة $n من ${pages.size}")
                    if (footer.isNotBlank()) append(" — $footer")
                }
                tp.textSize = 9f
                cv.drawText(pageNote, right, y + 44f, tp)
                tp.textAlign = Paint.Align.CENTER
                y += SheetLayout.TITLE_H
                // رأس الجدول
                val hh = SheetLayout.HEAD_H
                fun box(c: SheetLayout.Col, yy: Float, h: Float, fill: Paint? = null) {
                    if (fill != null) cv.drawRect(c.x0, yy, c.x1, yy + h, fill)
                    cv.drawRect(c.x0, yy, c.x1, yy + h, line)
                }
                box(cols.no, y, hh, fillHead); cell(cv, cols.no, y, hh, "م", 10f, true)
                box(cols.name, y, hh, fillHead); cell(cv, cols.name, y, hh, "اسم الطالب", 10f, true)
                for (i in 0 until com.uniatt.core.AttendanceSheet.PAGE) {
                    val c = cols.lectures[i]; box(c, y, hh, fillHead)
                    val idx = pg.lectureFrom + i
                    val l = data.lectures.getOrNull(idx)
                    if (idx < pg.lectureTo && l != null) {
                        cell(cv, c, y, hh * 0.6f, "${idx + 1}${l.tag}", 9f, true)
                        cell(cv, c, y + hh * 0.45f, hh * 0.55f, SheetLayout.dateLabel(l), 7f)
                    }
                }
                cols.notes?.let { box(it, y, hh, fillHead); cell(cv, it, y, hh, "ملاحظات", 10f, true) }
                y += hh
                // الصفوف
                for (r in pg.rowFrom until pg.rowTo) {
                    val row = data.rows[r]
                    val rh = SheetLayout.ROW_H
                    box(cols.no, y, rh); cell(cv, cols.no, y, rh, "${row.no}", 8.5f)
                    box(cols.name, y, rh); cell(cv, cols.name, y, rh, row.name, 9f, right = true)
                    for (i in 0 until com.uniatt.core.AttendanceSheet.PAGE) {
                        val c = cols.lectures[i]; box(c, y, rh)
                        val idx = pg.lectureFrom + i
                        if (idx < pg.lectureTo && row.present[idx]) {
                            val p = Path()
                            p.moveTo(c.cx - 4f, y + rh / 2); p.lineTo(c.cx - 1f, y + rh / 2 + 3.5f); p.lineTo(c.cx + 4.5f, y + rh / 2 - 4f)
                            cv.drawPath(p, check)
                        }
                    }
                    cols.notes?.let { box(it, y, rh); cell(cv, it, y, rh, notes[row.studentId] ?: "", 7.5f) }
                    y += rh
                }
                doc.finishPage(page)
            }
            doc.writeTo(out)
        } finally { doc.close() }
    }
}
