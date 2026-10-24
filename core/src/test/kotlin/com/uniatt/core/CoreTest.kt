package com.uniatt.core

import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.UUID

class CoreTest {
    private fun req() = Protocol.AttendRequest(Protocol.uuidToBytes(UUID.randomUUID()), ByteArray(16) { it.toByte() }, 1234567890L)
    private fun ec() = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private fun sign(k: java.security.PrivateKey, d: ByteArray) =
        Signature.getInstance("SHA256withECDSA").run { initSign(k); update(d); sign() }

    // ---------- الأكواد ----------
    @Test fun codeRoundTripAndNormalization() {
        val c = Codes.random()
        assertEquals(c, Codes.normalize(Codes.format(c).lowercase()))
        assertEquals(c, Codes.normalize(c.map { ch -> if (ch.isDigit()) ('٠' + (ch - '0')) else ch }.joinToString("")))
        assertNull(Codes.normalize("ABC"))
        assertNull(Codes.normalize("UUUU-UUUU-UUUU"))
    }

    @Test fun anySingleCharTypoIsDetected() {
        repeat(50) {
            val c = Codes.random()
            for (i in c.indices) for (a in Codes.ALPHABET) {
                if (a == c[i]) continue
                assertNull(Codes.normalize(c.substring(0, i) + a + c.substring(i + 1)))
            }
        }
    }

    @Test fun newUniqueAvoidsExistingTags() {
        val used = HashSet<String>()
        repeat(300) { val c = Codes.newUnique(used); assertTrue(used.add(Codes.tag(c))) }
    }

    // ---------- الصندوق المشفّر ----------
    @Test fun boxRoundTripWrongCodeAndTamper() {
        val code = Codes.random(); val plain = "كشف تجريبي".toByteArray()
        val blob = Box.seal(Box.MAGIC_ROSTER, "D1", plain, code)
        assertEquals("D1", Box.hint(Box.MAGIC_ROSTER, blob))
        assertArrayEquals(plain, Box.open(Box.MAGIC_ROSTER, blob, code))
        assertNull(Box.open(Box.MAGIC_ROSTER, blob, Codes.random()))
        assertNull(Box.open(Box.MAGIC_ATTEND, blob, code))
        for (i in listOf(0, 4, 5, 10, blob.size - 1)) {
            val b = blob.clone(); b[i] = (b[i].toInt() xor 1).toByte()
            assertNull(Box.open(Box.MAGIC_ROSTER, b, code))
        }
        assertNull(Box.open(Box.MAGIC_ROSTER, blob.copyOf(10), code))
    }

    // ---------- المفاتيح والتواقيع ----------
    @Test fun rawPointRoundTripAndSignature() {
        val kp = ec(); val enc = kp.public.encoded
        val raw = EcKey.rawFromX509(enc)!!
        assertEquals(65, raw.size)
        assertArrayEquals(enc, EcKey.toX509(raw))
        val data = "hello".toByteArray(); val sig = sign(kp.private, data)
        assertTrue(SigVerifier.verify(raw, data, sig))
        assertFalse(SigVerifier.verify(raw, "hellp".toByteArray(), sig))
        val bad = raw.clone(); bad[10] = (bad[10].toInt() xor 1).toByte()
        assertFalse(SigVerifier.verify(bad, data, sig))
    }

    // ---------- البروتوكول ----------
    @Test fun attendCommandAndResponseRoundTrip() {
        val r = req()
        val p = Protocol.parseAttendCommand(Protocol.buildAttendCommand(r))!!
        assertArrayEquals(r.sessionId, p.sessionId); assertArrayEquals(r.nonce, p.nonce); assertEquals(r.timeMs, p.timeMs)

        val reply = Protocol.AttendReply("ABCD", ByteArray(65) { 4 }, ByteArray(32) { 1 }, ByteArray(70) { 7 })
        val resp = Protocol.buildAttendResponse(reply)
        assertTrue(resp.size <= 255)
        val back = Protocol.parseAttendResponse(resp)!!
        assertEquals("ABCD", back.tag); assertEquals(70, back.sig.size)
        assertNull(Protocol.parseAttendResponse(Protocol.SW_ERR))
    }

