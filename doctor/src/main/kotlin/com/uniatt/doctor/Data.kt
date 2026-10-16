package com.uniatt.doctor

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** المواد تأتي من ملف الكشف فقط (معرّفها هو معرّف المسؤول). */
@Entity data class CourseSection(
    @PrimaryKey val id: Long,
    val code: String, val name: String, val section: String
)

/**
 * tag/keyHex: من كود الطالب (الوسم للبحث، والمفتاح للتحقق من معرفته بالكود).
 * publicKeyHex: مفتاح جهازه، يُربط تلقائيًا عند أول تسجيل حضور ناجح (null = لم يرتبط بعد).
 */
@Entity(indices = [Index(value = ["tag"], unique = true)])
data class StudentEntity(
    @PrimaryKey val studentId: String,
    val name: String, val faculty: String, val major: String, val level: String, val section: String,
    val tag: String, val keyHex: String,
    val publicKeyHex: String? = null
)

@Entity(primaryKeys = ["studentId", "courseSectionId"])
data class Enrollment(val studentId: String, val courseSectionId: Long)

@Entity data class SessionEntity(
    @PrimaryKey val sessionId: String,
    val courseSectionId: Long, val doctorId: String,
    val startedAt: Long, val durationMin: Int, val endedAt: Long? = null,
    /** عدد الطلاب الذي عدّه الدكتور فعليًا في القاعة (-1 = لم يُدخله). */
    val countedCount: Int = -1,
    /** رقم المحاضرة في يومها لنفس المادة/الشعبة (0 = الأولى، 1 = الثانية المتتالية…) — يبدؤها الدكتور صراحةً. */
    val part: Int = 0
)

/**
 * حالة سجل الحضور: PRESENT = سجّله هاتف الطالب (بلوتوث/QR)، MANUAL = حضّره الدكتور يدويًا،
 * CANCELLED = ألغى الدكتور تحضيره. يبقى سجل الملغى (بدل حذفه) ليمنع هاتف الطالب من إعادة تسجيل نفسه تلقائيًا؛
 * ولا يُحتسب في التقارير ولا يُرفع للمسؤول (فيزول عنده أيضًا).
 */
object AttStatus {
    const val PRESENT = "PRESENT"
    const val MANUAL = "MANUAL"
    const val CANCELLED = "CANCELLED"
}

/** القيد الفريد (sessionId, studentId) يمنع التسجيل المكرر على مستوى قاعدة البيانات. */
@Entity(indices = [Index(value = ["sessionId", "studentId"], unique = true)])
data class AttendanceRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String, val studentId: String, val studentName: String,
    val courseSectionId: Long, val courseName: String,
    val date: String, val timestamp: Long, val status: String
)

