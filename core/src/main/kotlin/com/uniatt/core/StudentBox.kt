package com.uniatt.core

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * بيانات الطالب وجدوله كما ينشرها المسؤول على الترحيل (مشفّرة بمفتاح مشتق من كود الطالب):
 * الطالب يكتب كوده → يشتق مفتاحه → يحسب عنوان صندوقه → يجلبه ويفكّه. نجاح الفكّ يثبت أن الكود صحيح ومسجَّل عند المسؤول،
 * ولا يستطيع أحد غير حامل الكود حتى معرفة عنوان الصندوق أو قراءته (Firebase لا ترى إلا بايتات مشفّرة).
 */
data class StudentPublic(
    val studentId: String, val name: String, val faculty: String, val major: String,
    val level: String, val section: String, val group: String,
    val courses: List<String>, val timetable: List<TEntry>
) {
    private fun c(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim()

    fun toText(): String = buildString {
        append("USP1\n")
        append("P\t${c(studentId)}\t${c(name)}\t${c(faculty)}\t${c(major)}\t${c(level)}\t${c(section)}\t${c(group)}\n")
        courses.forEach { append("C\t${c(it)}\n") }
        timetable.forEach { append("T\t${Timetable.encode(listOf(it))}\n") }
    }

    /** نفس بيانات NFC القديمة (مواد الطالب مع مواعيدها)، لدمجها في «بياناتي» و«المواد». */
    fun toProfile() = StudentProfile(studentId, name, faculty, major, level, section, courses)

    companion object {
        fun parse(text: String): StudentPublic? = try {
            val lines = text.split('\n').filter { it.isNotEmpty() }
            if (lines.firstOrNull() != "USP1") null
            else {
                var p: List<String>? = null
                val courses = ArrayList<String>(); val tt = ArrayList<TEntry>()
                for (line in lines.drop(1)) {
                    when {
                        line.startsWith("P\t") -> p = line.split('\t')
                        line.startsWith("C\t") -> courses.add(line.substring(2))
                        line.startsWith("T\t") -> tt.addAll(Timetable.decode(line.substring(2)))
                    }
                }
                if (p == null || p.size != 8 || p[1].isBlank()) null
                else StudentPublic(p[1], p[2], p[3], p[4], p[5], p[6], p[7], courses, tt)
            }
        } catch (e: Exception) { null }
    }
}

object StudentBox {
    val MAGIC: ByteArray = "UAS1".toByteArray(Charsets.US_ASCII)
    private const val IV = 12

    /** عنوان صندوق الطالب (128 بت) مشتق من مفتاحه؛ لا يُعرف إلا بمعرفة الكود. */
    fun boxId(keyHex: String): String =
        Hex.enc(MessageDigest.getInstance("SHA-256").digest("uniatt-sbox-id|$keyHex".toByteArray(Charsets.UTF_8))).substring(0, 32)

    fun path(keyHex: String) = "m/${boxId(keyHex)}/stu"

    /** مفتاح التشفير مشتق من مفتاح الطالب بسياق منفصل عن إثبات الحضور (HMAC). */
    private fun aesKey(keyHex: String) = SecretKeySpec(Hmac.sha256(Hex.dec(keyHex), "uniatt-sbox-enc".toByteArray(Charsets.UTF_8)), "AES")

    fun seal(keyHex: String, plain: ByteArray, rnd: SecureRandom = SecureRandom()): ByteArray {
        val iv = ByteArray(IV).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, aesKey(keyHex), GCMParameterSpec(128, iv)); updateAAD(MAGIC)
        }
        return MAGIC + iv + c.doFinal(plain)
    }

    /** null = كود/مفتاح خاطئ أو بيانات معدَّلة/تالفة. */
    fun open(keyHex: String, blob: ByteArray): ByteArray? = try {
        if (blob.size < 4 + IV + 16 || !blob.copyOfRange(0, 4).contentEquals(MAGIC)) null
        else Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, aesKey(keyHex), GCMParameterSpec(128, blob.copyOfRange(4, 4 + IV))); updateAAD(MAGIC)
            doFinal(blob.copyOfRange(4 + IV, blob.size))
        }
    } catch (e: Exception) { null }

    fun seal(keyHex: String, pub: StudentPublic): ByteArray = seal(keyHex, pub.toText().toByteArray(Charsets.UTF_8))
    fun openPublic(keyHex: String, blob: ByteArray): StudentPublic? =
        open(keyHex, blob)?.let { StudentPublic.parse(String(it, Charsets.UTF_8)) }
}