    @Test fun proofAndSignatureBindToKeyAndNonce() {
        val code = Codes.random(); val tag = Codes.tag(code); val key = Kdf.studentKey(code)
        val kp = ec(); val pub = EcKey.rawFromX509(kp.public.encoded)!!
        val r = req(); val other = Protocol.AttendRequest(r.sessionId, ByteArray(16) { 9 }, r.timeMs)
        val proof = Hmac.sha256(key, Protocol.bindMessage(tag, pub, r))
        assertTrue(Hmac.equal(proof, Hmac.sha256(key, Protocol.bindMessage(tag, pub, r))))
        assertFalse(Hmac.equal(proof, Hmac.sha256(key, Protocol.bindMessage(tag, pub, other))))      // nonce مختلف
        assertFalse(Hmac.equal(proof, Hmac.sha256(Kdf.studentKey(Codes.random()), Protocol.bindMessage(tag, pub, r)))) // كود مختلف
        val sig = sign(kp.private, Protocol.signedBytes(tag, pub, r))
        assertTrue(SigVerifier.verify(pub, Protocol.signedBytes(tag, pub, r), sig))
        assertFalse(SigVerifier.verify(pub, Protocol.signedBytes(tag, pub, other), sig))
    }

    @Test fun profileChunksReassemble() {
        val p = StudentProfile("1001", "محمد أحمد عبدالرحمن الشهري", "كلية الحاسبات", "علوم الحاسب", "3", "A",
            listOf("CS101 برمجة (A)", "MATH1 رياضيات (B)", "PHY2 فيزياء (C)"))
        val payload = p.encode()
        val cmds = Protocol.buildProfileCommands(payload)
        assertTrue(cmds.all { it.size <= 255 })
        val asm = ProfileAssembler(); var out: ByteArray? = null
        cmds.forEach { out = asm.add(Protocol.parseProfileCommand(it)!!) }
        assertEquals(p, StudentProfile.decode(out!!))
        // جزء خارج الترتيب يُرفض
        val big = Protocol.buildProfileCommands(ByteArray(450) { 1 })
        assertNull(ProfileAssembler().add(Protocol.parseProfileCommand(big[1])!!))
    }

    @Test fun resultCommandRoundTrip() {
        assertEquals(Status.KEY_MISMATCH, Protocol.parseResultCommand(Protocol.buildResultCommand(Status.KEY_MISMATCH)))
    }

    // ---------- ملفات الكشف والحضور ----------
    @Test fun rosterSealOpen() {
        val code = Codes.random()
        val key = Hex.enc(Kdf.studentKey(Codes.random()))
        val r = Roster(5, "D1", "د. خالد", listOf(RCourse(1, "CS101", "برمجة", "A")),
            listOf(RStudent("1001", "محمد\tأحمد", "كلية", "تخصص", "3", "A", "ABCD", key, listOf(1L))))
        val back = Roster.open(r.seal(code), code)!!
        assertEquals(5L, back.epoch); assertEquals("D1", back.doctorId)
        assertEquals("محمد أحمد", back.students[0].name)        // TAB داخل الحقل يُستبدل بمسافة
        assertEquals(listOf(1L), back.students[0].courseIds)
        assertNull(Roster.open(r.seal(code), Codes.random()))
        assertNull(Roster.parse("garbage"))
    }

    @Test fun attendanceFileSealOpen() {
        val code = Codes.random()
        val f = AttendanceFile("D1", 5, listOf(ASession("s1", 1, 1000, 15)), listOf(ARecord("s1", "1001", 1500)),
            listOf(ABinding("1001", "ABCD", "04" + "ab".repeat(64))))
        val back = AttendanceFile.open(f.seal(code), code)!!
        assertEquals(f, back)
        assertNull(AttendanceFile.open(f.seal(code), Codes.random()))
    }

