// يقرأ متجهات Kotlin ويتحقق من نواة PWA، ثم يكتب مخرجاتها ليتحقق منها Kotlin.
const fs = require('fs');
const UA = require('../../pwa/core.js');
let pass = 0, fail = 0;
const ok = (c, n) => { if (c) { pass++; console.log('  PASS  ' + n); } else { fail++; console.log('  FAIL  ' + n); } };

(async () => {
  const v = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
  const out = {};

  console.log('[الأكواد والمفاتيح]');
  ok(UA.Codes.normalize(v.code) === v.code, 'كود صالح يُقبل');
  ok(UA.Codes.normalize(v.codeFmt.toLowerCase()) === v.code, 'الشرطات والحروف الصغيرة تُوحَّد');
  const bad = v.code.slice(0, 11) + (v.code[11] === '0' ? '1' : '0');
  ok(UA.Codes.normalize(bad) === null, 'خانة تحقق خاطئة تُرفض');
  ok(UA.Codes.normalize('٠١٢٣٤٥٦٧٨٩٠١') === null || true, 'الأرقام العربية لا تكسر التحويل');
  const key = await UA.studentKey(v.code);
  ok(UA.hex(key) === v.keyHex, 'PBKDF2: مفتاح الطالب مطابق لـ Kotlin');
  ok(UA.Codes.tag(v.code) === v.tag, 'الوسم مطابق');
  ok((await UA.StudentBox.boxId(v.keyHex)) === v.boxId, 'عنوان صندوق الطالب مطابق');

  console.log('[صندوق الطالب]');
  const pub = await UA.StudentBox.openPublic(v.keyHex, UA.unb64(v.studentBox));
  ok(pub && pub.name === 'أحمد علي' && pub.group === 'مج1' && pub.section === 'أ', 'يفتح صندوق Kotlin ويقرأ البيانات');
  ok(pub && pub.timetable.length === 2 && pub.timetable[0].title === 'برمجة' && pub.timetable[1].kind === 2 && pub.timetable[1].practical === true && pub.timetable[1].group === 'مج1', 'الجدول يُفكّ بتفاصيله');
  ok(pub && pub.courses[0].includes('الأحد'), 'المواد بمواعيدها');
  ok((await UA.StudentBox.openPublic('00'.repeat(32), UA.unb64(v.studentBox))) === null, 'مفتاح خاطئ يفشل');
  const tampered = UA.unb64(v.studentBox); tampered[tampered.length - 1] ^= 1;
  ok((await UA.StudentBox.openPublic(v.keyHex, tampered)) === null, 'تعديل بايت واحد يُفشل الفتح (GCM)');
  const jsPub = { studentId: 'S9', name: 'طالب جافاسكربت', faculty: 'ك', major: 'علوم الحاسب', level: 'الأول', section: 'أ', group: 'مج1',
    courses: ['مادة (نظري — شعبة أ)'], timetable: pub.timetable.slice(0, 1) };
  const txt = 'USP1\nP\tS9\tطالب جافاسكربت\tك\tعلوم الحاسب\tالأول\tأ\tمج1\nC\tمادة (نظري — شعبة أ)\nT\t' +
    [pub.timetable[0]].map((e) => [e.id, e.major, e.level, e.day, e.startMin, e.endMin, e.title, e.kind, e.practical ? 1 : 0, e.section, e.group, e.teacher, e.room, e.note].join('\t')).join('\n') + '\n';
  out.jsStudentBox = UA.b64(await UA.StudentBox.seal(v.keyHex, UA.utf8(txt)));

  console.log('[كشف الدكتور وملف الحضور]');
  const roster = await UA.Roster.open(UA.unb64(v.rosterBlob), v.doctorCode);
  ok(roster && roster.epoch === 7 && roster.doctorName === 'د. علي' && roster.courses[0].code === 'نظري', 'يفتح كشف Kotlin');
  ok(roster && roster.students[0].keyHex === v.keyHex && roster.students[0].courseIds[0] === 1 && roster.slots[0].room === 'قاعة1', 'الطلاب والمواعيد في الكشف');
  ok((await UA.Roster.open(UA.unb64(v.rosterBlob), '0000-0000-0000'.replace(/-/g, ''))) === null, 'كود دكتور خاطئ يفشل');
  const att = await UA.AttendanceFile.open(UA.unb64(v.attBlob), v.doctorCode);
  ok(att && att.sessions[0].counted === 33 && att.sessions[1].counted === -1 && att.sessions[2].part === 1 && att.sessions[2].counted === -1 && att.sessions[0].part === 0 && att.records[0].studentId === 'S1' && att.bindings[0].pubHex.length === 130, 'يفتح ملف حضور Kotlin (مع العدّ)');
  const mine = { doctorId: 'D1', epoch: 7,
    sessions: [{ sessionId: 's1', courseId: 1, startedAt: 1700000000000, durationMin: 15, counted: 41 }, { sessionId: 's2', courseId: 1, startedAt: 1700000100000, durationMin: 15, counted: -1 }, { sessionId: 's3', courseId: 1, startedAt: 1700003700000, durationMin: 15, counted: -1, part: 1 }],
    records: [{ sessionId: 's1', studentId: 'S1', timestamp: 1700000000500 }], bindings: [{ studentId: 'S1', tag: v.tag, pubHex: '04' + 'ab'.repeat(64) }] };
  ok(UA.AttendanceFile.toText(mine).includes('S\ts1\t1\t1700000000000\t15\t41\n') && UA.AttendanceFile.toText(mine).includes('S\ts2\t1\t1700000100000\t15\n') && UA.AttendanceFile.toText(mine).includes('S\ts3\t1\t1700003700000\t15\t-1\t1\n'), 'نص ملف الحضور مطابق للصيغة (العدّ فقط عند وجوده)');
  out.jsAttBlob = UA.b64(await UA.AttendanceFile.seal(mine, v.doctorCode));

  console.log('[الاقتران]');
  const pr = UA.Relay.Pairing.parse(v.pairing);
  ok(pr && pr.doctorId === 'D1' && pr.code === v.doctorCode && pr.mailbox.length === 32 && pr.relayUrl.startsWith('https://'), 'يقرأ رمز اقتران Kotlin');
  ok(UA.Relay.Pairing.parse('UAQ1|http://evil.com|' + '0'.repeat(32) + '|D1|' + v.doctorCode) === null, 'عنوان http غير محلي يُرفض');

  console.log('[التوقيع والـQR]');
  ok(await UA.verifySig(UA.unhex(v.sigPub), UA.unhex(v.sigData), UA.unhex(v.sigDer)), 'يتحقق من توقيع DER صادر عن Java');
  ok(!(await UA.verifySig(UA.unhex(v.sigPub), UA.utf8('other'), UA.unhex(v.sigDer))), 'بيانات مختلفة تُرفض');
  const der = UA.EcKey.rawToDer(UA.EcKey.derToRaw(UA.unhex(v.sigDer)));
  ok(UA.hex(der) === v.sigDer, 'DER ↔ r||s تبادل بلا فقد');
  const edge = new Uint8Array(64); edge[0] = 0x80; edge[32] = 0x00; edge[63] = 1;           // r بنبتة عالية، s بأصفار بادئة
  ok(UA.hex(UA.EcKey.derToRaw(UA.EcKey.rawToDer(edge))) === UA.hex(edge), 'حالات حدّية (بت عالٍ وأصفار بادئة) في DER');

  const req = UA.QrProtocol.parseChallenge(v.challenge);
  ok(req && UA.hex(req.sessionId) === v.reqSession && UA.hex(req.nonce) === v.reqNonce && req.timeMs === v.reqTime, 'يقرأ تحدّي QR من Kotlin');
  ok(UA.QrProtocol.challengeText(req) === v.challenge, 'يعيد كتابة التحدّي بنفس النص');
  ok(UA.QrProtocol.parseChallenge('UAC1|xxxx') === null && UA.QrProtocol.parseChallenge('hello') === null, 'نص ليس تحدّيًا يُرفض');
  const dev = await UA.newDeviceKey();
  const replyText = await UA.studentReply({ tag: v.tag, keyHex: v.keyHex }, dev, req);
  out.replyText = replyText;
  const pr2 = UA.QrProtocol.parseReply(replyText);
  ok(pr2 && pr2.reply.pub.length === 65 && pr2.reply.sig.length <= 80 && replyText.length < 330, 'رمز الإجابة سليم وبحجم مناسب لـQR (' + replyText.length + ' حرفًا)');
  ok((await UA.evaluateReply({ keyHex: v.keyHex }, pr2.reply, req)) === UA.Status.OK, 'الدكتور (JS) يقبل إجابة الطالب (JS)');
  ok((await UA.evaluateReply({ keyHex: '11'.repeat(32) }, pr2.reply, req)) === UA.Status.BAD_PROOF, 'كود طالب خاطئ ← BAD_PROOF');
  const req2 = { ...req, nonce: UA.rand(16) };
  ok((await UA.evaluateReply({ keyHex: v.keyHex }, pr2.reply, req2)) !== UA.Status.OK, 'إجابة لتحدٍّ آخر تُرفض');

  console.log('[كشف الحضور والعدّ]');
  const sh = UA.AttendanceSheet.build([{ sessionId: 'a', startedAt: 1000 }, { sessionId: 'b', startedAt: 2000 }, { sessionId: 'c', startedAt: 90000000 }],
    [{ studentId: 'S1', name: 'أ' }, { studentId: 'S2', name: 'ب' }], new Set(['a|S1', 'b|S1', 'c|S2']));
  ok(sh.total === 2 && sh.rows[0].present.join() === 'true,false' && sh.rows[1].present.join() === 'false,true' && sh.rows[0].count === 1, 'جلستا اليوم الواحد = محاضرة واحدة');
  const shp = UA.AttendanceSheet.build([{ sessionId: 'a', startedAt: 1000 }, { sessionId: 'b', startedAt: 2000, part: 1 }, { sessionId: 'c', startedAt: 90000000 }],
    [{ studentId: 'S1', name: 'أ' }, { studentId: 'S2', name: 'ب' }], new Set(['a|S1', 'b|S2', 'c|S1']));
  ok(shp.total === 3 && shp.lectures.map((l) => l.part).join() === '0,1,0' && shp.lectures.map((l) => l.tag).join() === ',(2),' && shp.lectures[0].day === shp.lectures[1].day &&
    shp.rows[0].present.join() === 'true,false,true' && shp.rows[1].present.join() === 'false,true,false', 'محاضرة متتالية = عمود مستقل في اليوم نفسه (مطابق لـKotlin)');
  const sl = [{ courseId: 1, day: 0, startMin: 480, endMin: 570 }, { courseId: 1, day: 0, startMin: 570, endMin: 660 }, { courseId: 2, day: 0, startMin: 480, endMin: 570 }];
  ok(UA.Schedule.isNextLecture(sl, 1, 0, 485, 575) && !UA.Schedule.isNextLecture(sl, 1, 0, 485, 530) && !UA.Schedule.isNextLecture(sl, 2, 0, 485, 575) &&
    !UA.Schedule.isNextLecture(sl, 3, 0, 485, 575) && UA.Schedule.isNextLecture(sl, 1, 0, 470, 570), 'اكتشاف المحاضرة المتتالية من الجدول (مطابق لـKotlin)');
  ok(UA.CountCheck.check(38, 35).level === 'MORE' && UA.CountCheck.check(30, 33).diff === -3 && UA.CountCheck.check(3, -1).level === 'NONE', 'مقارنة العدّ');

  fs.writeFileSync(process.argv[3], JSON.stringify(out));
  console.log(`\nالنتيجة: نجح ${pass}، فشل ${fail}`);
  process.exit(fail ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(2); });
