package com.uniatt.admin

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.IOException

/** قراءة نص صفحات PDF (طبقة النص فقط، ليس OCR للصور الممسوحة) عبر PDFBox. */
object PdfText {
    /** نص كل صفحة بالترتيب. يرمي استثناء إن كان الملف تالفًا أو محميًا بكلمة مرور. */
    fun pages(ctx: Context, uri: Uri): List<String> {
        PDFBoxResourceLoader.init(ctx.applicationContext)
        val input = ctx.contentResolver.openInputStream(uri) ?: throw IOException("تعذّر فتح الملف")
        input.use { stream ->
            PDDocument.load(stream).use { doc ->
                val stripper = PDFTextStripper()
                return (1..doc.numberOfPages).map { p ->
                    stripper.startPage = p
                    stripper.endPage = p
                    stripper.getText(doc)
                }
            }
        }
    }
}
