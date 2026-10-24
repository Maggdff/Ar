package com.uniatt.doctor

import android.content.Context
import androidx.room.withTransaction
import com.uniatt.core.Box
import com.uniatt.core.Codes
import com.uniatt.core.TableCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * نسخة الدكتور الاحتياطية المشفّرة: كل المواد والطلاب والتسجيلات والجلسات والحضور (بما فيه الملغى).
 * الاستعادة «دمج»: تضيف المفقود فقط ولا تحذف ولا تستبدل أي سجل موجود، فلا تتلف بيانات أحدث من النسخة.
 * (بيانات الربط بالمسؤول — الكود وصندوق البريد — ليست فيها عمدًا: تُستعاد بمسح QR جديد من المسؤول.)
 */
object DoctorBackup {
    private const val VER = "1"

    suspend fun export(ctx: Context): Pair<File, String> = withContext(Dispatchers.IO) {
        val db = AppDb.get(ctx); val dao = db.dao()
        val prefs = ctx.applicationContext.getSharedPreferences("doctor", 0)
        val tables = db.withTransaction {
            linkedMapOf(
                "course" to dao.coursesOnce().map { listOf(it.id.toString(), it.code, it.name, it.section) },
                "student" to dao.allStudents().map { listOf(it.studentId, it.name, it.faculty, it.major, it.level, it.section, it.tag, it.keyHex, it.publicKeyHex ?: "") },
                "enroll" to dao.allEnrollments().map { listOf(it.studentId, it.courseSectionId.toString()) },
                "session" to dao.allSessions().map { listOf(it.sessionId, it.courseSectionId.toString(), it.doctorId, it.startedAt.toString(), it.durationMin.toString(), it.endedAt?.toString() ?: "", it.countedCount.toString(), it.part.toString()) },
                "record" to dao.allRecordsRaw().map { listOf(it.sessionId, it.studentId, it.studentName, it.courseSectionId.toString(), it.courseName, it.date, it.timestamp.toString(), it.status) }
            )
        }
        val meta = mapOf("app" to "doctor", "ver" to VER, "created" to System.currentTimeMillis().toString(),
            "doctor" to (prefs.getString("id", "") ?: ""), "name" to (prefs.getString("name", "") ?: ""))
        val pass = Codes.random()
        val blob = Box.seal(Box.MAGIC_DOCTOR_BACKUP, "doctor", TableCodec.encode(meta, tables).toByteArray(Charsets.UTF_8), pass)
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        File(dir, "uniatt_doctor_backup_${System.currentTimeMillis() / 1000}.udb").also { it.writeBytes(blob) } to Codes.format(pass)
    }

    /** يعيد رسالة النتيجة. */
    suspend fun restore(ctx: Context, bytes: ByteArray, codeInput: String): String = withContext(Dispatchers.IO) {
        val pass = Codes.normalize(codeInput) ?: return@withContext "كود النسخة غير صحيح"
        val plain = Box.open(Box.MAGIC_DOCTOR_BACKUP, bytes, pass) ?: return@withContext "تعذّرت الاستعادة: الكود خاطئ أو الملف تالف"
        val d = TableCodec.decode(String(plain, Charsets.UTF_8))
        if (d == null || d.meta["app"] != "doctor") return@withContext "هذا الملف ليس نسخة احتياطية لتطبيق الدكتور"
        try {
            val db = AppDb.get(ctx); val dao = db.dao()
            var sessions = 0; var records = 0
            db.withTransaction {
                d.table("course").forEach { dao.upsertCourse(CourseSection(it[0].toLong(), it[1], it[2], it[3])) }
                d.table("student").forEach {
                    val cur = dao.student(it[0])
                    // لا نستبدل طالبًا موجودًا (قد يكون مفتاح جهازه ارتبط بعد النسخة)
                    if (cur == null) dao.upsertStudent(StudentEntity(it[0], it[1], it[2], it[3], it[4], it[5], it[6], it[7], it[8].ifEmpty { null }))
                }
                d.table("enroll").forEach { dao.enroll(Enrollment(it[0], it[1].toLong())) }
                d.table("session").forEach {
                    if (dao.restoreSession(SessionEntity(it[0], it[1].toLong(), it[2], it[3].toLong(), it[4].toInt(), it[5].toLongOrNull(), it[6].toInt(), it[7].toInt())) != -1L) sessions++
                }
                d.table("record").forEach {
                    if (dao.addAttendance(AttendanceRecord(0, it[0], it[1], it[2], it[3].toLong(), it[4], it[5], it[6].toLong(), it[7])) != -1L) records++
                }
            }
            "تمت الاستعادة (دمجًا دون حذف أي شيء): $sessions جلسة و$records تسجيل حضور جديدًا"
        } catch (e: Exception) {
            "تعذّرت الاستعادة: ${e.message ?: "ملف غير متوافق"}"      // المعاملة تتراجع كلها: لا استعادة جزئية
        }
    }
}