    @Test fun attendanceFileCountedRoundTripAndOldFormat() {
        val code = Codes.random()
        val f = AttendanceFile("D1", 5, listOf(ASession("s1", 1, 1000, 15, 38), ASession("s2", 1, 2000, 15)), emptyList(), emptyList())
        val back = AttendanceFile.open(f.seal(code), code)!!
        assertEquals(38, back.sessions[0].counted); assertEquals(-1, back.sessions[1].counted)
        // ملف قديم بلا حقل العدّ يُقرأ كـ -1
        val old = AttendanceFile.parse("UAT1\nD\tD1\t5\nS\ts1\t1\t1000\t15\n")!!
        assertEquals(-1, old.sessions[0].counted); assertEquals(0, old.sessions[0].part)
        // بحقل العدّ ورقم الجزء معًا (7 حقول): صالح
        val withPart = AttendanceFile.parse("UAT1\nD\tD1\t5\nS\ts1\t1\t1000\t15\t3\t9\n")!!
        assertEquals(3, withPart.sessions[0].counted); assertEquals(9, withPart.sessions[0].part)
        assertEquals(0, AttendanceFile.parse("UAT1\nD\tD1\t5\nS\ts1\t1\t1000\t15\t0\n")!!.sessions[0].counted)
        // حقل زائد عن المعروف (8 حقول): غير صالح
        assertNull(AttendanceFile.parse("UAT1\nD\tD1\t5\nS\ts1\t1\t1000\t15\t3\t9\t1\n"))
    }

    @Test fun countCheck() {
        assertEquals(CountCheck.Level.NONE, CountCheck.check(10, -1).level)
        assertEquals(CountCheck.Level.OK, CountCheck.check(35, 35).level)
        val more = CountCheck.check(38, 35); assertEquals(CountCheck.Level.MORE, more.level); assertEquals(3, more.diff)
        val less = CountCheck.check(30, 35); assertEquals(CountCheck.Level.LESS, less.level); assertEquals(-5, less.diff)
        assertEquals("مسجَّل 38 • معدود 35 • زاد 3: راجع القائمة وألغِ من ليس في القاعة", more.text)
    }

    // ---- الاقتران عبر QR والترحيل ----
    @Test fun pairingRoundTrip() {
        val code = Codes.random(); val mbox = Pairing.newMailbox()
        val info = Pairing.Info("https://demo-default-rtdb.firebaseio.com", mbox, "D1", code)
        assertEquals(info, Pairing.parse(Pairing.format(info)))
        assertEquals(code, Pairing.parse(Pairing.format(info).replace(code, Codes.format(code).lowercase()))?.code)
    }

    @Test fun pairingRejectsBadInput() {
        val code = Codes.random(); val mbox = Pairing.newMailbox()
        val txt = Pairing.format(Pairing.Info("https://x.firebaseio.com", mbox, "D1", code))
        assertNull(Pairing.parse("UAQ1|a"))
        assertNull(Pairing.parse(txt.replace("UAQ1", "UAQ2")))
        assertNull(Pairing.parse(txt.replace("https://", "http://")))
        assertNull(Pairing.parse(txt.replace(mbox, mbox.substring(1))))
        val bad = code.substring(0, 11) + (if (code[11] == '0') '1' else '0')
        assertNull(Pairing.parse(txt.replace(code, bad)))
    }

    @Test fun jsonStringCodec() {
        val weird = "سطر 1\nسطر \"2\"\t\\ end \u0001"
        assertEquals(weird, Json.unquote(Json.quote(weird)))
        assertNull(Json.unquote("123"))
        assertNull(Json.unquote("null"))
    }

    // ---- الجدول ----
    @Test fun scheduleTimeParsing() {
        assertEquals(8 * 60 + 30, Schedule.parseTime("8:30"))
        assertEquals(8 * 60 + 30, Schedule.parseTime("08:30"))
        assertEquals(8 * 60 + 30, Schedule.parseTime("\u0668:\u0663\u0660"))     // ٨:٣٠
        assertEquals(8 * 60 + 30, Schedule.parseTime("0830"))
        assertNull(Schedule.parseTime("25:00")); assertNull(Schedule.parseTime("8:5")); assertNull(Schedule.parseTime("abc"))
        assertEquals("08:05", Schedule.fmt(8 * 60 + 5))
    }

    @Test fun scheduleCurrentAndOverlap() {
        val a = RSlot(1, 0, 8 * 60, 9 * 60 + 30, "B1")
        val b = RSlot(2, 0, 9 * 60, 10 * 60)
        assertTrue(Schedule.overlaps(a, b))
        assertFalse(Schedule.overlaps(a, RSlot(2, 0, 9 * 60 + 30, 10 * 60)))   // تلاصق بلا تداخل
        assertFalse(Schedule.overlaps(a, RSlot(2, 1, 8 * 60, 9 * 60)))         // يوم آخر
        assertEquals(a, Schedule.current(listOf(a, b), 0, 7 * 60 + 50))        // قبل البدء بـ10 دقائق
        assertNull(Schedule.current(listOf(a), 0, 7 * 60 + 30))                // مبكر جدًا
        assertNull(Schedule.current(listOf(a), 0, 9 * 60 + 30))                // انتهى
        assertNull(Schedule.current(listOf(a), 3, 8 * 60 + 30))                // يوم آخر
    }