@Dao interface AttDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertCourse(c: CourseSection)
    @Query("DELETE FROM Enrollment WHERE courseSectionId IN (:ids)") suspend fun clearEnrollments(ids: List<Long>)
    @Query("SELECT * FROM CourseSection ORDER BY name") fun courses(): Flow<List<CourseSection>>
    @Query("SELECT * FROM CourseSection") suspend fun coursesOnce(): List<CourseSection>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertStudent(s: StudentEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun enroll(e: Enrollment)
    @Query("SELECT * FROM StudentEntity WHERE studentId=:id") suspend fun student(id: String): StudentEntity?
    @Query("SELECT * FROM StudentEntity WHERE tag=:tag") suspend fun studentByTag(tag: String): StudentEntity?
    @Query("UPDATE StudentEntity SET publicKeyHex=:k WHERE studentId=:id") suspend fun bindKey(id: String, k: String)
    @Query("SELECT * FROM StudentEntity WHERE publicKeyHex IS NOT NULL") suspend fun boundStudents(): List<StudentEntity>

    @Query("SELECT COUNT(*) FROM Enrollment WHERE studentId=:sid AND courseSectionId=:cid")
    suspend fun enrolled(sid: String, cid: Long): Int
    @Query("SELECT c.* FROM CourseSection c JOIN Enrollment e ON e.courseSectionId=c.id WHERE e.studentId=:sid")
    suspend fun coursesOfStudent(sid: String): List<CourseSection>
    @Query("SELECT s.* FROM StudentEntity s JOIN Enrollment e ON e.studentId=s.studentId WHERE e.courseSectionId=:cid ORDER BY s.name")
    fun studentsOf(cid: Long): Flow<List<StudentEntity>>
    @Query("SELECT s.* FROM StudentEntity s JOIN Enrollment e ON e.studentId=s.studentId WHERE e.courseSectionId=:cid ORDER BY s.name")
    suspend fun studentsOfOnce(cid: Long): List<StudentEntity>

    @Insert suspend fun addSession(s: SessionEntity)
    @Query("UPDATE SessionEntity SET endedAt=:t WHERE sessionId=:id") suspend fun endSession(id: String, t: Long)
    @Query("SELECT * FROM SessionEntity WHERE courseSectionId=:cid ORDER BY startedAt DESC")
    fun sessionsOf(cid: Long): Flow<List<SessionEntity>>
    @Query("SELECT * FROM SessionEntity") suspend fun allSessions(): List<SessionEntity>
    @Query("SELECT COUNT(*) FROM SessionEntity WHERE courseSectionId=:cid") suspend fun sessionCount(cid: Long): Int
    @Query("SELECT * FROM SessionEntity WHERE courseSectionId=:cid") suspend fun sessionsOnce(cid: Long): List<SessionEntity>
    /** آخر جلسة للمادة منذ بداية اليوم (لاستئنافها بدل فتح جلسة ثانية للمحاضرة نفسها). */
    @Query("SELECT * FROM SessionEntity WHERE courseSectionId=:cid AND startedAt>=:from ORDER BY startedAt DESC LIMIT 1")
    suspend fun lastSessionSince(cid: Long, from: Long): SessionEntity?
    /** أعلى رقم محاضرة بدأت اليوم للمادة (null = لا جلسات اليوم). */
    @Query("SELECT MAX(part) FROM SessionEntity WHERE courseSectionId=:cid AND startedAt>=:from")
    suspend fun maxPartSince(cid: Long, from: Long): Int?
    @Query("UPDATE SessionEntity SET endedAt=NULL, durationMin=:dur WHERE sessionId=:id") suspend fun reopenSession(id: String, dur: Int)
    @Query("UPDATE SessionEntity SET countedCount=:n WHERE sessionId=:id") suspend fun setCounted(id: String, n: Int)
    @Query("SELECT * FROM SessionEntity WHERE sessionId=:id") fun sessionFlow(id: String): Flow<SessionEntity?>

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun addAttendance(a: AttendanceRecord): Long
    @Query("SELECT * FROM AttendanceRecord WHERE sessionId=:sid ORDER BY timestamp DESC")
    fun attendanceOf(sid: String): Flow<List<AttendanceRecord>>
    /** الحضور الفعلي فقط (بلا الملغى): للتقارير والرفع للمسؤول. */
    @Query("SELECT * FROM AttendanceRecord WHERE courseSectionId=:cid AND status != 'CANCELLED'") suspend fun attendanceForCourse(cid: Long): List<AttendanceRecord>
    @Query("SELECT * FROM AttendanceRecord WHERE status != 'CANCELLED'") suspend fun allAttendance(): List<AttendanceRecord>
    @Query("SELECT * FROM AttendanceRecord WHERE courseSectionId=:cid AND status != 'CANCELLED'") fun attendanceFlowOfCourse(cid: Long): Flow<List<AttendanceRecord>>
    @Query("SELECT * FROM AttendanceRecord WHERE sessionId=:sid AND studentId=:stu LIMIT 1") suspend fun record(sid: String, stu: String): AttendanceRecord?
    @Query("UPDATE AttendanceRecord SET status=:status WHERE sessionId=:sid AND studentId=:stu") suspend fun setStatus(sid: String, stu: String, status: String)
}

@Database(
    entities = [CourseSection::class, StudentEntity::class, Enrollment::class, SessionEntity::class, AttendanceRecord::class],
    version = 4, exportSchema = false
)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): AttDao
    companion object {
        @Volatile private var I: AppDb? = null
        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE SessionEntity ADD COLUMN countedCount INTEGER NOT NULL DEFAULT -1")
            }
        }
        private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE SessionEntity ADD COLUMN part INTEGER NOT NULL DEFAULT 0")
            }
        }
        fun get(c: Context): AppDb = I ?: synchronized(this) {
            I ?: Room.databaseBuilder(c.applicationContext, AppDb::class.java, "attendance.db")
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4)           // يحفظ الحضور المسجَّل (لا مسح للبيانات)
                .fallbackToDestructiveMigration()       // نسخ التطوير الأقدم من 2 فقط
                .build().also { I = it }
        }
    }
}
