package com.uniatt.student

import android.content.Context
import com.uniatt.core.AttendancePolicy
import com.uniatt.core.BleProtocol
import com.uniatt.core.Hmac
import com.uniatt.core.Protocol
import com.uniatt.core.QrProtocol

/** الطالب خارج وقت أي محاضرة في جدوله (أو لا جدول عنده): لا يُجهَّز ردّ حضور. الرسالة جاهزة للعرض. */
class NotInLectureException(message: String) : Exception(message)

/** يجهّز إجابة الطالب على تحدّي QR الذي يعرضه الدكتور (الإثباتات نفسها المستخدمة في البلوتوث). */
object StudentQr {
    private fun attendReply(ctx: Context, req: Protocol.AttendRequest): Protocol.AttendReply {
        val store = StudentStore(ctx)
        val c = store.credentials() ?: throw IllegalStateException("التطبيق غير مُفعَّل")
        // «لا تحضير إلا حسب الجدول»: وقت التحدّي هو وقت هاتف الدكتور (الموثوق)، فلا يفيد الطالب تغيير ساعة هاتفه
        val gate = AttendancePolicy.checkStudent(store.timetable(), req.timeMs)
        if (!gate.ok) throw NotInLectureException(gate.message)
        val pub = DeviceKey.publicRaw()
        val proof = Hmac.sha256(c.key, Protocol.bindMessage(c.tag, pub, req))          // إثبات معرفة الكود
        val sig = DeviceKey.sign(Protocol.signedBytes(c.tag, pub, req))                // إثبات حيازة الجهاز
        return Protocol.AttendReply(c.tag, pub, proof, sig)
    }

    fun reply(ctx: Context, req: Protocol.AttendRequest): String = QrProtocol.replyText(req.nonce, attendReply(ctx, req))

    /** حمولة الإجابة للبلوتوث (قبل التجزئة). */
    fun payload(ctx: Context, req: Protocol.AttendRequest): ByteArray = BleProtocol.replyPayload(req.nonce, attendReply(ctx, req))
}