    @Test fun scheduleRosterAndStorageRoundTrip() {
        val slots = listOf(RSlot(1, 0, 480, 570, "قاعة 3"), RSlot(1, 2, 600, 690), RSlot(2, 4, 720, 810, "B1"))
        val r = Roster(7, "D1", "د. خالد", listOf(RCourse(1, "CS101", "برمجة", "A"), RCourse(2, "M1", "رياضيات", "B")), emptyList(), slots)
        assertEquals(slots, Roster.parse(r.toText())?.slots)
        val code = Codes.random()
        assertEquals(slots, Roster.open(r.seal(code), code)?.slots)
        assertEquals(listOf(RSlot(1, 0, 480, 570, "قاعة 3")), Schedule.decode(Schedule.encode(listOf(slots[0]))))
        assertEquals("الأحد 08:00–09:30 (قاعة 3)، الثلاثاء 10:00–11:30", Schedule.describeAll(slots, 1))
    }

    // ---- بديل QR (تحدٍّ وإجابة) ----
    @Test fun qrChallengeRoundTripAndRejects() {
        val r = req()
        val t = QrProtocol.challengeText(r)
        val back = QrProtocol.parseChallenge(t)!!
        assertArrayEquals(r.sessionId, back.sessionId); assertArrayEquals(r.nonce, back.nonce); assertEquals(r.timeMs, back.timeMs)
        assertNull(QrProtocol.parseChallenge(t.replace("UAC1", "UAC2")))
        assertNull(QrProtocol.parseChallenge(t.dropLast(4)))
        assertNull(QrProtocol.parseChallenge("hello"))
        assertNull(QrProtocol.parseChallenge("UAC1|!!!"))
    }

    @Test fun qrFullChallengeReplyFlow() {
        val code = Codes.random(); val tag = Codes.tag(code); val key = Kdf.studentKey(code)
        val kp = ec(); val pub = EcKey.rawFromX509(kp.public.encoded)!!
        var now = 1_000_000L
        val ch = QrChallenges(clock = { now })
        val sid = UUID.randomUUID()
        // الدكتور يصدر تحدّيًا ويعرضه
        val shown = QrProtocol.challengeText(ch.issue(sid))
        // الطالب يمسحه ويحسب الإجابة كما في خدمة البلوتوث
        val req = QrProtocol.parseChallenge(shown)!!
        val proof = Hmac.sha256(key, Protocol.bindMessage(tag, pub, req))
        val sig = sign(kp.private, Protocol.signedBytes(tag, pub, req))
        val replyTxt = QrProtocol.replyText(req.nonce, Protocol.AttendReply(tag, pub, proof, sig))
        // الدكتور يمسح الإجابة ويتحقق
        val pr = QrProtocol.parseReply(replyTxt)!!
        val mine = ch.find(pr.nonce)!!
        assertEquals(tag, pr.reply.tag)
        assertTrue(Hmac.equal(Hmac.sha256(key, Protocol.bindMessage(pr.reply.tag, pr.reply.pub, mine)), pr.reply.proof))
        assertTrue(SigVerifier.verify(pr.reply.pub, Protocol.signedBytes(pr.reply.tag, pr.reply.pub, mine), pr.reply.sig))
        assertTrue(replyTxt.length < 400)                                    // يتّسع في QR مقروء
        // nonce لم يصدر عن هاتف الدكتور => لا تحدّي
        assertNull(ch.find(ByteArray(16) { 7 }))
        // انتهاء الصلاحية بعد 60 ثانية
        now += 61_000
        assertNull(ch.find(pr.nonce))
    }

