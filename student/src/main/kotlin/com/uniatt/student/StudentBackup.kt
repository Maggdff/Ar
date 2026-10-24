package com.uniatt.student

import android.content.Context
import com.uniatt.core.Box
import com.uniatt.core.Codes
import com.uniatt.core.TableCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * نسخة الطالب الاحتياطية المشفّرة: سجل الحضور. الاستعادة «دمج» (تضيف المفقود فقط ولا تحذف شيئًا).
 * كود التفعيل ومفتاح الجهاز ليسا فيها عمدًا (الكود يرتبط بهاتف واحد على الخادم).
 */
object StudentBackup {
    suspend fun export(ctx: Context): Pair<File, String> = withContext(Dispatchers.IO) {
        val st = StudentStore(ctx)
        val rows = st.history().reversed().map { listOf(it.time.toString(), it.sessionId, it.status.toString()) }
        val meta = mapOf("app" to "student", "ver" to "1", "created" to System.currentTimeMillis().toString(), "id" to (st.profile()?.studentId ?: ""))
        val pass = Codes.random()
        val blob = Box.seal(Box.MAGIC_STUDENT_BACKUP, "student", TableCodec.encode(meta, mapOf("history" to rows)).toByteArray(Charsets.UTF_8), pass)
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        File(dir, "uniatt_student_backup_${System.currentTimeMillis() / 1000}.usb").also { it.writeBytes(blob) } to Codes.format(pass)
    }

    suspend fun restore(ctx: Context, bytes: ByteArray, codeInput: String): String = withContext(Dispatchers.IO) {
        val pass = Codes.normalize(codeInput) ?: return@withContext "كود النسخة غير صحيح"
        val plain = Box.open(Box.MAGIC_STUDENT_BACKUP, bytes, pass) ?: return@withContext "تعذّرت الاستعادة: الكود خاطئ أو الملف تالف"
        val d = TableCodec.decode(String(plain, Charsets.UTF_8))
        if (d == null || d.meta["app"] != "student") return@withContext "هذا الملف ليس نسخة احتياطية لتطبيق الطالب"
        try {
            val items = d.table("history").map { HistoryItem(it[0].toLong(), it[1], it[2].toInt()) }
            val added = StudentStore(ctx).mergeHistory(items)
            "تمت الاستعادة (دمجًا دون حذف أي شيء): $added تسجيل جديد"
        } catch (e: Exception) { "تعذّرت الاستعادة: ${e.message ?: "ملف غير متوافق"}" }
    }
}
