/* واجهة الدكتور: ربط بـQR، مزامنة الكشف، حضور بالـQR، تحضير يدوي، عدّ القاعة، كشف الحضور */
const Doctor = (() => {
  const KEY = 'ua.doctor';
  const fresh = () => ({ pin: null, pair: null, doctorId: '', name: '', epoch: -1, ackEpoch: -1, attHash: null,
    slots: [], courses: [], students: [], sessions: [], att: [], counts: {}, activeId: null });
  let D = Object.assign(fresh(), store.get(KEY, null) || {});
  let app = null, unlocked = false, tab = 'live', syncing = false, syncInfo = '', syncTimer = null;
  let A = null;                         // الجلسة الجارية
  let events = [], seen = new Map(), issued = new Map();
  let live = null;                      // مراجع عناصر شاشة الحضور المباشر
  let scanner = null, qrTimer = null, clockTimer = null;
  let sheetCourse = null, sheetPage = 0, historyId = null;
  const save = () => store.set(KEY, D);
  /** الوقت الموثوق (وقت Firebase + ساعة رتيبة) لا ساعة الهاتف: عليه تُبنى بوابة «لا تحضير إلا حسب الجدول». */
  const clock = UA.TrustedClock();
  let resumeTried = false, startSynced = false, visHooked = false;
  async function syncClock() {
    if (!D || !D.pair) return false;
    const box = new UA.Relay.Mailbox(new UA.Relay.Client(D.pair.relayUrl), D.pair.mailbox);
    try { await clock.syncWith(() => box.serverTimeMs()); resumeTried = false; return true; } catch (e) { return false; }
  }
  const S = UA.Status;
  const course = (id) => D.courses.find((c) => c.id === id);
  const cname = (c) => `${c.code} ${c.name} (${c.section})`;
  const byName = (a, b) => a.name.localeCompare(b.name, 'ar');
  const registered = (sid) => D.att.filter((a) => a.sessionId === sid && a.status !== 'CANCELLED').length;

  // ---------- المزامنة ----------
  const describeSync = (r) => r.error ? '⚠ ' + r.error
    : r.offline ? 'لا اتصال بالإنترنت — تتم المزامنة عند توفّره'
    : r.waitingAdmin ? 'تمّ الربط. بانتظار أن ينشر المسؤول كشفك (يحتاج اتصالًا بالإنترنت عنده)'
    : 'آخر مزامنة ' + fmtClock(Date.now()) + (r.rosterUpdated ? ` — وصل كشف جديد (إصدار ${D.epoch})` : '') + (r.uploaded ? ' — رُفع الحضور للمسؤول' : '');

  function applyRoster(roster) {
    const ids = new Set(roster.courses.map((c) => c.id));
    for (const c of roster.courses) { const i = D.courses.findIndex((x) => x.id === c.id); if (i >= 0) D.courses[i] = c; else D.courses.push(c); }
    D.students.forEach((s) => { s.courseIds = s.courseIds.filter((id) => !ids.has(id)); });
    let resets = 0;
    for (const r of roster.students) {
      const old = D.students.find((s) => s.studentId === r.studentId);
      const keep = old && old.tag === r.tag;                         // وسم جديد = كود جديد = يُلغى ربط الجهاز القديم
      if (old && !keep && old.publicKeyHex) resets++;
      const entry = { studentId: r.studentId, name: r.name, faculty: r.faculty, major: r.major, level: r.level, section: r.section, tag: r.tag, keyHex: r.keyHex,
        publicKeyHex: keep ? old.publicKeyHex || null : null, courseIds: (old ? old.courseIds : []).filter((id) => !ids.has(id)).concat(r.courseIds.filter((id) => ids.has(id))) };
      if (old) Object.assign(old, entry); else D.students.push(entry);
    }
    return resets;
  }

  function buildAttendanceFile() {
    return { doctorId: D.doctorId, epoch: D.epoch,
      sessions: D.sessions.slice().sort((a, b) => a.startedAt - b.startedAt).map((s) => ({ sessionId: s.sessionId, courseId: s.courseId, startedAt: s.startedAt, durationMin: s.durationMin, counted: D.counts[s.sessionId] ?? -1, part: s.part || 0 })),
      records: D.att.filter((a) => a.status !== 'CANCELLED').sort((a, b) => a.timestamp - b.timestamp || (a.studentId < b.studentId ? -1 : 1)).map((a) => ({ sessionId: a.sessionId, studentId: a.studentId, timestamp: a.timestamp })),
      bindings: D.students.filter((s) => s.publicKeyHex).sort((a, b) => (a.studentId < b.studentId ? -1 : 1)).map((s) => ({ studentId: s.studentId, tag: s.tag, pubHex: s.publicKeyHex })) };
  }

  async function runSync(retried = false) {
    const { relayUrl, mailbox, code } = D.pair;
    const box = new UA.Relay.Mailbox(new UA.Relay.Client(relayUrl), mailbox);
    let updated = false, resets = 0, uploaded = false, waiting = false;
    try {
      try { await clock.syncWith(() => box.serverTimeMs()); resumeTried = false; }
      catch (e) { if (e instanceof UA.Relay.OfflineError || (e instanceof UA.Relay.RelayError && e.code === 404)) throw e; }
      const rv = await box.rosterEpoch();
      if (rv === null && D.epoch < 0) waiting = true;
      if (rv !== null && rv > D.epoch) {
        const blob = await box.fetchRoster();
        if (blob) {
          const roster = await UA.Roster.open(blob, code);
          if (!roster) return { error: 'تعذّر فتح الكشف: الكود تغيّر أو الملف تالف. اطلب QR جديدًا من المسؤول.' };
          if (roster.doctorId !== D.doctorId) return { error: `الكشف يخص دكتورًا آخر (${roster.doctorName})` };
          if (roster.epoch > D.epoch) { resets = applyRoster(roster); D.name = roster.doctorName; D.epoch = roster.epoch; D.slots = roster.slots; updated = true; save(); }
        }
      }
      if (D.epoch >= 0 && D.ackEpoch !== D.epoch) { await box.publishAck(D.epoch); D.ackEpoch = D.epoch; save(); }
      if (D.epoch >= 0 && D.sessions.length) {
        const file = buildAttendanceFile();
        const hsh = UA.hex(await UA.sha256(UA.utf8(UA.AttendanceFile.toText(file))));
        if (hsh !== D.attHash) { await box.publishAttendance(await UA.AttendanceFile.seal(file, code)); D.attHash = hsh; save(); uploaded = true; }
      }
      return { waitingAdmin: waiting, rosterUpdated: updated, resetCount: resets, uploaded };
    } catch (e) {
      if (e instanceof UA.Relay.OfflineError) return { offline: true, rosterUpdated: updated, resetCount: resets, uploaded };
      if (e instanceof UA.Relay.RelayError) {
        if (e.code === 404 && !retried) {
          let found = null; try { found = await UA.Relay.find(relayUrl); } catch (x) { return { offline: true }; }
          if (found && found !== relayUrl) { D.pair.relayUrl = found; save(); return runSync(true); }
        }
        return { error: UA.Relay.friendly(e), rosterUpdated: updated, resetCount: resets, uploaded };
      }
      return { error: e.message || String(e), rosterUpdated: updated, resetCount: resets, uploaded };
    }
  }

  async function syncNow(manual = false) {
    if (!D.pair) { if (manual) toast('اربط الهاتف بالمسؤول أولًا'); return; }
    if (syncing) return;
    syncing = true; paintSyncStatus();
    let r = null;
    try {
      r = await runSync();
      syncInfo = describeSync(r);
      if (r.rosterUpdated && r.resetCount > 0) toast(`وصل كشف جديد. أُلغي ربط ${r.resetCount} جهاز بسبب أكواد جديدة من الإدارة.`);
      else if (manual || r.error) toast(syncInfo);
    } finally {
      syncing = false; paintSyncStatus();
      if (r && r.rosterUpdated && unlocked && D.pair && !A) render();          // كشف جديد: أعد رسم الشاشة (بلا مقاطعة جلسة جارية)
    }
  }
  function scheduleSync() { clearTimeout(syncTimer); syncTimer = setTimeout(() => { if (navigator.onLine) syncNow(false); }, 2500); }
  function paintSyncStatus() { const e = $('#syncstatus'); if (e) e.textContent = syncing ? 'جارٍ المزامنة…' : syncInfo; }

  // ---------- القفل ----------
  const hashPin = async (p) => UA.hex(await UA.sha256(UA.utf8(p)));
  function renderLock() {
    const creating = !D.pin;
    const p1 = h('input', { type: 'password', inputmode: 'numeric', autocomplete: 'off', placeholder: creating ? 'رمز دخول جديد (4 أرقام فأكثر)' : 'رمز الدخول' });
    const p2 = creating ? h('input', { type: 'password', inputmode: 'numeric', autocomplete: 'off', placeholder: 'أعد كتابة الرمز' }) : null;
    const msg = h('p', { class: 'bad' });
    const go = async () => {
      if (creating) {
        if (!/^\d{4,}$/.test(p1.value)) { msg.textContent = 'الرمز 4 أرقام على الأقل'; return; }
        if (p1.value !== p2.value) { msg.textContent = 'الرمزان غير متطابقين'; return; }
        D.pin = await hashPin(p1.value); save();
      } else if ((await hashPin(p1.value)) !== D.pin) { msg.textContent = 'رمز الدخول خاطئ'; return; }
      unlocked = true; render();
    };
    p1.addEventListener('keydown', (e) => { if (e.key === 'Enter' && !creating) go(); });
    app.replaceChildren(h('header', { class: 'top' }, h('span', {}, 'حضور الجامعة — الدكتور')),
      h('main', {}, h('div', { class: 'card' }, h('h2', {}, creating ? 'إنشاء رمز دخول' : 'تسجيل الدخول'),
        h('p', { class: 'muted' }, creating ? 'رمز يقفل التطبيق على هذا الهاتف فقط (لا علاقة له بكودك عند الإدارة).' : ''),
        p1, p2, h('button', { class: 'btn block', onclick: go }, creating ? 'حفظ ودخول' : 'دخول'), msg),
        h('p', { class: 'center' }, h('button', { class: 'link', onclick: () => { store.del('ua.role'); location.reload(); } }, 'تغيير الدور (طالب / دكتور)'))));
  }

  // ---------- الربط ----------
  function pairFrom(text) {
    const info = UA.Relay.Pairing.parse(text);
    if (!info) { toast('هذا ليس رمز ربط صالحًا من تطبيق المسؤول'); return false; }
    if (D.doctorId && D.doctorId !== info.doctorId) { toast(`هذا الهاتف مرتبط بدكتور آخر (${D.doctorId}). امسح بياناته أولًا من الإعدادات.`); return false; }
    (async () => {
      // رمز الربط يعمل على هاتف واحد: أول هاتف يمسحه يسجّل مفتاح جهازه، وغيره يُرفض
      try {
        const dev = UA.hex(await UA.devicePublicRaw(await DeviceKeys.device()));
        if ((await UA.DeviceClaim.claim(new UA.Relay.Client(info.relayUrl), `m/${info.mailbox}`, dev)) === 'Taken') { toast(UA.DeviceClaim.TAKEN_DOCTOR); return; }
      } catch (e) { toast(e instanceof UA.Relay.RelayError || e instanceof UA.Relay.OfflineError ? UA.Relay.friendly(e) : String(e.message || e)); return; }
      const newBox = !D.pair || D.pair.mailbox !== info.mailbox;
      D.pair = info; D.doctorId = info.doctorId;
      if (newBox) { D.epoch = -1; D.ackEpoch = -1; D.attHash = null; }
      save(); render(); syncNow(true);
    })();
    return true;
  }
  function renderPair() {
    stopScanner();
    const ta = h('textarea', { rows: '3', placeholder: 'أو الصق نص رمز الربط هنا (UAQ1|…)', dir: 'ltr' });
    const holder = h('div'); let done = false;
    app.replaceChildren(h('header', { class: 'top' }, h('span', {}, 'حضور الجامعة — الدكتور')),
      h('main', {}, h('div', { class: 'card' }, h('h2', {}, 'الربط بالمسؤول'),
        h('p', { class: 'muted' }, 'امسح رمز QR الذي يعرضه تطبيق المسؤول لك (مرة واحدة فقط). يحتاج هذا الهاتف إنترنت للمزامنة.'),
        h('button', { class: 'btn block', onclick: async () => {
          if (scanner) return;
          scanner = new Scanner((t) => { if (done) return; if (pairFrom(t)) { done = true; stopScanner(); } });
          holder.replaceChildren(scanner.el); if (!(await scanner.start())) { stopScanner(); holder.replaceChildren(); }
        } }, '📷 مسح رمز الربط'), holder, ta,
        h('button', { class: 'btn ghost block', onclick: () => pairFrom(ta.value) }, 'ربط بالنص')),
        h('p', { class: 'center' }, h('button', { class: 'link', onclick: () => { store.del('ua.role'); location.reload(); } }, 'تغيير الدور (طالب / دكتور)'))));
  }

  // ---------- الجلسة ----------
  const uuidBytes = (u) => UA.unhex(u.replace(/-/g, ''));
  const uuid = () => {
    if (crypto.randomUUID) return crypto.randomUUID();
    const b = UA.rand(16); b[6] = (b[6] & 0x0f) | 0x40; b[8] = (b[8] & 0x3f) | 0x80;
    const x = UA.hex(b); return `${x.slice(0, 8)}-${x.slice(8, 12)}-${x.slice(12, 16)}-${x.slice(16, 20)}-${x.slice(20)}`;
  };
  function issue() {
    const now = clock.now();
    for (const [k, v] of issued) if (now - v.t > UA.QrProtocol.TTL_MS) issued.delete(k);
    const req = { sessionId: uuidBytes(A.sessionId), nonce: UA.rand(16), timeMs: now };
    issued.set(UA.hex(req.nonce), { req, t: now });
    return req;
  }
  const findChallenge = (nonce) => { const v = issued.get(UA.hex(nonce)); return v && clock.now() - v.t <= UA.QrProtocol.TTL_MS ? v.req : null; };
  const endsAt = () => A.startedAt + A.durationMin * 60000;

  /** جلسات المادة منذ منتصف الليل (محاضرات اليوم). */
  const todaySessions = (courseId) => { const mid = new Date(clock.now()); mid.setHours(0, 0, 0, 0); return D.sessions.filter((s) => s.courseId === courseId && s.startedAt >= mid.getTime()); };
  /** newLecture: محاضرة متتالية جديدة في اليوم نفسه (part = أعلى رقم اليوم + 1) بدل استئناف جلسة اليوم. */
  const minOf = (ms) => { const d = new Date(ms); return d.getHours() * 60 + d.getMinutes(); };
  /** الجدول يقتضي الآن محاضرة متتالية جديدة (موعد لاحق غير موعد آخر جلسة اليوم لنفس المادة). */
  function nextBySchedule(courseId) {
    const last = todaySessions(courseId).sort((a, b) => b.startedAt - a.startedAt)[0];
    return !!last && UA.Schedule.isNextLecture(D.slots, courseId, new Date(clock.now()).getDay(), minOf(last.startedAt), minOf(clock.now()));
  }
  /**
   * بوابة «لا تحضير إلا حسب الجدول»: نزامن وقت الخادم أولًا (متى توفّر الإنترنت)، ثم نفحص موعد المادة بالوقت الموثوق لا بساعة الهاتف.
   * (المتصفح بلا ساعة رتيبة تبقى عبر السكون وإعادة التحميل، فلذلك نطلب وقتًا جديدًا من الخادم عند كل بدء.)
   */
  async function gateStart(courseId, minutes) {
    if (navigator.onLine) await syncClock();
    const r = clock.read();
    if (!r.trusted) { toast(UA.AttendancePolicy.MSG_NEEDS_SYNC); return null; }
    const d = UA.AttendancePolicy.checkDoctorStart(D.slots, courseId, r.ms, minutes);
    if (!d.ok) { toast(d.message); return null; }
    return { now: Math.round(r.ms), minutes: d.minutes, skewed: r.tampered };
  }
  /** newLecture: true = محاضرة متتالية جديدة، false = استئناف، undefined = حسب الجدول (تلقائيًا). */
  async function startSession(courseId, requested, newLecture) {
    const g = await gateStart(courseId, requested); if (!g) return;      // مرفوض: لا يتغير شيء
    if (A) endSession(true);
    const now = g.now, minutes = g.minutes;
    const today = todaySessions(courseId);
    const top = today.length ? Math.max(...today.map((s) => s.part || 0)) : null;
    const next = newLecture === undefined ? nextBySchedule(courseId) : newLecture;
    const old = next ? undefined : today.slice().sort((a, b) => b.startedAt - a.startedAt)[0];
    let s;
    if (old) { old.durationMin = Math.min(1440, Math.max(1, Math.floor((now - old.startedAt) / 60000) + minutes)); old.endedAt = null; s = old; }
    else { s = { sessionId: uuid(), courseId, doctorId: D.doctorId, startedAt: now, durationMin: minutes, endedAt: null, part: top === null ? 0 : top + 1 }; D.sessions.push(s); }
    D.activeId = s.sessionId; save();
    A = s; events = []; seen = new Map(); issued = new Map();
    if (g.skewed) addEvent(false, '⚠ ساعة هاتفك لا تطابق الوقت الحقيقي — يُعتمد الوقت الحقيقي من الخادم، فتغيير الساعة لا يفيد');
    if (minutes < requested) addEvent(true, `قُصّت مدة الجلسة إلى ${minutes} دقيقة لأنها تنتهي مع نهاية موعد المحاضرة`);
    if (old) addEvent(true, 'استُؤنفت محاضرة اليوم نفسها — لن يتكرر تسجيل من حضر قبل الإيقاف');
    else if (top !== null) addEvent(true, `بدأت محاضرة متتالية جديدة (${top + 2}) — تُحتسب عمودًا مستقلًا في الكشف`);
    scheduleSync(); tab = 'live'; render();
  }
  /** تمديد الجلسة 15 دقيقة كحدّ أقصى، ولا يتجاوز أبدًا نهاية الموعد في الجدول. */
  function extendSession() {
    const r = clock.read(); if (!r.trusted) { toast(UA.AttendancePolicy.MSG_NEEDS_SYNC); return; }
    const slot = UA.AttendancePolicy.currentSlot(D.slots.filter((x) => x.courseId === A.courseId), r.ms);
    if (!slot) { toast('انتهى موعد المحاضرة في الجدول، فلا يمكن التمديد.'); return; }
    const slotEnd = r.ms + (slot.endMin * 60 - UA.AttendancePolicy.localTime(r.ms).secondOfDay) * 1000;
    const dur = Math.floor((Math.min(endsAt() + 15 * 60000, slotEnd) - A.startedAt) / 60000);
    if (dur <= A.durationMin) { toast('الجلسة تنتهي أصلًا مع نهاية موعد المحاضرة، فلا تمديد.'); return; }
    A.durationMin = dur; save(); paintClock();
  }
  function endSession(silent = false) {
    if (!A) return;
    A.endedAt = clock.now(); D.activeId = null; save(); A = null; stopLive();
    scheduleSync(); if (!silent) render();
  }
  function stopScanner() { if (scanner) { scanner.stop(); scanner = null; } }
  function stopLive() { stopScanner(); clearInterval(qrTimer); clearInterval(clockTimer); qrTimer = clockTimer = null; live = null; }

  function addEvent(ok, text) { events = [{ ok, text, time: Date.now() }, ...events].slice(0, 50); paintEvents(); }
  function describeOutcome(o, tag) {
    const name = o.student ? o.student.name : '';
    if (o.cancelled) return `⚠ ${name} — ألغيتَ تحضيره في هذه المحاضرة (يمكنك إعادته من «الحاضرون»)`;
    switch (o.status) {
      case S.OK: return `✓ تم تسجيل حضور ${name}`;
      case S.DUPLICATE: return `⚠ ${name} — سُجّل حضوره مسبقًا`;
      case S.NOT_ENROLLED: return `✗ الطالب غير مسجل في هذه المادة (${name})`;
      case S.UNKNOWN_STUDENT: return `✗ كود غير موجود في الكشف (${tag}) — اضغط «مزامنة الآن» في الإعدادات`;
      case S.BAD_PROOF: return '✗ كود الطالب غير صحيح';
      case S.KEY_MISMATCH: return `✗ ${name}: الكود مرتبط بجهاز آخر — يحتاج كودًا جديدًا من الإدارة`;
      case S.BAD_SIGNATURE: return '✗ فشل التحقق من جهاز الطالب';
      default: return '✗ انتهت مدة الجلسة';
    }
  }

  async function evaluate(reply, req) {
    if (!A || !clock.read().trusted || clock.now() > endsAt()) return { status: S.EXPIRED };
    const st = D.students.find((x) => x.tag === reply.tag);
    if (!st) return { status: S.UNKNOWN_STUDENT };
    const v = await UA.evaluateReply(st, reply, req);                       // 1) كود الطالب  2) توقيع جهازه
    if (v !== S.OK) return { status: v, student: st };
    if (!st.courseIds.includes(A.courseId)) return { status: S.NOT_ENROLLED, student: st };
    const pubHex = UA.hex(reply.pub);                                       // 4) ربط الجهاز عند أول مرة
    if (!st.publicKeyHex) st.publicKeyHex = pubHex; else if (st.publicKeyHex !== pubHex) return { status: S.KEY_MISMATCH, student: st };
    const old = D.att.find((a) => a.sessionId === A.sessionId && a.studentId === st.studentId);
    let status = S.DUPLICATE;
    if (!old) { D.att.push({ sessionId: A.sessionId, studentId: st.studentId, studentName: st.name, courseId: A.courseId, timestamp: req.timeMs, status: 'PRESENT' }); status = S.OK; }
    save(); scheduleSync();
    return { status, student: st, cancelled: !!old && old.status === 'CANCELLED' };
  }

  async function onReply(text) {
    const pr = UA.QrProtocol.parseReply(text); if (!pr) return;
    // نفس الرمز بالضبط يمرّ عبر عدة لقطات كاميرا فيُعالَج مرة واحدة؛ أما إجابة أخرى لنفس التحدّي (توقيع مختلف) فتُعالَج
    const now = Date.now(), k = String(text).trim();
    for (const [kk, t] of seen) if (now - t > 120000) seen.delete(kk);
    if (seen.has(k)) return; seen.set(k, now);
    const req = findChallenge(pr.nonce);
    if (!req) { addEvent(false, '✗ رمز قديم أو لم يصدر من هذا الهاتف — اطلب من الطالب مسح الرمز الحالي'); return; }
    const out = await evaluate(pr.reply, req);
    addEvent(UA.Status.ok(out.status) && !out.cancelled, describeOutcome(out, pr.reply.tag));
    if (live) { paintPresent(); paintCount(); }
    if (navigator.vibrate) navigator.vibrate(UA.Status.ok(out.status) ? 60 : [60, 60, 60]);
  }

  // ---------- تحضير يدوي وإلغاء (للجلسة الجارية أو السابقة) ----------
  function markPresent(sessionId, c, st) {
    const old = D.att.find((a) => a.sessionId === sessionId && a.studentId === st.studentId);
    if (!old) D.att.push({ sessionId, studentId: st.studentId, studentName: st.name, courseId: c.id, timestamp: clock.now(), status: 'MANUAL' });
    else if (old.status === 'CANCELLED') old.status = 'MANUAL';
    else return;
    save(); addEvent(true, `✓ حضّرتَ ${st.name} يدويًا`); scheduleSync();
  }
  function cancelAttendance(sessionId, st) {
    const a = D.att.find((x) => x.sessionId === sessionId && x.studentId === st.studentId); if (!a) return;
    a.status = 'CANCELLED'; save(); addEvent(false, `⊘ ألغيتَ تحضير ${st.name}`); scheduleSync();
  }

  /** حاضرون + تحضير يدوي لجلسة (يُستعمل في الحضور المباشر وفي سجل المحاضرات). */
  function attendanceEditor(sessionId, c, onChange) {
    const q = h('input', { type: 'search', placeholder: 'ابحث باسم الطالب للتحضير اليدوي…' });
    const presentBox = h('div', { class: 'list' }), candBox = h('div', { class: 'list' });
    const students = D.students.filter((s) => s.courseIds.includes(c.id)).sort(byName);
    const paint = () => {
      const rows = D.att.filter((a) => a.sessionId === sessionId && a.status !== 'CANCELLED').sort((a, b) => b.timestamp - a.timestamp);
      presentBox.replaceChildren(...(rows.length ? rows.map((a) => {
        const st = D.students.find((s) => s.studentId === a.studentId) || { studentId: a.studentId, name: a.studentName };
        return h('div', { class: 'item' }, h('span', {}, st.name, ' ', h('span', { class: 'badge' }, a.status === 'MANUAL' ? 'يدوي' : fmtClock(a.timestamp))),
          h('button', { class: 'btn danger small', onclick: () => { cancelAttendance(sessionId, st); paint(); onChange && onChange(); } }, 'إلغاء'));
      }) : [h('p', { class: 'muted' }, 'لا حاضرون بعد.')]));
      const term = q.value.trim();
      const here = new Set(D.att.filter((a) => a.sessionId === sessionId && a.status !== 'CANCELLED').map((a) => a.studentId));
      const cand = (term ? students.filter((s) => !here.has(s.studentId) && s.name.includes(term)) : []).slice(0, 12);
      candBox.replaceChildren(...cand.map((s) => h('div', { class: 'item' }, h('span', {}, s.name),
        h('button', { class: 'btn small', onclick: () => { markPresent(sessionId, c, s); q.value = ''; paint(); onChange && onChange(); } }, 'حضّر'))));
    };
    q.addEventListener('input', paint); paint();
    const el = h('div', {}, h('h3', {}, 'الحاضرون'), presentBox, h('h3', {}, 'تحضير يدوي'), q, candBox);
    return { el, paint };
  }

  // ---------- عدّ القاعة ----------
  function countCard(sessionId, regFn, onChange) {
    const line = h('p', { class: 'countline' });
    const input = h('input', { type: 'number', inputmode: 'numeric', min: '0', max: '2000', placeholder: 'العدد', style: 'width:110px;text-align:center' });
    const setC = (n) => { const v = Math.max(0, Math.min(2000, n)); D.counts[sessionId] = v; save(); input.value = v; paintLine(); scheduleSync(); onChange && onChange(); };
    const cur = () => (D.counts[sessionId] ?? -1);
    const paintLine = () => {
      const r = UA.CountCheck.check(regFn(), cur());
      line.textContent = r.text; line.className = 'countline ' + ({ OK: 'ok', MORE: 'bad', LESS: 'warn' }[r.level] || 'muted');
    };
    if (cur() >= 0) input.value = cur();
    const el = h('div', { class: 'card' }, h('h3', {}, 'عدّ الطلاب في القاعة'),
      h('div', { class: 'row' },
        h('button', { class: 'btn ghost small', onclick: () => setC((cur() < 0 ? regFn() : cur()) - 1) }, '−'), input,
        h('button', { class: 'btn ghost small', onclick: () => setC((cur() < 0 ? regFn() : cur()) + 1) }, '+'),
        h('button', { class: 'btn small', onclick: () => { const n = parseInt(input.value, 10); if (!Number.isNaN(n)) setC(n); } }, 'حفظ')),
      line, h('p', { class: 'muted' }, 'عُدّ الجالسين في القاعة وأدخل الرقم؛ يكشف من سُجّل وهو خارج القاعة أو في قاعة مجاورة، ويصل المسؤول في التقارير.'),
      cur() >= 0 ? h('button', { class: 'link', onclick: () => { delete D.counts[sessionId]; save(); input.value = ''; paintLine(); scheduleSync(); } }, 'مسح العدّ') : null);
    paintLine();
    return { el, paint: paintLine };
  }

  // ---------- شاشة الحضور ----------
  function paintEvents() {
    if (!live) return;
    live.events.replaceChildren(...(events.length ? events.map((e) => h('div', { class: 'ev ' + (e.ok ? 'ok' : 'bad') }, fmtClock(e.time) + '  ' + e.text)) : [h('p', { class: 'muted' }, 'لا أحداث بعد.')]));
  }
  const paintPresent = () => live && live.editor.paint();
  const paintCount = () => { if (!live) return; live.count.paint(); live.reg.textContent = String(registered(A.sessionId)); };
  function paintClock() {
    if (!live || !A) return;
    const left = endsAt() - clock.now();
    live.timer.textContent = left > 0 ? `المتبقي ${pad2(Math.floor(left / 60000))}:${pad2(Math.floor((left % 60000) / 1000))}` : 'انتهت مدة الجلسة';
    live.timer.className = left > 0 ? 'badge' : 'badge bad';
  }
  function paintQr() { if (live && A && clock.read().trusted && clock.now() <= endsAt()) drawQr(live.canvas, UA.QrProtocol.challengeText(issue()), 420); else if (live && A && !clock.read().trusted) syncClock().then(paintQr); }

  function viewLive(m) {
    const c = course(A.courseId);
    const canvas = document.createElement('canvas');
    const camHolder = h('div'), camBtn = h('button', { class: 'btn ghost block' }, '📷 تشغيل الكاميرا لقراءة إجابات الطلاب');
    const startCam = async () => {
      if (scanner) return;
      scanner = new Scanner(onReply); camHolder.replaceChildren(scanner.el);
      if (!(await scanner.start())) { stopScanner(); camHolder.replaceChildren(); camBtn.hidden = false; return; }
      camBtn.hidden = true;
    };
    camBtn.addEventListener('click', startCam);
    const reg = h('strong', {}, String(registered(A.sessionId))), timer = h('span', { class: 'badge' });
    const evBox = h('div');
    const count = countCard(A.sessionId, () => registered(A.sessionId), () => { reg.textContent = String(registered(A.sessionId)); });
    const editor = attendanceEditor(A.sessionId, c, () => { reg.textContent = String(registered(A.sessionId)); count.paint(); });
    live = { canvas, events: evBox, timer, reg, count, editor };
    m.append(
      h('div', { class: 'card' }, h('div', { class: 'row between' }, h('h2', {}, cname(c)), timer),
        h('div', { class: 'row between' }, h('span', {}, 'المسجَّلون: ', reg, ' من ', String(D.students.filter((s) => s.courseIds.includes(c.id)).length)),
          h('button', { class: 'btn ghost small', onclick: extendSession }, '+15 دقيقة'))),
      h('div', { class: 'card center' }, h('h3', {}, 'رمز الحضور (يتغيّر كل 10 ثوانٍ)'),
        h('div', { class: 'qrbox' }, canvas), h('p', { class: 'muted' }, 'يمسحه الطلاب بتطبيقهم، ثم تمسح أنت الرمز الذي يظهر عندهم بالكاميرا.'), camBtn, camHolder),
      count.el,
      h('div', { class: 'card' }, h('h3', {}, 'آخر الأحداث'), evBox),
      h('div', { class: 'card' }, editor.el),
      h('button', { class: 'btn danger block', onclick: async () => { if (await confirmDialog('إنهاء جلسة الحضور؟ (يمكنك استئنافها اليوم نفسه ولن يتكرر أحد)', 'إنهاء')) endSession(); } }, '■ إنهاء الحضور'));
    paintEvents(); paintClock(); paintQr();
    qrTimer = setInterval(paintQr, 10000); clockTimer = setInterval(paintClock, 1000);
    startCam();
  }

  function viewStart(m) {
    if (!D.courses.length) {
      m.append(h('div', { class: 'card' }, h('h2', {}, 'لا مواد بعد'), h('p', { class: 'muted' }, D.epoch < 0 ? 'بانتظار أن يصل كشفك من المسؤول. اضغط «مزامنة الآن» من الإعدادات.' : 'لم تُسنَد إليك مواد بعد.'),
        h('button', { class: 'btn block', onclick: () => syncNow(true) }, '⟳ مزامنة الآن'), h('p', { id: 'syncstatus', class: 'muted' }, syncInfo)));
      return;
    }
    const r0 = clock.read();
    if (!r0.trusted && !startSynced) { startSynced = true; syncClock().then(() => { startSynced = false; if (unlocked && tab === 'live' && !A && clock.read().trusted) render(); }); }
    const slot = r0.trusted ? UA.AttendancePolicy.currentSlot(D.slots, r0.ms) : null;
    const slotCourse = slot && course(slot.courseId);
    const slotLeft = slot ? UA.AttendancePolicy.remainingMinutes(slot, r0.ms) : 0;
    let pick = slotCourse ? slotCourse.id : D.courses[0].id;
    let minutes = slot ? Math.max(1, Math.min(240, slotLeft)) : 30;
    const sel = h('select', { onchange: () => { pick = Number(sel.value); } },
      D.courses.map((c) => h('option', { value: c.id, selected: c.id === pick }, cname(c) + ' — ' + D.students.filter((s) => s.courseIds.includes(c.id)).length + ' طالب')));
    const chips = h('div', { class: 'chips' });
    const paintChips = () => chips.replaceChildren(...[15, 30, 45, 60, 90, 120].map((v) => h('button', { class: 'chip' + (v === minutes ? ' on' : ''), onclick: () => { minutes = v; paintChips(); } }, v + ' دقيقة')));
    paintChips();
    const sched = h('p', { class: 'muted' });
    const paintSched = () => { sched.textContent = UA.Schedule.describeAll(D.slots, Number(sel.value)) || 'لا موعد في الجدول لهذه المادة'; };
    sel.addEventListener('change', paintSched); paintSched();
    // محاضرة متتالية جديدة (تظهر فقط إن بدأت للمادة محاضرة اليوم)
    const next = h('div', {});
    const paintNext = () => {
      const t = todaySessions(Number(sel.value)), top = t.length ? Math.max(...t.map((s) => s.part || 0)) : -1;
      next.replaceChildren(...(top < 0 || nextBySchedule(Number(sel.value)) ? [] : [
        h('button', { class: 'btn ghost block', onclick: async () => {
          if (await confirmDialog(`ستُسجَّل المحاضرة ${top + 2} لهذه المادة اليوم كعمود مستقل في الكشف، ويحضر الطلاب فيها من جديد. لا يمكن دمجها مع السابقة بعد ذلك. متابعة؟`, 'نعم، ابدأ'))
            startSession(Number(sel.value), minutes, true);
        } }, `＋ بدء محاضرة متتالية جديدة (المحاضرة ${top + 2} اليوم)`),
        h('p', { class: 'muted' }, 'للمحاضرتين المتتاليتين لنفس الشعبة/المجموعة في اليوم نفسه: تُحتسب الثانية عمودًا مستقلًا (2) في الكشف.')]));
    };
    sel.addEventListener('change', paintNext); paintNext();
    m.append(
      slotCourse ? h('div', { class: 'hint' }, `🗓 حسب الجدول الآن: ${cname(slotCourse)} (${UA.Schedule.describe(slot, true)}) — المتبقي ${slotLeft} دقيقة`)
        : h('div', { class: 'hint' }, r0.trusted ? 'لا محاضرة الآن حسب الجدول: لا يُبدأ الحضور إلا داخل موعد المادة.' : 'جارٍ التأكد من الوقت الحقيقي من الخادم… (يلزم اتصال بالإنترنت)'),
      h('div', { class: 'card' }, h('h2', {}, 'بدء الحضور'), h('label', {}, 'المادة'), sel, sched, h('label', {}, 'مدة الجلسة (تُقصّ عند نهاية الموعد)'), chips,
        h('button', { class: 'btn block', onclick: () => startSession(pick, minutes) }, nextBySchedule(pick) ? '▶ بدء المحاضرة التالية حسب الجدول بالـQR' : todaySessions(pick).length ? '▶ استئناف محاضرة اليوم بالـQR' : '▶ بدء الحضور بالـQR'), next),
      h('div', { class: 'hint' }, 'يُشغَّل الحضور بالـQR: يعرض هاتفك رمزًا متغيّرًا، يمسحه الطالب، ثم تمسح إجابته بالكاميرا. الحضور التلقائي بالبلوتوث غير متاح على الآيفون.'),
      h('p', { id: 'syncstatus', class: 'muted' }, syncInfo));
  }

  // ---------- تصدير الكشف PDF (طباعة A4 أفقي → «حفظ كـPDF» أو مشاركة من قائمة الطباعة) ----------
  const esc = (x) => String(x).replace(/[&<>"']/g, (ch) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[ch]));
  /** يبني صفحة طباعة كاملة: لكل 16 محاضرة جدول بكل الطلاب (يتكرر الرأس عند تعدّد الصفحات). */
  function sheetPrintHtml(c, data) {
    const P = UA.AttendanceSheet.PAGE;
    let body = '';
    for (let pg = 0; pg < data.pages; pg++) {
      const head = '<tr><th class="no">م</th><th class="nm">اسم الطالب</th>' +
        Array.from({ length: P }, (_, i) => { const l = data.lectures[pg * P + i]; return '<th>' + (l ? `${pg * P + i + 1}${esc(l.tag)}<br><small>${esc(l.day.slice(5).replace('-', '/'))}</small>` : '') + '</th>'; }).join('') + '<th class="ob">ملاحظات</th></tr>';
      const rows = data.rows.map((r) => '<tr><td>' + r.no + '</td><td class="nm">' + esc(r.name) + '</td>' +
        Array.from({ length: P }, (_, i) => { const idx = pg * P + i; return '<td class="ck">' + (idx < data.total && r.present[idx] ? '✓' : '') + '</td>'; }).join('') +
        '<td class="ob">' + (data.total ? `<span dir="ltr">${r.count}/${data.total} (${UA.AttendanceSheet.percent(r.count, data.total)}%)</span>` : '') + '</td></tr>').join('');
      body += `<section${pg ? ' class="brk"' : ''}><h1>كشف حضور وغياب الطلاب — ${esc(c.name)}</h1><p>${esc(cname(c))} — الطلاب: ${data.rows.length} — المحاضرات: ${data.total}` +
        (data.pages > 1 ? ` — أعمدة ${pg * P + 1}–${Math.min((pg + 1) * P, data.total)}` : '') + `</p><table><thead>${head}</thead><tbody>${rows}</tbody></table></section>`;
    }
    return '<!doctype html><html lang="ar" dir="rtl"><head><meta charset="utf-8"><title>' + esc('attendance_' + c.name + '_' + c.section) + '</title><style>' +
      '@page{size:A4 landscape;margin:10mm}body{font-family:system-ui,"Segoe UI",Tahoma,Arial,sans-serif;margin:0;color:#000}' +
      'h1{font-size:15px;margin:0 0 2px}p{font-size:11px;margin:0 0 6px}table{width:100%;border-collapse:collapse;table-layout:fixed;font-size:10px}' +
      'thead{display:table-header-group}tr{page-break-inside:avoid}th,td{border:.5px solid #444;padding:1px 2px;text-align:center;height:5.4mm;overflow:hidden}' +
      'th{background:#d6ecd9}small{font-size:7px;font-weight:400}.no{width:7mm}.nm{width:52mm;text-align:right;white-space:nowrap}.ob{width:34mm;font-size:8px}.ck{color:#1b7f3a;font-weight:700}' +
      '.brk{page-break-before:always}</style></head><body>' + body + '</body></html>';
  }
  function printSheet(c, data) {
    const html = sheetPrintHtml(c, data);
    const w = window.open('', '_blank');
    if (!w) { toast('اسمح بالنوافذ المنبثقة لهذا الموقع ثم أعد المحاولة'); return; }
    w.document.open(); w.document.write(html); w.document.close();
    toast('في نافذة الطباعة اختر «حفظ كـPDF» (على الآيفون: شارك ← حفظ في الملفات)', 6000);
    setTimeout(() => { try { w.focus(); w.print(); } catch (e) { /* يمكن للمستخدم الطباعة يدويًا */ } }, 400);
  }

  // ---------- الكشف ----------
  function viewSheet(m) {
    if (!D.courses.length) { m.append(h('div', { class: 'card muted' }, 'لا مواد بعد.')); return; }
    if (sheetCourse === null || !course(sheetCourse)) sheetCourse = D.courses[0].id;
    const c = course(sheetCourse);
    const sel = h('select', { onchange: () => { sheetCourse = Number(sel.value); sheetPage = 0; historyId = null; render(); } },
      D.courses.map((x) => h('option', { value: x.id, selected: x.id === sheetCourse }, cname(x))));
    const students = D.students.filter((s) => s.courseIds.includes(c.id)).sort(byName);
    const sess = D.sessions.filter((s) => s.courseId === c.id);
    const present = new Set(D.att.filter((a) => a.status !== 'CANCELLED' && a.courseId === c.id).map((a) => a.sessionId + '|' + a.studentId));
    const data = UA.AttendanceSheet.build(sess, students, present);
    const p = Math.min(sheetPage, data.pages - 1), P = UA.AttendanceSheet.PAGE;
    const head = h('tr', {}, h('th', {}, 'م'), h('th', {}, 'الاسم'),
      ...Array.from({ length: P }, (_, i) => { const l = data.lectures[p * P + i]; return h('th', {}, String(p * P + i + 1) + (l ? l.tag : '')); }), h('th', {}, 'المجموع'));
    const body = data.rows.map((r) => h('tr', {}, h('td', {}, String(r.no)), h('td', { class: 'n' }, r.name),
      ...Array.from({ length: P }, (_, i) => { const idx = p * P + i; return h('td', {}, idx < data.total ? (r.present[idx] ? '✓' : '') : ''); }),
      h('td', {}, data.total ? `${r.count}/${data.total} (${UA.AttendanceSheet.percent(r.count, data.total)}%)` : '')));
    const csv = () => {
      const t = 'studentId,name,attended,sessions,percent\n' + data.rows.map((r) => `${r.studentId},"${r.name.replace(/"/g, '""')}",${r.count},${data.total},${UA.AttendanceSheet.percent(r.count, data.total)}`).join('\n') + '\n';
      const a = h('a', { href: URL.createObjectURL(new Blob(['\ufeff' + t], { type: 'text/csv;charset=utf-8' })), download: `attendance-${c.id}.csv` }); document.body.append(a); a.click(); a.remove();
    };
    m.append(h('h2', {}, 'كشف الحضور'), sel,
      data.pages > 1 ? h('div', { class: 'chips' }, Array.from({ length: data.pages }, (_, i) => h('button', { class: 'chip' + (i === p ? ' on' : ''), onclick: () => { sheetPage = i; render(); } }, `${i * P + 1}–${Math.min((i + 1) * P, data.total)}`))) : null,
      students.length ? h('div', { class: 'sheet' }, h('table', {}, h('thead', {}, head), h('tbody', {}, body))) : h('div', { class: 'card muted' }, 'لا طلاب مسجَّلون في هذه المادة.'),
      h('div', { class: 'row' }, h('span', { class: 'badge' }, `المحاضرات: ${data.total}`), h('button', { class: 'btn ghost small', onclick: csv }, '⬇ تصدير CSV'),
        h('button', { class: 'btn small', onclick: () => printSheet(c, data), disabled: !students.length }, '🖨 تصدير PDF')));
    // سجل المحاضرات: عرض/تعديل التحضير بأثر رجعي
    const days = data.lectures.slice().reverse();
    m.append(h('h3', {}, 'سجل المحاضرات'), days.length ? h('div', { class: 'card list' }, days.map((l) => {
      const last = l.ids[l.ids.length - 1];
      const n = new Set(D.att.filter((a) => l.ids.includes(a.sessionId) && a.status !== 'CANCELLED').map((a) => a.studentId)).size;
      return h('div', { class: 'item' }, h('span', {}, l.day, l.tag ? ' ' + l.tag : '', ' ', h('span', { class: 'badge' }, n + ' حاضر')), h('button', { class: 'btn ghost small', onclick: () => { historyId = historyId === last ? null : last; render(); } }, historyId === last ? 'إخفاء' : 'تعديل'));
    })) : h('p', { class: 'muted' }, 'لا محاضرات بعد.'));
    if (historyId && sess.some((s) => s.sessionId === historyId)) m.append(h('div', { class: 'card' }, attendanceEditor(historyId, c, null).el));
  }

  // ---------- الإعدادات ----------
  function viewSettings(m) {
    m.append(
      h('div', { class: 'card' }, h('h2', {}, D.name || 'الدكتور'),
        h('p', { class: 'muted' }, `المعرّف: ${D.doctorId} • إصدار الكشف: ${D.epoch < 0 ? 'لم يصل' : D.epoch} • ${D.courses.length} مادة • ${D.students.length} طالب`),
        h('button', { class: 'btn block', onclick: () => syncNow(true) }, '⟳ مزامنة الآن'), h('p', { id: 'syncstatus', class: 'muted' }, syncing ? 'جارٍ المزامنة…' : syncInfo),
        h('p', { class: 'muted' }, 'تتم المزامنة تلقائيًا عند توفّر الإنترنت: ينزل الكشف الجديد ويُرفع الحضور (مشفّرًا) للمسؤول.')),
      h('div', { class: 'card' }, h('button', { class: 'btn ghost block', onclick: () => { if (A) { toast('أنهِ جلسة الحضور أولًا'); return; } unlocked = false; render(); } }, '🔒 قفل التطبيق الآن')),
      h('div', { class: 'card' }, h('button', { class: 'btn danger block', onclick: async () => {
        if (A) { toast('أنهِ جلسة الحضور أولًا'); return; }
        if (await confirmDialog('سيُمسح الكشف وكل سجلات الحضور من هذا الهاتف ويُفك الربط. تأكد أن الحضور رُفع للمسؤول أولًا. متابعة؟', 'مسح')) {
          const pin = D.pin; D = fresh(); D.pin = pin; save(); syncInfo = ''; render();
        }
      } }, 'فك الارتباط ومسح البيانات'),
        h('p', { class: 'center' }, h('button', { class: 'link', onclick: () => { store.del('ua.role'); location.reload(); } }, 'تغيير الدور (طالب / دكتور)'))));
  }

  function render() {
    stopLive();
    if (!unlocked) return renderLock();
    if (!D.pair) return renderPair();
    if (!A && D.activeId) {                                    // استئناف جلسة جارية بعد إعادة فتح التطبيق
      const r = clock.read();
      if (!r.trusted) { if (!resumeTried) { resumeTried = true; syncClock().then(() => { if (unlocked && D.pair && !A) render(); }); } }   // المرساة تضيع بإعادة التحميل
      else { const s = D.sessions.find((x) => x.sessionId === D.activeId); if (s && r.ms < s.startedAt + s.durationMin * 60000) A = s; else D.activeId = null; }
    }
    const m = h('main');
    if (tab === 'live') { if (A) viewLive(m); else viewStart(m); }
    else if (tab === 'sheet') viewSheet(m); else viewSettings(m);
    app.replaceChildren(h('header', { class: 'top' }, h('span', {}, 'حضور الجامعة'), h('span', { class: 'badge' }, D.name || '…')), m,
      tabsBar([['live', '📷', 'الحضور'], ['sheet', '📋', 'الكشف'], ['set', '⚙️', 'الإعدادات']], tab, (t) => { tab = t; render(); }));
  }

  return {
    mount(el) {
      app = el; render(); if (D.pair && navigator.onLine) syncNow(false);
      if (!visHooked) { visHooked = true; document.addEventListener('visibilitychange', () => { if (!document.hidden && unlocked && D.pair && navigator.onLine) syncClock(); }); }
    },
    unmount() { stopLive(); },
    onOnline() { if (D.pair && unlocked) syncNow(false); },
  };
})();