    @Test fun qrReplyRejectsTamperAndGarbage() {
        val code = Codes.random(); val tag = Codes.tag(code); val key = Kdf.studentKey(code)
        val kp = ec(); val pub = EcKey.rawFromX509(kp.public.encoded)!!
        val r = req()
        val txt = QrProtocol.replyText(r.nonce, Protocol.AttendReply(tag, pub, Hmac.sha256(key, Protocol.bindMessage(tag, pub, r)), sign(kp.private, Protocol.signedBytes(tag, pub, r))))
        assertNull(QrProtocol.parseReply(txt.replace("UAR1", "UAC1")))
        assertNull(QrProtocol.parseReply("UAR1|abc"))
        assertNull(QrProtocol.parseReply(txt.substringBeforeLast('|') + "|AAAA"))
        // تعديل حرف داخل الإجابة => إمّا يفشل التحليل أو يفشل إثبات الكود/التوقيع
        val i = txt.lastIndexOf('|') + 20
        val tampered = txt.substring(0, i) + (if (txt[i] == 'A') 'B' else 'A') + txt.substring(i + 1)
        val pr = QrProtocol.parseReply(tampered)
        if (pr != null) assertFalse(Hmac.equal(Hmac.sha256(key, Protocol.bindMessage(pr.reply.tag, pr.reply.pub, r)), pr.reply.proof) &&
            SigVerifier.verify(pr.reply.pub, Protocol.signedBytes(pr.reply.tag, pr.reply.pub, r), pr.reply.sig))
    }

    // ---- بلوتوث BLE ----
    @Test fun bleChallengeAndChunkedReplyRoundTrip() {
        val code = Codes.random(); val tag = Codes.tag(code); val key = Kdf.studentKey(code)
        val kp = ec(); val pub = EcKey.rawFromX509(kp.public.encoded)!!
        val ch = QrChallenges()
        val req0 = ch.issue(UUID.randomUUID())
        val req = BleProtocol.parseChallenge(BleProtocol.challengeBytes(req0))!!
        val proof = Hmac.sha256(key, Protocol.bindMessage(tag, pub, req))
        val sig = sign(kp.private, Protocol.signedBytes(tag, pub, req))
        val payload = BleProtocol.replyPayload(req.nonce, Protocol.AttendReply(tag, pub, proof, sig))
        val chunks = BleProtocol.chunk(payload, 18)               // أسوأ حالة: MTU افتراضي (20 بايت)
        assertTrue(chunks.all { it.size <= 20 })
        val asm = ProfileAssembler()
        var got: ByteArray? = null
        chunks.forEach { got = BleProtocol.add(asm, it) ?: got }
        assertArrayEquals(payload, got!!)
        val pr = BleProtocol.parseReplyPayload(got!!)!!
        val mine = ch.find(pr.nonce)!!
        assertTrue(Hmac.equal(Hmac.sha256(key, Protocol.bindMessage(pr.reply.tag, pr.reply.pub, mine)), pr.reply.proof))
        assertTrue(SigVerifier.verify(pr.reply.pub, Protocol.signedBytes(pr.reply.tag, pr.reply.pub, mine), pr.reply.sig))
    }

    @Test fun bleChunkingRejectsDisorderAndBadInput() {
        val payload = ByteArray(100) { it.toByte() }
        val chunks = BleProtocol.chunk(payload, 30)
        assertNull(BleProtocol.add(ProfileAssembler(), chunks[1]))            // لا تبدأ إلا من القطعة 0
        val asm2 = ProfileAssembler()
        assertNull(BleProtocol.add(asm2, chunks[0])); assertNull(BleProtocol.add(asm2, chunks[2]))   // خارج الترتيب
        assertNull(BleProtocol.add(ProfileAssembler(), byteArrayOf(0, 0, 1)))
        assertNull(BleProtocol.add(ProfileAssembler(), byteArrayOf(5, 2, 1)))
        assertNull(BleProtocol.parseChallenge(ByteArray(39)))
        assertNull(BleProtocol.parseReplyPayload(ByteArray(10)))
        assertNull(BleProtocol.parseReplyPayload(ByteArray(60)))
    }

    @Test fun bleResultEncoding() {
        val prof = byteArrayOf(1, 2, 3)
        val ok = BleProtocol.decodeResult(BleProtocol.encodeResult(Status.OK, prof))!!
        assertEquals(Status.OK, ok.status); assertArrayEquals(prof, ok.profile!!)
        val bad = BleProtocol.decodeResult(BleProtocol.encodeResult(Status.NOT_ENROLLED, prof))!!
        assertEquals(Status.NOT_ENROLLED, bad.status); assertNull(bad.profile)
        assertNull(BleProtocol.decodeResult(byteArrayOf(BleProtocol.NO_RESULT)))
        assertNull(BleProtocol.decodeResult(ByteArray(0)))
    }

