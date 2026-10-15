const nc = require('crypto'), assert = require('assert'), UA = require('../shared/core.js');
(async () => {
  // الأكواد: توليد بنفس خوارزمية أندرويد ثم تطبيعها
  const A = '0123456789ABCDEFGHJKMNPQRSTVWXYZ'; let body = ''; for (let i = 0; i < 11; i++) body += A[nc.randomInt(32)];
  const code = body + UA.Codes.check(body);
  assert.strictEqual(UA.Codes.normalize(UA.Codes.format(code).toLowerCase()), code);
  assert.strictEqual(UA.Codes.normalize('٠١٢٣-٤٥٦٧-٨٩٠١'), null);          // كود غير صالح لا يُقبل
  const bad = code.slice(0, 5) + (code[5] === '0' ? '1' : '0') + code.slice(6);
  assert.strictEqual(UA.Codes.normalize(bad), null);                           // خطأ خانة واحدة يُكشف

  // مفتاح الطالب مقابل PBKDF2 من Node (نفس معاملات Kdf.studentKey)
  const key = await UA.studentKey(code), kh = UA.hex(key);
  assert.strictEqual(kh, nc.pbkdf2Sync(code, 'uniatt-student|' + code.slice(0, 4), 10000, 32, 'sha256').toString('hex'));

  // صندوق الطالب: نختمه بتنفيذ Node مستقل (كما يفعل Kotlin) ويفتحه core.js
  const boxId = nc.createHash('sha256').update('uniatt-sbox-id|' + kh).digest('hex').slice(0, 32);
  assert.strictEqual(await UA.StudentBox.boxId(kh), boxId);
  const aes = nc.createHmac('sha256', key).update('uniatt-sbox-enc').digest();
  const tt = ['7', 'هندسة', '2', '1', '480', '600', 'برمجة', '0', '0', 'أ', '', 'د. علي', 'قاعة 3', ''].join('\t');
  const text = 'USP1\nP\tS1\tأحمد\tكلية\tهندسة\t2\tأ\tمج1\nC\tبرمجة\nT\t' + tt + '\n';
  const iv = nc.randomBytes(12), ci = nc.createCipheriv('aes-256-gcm', aes, iv); ci.setAAD(Buffer.from('UAS1'));
  const ct = Buffer.concat([ci.update(text, 'utf8'), ci.final(), ci.getAuthTag()]);
  const blob = new Uint8Array(Buffer.concat([Buffer.from('UAS1'), iv, ct]));
  const pub = UA.parseStudentPublic(new TextDecoder().decode(await UA.StudentBox.open(kh, blob)));
  assert.strictEqual(pub.name, 'أحمد'); assert.strictEqual(pub.timetable[0].room, 'قاعة 3'); assert.strictEqual(pub.courses[0], 'برمجة');
  const bad2 = Uint8Array.from(blob); bad2[20] ^= 1; assert.strictEqual(await UA.StudentBox.open(kh, bad2), null);
  // العكس: seal من core.js يفتحه Node
  const sb = Buffer.from(await UA.StudentBox.seal(kh, new TextEncoder().encode('x')));
  const d = nc.createDecipheriv('aes-256-gcm', aes, sb.subarray(4, 16)); d.setAAD(Buffer.from('UAS1')); d.setAuthTag(sb.subarray(sb.length - 16));
  assert.strictEqual(Buffer.concat([d.update(sb.subarray(16, sb.length - 16)), d.final()]).toString(), 'x');

  // QR: تحدٍّ ← إجابة ← تحقق كما يفعل Kotlin (HMAC + ECDSA بتوقيع DER)
  const req = { sessionId: nc.randomBytes(16), nonce: nc.randomBytes(16), timeMs: Date.now() };
  const ch = UA.QrProtocol.parseChallenge(UA.QrProtocol.challengeText(req));
  assert.deepStrictEqual(Buffer.from(ch.nonce), req.nonce); assert.strictEqual(ch.timeMs, req.timeMs);
  const kp = await UA.DeviceKey.generate(), tag = code.slice(0, 4);
  for (let i = 0; i < 40; i++) {                       // توقيعات كثيرة لاختبار حالات الأصفار/البت العالي في DER
    const r = await UA.QrProtocol.reply({ tag, keyBytes: key }, kp, ch), p = r.split('|');
    assert.strictEqual(p[0], 'UAR1'); const body = Buffer.from(UA.unb64u(p[2]));
    assert.deepStrictEqual(body.subarray(body.length - 2), Buffer.from([0x90, 0]));
    const t = body.subarray(0, 4).toString(), pubRaw = body.subarray(4, 69), proof = body.subarray(69, 101), sig = body.subarray(101, body.length - 2);
    assert.ok(sig.length <= 80 && t === tag && pubRaw[0] === 4);
    const msg = (l) => Buffer.concat([Buffer.from(l), Buffer.from(tag), pubRaw, req.sessionId, req.nonce, Buffer.from(UA.longBytes(req.timeMs))]);
    assert.ok(nc.timingSafeEqual(nc.createHmac('sha256', key).update(msg('UA-BIND')).digest(), proof));
    const X509 = Buffer.from('3059301306072a8648ce3d020106082a8648ce3d030107034200', 'hex');
    const pk = nc.createPublicKey({ key: Buffer.concat([X509, pubRaw]), format: 'der', type: 'spki' });
    assert.ok(nc.createVerify('SHA256').update(msg('UA-SIGN')).verify(pk, sig));
  }
  console.log('core.js: all interop checks passed');
})().catch(e => { console.error(e); process.exit(1); });
