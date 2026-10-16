import com.uniatt.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/** gen <out.json> : يولّد متجهات اختبار بكود Kotlin الأصلي.  verify <vectors.json> <js-out.json> : يتحقق مما أنتجته نسخة الجافاسكربت. */
private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
private fun unb64(s: String) = Base64.getDecoder().decode(s)

fun main(args: Array<String>) {
    when (args[0]) { "gen" -> gen(args[1]); "verify" -> verify(args[1], args[2]); else -> error("mode") }
}

private fun gen(out: String) {
    val code = Codes.random()
    val key = Kdf.studentKey(code); val keyHex = Hex.enc(key)
    val tt = listOf(
        TEntry(1, "علوم الحاسب", "الأول", 0, 480, 600, "برمجة", TKind.NORMAL, false, "أ", "", "د. علي", "قاعة1", ""),
        TEntry(2, "علوم الحاسب", "الأول", 2, 600, 720, "برمجة", TKind.EXAM, true, "", "مج1", "", "معمل", "ملاحظة")
    )
    val pub = StudentPublic("S1", "أحمد علي", "كلية", "علوم الحاسب", "الأول", "أ", "مج1", listOf("برمجة (نظري — شعبة أ) — الأحد 08:00–10:00"), tt)
    val sbox = StudentBox.seal(keyHex, pub)

    val dcode = Codes.random()
    val roster = Roster(7, "D1", "د. علي", listOf(RCourse(1, "نظري", "برمجة", "أ")),
        listOf(RStudent("S1", "أحمد علي", "كلية", "علوم الحاسب", "الأول", "أ", Codes.tag(code), keyHex, listOf(1L))),
        listOf(RSlot(1, 0, 480, 600, "قاعة1")))
    val rosterBlob = roster.seal(dcode)

    val att = AttendanceFile("D1", 7, listOf(ASession("s1", 1, 1_700_000_000_000L, 15, 33), ASession("s2", 1, 1_700_000_100_000L, 15), ASession("s3", 1, 1_700_003_700_000L, 15, -1, 1)),
        listOf(ARecord("s1", "S1", 1_700_000_000_500L)), listOf(ABinding("S1", Codes.tag(code), "04" + "ab".repeat(64))))
    val attBlob = att.seal(dcode)

    val rnd = java.security.SecureRandom()
    val req = Protocol.AttendRequest(ByteArray(16).also { rnd.nextBytes(it) }, ByteArray(16).also { rnd.nextBytes(it) }, System.currentTimeMillis())

    // توقيع Kotlin حقيقي لتتحقق منه الجافاسكربت
    val g = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    val data = "hello-uniatt".toByteArray()
    val sig = Signature.getInstance("SHA256withECDSA").run { initSign(g.private); update(data); sign() }
    val raw = EcKey.rawFromX509(g.public.encoded)!!

    val pairing = Pairing.format(Pairing.Info("https://maggd-141d1-default-rtdb.europe-west1.firebasedatabase.app", Pairing.newMailbox(), "D1", dcode))

    File(out).writeText(JSONObject()
        .put("code", code).put("codeFmt", Codes.format(code)).put("keyHex", keyHex).put("tag", Codes.tag(code)).put("boxId", StudentBox.boxId(keyHex))
        .put("studentBox", b64(sbox)).put("studentText", pub.toText())
        .put("doctorCode", dcode).put("rosterBlob", b64(rosterBlob)).put("rosterText", roster.toText())
        .put("attBlob", b64(attBlob)).put("attText", att.toText())
        .put("challenge", QrProtocol.challengeText(req)).put("reqSession", Hex.enc(req.sessionId)).put("reqNonce", Hex.enc(req.nonce)).put("reqTime", req.timeMs)
        .put("sigPub", Hex.enc(raw)).put("sigData", Hex.enc(data)).put("sigDer", Hex.enc(sig))
        .put("pairing", pairing)
        .toString())
    println("vectors written")
}

private fun verify(vecPath: String, jsPath: String) {
    var pass = 0; var fail = 0
    fun ok(c: Boolean, n: String) { if (c) { pass++; println("  PASS  $n") } else { fail++; println("  FAIL  $n") } }
    val v = JSONObject(File(vecPath).readText()); val j = JSONObject(File(jsPath).readText())
    val keyHex = v.getString("keyHex")

    // إجابة QR من الجافاسكربت (مفتاح جهاز WebCrypto، توقيع محوَّل إلى DER)
    val parsed = QrProtocol.parseReply(j.getString("replyText"))
    ok(parsed != null, "Kotlin يقرأ رمز إجابة الجافاسكربت")
    if (parsed != null) {
        val req = Protocol.AttendRequest(Hex.dec(v.getString("reqSession")), Hex.dec(v.getString("reqNonce")), v.getLong("reqTime"))
        ok(parsed.nonce.contentEquals(req.nonce), "الـnonce مطابق")
        ok(parsed.reply.tag == v.getString("tag"), "وسم الطالب مطابق")
        val exp = Hmac.sha256(Hex.dec(keyHex), Protocol.bindMessage(parsed.reply.tag, parsed.reply.pub, req))
        ok(Hmac.equal(exp, parsed.reply.proof), "إثبات معرفة الكود (HMAC) صحيح")
        ok(SigVerifier.verify(parsed.reply.pub, Protocol.signedBytes(parsed.reply.tag, parsed.reply.pub, req), parsed.reply.sig), "توقيع جهاز WebCrypto يُقبل في Kotlin (DER)")
        ok(parsed.reply.pub.size == 65 && parsed.reply.pub[0] == 4.toByte(), "المفتاح العام نقطة خام 65 بايت")
        ok(!SigVerifier.verify(parsed.reply.pub, Protocol.signedBytes("ZZZZ", parsed.reply.pub, req), parsed.reply.sig), "التوقيع لا يصلح لوسم آخر")
    }
    // صندوق الطالب المشفّر بالجافاسكربت
    val jsBox = StudentBox.openPublic(keyHex, unb64(j.getString("jsStudentBox")))
    ok(jsBox != null && jsBox.name == "طالب جافاسكربت" && jsBox.timetable.size == 1 && jsBox.courses == listOf("مادة (نظري — شعبة أ)"), "Kotlin يفتح صندوق طالب شفّره الجافاسكربت")
    // ملف الحضور المشفّر بالجافاسكربت
    val jsAtt = AttendanceFile.open(unb64(j.getString("jsAttBlob")), v.getString("doctorCode"))
    ok(jsAtt != null && jsAtt.sessions.size == 3 && jsAtt.sessions[0].counted == 41 && jsAtt.sessions[1].counted == -1 && jsAtt.sessions[2].part == 1 && jsAtt.sessions[2].counted == -1 && jsAtt.sessions[0].part == 0 && jsAtt.records.size == 1 && jsAtt.bindings.size == 1,
        "Kotlin يفتح ملف حضور شفّره الجافاسكربت (بالعدّ)")
    ok(AttendanceFile.open(unb64(j.getString("jsAttBlob")), Codes.random()) == null, "كود دكتور خاطئ يفشل")
    println("\nالنتيجة: نجح $pass، فشل $fail")
    if (fail > 0) System.exit(1)
}