    // ---------- الجداول وصندوق الطالب (التحقق من الكود عبر الإنترنت) ----------
    private fun te(id: Long, title: String, day: Int = 6, major: String = "أمن", level: String = "الأول",
                   sec: String = "", grp: String = "", kind: Int = TKind.NORMAL, prac: Boolean = false) =
        TEntry(id, major, level, day, 480, 600, title, kind, prac, sec, grp)

    @Test fun timetableMatchesSectionAndPracticalGroup() {
        val all = listOf(
            te(1, "عامة"), te(2, "شعبة أ", sec = "أ"), te(3, "شعبة ب", sec = "ب"),
            te(4, "عملي مج3", grp = "مج3", prac = true), te(5, "تعويضية", grp = "مج3", kind = TKind.MAKEUP),
            te(6, "تخصص آخر", major = "حاسب"), te(7, "مستوى آخر", level = "الثاني"), te(8, "بلا مستوى", level = "")
        )
        assertEquals(setOf(1L, 2L, 4L, 5L, 8L), Timetable.forStudent(all, "أمن", "الأول", "أ", "مج3").map { it.id }.toSet())
        assertEquals(setOf(1L, 3L, 8L), Timetable.forStudent(all, "أمن", "الأول", "ب", "مج1").map { it.id }.toSet())
        assertEquals(setOf(1L, 2L, 8L), Timetable.forStudent(all, "أمن", "الأول", "أ", "").map { it.id }.toSet())
        assertEquals(5, Timetable.forStudent(all, " أمن ", "الأول", "أ", "مج3").size)
    }

    @Test fun timetableEncodingRoundTripAndValidation() {
        val e = te(7, "عنوان\tبه\nتاب", kind = TKind.EXAM).copy(teacher = "د. أ", room = "قاعة/ (103)", note = "ملاحظة")
        val back = Timetable.decode(Timetable.encode(listOf(e, te(8, "ثانية", prac = true))))
        assertEquals(2, back.size)
        assertEquals("عنوان به تاب", back[0].title); assertEquals(TKind.EXAM, back[0].kind); assertEquals("قاعة/ (103)", back[0].room)
        assertTrue(back[1].practical)
        assertTrue(Timetable.decode("garbage\n1\t2").isEmpty())
        assertFalse(Timetable.valid(te(1, " ")))
        assertFalse(Timetable.valid(te(1, "x").copy(startMin = 600, endMin = 600)))
    }

    @Test fun studentBoxProvesCodeAndHidesContent() {
        val code = Codes.random(); val key = Hex.enc(Kdf.studentKey(code))
        val other = Hex.enc(Kdf.studentKey(Codes.random()))
        val pub = StudentPublic("1001", "محمد أحمد", "الهندسة", "أمن", "الأول", "أ", "مج3", listOf("CS101 برمجة (أ)"), listOf(te(1, "عامة"), te(2, "عملي", grp = "مج3", prac = true)))
        val blob = StudentBox.seal(key, pub)
        assertEquals(pub, StudentBox.openPublic(key, blob))
        assertNull(StudentBox.open(other, blob))                         // كود آخر لا يفتح الصندوق
        assertFalse(String(blob, Charsets.ISO_8859_1).contains("محمد"))  // مشفّر
        val bad = blob.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertNull(StudentBox.open(key, bad))                            // أي تعديل يُفشل الفتح
        assertEquals(32, StudentBox.boxId(key).length)
        assertTrue(StudentBox.boxId(key) != StudentBox.boxId(other))
        assertTrue(StudentBox.path(key).startsWith("m/") && StudentBox.path(key).endsWith("/stu"))
        assertNull(StudentPublic.parse("USP1\nP\tx"))
    }

