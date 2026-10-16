/* UniAttendance PWA — نواة البروتوكول (تطابق Kotlin في core/ بايتًا ببايت).
 * تعمل في المتصفح (Safari على الآيفون) وفي Node للاختبار. تعتمد على WebCrypto فقط. */
const UA = (() => {
  'use strict';
  const subtle = globalThis.crypto.subtle;
  const te = new TextEncoder(), td = new TextDecoder();
  const utf8 = (s) => te.encode(s);
  const str = (b) => td.decode(b);

  // ---------- بايتات ----------
  const concat = (...parts) => {
    const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
    let o = 0; for (const p of parts) { out.set(p, o); o += p.length; }
    return out;
  };
  const hex = (b) => Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('');
  const unhex = (s) => {
    if (s.length % 2) throw new Error('odd hex length');
    const out = new Uint8Array(s.length / 2);
    for (let i = 0; i < out.length; i++) out[i] = parseInt(s.substr(2 * i, 2), 16);
    return out;
  };
  const b64 = (b) => { let s = ''; for (let i = 0; i < b.length; i += 0x8000) s += String.fromCharCode.apply(null, b.subarray(i, i + 0x8000)); return btoa(s); };
  const unb64 = (s) => Uint8Array.from(atob(s), (c) => c.charCodeAt(0));
  const b64u = (b) => b64(b).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  const unb64u = (s) => { let t = s.replace(/-/g, '+').replace(/_/g, '/'); while (t.length % 4) t += '='; return unb64(t); };
  const eqBytes = (a, b) => { if (a.length !== b.length) return false; let d = 0; for (let i = 0; i < a.length; i++) d |= a[i] ^ b[i]; return d === 0; };
  const rand = (n) => globalThis.crypto.getRandomValues(new Uint8Array(n));
  const longBytes = (v) => { const b = new Uint8Array(8); new DataView(b.buffer).setBigInt64(0, BigInt(v)); return b; };
  const readLong = (b, off = 0) => Number(new DataView(b.buffer, b.byteOffset + off, 8).getBigInt64(0));

  // ---------- الأكواد ----------
  const Codes = (() => {
    const ALPHABET = '0123456789ABCDEFGHJKMNPQRSTVWXYZ', LENGTH = 12, TAG_LEN = 4;
    const check = (body) => { let sum = 0; for (let i = 0; i < body.length; i++) sum += (2 * i + 1) * ALPHABET.indexOf(body[i]); return ALPHABET[sum % 32]; };
    /** يوحّد ما كتبه المستخدم؛ null إن كان غير صالح (كما في Kotlin). */
    const normalize = (input) => {
      let s = '';
      for (const ch of String(input)) {
        const cp = ch.codePointAt(0);
        let c;
        if (cp >= 48 && cp <= 57) c = ch;
        else if (cp >= 0x660 && cp <= 0x669) c = String(cp - 0x660);
        else if (cp >= 0x6f0 && cp <= 0x6f9) c = String(cp - 0x6f0);
        else if (/\p{L}/u.test(ch)) c = ch.toUpperCase();
        else continue;
        s += c === 'O' ? '0' : (c === 'I' || c === 'L') ? '1' : c;
      }
      if (s.length !== LENGTH || [...s].some((x) => ALPHABET.indexOf(x) < 0)) return null;
      return check(s.slice(0, -1)) === s[s.length - 1] ? s : null;
    };
    const format = (code) => code.match(/.{4}/g).join('-');
    const tag = (code) => code.substr(0, TAG_LEN);
    return { ALPHABET, LENGTH, TAG_LEN, normalize, format, tag, check };
  })();

  // ---------- مشتقات المفاتيح والتشفير ----------
  const pbkdf2 = async (passwordBytes, salt, iterations) => {
    const k = await subtle.importKey('raw', passwordBytes, 'PBKDF2', false, ['deriveBits']);
    return new Uint8Array(await subtle.deriveBits({ name: 'PBKDF2', hash: 'SHA-256', salt, iterations }, k, 256));
  };
  const hmac = async (keyBytes, data) => {
    const k = await subtle.importKey('raw', keyBytes, { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
    return new Uint8Array(await subtle.sign('HMAC', k, data));
  };
  const sha256 = async (data) => new Uint8Array(await subtle.digest('SHA-256', data));
  const STUDENT_ITER = 10000, DOCTOR_ITER = 100000;
  /** مفتاح الطالب المشتق من كوده (32 بايت). */
  const studentKey = (code) => pbkdf2(utf8(code), utf8('uniatt-student|' + Codes.tag(code)), STUDENT_ITER);

  const aesGcm = async (mode, keyBytes, iv, aad, data) => {
    const k = await subtle.importKey('raw', keyBytes, 'AES-GCM', false, [mode]);
    return new Uint8Array(await subtle[mode]({ name: 'AES-GCM', iv, additionalData: aad, tagLength: 128 }, k, data));
  };

  /** صندوق الدكتور: magic(4) | hintLen(1) | hint | salt(16) | iv(12) | ciphertext+tag — بمفتاح PBKDF2 من كود الدكتور. */
  const Box = (() => {
    const MAGIC_ROSTER = utf8('UAR1'), MAGIC_ATTEND = utf8('UAT1'), MAGIC_BACKUP = utf8('UAB1');
    const SALT = 16, IV = 12, TAG = 16;
    const seal = async (magic, hint, plain, code) => {
      const h = utf8(hint); if (h.length > 255) throw new Error('hint too long');
      const salt = rand(SALT), iv = rand(IV);
      const header = concat(magic, Uint8Array.of(h.length), h);
      const key = await pbkdf2(utf8(code), salt, DOCTOR_ITER);
      return concat(header, salt, iv, await aesGcm('encrypt', key, iv, header, plain));
    };
    const hint = (magic, blob) => {
      if (blob.length < 5 || !eqBytes(blob.subarray(0, 4), magic)) return null;
      const n = blob[4];
      if (blob.length < 5 + n + SALT + IV + TAG) return null;
      return str(blob.subarray(5, 5 + n));
    };
    /** null = ملف تالف أو كود خاطئ. */
    const open = async (magic, blob, code) => {
      try {
        if (hint(magic, blob) === null) return null;
        const n = blob[4], hdrEnd = 5 + n;
        const header = blob.subarray(0, hdrEnd), salt = blob.subarray(hdrEnd, hdrEnd + SALT);
        const iv = blob.subarray(hdrEnd + SALT, hdrEnd + SALT + IV), ct = blob.subarray(hdrEnd + SALT + IV);
        const key = await pbkdf2(utf8(code), salt, DOCTOR_ITER);
        return await aesGcm('decrypt', key, iv, header, ct);
      } catch (e) { return null; }
    };
    return { MAGIC_ROSTER, MAGIC_ATTEND, MAGIC_BACKUP, seal, open, hint };
  })();

  // ---------- المنحنى P-256: DER <-> r||s ----------
  const EcKey = (() => {
    const RAW_SIZE = 65;
    const derToRaw = (der) => {
      // SEQUENCE { INTEGER r, INTEGER s }
      let p = 0; if (der[p++] !== 0x30) throw new Error('bad der');
      let len = der[p++]; if (len & 0x80) { const n = len & 0x7f; len = 0; for (let i = 0; i < n; i++) len = (len << 8) | der[p++]; }
      const readInt = () => {
        if (der[p++] !== 0x02) throw new Error('bad der int');
        const l = der[p++]; let v = der.subarray(p, p + l); p += l;
        while (v.length > 32 && v[0] === 0) v = v.subarray(1);
        if (v.length > 32) throw new Error('int too long');
        const out = new Uint8Array(32); out.set(v, 32 - v.length); return out;
      };
      const r = readInt(), s = readInt();
      return concat(r, s);
    };
    const rawToDer = (raw) => {
      const enc = (v) => {
        let i = 0; while (i < v.length - 1 && v[i] === 0) i++;
        let x = v.subarray(i); if (x[0] & 0x80) x = concat(Uint8Array.of(0), x);
        return concat(Uint8Array.of(0x02, x.length), x);
      };
      const body = concat(enc(raw.subarray(0, 32)), enc(raw.subarray(32, 64)));
      return concat(Uint8Array.of(0x30, body.length), body);   // الطول < 128 دائمًا (≤ 70)
    };
    return { RAW_SIZE, derToRaw, rawToDer };
  })();

  /** التحقق من توقيع الجهاز (الدكتور): pubRaw نقطة خام 65 بايت، sig بصيغة DER. */
  const verifySig = async (pubRaw, data, sigDer) => {
    try {
      if (pubRaw.length !== EcKey.RAW_SIZE || pubRaw[0] !== 4) return false;
      const k = await subtle.importKey('raw', pubRaw, { name: 'ECDSA', namedCurve: 'P-256' }, false, ['verify']);
      return await subtle.verify({ name: 'ECDSA', hash: 'SHA-256' }, k, EcKey.derToRaw(sigDer), data);
    } catch (e) { return false; }
  };
  /** يولّد مفتاح جهاز جديدًا (المفتاح الخاص غير قابل للاستخراج). */
  const newDeviceKey = () => subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, false, ['sign', 'verify']);
  const devicePublicRaw = async (pair) => new Uint8Array(await subtle.exportKey('raw', pair.publicKey));
  const deviceSign = async (pair, data) =>
    EcKey.rawToDer(new Uint8Array(await subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, pair.privateKey, data)));

  // ---------- النتائج ----------
  const Status = {
    OK: 0, DUPLICATE: 1, NOT_ENROLLED: 2, BAD_SIGNATURE: 3, EXPIRED: 4, UNKNOWN_STUDENT: 5, KEY_MISMATCH: 6, BAD_PROOF: 7,
    ok: (c) => c === 0 || c === 1,
  };

  // ---------- الحضور (HMAC + توقيع الجهاز) ----------
  const Protocol = (() => {
    const LABEL_BIND = utf8('UA-BIND'), LABEL_SIGN = utf8('UA-SIGN');
    const SW_OK = Uint8Array.of(0x90, 0x00);
    const PROOF_SIZE = 32, MAX_SIG = 80;
    const core = (tag, pub, r) => concat(utf8(tag), pub, r.sessionId, r.nonce, longBytes(r.timeMs));
    const bindMessage = (tag, pub, r) => concat(LABEL_BIND, core(tag, pub, r));
    const signedBytes = (tag, pub, r) => concat(LABEL_SIGN, core(tag, pub, r));
    const buildAttendResponse = (rep) => concat(utf8(rep.tag), rep.pub, rep.proof, rep.sig, SW_OK);
    const parseAttendResponse = (resp) => {
      const fixed = Codes.TAG_LEN + EcKey.RAW_SIZE + PROOF_SIZE;
      if (resp.length < fixed + 8 + 2) return null;
      if (resp[resp.length - 2] !== 0x90 || resp[resp.length - 1] !== 0) return null;
      const body = resp.subarray(0, resp.length - 2), sig = body.subarray(fixed);
      if (sig.length > MAX_SIG) return null;
      return { tag: str(body.subarray(0, Codes.TAG_LEN)), pub: body.subarray(Codes.TAG_LEN, Codes.TAG_LEN + EcKey.RAW_SIZE),
        proof: body.subarray(Codes.TAG_LEN + EcKey.RAW_SIZE, fixed), sig };
    };
    return { bindMessage, signedBytes, buildAttendResponse, parseAttendResponse };
  })();

  // ---------- QR: تحدٍّ وإجابة ----------
  const QrProtocol = (() => {
    const CHALLENGE = 'UAC1', REPLY = 'UAR1', TTL_MS = 60000;
    const challengeText = (r) => CHALLENGE + '|' + b64u(concat(r.sessionId, r.nonce, longBytes(r.timeMs)));
    const parseChallenge = (text) => {
      try {
        const p = String(text).trim().split('|');
        if (p.length !== 2 || p[0] !== CHALLENGE) return null;
        const d = unb64u(p[1]); if (d.length !== 40) return null;
        return { sessionId: d.slice(0, 16), nonce: d.slice(16, 32), timeMs: readLong(d, 32) };
      } catch (e) { return null; }
    };
    const replyText = (nonce, rep) => REPLY + '|' + b64u(nonce) + '|' + b64u(Protocol.buildAttendResponse(rep));
    const parseReply = (text) => {
      try {
        const p = String(text).trim().split('|');
        if (p.length !== 3 || p[0] !== REPLY) return null;
        const nonce = unb64u(p[1]); if (nonce.length !== 16) return null;
        const reply = Protocol.parseAttendResponse(unb64u(p[2])); if (!reply) return null;
        return { nonce, reply };
      } catch (e) { return null; }
    };
    return { CHALLENGE, REPLY, TTL_MS, challengeText, parseChallenge, replyText, parseReply };
  })();

  /** إجابة الطالب على تحدٍّ: إثبات معرفة الكود (HMAC) + إثبات حيازة الجهاز (توقيع). */
  const studentReply = async (cred, devPair, req) => {
    const pub = await devicePublicRaw(devPair);
    const proof = await hmac(unhex(cred.keyHex), Protocol.bindMessage(cred.tag, pub, req));
    const sig = await deviceSign(devPair, Protocol.signedBytes(cred.tag, pub, req));
    return QrProtocol.replyText(req.nonce, { tag: cred.tag, pub, proof, sig });
  };

  /** تحقق الدكتور من إجابة طالب (نفس خطوات AttendanceEvaluator): يعيد {status, student}. */
  const evaluateReply = async (st, reply, req) => {
    const expected = await hmac(unhex(st.keyHex), Protocol.bindMessage(reply.tag, reply.pub, req));
    if (!eqBytes(expected, reply.proof)) return Status.BAD_PROOF;
    if (!(await verifySig(reply.pub, Protocol.signedBytes(reply.tag, reply.pub, req), reply.sig))) return Status.BAD_SIGNATURE;
    return Status.OK;
  };

  // ---------- جدول الطالب والأسبوع ----------
  const Schedule = (() => {
    const DAYS = ['الأحد', 'الإثنين', 'الثلاثاء', 'الأربعاء', 'الخميس', 'الجمعة', 'السبت'];
    const fmt = (m) => String(Math.floor(m / 60)).padStart(2, '0') + ':' + String(m % 60).padStart(2, '0');
    const valid = (s) => s.day >= 0 && s.day <= 6 && s.startMin >= 0 && s.startMin <= 1439 && s.endMin >= 1 && s.endMin <= 1440 && s.endMin > s.startMin;
    const describe = (s, withRoom = true) => `${DAYS[s.day] || '؟'} ${fmt(s.startMin)}–${fmt(s.endMin)}` + (withRoom && s.room ? ` (${s.room})` : '');
    const describeAll = (slots, courseId, withRoom = true) =>
      slots.filter((s) => s.courseId === courseId).sort((a, b) => a.day - b.day || a.startMin - b.startMin).map((s) => describe(s, withRoom)).join('، ');
    /** الموعد الجاري الآن (أو الذي يبدأ خلال earlyMin دقيقة). */
    const current = (slots, day, nowMin, earlyMin = 15) =>
      slots.filter((s) => s.day === day && nowMin >= s.startMin - earlyMin && nowMin < s.endMin).sort((a, b) => a.startMin - b.startMin)[0] || null;
    /** ترتيب موعد المادة في اليوم الذي يقع فيه الوقت (الجاري أو آخر موعد بدأ)، وإلا null. */
    const slotIndex = (slots, courseId, day, min, earlyMin = 15) => {
      const l = slots.filter((s) => s.courseId === courseId && s.day === day).sort((a, b) => a.startMin - b.startMin);
      const inside = l.findIndex((s) => min >= s.startMin - earlyMin && min < s.endMin);
      if (inside >= 0) return inside;
      let last = -1; l.forEach((s, i) => { if (s.startMin <= min) last = i; });
      return last >= 0 ? last : null;
    };
    /** الآن في موعد لاحق لنفس المادة غير موعد آخر جلسة اليوم ⇒ محاضرة متتالية حسب الجدول. */
    const isNextLecture = (slots, courseId, day, lastStartMin, nowMin) => {
      const a = slotIndex(slots, courseId, day, lastStartMin), b = slotIndex(slots, courseId, day, nowMin);
      return a !== null && b !== null && b > a;
    };
    return { DAYS, fmt, valid, describe, describeAll, current, slotIndex, isNextLecture };
  })();

  const Timetable = (() => {
    const KIND = { NORMAL: 0, MAKEUP: 1, EXAM: 2 };
    const kindName = (k) => (k === 1 ? 'تعويضية' : k === 2 ? 'اختبار' : 'عادية');
    const timeRange = (e) => `${Schedule.fmt(e.startMin)}–${Schedule.fmt(e.endMin === 1440 ? 1439 : e.endMin)}`;
    const audience = (e) => { const p = []; if (e.section) p.push('ش ' + e.section); if (e.group) p.push(e.group); return p.length ? p.join(' — ') : 'كامل'; };
    const valid = (e) => e.day >= 0 && e.day <= 6 && e.startMin >= 0 && e.startMin <= 1439 && e.endMin >= 1 && e.endMin <= 1440 && e.endMin > e.startMin && e.title.trim() && e.major.trim() && e.kind >= 0 && e.kind <= 2;
    const decode = (text) => (text || '').split('\n').filter((l) => l.trim()).map((l) => {
      const f = l.split('\t'); if (f.length < 14) return null;
      const e = { id: Number(f[0]), major: f[1], level: f[2], day: parseInt(f[3], 10), startMin: parseInt(f[4], 10), endMin: parseInt(f[5], 10),
        title: f[6], kind: parseInt(f[7], 10), practical: f[8] === '1', section: f[9], group: f[10], teacher: f[11], room: f[12], note: f[13] };
      return Number.isNaN(e.day + e.startMin + e.endMin + e.kind) || !valid(e) ? null : e;
    }).filter(Boolean);
    return { KIND, kindName, timeRange, audience, valid, decode };
  })();

  // ---------- «لا تحضير إلا حسب الجدول» (نسخة AttendancePolicy.kt بالمنطق والنصوص نفسها) ----------
  const AttendancePolicy = (() => {
    const EARLY_START_MIN = 0;            // 0 = لا تُفتح الجلسة إلا داخل الموعد نفسه
    const CLOCK_TOLERANCE_MS = 120000;    // فرق ساعة الهاتف عن الوقت الموثوق قبل اعتبارها معبوثًا بها (للتنبيه فقط)
    const MSG_NEEDS_SYNC = 'تعذّر التأكد من الوقت الحقيقي لهذا الهاتف. اتصل بالإنترنت مرة واحدة ثم أعد المحاولة.';
    const Reason = { NO_SLOTS: 'NO_SLOTS', OUTSIDE_SLOT: 'OUTSIDE_SLOT', TOO_LATE: 'TOO_LATE', NO_TIMETABLE: 'NO_TIMETABLE', OUTSIDE_LECTURE: 'OUTSIDE_LECTURE', CLOCK_NEEDS_SYNC: 'CLOCK_NEEDS_SYNC' };
    /** يوم الأسبوع (0 = الأحد) وثانية اليوم. tzOffsetMin = دقائق شرق UTC (للاختبار)؛ بدونها يُستعمل توقيت الجهاز. */
    const localTime = (ms, tzOffsetMin) => {
      let d, h, m, sec;
      if (tzOffsetMin === undefined || tzOffsetMin === null) { const x = new Date(ms); d = x.getDay(); h = x.getHours(); m = x.getMinutes(); sec = x.getSeconds(); }
      else { const x = new Date(ms + tzOffsetMin * 60000); d = x.getUTCDay(); h = x.getUTCHours(); m = x.getUTCMinutes(); sec = x.getUTCSeconds(); }
      const secondOfDay = h * 3600 + m * 60 + sec;
      return { day: d, secondOfDay, minute: Math.floor(secondOfDay / 60) };
    };
    const inside = (s, lt) => s.day === lt.day && lt.secondOfDay >= (s.startMin - EARLY_START_MIN) * 60 && lt.secondOfDay < s.endMin * 60;
    const remainingMinutes = (slot, nowMs, tz) => Math.floor(Math.max(0, slot.endMin * 60 - localTime(nowMs, tz).secondOfDay) / 60);
    const currentSlot = (slots, nowMs, tz) => {
      const lt = localTime(nowMs, tz);
      return slots.filter((s) => inside(s, lt)).sort((a, b) => a.startMin - b.startMin)[0] || null;
    };
    /** يعيد {ok, minutes, reason, message}. nowMs يجب أن يكون الوقت الموثوق لا ساعة الهاتف الخام. */
    const checkDoctorStart = (slots, courseId, nowMs, requestedMin, tz) => {
      const mine = slots.filter((s) => s.courseId === courseId);
      if (!mine.length) return { ok: false, minutes: 0, reason: Reason.NO_SLOTS, message: 'لا يوجد موعد لهذه المادة في الجدول، فلا يمكن بدء الحضور. راجع المسؤول.' };
      const lt = localTime(nowMs, tz);
      const slot = mine.filter((s) => inside(s, lt)).sort((a, b) => a.startMin - b.startMin)[0];
      if (!slot) return { ok: false, minutes: 0, reason: Reason.OUTSIDE_SLOT, message: `ليس الآن موعد هذه المادة. مواعيدها: ${Schedule.describeAll(mine, courseId, false)}. لا يبدأ الحضور إلا داخل الموعد.` };
      const left = remainingMinutes(slot, nowMs, tz);
      if (left < 1) return { ok: false, minutes: 0, reason: Reason.TOO_LATE, message: 'بقي أقل من دقيقة على نهاية موعد المحاضرة.' };
      return { ok: true, minutes: Math.min(Math.max(1, Math.floor(requestedMin) || 1), left), reason: null, message: '' };
    };
    /** هل يجوز للطالب إرسال حضور في الوقت timeMs (وقت التحدّي من هاتف الدكتور)؟ الاختبارات ليست محاضرات. */
    const checkStudent = (timetable, timeMs, tz) => {
      const lectures = (timetable || []).filter((e) => e.kind !== 2);
      if (!lectures.length) return { ok: false, reason: Reason.NO_TIMETABLE, message: 'لا يوجد جدول عندك بعد، فلا يُسجَّل الحضور. حدّث جدولك بالإنترنت ثم أعد المحاولة.' };
      const lt = localTime(timeMs, tz);
      const ok = lectures.some((e) => e.day === lt.day && lt.secondOfDay >= (e.startMin - EARLY_START_MIN) * 60 && lt.secondOfDay < e.endMin * 60);
      return ok ? { ok: true, reason: null, message: '' } : { ok: false, reason: Reason.OUTSIDE_LECTURE, message: 'ليس الآن وقت محاضرة في جدولك، فلا يُسجَّل الحضور.' };
    };
    return { EARLY_START_MIN, CLOCK_TOLERANCE_MS, MSG_NEEDS_SYNC, Reason, localTime, remainingMinutes, currentSlot, checkDoctorStart, checkStudent };
  })();

  /**
   * الساعة الموثوقة (نسخة TrustedClock.kt): وقت الخادم عند آخر مزامنة + المنقضي على ساعة رتيبة (performance.now).
   * تنبيه خاص بالمتصفح: لا ساعة رتيبة تبقى عبر إعادة تحميل الصفحة ولا عبر سكون الجهاز في Safari، فالمرساة في الذاكرة فقط:
   * بعد إعادة التحميل تلزم مزامنة جديدة، وعلى الواجهة إعادة المزامنة عند عودة الصفحة للظهور. (حماية أندرويد أقوى.)
   */
  const TrustedClock = (wall = () => Date.now(), mono = () => performance.now()) => {
    let anchor = null;
    const read = () => {
      if (!anchor) return { trusted: false, tampered: false, ms: 0, skewMs: 0 };
      const m = mono(); if (m < anchor.monoMs) return { trusted: false, tampered: false, ms: 0, skewMs: 0 };
      const t = anchor.serverMs + (m - anchor.monoMs), skew = wall() - t;
      return { trusted: true, ms: t, skewMs: skew, tampered: Math.abs(skew) > AttendancePolicy.CLOCK_TOLERANCE_MS };
    };
    return {
      read,
      anchorNow(serverMs) { anchor = { serverMs, monoMs: mono(), wallMs: wall() }; },
      /** fetchServerMs: async () => وقت الخادم. نعوّض نصف زمن الرحلة. الاستثناء يمرّ ولا تتغير المرساة. */
      async syncWith(fetchServerMs) {
        const t0 = mono(); const s = await fetchServerMs(); const t1 = mono();
        anchor = { serverMs: s + Math.max(0, t1 - t0) / 2, monoMs: t1, wallMs: wall() };
      },
      /** الوقت للعرض/التوقيع: الموثوق إن وُجد وإلا ساعة الجهاز (لا يُستعمل لقرار البدء). */
      now() { const r = read(); return r.trusted ? Math.round(r.ms) : wall(); },
    };
  };

  // ---------- صندوق الطالب (بيانات وجدول من الإدارة) ----------
  const StudentBox = (() => {
    const MAGIC = utf8('UAS1'), IV = 12;
    const boxId = async (keyHex) => hex(await sha256(utf8('uniatt-sbox-id|' + keyHex))).substring(0, 32);
    const path = async (keyHex) => 'm/' + (await boxId(keyHex)) + '/stu';
    const aesKey = (keyHex) => hmac(unhex(keyHex), utf8('uniatt-sbox-enc'));
    const seal = async (keyHex, plain) => {
      const iv = rand(IV);
      return concat(MAGIC, iv, await aesGcm('encrypt', await aesKey(keyHex), iv, MAGIC, plain));
    };
    const open = async (keyHex, blob) => {
      try {
        if (blob.length < 4 + IV + 16 || !eqBytes(blob.subarray(0, 4), MAGIC)) return null;
        return await aesGcm('decrypt', await aesKey(keyHex), blob.subarray(4, 4 + IV), MAGIC, blob.subarray(4 + IV));
      } catch (e) { return null; }
    };
    const parsePublic = (text) => {
      try {
        const lines = text.split('\n').filter((l) => l);
        if (lines[0] !== 'USP1') return null;
        let p = null; const courses = [], timetable = [];
        for (const line of lines.slice(1)) {
          if (line.startsWith('P\t')) p = line.split('\t');
          else if (line.startsWith('C\t')) courses.push(line.substring(2));
          else if (line.startsWith('T\t')) timetable.push(...Timetable.decode(line.substring(2)));
        }
        if (!p || p.length !== 8 || !p[1].trim()) return null;
        return { studentId: p[1], name: p[2], faculty: p[3], major: p[4], level: p[5], section: p[6], group: p[7], courses, timetable };
      } catch (e) { return null; }
    };
    const openPublic = async (keyHex, blob) => { const b = await open(keyHex, blob); return b ? parsePublic(str(b)) : null; };
    return { MAGIC, boxId, path, seal, open, parsePublic, openPublic };
  })();

  // ---------- ملف الكشف وملف الحضور (الدكتور) ----------
  const Roster = (() => {
    const parse = (text) => {
      try {
        const lines = text.split('\n').filter((l) => l);
        if (lines[0] !== 'UAR1') return null;
        let epoch = -1, did = '', dname = '', haveDoctor = false, bad = false;
        const courses = [], students = [], slots = [];
        for (const line of lines.slice(1)) {
          const f = line.split('\t');
          switch (f[0]) {
            case 'E': epoch = Number(f[1]); break;
            case 'D': did = f[1]; dname = f[2]; haveDoctor = true; break;
            case 'C': if (f.length === 5) courses.push({ id: Number(f[1]), code: f[2], name: f[3], section: f[4] }); else bad = true; break;
            case 'T': if (f.length === 6) {
              const sl = { courseId: Number(f[1]), day: parseInt(f[2], 10), startMin: parseInt(f[3], 10), endMin: parseInt(f[4], 10), room: f[5] };
              if (Schedule.valid(sl)) slots.push(sl); else bad = true;
            } else bad = true; break;
            case 'S': if (f.length === 10 && f[7].length === Codes.TAG_LEN && f[8].length === 64)
              students.push({ studentId: f[1], name: f[2], faculty: f[3], major: f[4], level: f[5], section: f[6], tag: f[7], keyHex: f[8],
                courseIds: f[9].split(';').filter((x) => x).map(Number) }); else bad = true; break;
            default: bad = true;
          }
        }
        return bad || epoch < 0 || !haveDoctor || !did.trim() ? null : { epoch, doctorId: did, doctorName: dname, courses, students, slots };
      } catch (e) { return null; }
    };
    const open = async (blob, doctorCode) => { const b = await Box.open(Box.MAGIC_ROSTER, blob, doctorCode); return b ? parse(str(b)) : null; };
    return { parse, open };
  })();

  const clean = (s) => String(s).replace(/[\t\n\r]/g, ' ').trim();
  const AttendanceFile = {
    toText: (f) => {
      let t = 'UAT1\n' + `D\t${clean(f.doctorId)}\t${f.epoch}\n`;
      for (const s of f.sessions) t += `S\t${s.sessionId}\t${s.courseId}\t${s.startedAt}\t${s.durationMin}` + ((s.counted >= 0 || s.part > 0) ? `\t${s.counted >= 0 ? s.counted : -1}` : '') + (s.part > 0 ? `\t${s.part}` : '') + '\n';
      for (const r of f.records) t += `A\t${r.sessionId}\t${clean(r.studentId)}\t${r.timestamp}\n`;
      for (const b of f.bindings) t += `B\t${clean(b.studentId)}\t${b.tag}\t${b.pubHex}\n`;
      return t;
    },
    parse: (text) => {
      try {
        const lines = text.split('\n').filter((l) => l);
        if (lines[0] !== 'UAT1') return null;
        let did = '', epoch = 0, bad = false; const sessions = [], records = [], bindings = [];
        for (const line of lines.slice(1)) {
          const f = line.split('\t');
          if (f[0] === 'D') { did = f[1]; epoch = Number(f[2]); }
          else if (f[0] === 'S') { if (f.length >= 5 && f.length <= 7) sessions.push({ sessionId: f[1], courseId: Number(f[2]), startedAt: Number(f[3]), durationMin: parseInt(f[4], 10), counted: f.length >= 6 ? parseInt(f[5], 10) : -1, part: f.length === 7 ? Math.max(0, parseInt(f[6], 10)) : 0 }); else bad = true; }
          else if (f[0] === 'A') { if (f.length === 4) records.push({ sessionId: f[1], studentId: f[2], timestamp: Number(f[3]) }); else bad = true; }
          else if (f[0] === 'B') { if (f.length === 4) bindings.push({ studentId: f[1], tag: f[2], pubHex: f[3] }); else bad = true; }
          else bad = true;
        }
        return bad || !did.trim() ? null : { doctorId: did, epoch, sessions, records, bindings };
      } catch (e) { return null; }
    },
    seal: (f, doctorCode) => Box.seal(Box.MAGIC_ATTEND, f.doctorId, utf8(AttendanceFile.toText(f)), doctorCode),
    open: async (blob, doctorCode) => { const b = await Box.open(Box.MAGIC_ATTEND, blob, doctorCode); return b ? AttendanceFile.parse(str(b)) : null; },
  };

  /** مقارنة المسجَّلين بعدد الدكتور الفعلي (كما في CountCheck). */
  const CountCheck = {
    check: (registered, counted) => {
      if (counted < 0) return { level: 'NONE', diff: 0, text: 'لم تُدخل عدد الحاضرين في القاعة بعد' };
      const d = registered - counted;
      if (d === 0) return { level: 'OK', diff: 0, text: `مسجَّل ${registered} = معدود ${counted} ✓ العدد مطابق` };
      if (d > 0) return { level: 'MORE', diff: d, text: `مسجَّل ${registered} • معدود ${counted} • زاد ${d}: راجع القائمة وألغِ من ليس في القاعة` };
      return { level: 'LESS', diff: d, text: `مسجَّل ${registered} • معدود ${counted} • ناقص ${-d}: قد يكون بعض من في القاعة لم يُسجَّل (حضّره يدويًا)` };
    },
  };

  // ---------- الترحيل: Firebase Realtime Database عبر REST ----------
  const Relay = (() => {
    const DEFAULT_URL = 'https://maggd-141d1-default-rtdb.europe-west1.firebasedatabase.app';
    class RelayError extends Error { constructor(code) { super('HTTP ' + code); this.code = code; } }
    class OfflineError extends Error { constructor() { super('offline'); } }
    const friendly = (e) => e instanceof OfflineError ? 'لا يوجد اتصال بالإنترنت'
      : e.code === 404 ? 'تعذّر العثور على قاعدة البيانات (404): عنوانها غير صحيح — غالبًا المنطقة.'
      : (e.code === 401 || e.code === 403) ? `قواعد Firebase تمنع الوصول (${e.code})`
      : e.code >= 500 ? `خطأ مؤقت في خادم Firebase (${e.code})` : (e.code ? `خطأ في الاتصال بالقاعدة (HTTP ${e.code})` : String(e.message || e));
    const normalizeUrl = (u) => {
      const t = String(u).trim().replace(/\/+$/, '');
      if (t.includes('|') || t.includes(' ')) return null;
      const local = t.startsWith('http://127.0.0.1') || t.startsWith('http://localhost');
      return (t.startsWith('https://') && t.length > 12) || local ? t : null;
    };
    class Client {
      constructor(base) { this.base = String(base).trim().replace(/\/+$/, ''); }
      async _fetch(path, init, query = '') {
        let r;
        try { r = await fetch(`${this.base}/${path}.json${query}`, init); } catch (e) { throw new OfflineError(); }
        if (!r.ok) throw new RelayError(r.status);
        return r;
      }
      async getString(path) {
        const t = (await (await this._fetch(path, { method: 'GET' })).text()).trim();
        if (t === 'null' || t === '') return null;
        const v = JSON.parse(t); return typeof v === 'string' ? v : null;
      }
      async getLong(path) { const s = await this.getString(path); const n = s === null ? NaN : Number(s); return Number.isNaN(n) ? null : n; }
      async putString(path, value) { await this._fetch(path, { method: 'PUT', headers: { 'Content-Type': 'application/json; charset=utf-8' }, body: JSON.stringify(value) }); }
      /** يكتب فقط إن كان المسار فارغًا (If-Match: null_etag، ذرّية على الخادم). false = مشغول (412). */
      async putStringIfAbsent(path, value) {
        let r;
        try { r = await fetch(`${this.base}/${path}.json`, { method: 'PUT', headers: { 'Content-Type': 'application/json; charset=utf-8', 'If-Match': 'null_etag' }, body: JSON.stringify(value) }); }
        catch (e) { throw new OfflineError(); }
        if (r.status === 412) return false;
        if (!r.ok) throw new RelayError(r.status);
        return true;
      }
      /** وقت الخادم بالميلي ثانية: يكتب ختم زمن من الخادم ({".sv":"timestamp"}) ويقرأ ما ثبّته (ساعة الهاتف لا تتدخل). المسار تحت m/. */
      async serverTimeMs(path) {
        const r = await this._fetch(path, { method: 'PUT', headers: { 'Content-Type': 'application/json; charset=utf-8' }, body: '{".sv":"timestamp"}' });
        const n = Number((await r.text()).trim()); if (!Number.isFinite(n) || n <= 0) throw new RelayError(502); return n;
      }
      async delete(path) { await this._fetch(path, { method: 'DELETE' }); }
      putBytes(path, bytes) { return this.putString(path, b64(bytes)); }
      async getBytes(path) { const s = await this.getString(path); if (s === null) return null; try { return unb64(s); } catch (e) { return null; } }
      async probe() { try { return (await fetch(`${this.base}/.json?shallow=true`)).status; } catch (e) { throw new OfflineError(); } }
    }
    const dbName = (url) => url.trim().replace(/^https?:\/\//, '').split('/')[0].split(':')[0].split('.')[0] || null;
    /** عنوان القاعدة الصحيح (المنطقة): 404 = خاطئ، وغيره = موجودة. */
    const find = async (current, probe) => {
      const name = dbName(current); if (!name) return null;
      const cands = [...new Set([current.trim().replace(/\/+$/, ''), `https://${name}.firebaseio.com`, `https://${name}.europe-west1.firebasedatabase.app`,
        `https://${name}.asia-southeast1.firebasedatabase.app`, `https://${name}.us-central1.firebasedatabase.app`])];
      for (const u of cands) { const code = await (probe ? probe(u) : new Client(u).probe()); if (code !== 404) return u; }
      return null;
    };
    class Mailbox {
      constructor(relay, id) { this.relay = relay; this.id = id; }
      rosterEpoch() { return this.relay.getLong(`m/${this.id}/rv`); }
      fetchRoster() { return this.relay.getBytes(`m/${this.id}/roster`); }
      publishAttendance(blob) { return this.relay.putBytes(`m/${this.id}/att`, blob); }
      publishAck(epoch) { return this.relay.putString(`m/${this.id}/ack`, String(epoch)); }
      serverTimeMs() { return this.relay.serverTimeMs(`m/${this.id}/clk`); }
    }
    const Pairing = {
      PREFIX: 'UAQ1',
      parse: (text) => {
        const p = String(text).trim().split('|');
        if (p.length !== 5 || p[0] !== 'UAQ1') return null;
        const url = normalizeUrl(p[1]); if (!url) return null;
        if (!/^[0-9a-f]{32}$/.test(p[2]) || !p[3].trim()) return null;
        const code = Codes.normalize(p[4]); if (!code) return null;
        return { relayUrl: url, mailbox: p[2], doctorId: p[3], code };
      },
    };
    return { DEFAULT_URL, RelayError, OfflineError, friendly, normalizeUrl, Client, find, Mailbox, Pairing };
  })();

  /**
   * ربط الكود بهاتف واحد (كما في DeviceClaim في Kotlin): أول هاتف يسجّل مفتاح جهازه العام في `dev` ويرفض غيره.
   * يعيد 'Ok' أو 'Taken'. لإعادة الربط بهاتف جديد يصدر المسؤول كودًا جديدًا (صندوق جديد).
   */
  const DeviceClaim = {
    TAKEN_STUDENT: 'هذا الكود مرتبط بهاتف آخر ولا يعمل على هذا الهاتف. إن غيّرت هاتفك أو فُقد، اطلب من الإدارة كودًا جديدًا.',
    TAKEN_DOCTOR: 'رمز الربط هذا استُخدم على هاتف آخر ولا يعمل هنا. اطلب من المسؤول «إلغاء الربط» ثم رمزًا جديدًا.',
    claim: async (client, boxPath, deviceHex) => {
      const path = `${boxPath}/dev`;
      if (await client.putStringIfAbsent(path, deviceHex)) return 'Ok';
      const cur = await client.getString(path);
      if (cur === null) return (await client.putStringIfAbsent(path, deviceHex)) ? 'Ok' : 'Taken';
      return cur.toLowerCase() === deviceHex.toLowerCase() ? 'Ok' : 'Taken';
    },
  };
  const claimStudentDevice = async (keyHex, deviceHex, relayUrl = Relay.DEFAULT_URL) =>
    DeviceClaim.claim(new Relay.Client(relayUrl), (await StudentBox.path(keyHex)).replace(/\/stu$/, ''), deviceHex);

  /** جلب بيانات الطالب: Ok(pub) | NotFound | Offline | Error(msg). */
  const fetchStudent = async (keyHex, relayUrl = Relay.DEFAULT_URL) => {
    let url = Relay.normalizeUrl(relayUrl); if (!url) return { kind: 'Error', msg: 'عنوان القاعدة غير صالح' };
    const path = await StudentBox.path(keyHex);
    try {
      let blob;
      try { blob = await new Relay.Client(url).getBytes(path); }
      catch (e) {
        if (!(e instanceof Relay.RelayError) || e.code !== 404) throw e;
        url = await Relay.find(url); if (!url) return { kind: 'Error', msg: Relay.friendly(e) };
        blob = await new Relay.Client(url).getBytes(path);
      }
      if (blob === null) return { kind: 'NotFound' };
      const pub = await StudentBox.openPublic(keyHex, blob);
      return pub ? { kind: 'Ok', pub, url } : { kind: 'Error', msg: 'بيانات الطالب على الخادم تالفة — راجع الإدارة' };
    } catch (e) {
      if (e instanceof Relay.OfflineError) return { kind: 'Offline' };
      return { kind: 'Error', msg: Relay.friendly(e) };
    }
  };

  // ---------- كشف الحضور (كالكشف الورقي: 16 محاضرة في الصفحة) ----------
  const AttendanceSheet = (() => {
    const PAGE = 16;
    const dayKey = (ms) => { const d = new Date(ms); return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`; };
    /** وسم المحاضرة المتتالية: 0 → «»، 1 → «(2)»… */
    const partTag = (part) => (part > 0 ? `(${part + 1})` : '');
    /** sessions: [{sessionId, startedAt, part?}] — students: [{studentId, name}] — present: Set("sid|studentId").
     *  المحاضرة = يوم + رقم المحاضرة المتتالية (part، الافتراضي 0)؛ الجلسات القديمة (بلا part) محاضرة = يوم. */
    const build = (sessions, students, present) => {
      const byKey = new Map();
      [...sessions].sort((a, b) => a.startedAt - b.startedAt || (a.sessionId < b.sessionId ? -1 : 1)).forEach((s) => {
        const part = Math.max(0, s.part || 0), day = dayKey(s.startedAt), k = day + '|' + part;
        if (!byKey.has(k)) byKey.set(k, { day, part, ids: [], firstStart: s.startedAt }); byKey.get(k).ids.push(s.sessionId);
      });
      const lectures = [...byKey.values()].sort((a, b) => (a.day < b.day ? -1 : a.day > b.day ? 1 : a.part - b.part))
        .map((l, i) => ({ no: i + 1, day: l.day, ids: l.ids, part: l.part, tag: partTag(l.part), firstStart: l.firstStart }));
      const rows = students.map((st, i) => {
        const marks = lectures.map((l) => l.ids.some((id) => present.has(id + '|' + st.studentId)));
        return { no: i + 1, studentId: st.studentId, name: st.name, present: marks, count: marks.filter(Boolean).length };
      });
      return { lectures, total: lectures.length, rows, pages: Math.max(1, Math.ceil(lectures.length / PAGE)) };
    };
    const percent = (n, total) => (total === 0 ? 0 : Math.floor((n * 100) / total));
    return { PAGE, dayKey, partTag, build, percent };
  })();

  return { utf8, str, concat, hex, unhex, b64, unb64, b64u, unb64u, eqBytes, rand, longBytes, readLong, Codes, pbkdf2, hmac, sha256, studentKey,
    Box, EcKey, verifySig, newDeviceKey, devicePublicRaw, deviceSign, Status, Protocol, QrProtocol, studentReply, evaluateReply,
    Schedule, Timetable, AttendancePolicy, TrustedClock, StudentBox, Roster, AttendanceFile, CountCheck, Relay, fetchStudent, DeviceClaim, claimStudentDevice, AttendanceSheet };
})();
if (typeof module !== 'undefined' && module.exports) module.exports = UA;
