/* UniAttendance PWA core — متوافق بايتًا ببايت مع core/ في أندرويد (Protocol/QrProtocol/StudentBox/Codes). */
(function (root) {
  'use strict';
  const C = root.crypto || (typeof require !== 'undefined' ? require('crypto').webcrypto : null);
  const S = C.subtle, enc = new TextEncoder(), dec = new TextDecoder();
  const ALPHABET = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';
  const DEFAULT_RELAY = 'https://maggd-141d1-default-rtdb.europe-west1.firebasedatabase.app';

  const hex = b => Array.from(b, x => x.toString(16).padStart(2, '0')).join('');
  const unhex = s => Uint8Array.from(s.match(/../g) || [], h => parseInt(h, 16));
  const cat = (...a) => { const o = new Uint8Array(a.reduce((n, x) => n + x.length, 0)); let p = 0; for (const x of a) { o.set(x, p); p += x.length; } return o; };
  const b64 = b => btoa(String.fromCharCode(...b));
  const unb64 = s => Uint8Array.from(atob(s), c => c.charCodeAt(0));
  const b64u = b => b64(b).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  const unb64u = s => unb64(s.replace(/-/g, '+').replace(/_/g, '/') + '==='.slice((s.length + 3) % 4));
  const longBytes = ms => { const b = new Uint8Array(8); new DataView(b.buffer).setBigInt64(0, BigInt(ms)); return b; };
  const ascii = s => enc.encode(s);

  // ---- الأكواد ----
  const Codes = {
    check(body) { let s = 0; [...body].forEach((c, i) => s += (2 * i + 1) * ALPHABET.indexOf(c)); return ALPHABET[s % 32]; },
    normalize(input) {
      let s = '';
      for (const ch of String(input)) {
        let c = ch;
        if (/\p{Nd}/u.test(c)) c = String(parseInt(c.normalize('NFKD'), 10) >= 0 ? parseInt(c.normalize('NFKD'), 10) : arabicDigit(c));
        else if (/\p{L}/u.test(c)) c = c.toUpperCase(); else continue;
        s += c === 'O' ? '0' : (c === 'I' || c === 'L') ? '1' : c;
      }
      if (s.length !== 12 || [...s].some(c => ALPHABET.indexOf(c) < 0)) return null;
      return Codes.check(s.slice(0, 11)) === s[11] ? s : null;
    },
    tag: c => c.slice(0, 4),
    format: c => c.match(/.{4}/g).join('-'),
  };
  function arabicDigit(c) { const n = c.charCodeAt(0); return n >= 0x660 && n <= 0x669 ? n - 0x660 : n >= 0x6f0 && n <= 0x6f9 ? n - 0x6f0 : 0; }

  // ---- التشفير ----
  async function hmac(keyBytes, data) {
    const k = await S.importKey('raw', keyBytes, { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
    return new Uint8Array(await S.sign('HMAC', k, data));
  }
  async function pbkdf2(pass, salt, iter) {
    const k = await S.importKey('raw', enc.encode(pass), 'PBKDF2', false, ['deriveBits']);
    return new Uint8Array(await S.deriveBits({ name: 'PBKDF2', hash: 'SHA-256', salt, iterations: iter }, k, 256));
  }
  const sha256 = async d => new Uint8Array(await S.digest('SHA-256', d));
  const studentKey = async code => pbkdf2(code, ascii('uniatt-student|' + Codes.tag(code)), 10000);

  const StudentBox = {
    MAGIC: ascii('UAS1'),
    async boxId(keyHex) { return hex(await sha256(ascii('uniatt-sbox-id|' + keyHex))).slice(0, 32); },
    async path(keyHex) { return 'm/' + await StudentBox.boxId(keyHex) + '/stu'; },
    async aesKey(keyHex, usage) {
      const raw = await hmac(unhex(keyHex), ascii('uniatt-sbox-enc'));
      return S.importKey('raw', raw, 'AES-GCM', false, usage);
    },
    async open(keyHex, blob) {
      try {
        if (blob.length < 32 || dec.decode(blob.slice(0, 4)) !== 'UAS1') return null;
        const k = await StudentBox.aesKey(keyHex, ['decrypt']);
        return new Uint8Array(await S.decrypt({ name: 'AES-GCM', iv: blob.slice(4, 16), additionalData: StudentBox.MAGIC, tagLength: 128 }, k, blob.slice(16)));
      } catch (e) { return null; }
    },
    async seal(keyHex, plain) {
      const iv = C.getRandomValues(new Uint8Array(12)), k = await StudentBox.aesKey(keyHex, ['encrypt']);
      return cat(StudentBox.MAGIC, iv, new Uint8Array(await S.encrypt({ name: 'AES-GCM', iv, additionalData: StudentBox.MAGIC, tagLength: 128 }, k, plain)));
    },
  };

  // ---- الجدول وبيانات الطالب ----
  const Timetable = {
    decode(text) {
      return (text || '').split('\n').filter(l => l.trim()).map(l => {
        const f = l.split('\t'); if (f.length < 14) return null;
        const e = { id: f[0], major: f[1], level: f[2], day: +f[3], startMin: +f[4], endMin: +f[5], title: f[6], kind: +f[7], practical: f[8] === '1', section: f[9], group: f[10], teacher: f[11], room: f[12], note: f[13] };
        return e.day >= 0 && e.day <= 6 && e.endMin > e.startMin && e.title.trim() ? e : null;
      }).filter(Boolean);
    },
    fmt: m => String(Math.floor(m / 60)).padStart(2, '0') + ':' + String(m % 60).padStart(2, '0'),
  };
  function parseStudentPublic(text) {
    const lines = text.split('\n').filter(Boolean);
    if (lines[0] !== 'USP1') return null;
    let p = null; const courses = [], timetable = [];
    for (const l of lines.slice(1)) {
      if (l.startsWith('P\t')) p = l.split('\t');
      else if (l.startsWith('C\t')) courses.push(l.slice(2));
      else if (l.startsWith('T\t')) timetable.push(...Timetable.decode(l.slice(2)));
    }
    if (!p || p.length !== 8 || !p[1].trim()) return null;
    return { studentId: p[1], name: p[2], faculty: p[3], major: p[4], level: p[5], section: p[6], group: p[7], courses, timetable };
  }

  // ---- الترحيل (Firebase REST) ----
  async function relayGet(base, path) {
    const r = await fetch(base.replace(/\/+$/, '') + '/' + path + '.json');
    if (!r.ok) { const e = new Error('HTTP ' + r.status); e.code = r.status; throw e; }
    const t = (await r.text()).trim();
    if (!t || t === 'null') return null;
    const v = JSON.parse(t); return typeof v === 'string' ? unb64(v) : null;
  }
  function friendly(code) {
    if (code === 404) return 'عنوان قاعدة البيانات غير صحيح (404).';
    if (code === 401 || code === 403) return 'قواعد Firebase تمنع الوصول (' + code + ') — انشر Rules كما في README.';
    return 'خطأ في الاتصال بالقاعدة (' + code + ').';
  }
  /** يعيد {ok, pub} أو {notFound} أو {offline} أو {error}. */
  async function fetchStudent(keyHex, relay = DEFAULT_RELAY) {
    try {
      const blob = await relayGet(relay, await StudentBox.path(keyHex));
      if (!blob) return { notFound: true };
      const plain = await StudentBox.open(keyHex, blob);
      const pub = plain && parseStudentPublic(dec.decode(plain));
      return pub ? { ok: true, pub } : { error: 'بيانات الطالب على الخادم تالفة — راجع الإدارة' };
    } catch (e) { return e.code ? { error: friendly(e.code) } : { offline: true }; }
  }

  // ---- مفتاح الجهاز (ECDSA P-256) ----
  function derSig(raw) {
    const part = x => { let i = 0; while (i < x.length - 1 && x[i] === 0) i++; x = x.slice(i); return x[0] & 0x80 ? cat(Uint8Array.of(0), x) : x; };
    const r = part(raw.slice(0, 32)), s = part(raw.slice(32));
    const body = cat(Uint8Array.of(2, r.length), r, Uint8Array.of(2, s.length), s);
    return cat(Uint8Array.of(0x30, body.length), body);
  }
  const DeviceKey = {
    async generate() { return S.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, false, ['sign']); },
    async publicRaw(kp) { return new Uint8Array(await S.exportKey('raw', kp.publicKey)); },
    async sign(kp, data) { return derSig(new Uint8Array(await S.sign({ name: 'ECDSA', hash: 'SHA-256' }, kp.privateKey, data))); },
  };

  // ---- بروتوكول QR ----
  const core = (tag, pub, req) => cat(ascii(tag), pub, req.sessionId, req.nonce, longBytes(req.timeMs));
  const QrProtocol = {
    TTL_MS: 60000,
    challengeText: r => 'UAC1|' + b64u(cat(r.sessionId, r.nonce, longBytes(r.timeMs))),
    parseChallenge(text) {
      const p = text.trim().split('|'); if (p.length !== 2 || p[0] !== 'UAC1') return null;
      try { const d = unb64u(p[1]); if (d.length !== 40) return null;
        return { sessionId: d.slice(0, 16), nonce: d.slice(16, 32), timeMs: Number(new DataView(d.buffer, d.byteOffset + 32, 8).getBigInt64(0)) };
      } catch (e) { return null; }
    },
    /** إجابة الطالب على تحدّي الدكتور. creds = {code, tag, keyBytes}. */
    async reply(creds, kp, req) {
      const pub = await DeviceKey.publicRaw(kp);
      const proof = await hmac(creds.keyBytes, cat(ascii('UA-BIND'), core(creds.tag, pub, req)));
      const sig = await DeviceKey.sign(kp, cat(ascii('UA-SIGN'), core(creds.tag, pub, req)));
      return 'UAR1|' + b64u(req.nonce) + '|' + b64u(cat(ascii(creds.tag), pub, proof, sig, Uint8Array.of(0x90, 0)));
    },
  };

  const api = { hex, unhex, cat, b64, unb64, b64u, unb64u, longBytes, Codes, hmac, pbkdf2, sha256, studentKey, StudentBox, Timetable,
    parseStudentPublic, fetchStudent, DeviceKey, derSig, QrProtocol, DEFAULT_RELAY };
  if (typeof module !== 'undefined' && module.exports) module.exports = api; else root.UA = api;
})(typeof globalThis !== 'undefined' ? globalThis : this);