    // ---------- استيراد كشوف المجموعات من PDF ----------
    private val sheetPage1 = """
الجمهورية اليمنية
2026/08/272027/2026 كـشـف حضــــور وغيـــــاب الطــــلب
المستوى الولاسم المادة /
 2
معمل الحاسوب 3
مهارات الحاسوب
08:00 ص - 10:00 ص  اسـم الستــاذ / الـوقـت /
عمـلي السبت اليوم /
123457 68
مـلحظــاتاســــم الطــــالبم
أبكالوريوس المن السيبراني  1
محمد عبدا الصلوي 1
دلل وضاح علي 2
Page 1 of 3طبع بواسطة مختص كلية الهندسة
"""
    private val sheetPage2 = """
المستوى الولاسم المادة /
معمل الحاسوب 3
12:00 م - 02:00 م  اسـم الستــاذ / الـوقـت /
عمـلي السبت اليوم /
ببكالوريوس المن السيبراني  1
سارة علي احمد 1
عبدالسلم خالد ناصر 2
"""
    private val sheetPage3 = """
12:00 م - 02:00 م  اسـم الستــاذ / الـوقـت /
عمـلي السبت اليوم /
ببكالوريوس المن السيبراني  1
عمر حفظ ا صالح 3
يوسف احمد علي 4
"""

    @Test fun pdfGroupSheetsAreParsedAndRepaired() {
        val sheets = GroupSheets.parse(listOf(sheetPage1, sheetPage2, sheetPage3))
        assertEquals(2, sheets.size)
        val a = sheets[0]; val b = sheets[1]
        assertEquals("أ", a.label); assertEquals("ب", b.label)
        assertEquals("الأمن السيبراني", a.major); assertEquals("الأول", a.level)
        assertEquals(6, a.day); assertEquals(480, a.startMin); assertEquals(600, a.endMin)
        assertEquals("السبت 08:00–10:00", a.whenText)
        assertEquals(listOf("محمد عبدالله الصلوي", "دلال وضاح علي"), a.names)
        assertEquals(2, a.fixed)
        assertEquals("السبت 12:00–14:00", b.whenText)
        assertEquals(listOf("سارة علي احمد", "عبدالسلام خالد ناصر", "عمر حفظ الله صالح", "يوسف احمد علي"), b.names)   // الصفحة الثالثة تكمل المجموعة
    }

    @Test fun pdfParserIgnoresNoiseAndWrongNumbers() {
        assertTrue(GroupSheets.parse(listOf("معمل الحاسوب 3\nأحمد محمد 1\nPage 1 of 1")).isEmpty())          // بلا ترويسة مجموعة
        val s = GroupSheets.parse(listOf("أبكالوريوس المن السيبراني  1\nمعمل الحاسوب 3\nأحمد محمد علي 1\nمحمد صالح ناصر 5\nخالد سعيد ناصر 2"))
        assertEquals(listOf("أحمد محمد علي", "خالد سعيد ناصر"), s[0].names)                                  // رقم 3 ورقم 5 لا يطابقان التسلسل
        // نمط آخر لاستخراج النص: الرقم قبل الاسم + علامات اتجاه
        val s2 = GroupSheets.parse(listOf("\u202Bأ 1 بكالوريوس المن السيبراني\u202C\n1 \u202Bآية فوزي عثمان\u202C\n2 رنا عبدالسلم علي"))
        assertEquals(listOf("آية فوزي عثمان", "رنا عبدالسلام علي"), s2[0].names)
    }

    @Test fun nameKeyMatchesNamesWithMissingGlyphs() {
        assertEquals(NameFix.key("محمد عبدا الصلوي"), NameFix.key("محمد عبدالله الصلوي"))
        assertEquals(NameFix.key("دلل وضاح"), NameFix.key("دلال وضاح"))
        assertEquals(NameFix.key("أحمد علي"), NameFix.key("احمد علي"))
        assertFalse(NameFix.key("علي محمد") == NameFix.key("عمر محمد"))
        assertEquals("الله", NameFix.fix("ا").first)
    }

    // ---------- كشف الحضور: المحاضرة = مادة + يوم ----------
    private fun at(day: Int, hour: Int): Long = java.util.Calendar.getInstance().apply { clear(); set(2026, 9, day, hour, 0, 0) }.timeInMillis

    @Test fun sheetCountsOneLecturePerDayEvenIfSessionRestarted() {
        val sessions = listOf("s2" to at(12, 12), "s1" to at(12, 10), "s3" to at(13, 10), "s4" to at(14, 9))   // s1+s2 في اليوم نفسه (إيقاف ثم تشغيل)
        val students = listOf("a" to "أحمد", "b" to "بشير", "c" to "جمال")
        val present = setOf("s1" to "a", "s2" to "a", "s2" to "b", "s3" to "a", "s4" to "c")
        val d = AttendanceSheet.build(sessions, students, present)
        assertEquals(3, d.total)
        assertEquals(listOf("s1", "s2"), d.lectures[0].sessionIds)
        assertEquals(listOf(1, 2, 3), d.lectures.map { it.no })
        assertEquals(listOf(true, true, false), d.rows[0].present)      // حضر في جلستي اليوم الأول: محاضرة واحدة
        assertEquals(2, d.rows[0].count)
        assertEquals(listOf(true, false, false), d.rows[1].present)     // حضر في الجلسة الثانية فقط من اليوم الأول
        assertEquals(listOf(false, false, true), d.rows[2].present)
        assertEquals(listOf(1, 2, 3), d.rows.map { it.no })
        assertEquals(66, AttendanceSheet.percent(d.rows[0].count, d.total))
        assertEquals(0, AttendanceSheet.percent(0, 0))
    }

    @Test fun sheetPagesAndEmptyCases() {
        assertEquals(1, AttendanceSheet.pageCount(0)); assertEquals(1, AttendanceSheet.pageCount(16))
        assertEquals(2, AttendanceSheet.pageCount(17)); assertEquals(3, AttendanceSheet.pageCount(33))
        val none = AttendanceSheet.build(emptyList(), listOf("a" to "x"), emptySet())
        assertEquals(0, none.total); assertEquals(1, none.pages); assertEquals(0, none.rows[0].count)
        val many = AttendanceSheet.build((1..20).map { "s$it" to at(1, 9) + it * 86_400_000L }, emptyList(), emptySet())
        assertEquals(20, many.total); assertEquals(2, many.pages)
    }

    // ---------- محاضرتان متتاليتان في اليوم نفسه (part) + تخطيط PDF ----------
    @Test fun consecutiveLecturesAreSeparateColumns() {
        val sessions = listOf("a" to at(12, 8), "b" to at(12, 10), "c" to at(13, 8))
        val d = AttendanceSheet.build(sessions, listOf("x" to "س", "y" to "ص"), setOf("a" to "x", "b" to "y", "c" to "x"), mapOf("b" to 1))
        assertEquals(3, d.total)
        assertEquals(listOf(0, 1, 0), d.lectures.map { it.part })
        assertEquals(d.lectures[0].dayKey, d.lectures[1].dayKey)
        assertEquals(listOf("", "(2)", ""), d.lectures.map { it.tag })
        assertEquals(listOf(true, false, true), d.rows[0].present)
        assertEquals(listOf(false, true, false), d.rows[1].present)
        // بلا parts: السلوك القديم (محاضرة = يوم)
        assertEquals(2, AttendanceSheet.build(sessions, emptyList(), emptySet()).total)
    }

    @Test fun sessionPartRoundTripAndOldFormat() {
        val f = AttendanceFile("D1", 5, listOf(ASession("s1", 1, 1000, 15), ASession("s2", 1, 2000, 15, 38, 1), ASession("s3", 1, 3000, 15, -1, 2)), emptyList(), emptyList())
        val back = AttendanceFile.parse(f.toText())!!
        assertEquals(listOf(0, 1, 2), back.sessions.map { it.part })
        assertEquals(listOf(-1, 38, -1), back.sessions.map { it.counted })
        assertEquals(0, AttendanceFile.parse("UAT1\nD\tD1\t5\nS\ts1\t1\t1000\t15\t7\n")!!.sessions[0].part)
    }

    @Test fun pdfLayoutPlan() {
        val rp = SheetLayout.rowsPerPage
        assertTrue(rp in 20..40)
        assertEquals(1, SheetLayout.plan(0, 0).size)
        assertEquals(1, SheetLayout.plan(rp, 16).size)
        assertEquals(2, SheetLayout.plan(rp + 1, 16).size)
        val p = SheetLayout.plan(60, 20)
        assertEquals(2 * ((60 + rp - 1) / rp), p.size)
        assertEquals(setOf(0 to 16, 16 to 20), p.map { it.lectureFrom to it.lectureTo }.toSet())
        assertEquals(60, p.filter { it.lecturePage == 0 }.sumOf { it.rowTo - it.rowFrom })
        val c = SheetLayout.columns(true)
        assertEquals(16, c.lectures.size)
        val notes = c.notes!!
        assertTrue(c.lectures.first().x1 <= c.name.x0 + 0.01f && notes.x1 <= c.lectures.last().x0 + 0.01f && notes.x0 >= SheetLayout.MARGIN - 0.01f)
    }
}

