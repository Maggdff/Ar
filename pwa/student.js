/* واجهة الطالب: تفعيل بالكود، جدولي، تسجيل الحضور بالـQR (تحدٍّ ← إجابة) */
const Student = (() => {
  const KEY = 'ua.student';
  let S = store.get(KEY, null);        // {tag, keyHex, profile, ttAt, lastTry, relay, log[]}
  let tab = 'attend', app = null, scanner = null, busy = false;
  const save = () => store.set(KEY, S);
  const stopScan = () => { if (scanner) { scanner.stop(); scanner = null; } };

  async function refresh() {
    S.lastTry = Date.now();
    const f = await UA.fetchStudent(S.keyHex, S.relay || UA.Relay.DEFAULT_URL);
    let msg;
    if (f.kind === 'Ok') {
      // أكواد فُعّلت قبل هذه الحماية: تُربط بهذا الهاتف الآن إن لم تكن مرتبطة بغيره
      try {
        const dev = UA.hex(await UA.devicePublicRaw(await DeviceKeys.device()));
        if ((await UA.claimStudentDevice(S.keyHex, dev, f.url || S.relay || UA.Relay.DEFAULT_URL)) === 'Taken') { save(); return UA.DeviceClaim.TAKEN_STUDENT; }
      } catch (e) { if (!(e instanceof UA.Relay.OfflineError)) { save(); return e instanceof UA.Relay.RelayError ? UA.Relay.friendly(e) : String(e.message || e); } }
      S.profile = f.pub; S.ttAt = Date.now(); S.relay = f.url; msg = '✓ تم تحديث جدولك وبياناتك'; }
    else if (f.kind === 'NotFound') msg = 'لم يعد كودك مسجّلًا لدى الإدارة — راجعها (بقي آخر جدول وصلك)';
    else if (f.kind === 'Offline') msg = 'لا اتصال بالإنترنت — يُحدَّث الجدول تلقائيًا عند توفّره';
    else msg = f.msg;
    save(); return msg;
  }

  // ---------- التفعيل ----------
  function renderActivate(note = '') {
    stopScan();
    const input = h('input', { class: 'code', placeholder: 'XXXX-XXXX-XXXX', autocomplete: 'off', autocapitalize: 'characters', autocorrect: 'off', spellcheck: 'false', maxlength: '24' });
    const status = h('p', { class: 'muted' }, note);
    const btn = h('button', { class: 'btn block', onclick: async () => {
      if (busy) return;
      const code = UA.Codes.normalize(input.value);
      if (!code) { status.className = 'bad'; status.textContent = 'الكود غير صحيح — تأكد من كتابته (12 خانة: أرقام وحروف).'; return; }
      busy = true; btn.disabled = true; status.className = 'muted'; status.textContent = 'جارٍ التحقق من الكود…';
      try {
        const keyHex = UA.hex(await UA.studentKey(code));
        const f = await UA.fetchStudent(keyHex, UA.Relay.DEFAULT_URL);
        if (f.kind === 'Ok') {
          try { await DeviceKeys.device(); } catch (e) { throw new Error('تعذّر التفعيل على هذا الجهاز (التخزين غير متاح — لا تستخدم التصفح الخاص)'); }
          try {
            const dev = UA.hex(await UA.devicePublicRaw(await DeviceKeys.device()));
            if ((await UA.claimStudentDevice(keyHex, dev, f.url || UA.Relay.DEFAULT_URL)) === 'Taken') { status.className = 'bad'; status.textContent = UA.DeviceClaim.TAKEN_STUDENT; return; }
          } catch (e) { throw new Error(e instanceof UA.Relay.RelayError || e instanceof UA.Relay.OfflineError ? UA.Relay.friendly(e) : (e.message || String(e))); }
          S = { tag: UA.Codes.tag(code), keyHex, profile: f.pub, ttAt: Date.now(), lastTry: Date.now(), relay: f.url, log: [] };
          save(); try { navigator.storage && navigator.storage.persist && navigator.storage.persist(); } catch (e) { /* اختياري */ }
          tab = 'attend'; render(); return;
        }
        status.className = 'bad';
        status.textContent = f.kind === 'NotFound' ? 'هذا الكود غير مسجَّل لدى الإدارة (أو لم تتم مزامنته بعد). تأكد من كتابته وأعد المحاولة، وإن استمرت المشكلة فراجع الإدارة.'
          : f.kind === 'Offline' ? 'لا يوجد اتصال بالإنترنت. أول تفعيل يحتاج إنترنت للتحقق من الكود؛ اتصل بالإنترنت ثم أعد المحاولة.' : f.msg;
      } catch (e) { status.className = 'bad'; status.textContent = e.message || String(e); }
      finally { busy = false; btn.disabled = false; }
    } }, 'تفعيل');
    app.replaceChildren(
      h('header', { class: 'top' }, h('span', {}, 'حضور الجامعة — الطالب')),
      h('main', {}, h('div', { class: 'card' },
        h('h2', {}, 'تفعيل التطبيق'),
        h('p', { class: 'muted' }, 'اكتب الكود الذي أعطتك إياه الإدارة (مرة واحدة فقط). يلزم إنترنت في هذه الخطوة فقط للتحقق منه.'),
        input, btn, status),
        h('div', { class: 'hint' }, 'بعد التفعيل يظهر جدولك وبياناتك. سجّل حضورك في أول محاضرة بمسح رمز QR الذي يعرضه الدكتور؛ فيرتبط هذا الجهاز بهويتك.'),
        h('p', { class: 'center' }, h('button', { class: 'link', onclick: () => { store.del('ua.role'); location.reload(); } }, 'تغيير الدور (طالب / دكتور)'))));
  }

  // ---------- الحضور ----------
  function replyDialog(text, ok) {
    const d = h('dialog', {}, h('h3', {}, 'اعرض هذا الرمز على هاتف الدكتور'),
      h('div', { class: 'center' }, qrBox(text)),
      h('p', { class: 'muted center' }, 'يمسحه الدكتور بكاميرا هاتفه. يبقى صالحًا قرابة دقيقة من ظهور رمز الدكتور؛ إن تأخرت فامسح رمزه من جديد.'),
      h('button', { class: 'btn block', onclick: () => { d.close(); d.remove(); } }, 'تم'));
    document.body.append(d); d.showModal();
  }

  async function onChallenge(text, msgEl) {
    if (busy) return;
    const req = UA.QrProtocol.parseChallenge(text);
    if (!req) {
      msgEl.className = 'bad'; msgEl.textContent = 'هذا ليس رمز الدكتور. اطلب منه تفعيل «تسجيل بالـQR» في شاشة الحضور المباشر ثم امسح الرمز الذي يظهر في هاتفه.'; return;
    }
    // «لا تحضير إلا حسب الجدول»: وقت التحدّي هو وقت هاتف الدكتور (الموثوق)، فلا يفيد تغيير ساعة هاتف الطالب
    const gate = UA.AttendancePolicy.checkStudent((S.profile && S.profile.timetable) || [], req.timeMs);
    if (!gate.ok) { msgEl.className = 'bad'; msgEl.textContent = gate.message; return; }
    busy = true; stopScan();
    try {
      const dev = await DeviceKeys.device();
      const reply = await UA.studentReply({ tag: S.tag, keyHex: S.keyHex }, dev, req);
      S.log = [{ t: Date.now(), s: UA.hex(req.sessionId).slice(0, 8) }, ...(S.log || [])].slice(0, 30); save();
      render(); replyDialog(reply);
    } catch (e) { msgEl.className = 'bad'; msgEl.textContent = 'تعذّر تجهيز الإجابة على هذا الجهاز'; }
    finally { busy = false; }
  }

  function viewAttend(m) {
    const msgEl = h('p', { class: 'muted' });
    const holder = h('div');
    const startBtn = h('button', { class: 'btn block', onclick: async () => {
      if (scanner) return;
      scanner = new Scanner((t) => onChallenge(t, msgEl));
      holder.replaceChildren(scanner.el);
      if (!(await scanner.start())) { stopScan(); holder.replaceChildren(); return; }
      msgEl.className = 'muted'; msgEl.textContent = 'وجّه الكاميرا إلى الرمز الظاهر في هاتف الدكتور…';
    } }, '📷 مسح رمز الدكتور وتسجيل الحضور');
    m.append(
      h('div', { class: 'card' }, h('h2', {}, 'تسجيل الحضور'),
        h('p', { class: 'muted' }, 'اطلب من الدكتور تفعيل «تسجيل بالـQR»، ثم امسح الرمز الظاهر في هاتفه وأره الرمز الذي سيظهر عندك. يتأكد الدكتور من كودك ومن جهازك دون إنترنت عندك. لا يُسجَّل الحضور إلا في وقت محاضرة من جدولك.'),
        startBtn, holder, msgEl),
      h('div', { class: 'card' }, h('h3', {}, 'آخر إجاباتك'),
        (S.log || []).length ? h('div', { class: 'list' }, S.log.slice(0, 8).map((l) => h('div', { class: 'item' }, h('span', {}, fmtDateTime(l.t)), h('span', { class: 'badge' }, 'أُرسلت إجابة')))) : h('p', { class: 'muted' }, 'لا إجابات بعد.'),
        h('p', { class: 'muted' }, 'نتيجة التسجيل (✓ أو ✗) تظهر في هاتف الدكتور. إن لم يُسجَّل حضورك فاعرض الرمز مرة أخرى أو اطلب من الدكتور تحضيرك يدويًا.')),
      h('div', { class: 'hint' }, '⚠ هذا الجهاز يرتبط بهويتك عند أول حضور. لا تمسح بيانات Safari ولا تحذف التطبيق من الشاشة الرئيسية، وإلا ضاع الربط واحتجت لكود جديد من الإدارة.'));
  }

  // ---------- الجدول ----------
  function viewTimetable(m) {
    const tt = (S.profile.timetable || []).slice().sort((a, b) => a.startMin - b.startMin);
    m.append(h('div', { class: 'row between' }, h('h2', {}, 'جدولي'), h('span', { class: 'muted' }, S.ttAt ? 'تحديث: ' + fmtDateTime(S.ttAt) : '')));
    if (!tt.length) m.append(h('div', { class: 'card muted' }, 'لا يوجد جدول بعد. يظهر هنا عندما تنشره الإدارة.'));
    for (const d of DAY_ORDER) {
      const es = tt.filter((e) => e.day === d); if (!es.length) continue;
      m.append(h('h3', {}, UA.Schedule.DAYS[d]));
      for (const e of es) {
        const cls = e.kind === 2 ? 'exam' : e.kind === 1 ? 'makeup' : e.practical ? 'prac' : '';
        m.append(h('div', { class: 'tt ' + cls },
          h('strong', {}, e.title + (e.practical ? ' (عملي)' : '') + (e.kind === 1 ? ' — تعويضية' : e.kind === 2 ? ' — اختبار' : '')),
          h('small', {}, UA.Timetable.timeRange(e) + ' • ' + UA.Timetable.audience(e)),
          e.teacher ? h('small', {}, e.teacher) : null, e.room ? h('small', {}, e.room) : null, e.note ? h('small', {}, e.note) : null));
      }
    }
  }

  // ---------- بياناتي ----------
  function viewMe(m) {
    const p = S.profile, st = h('p', { class: 'muted' });
    const row = (k, v) => v ? h('div', { class: 'item' }, h('span', { class: 'muted' }, k), h('strong', {}, v)) : null;
    m.append(
      h('div', { class: 'card' }, h('h2', {}, p.name), h('div', { class: 'list' },
        row('الرقم', p.studentId), row('الكلية', p.faculty), row('التخصص', p.major), row('المستوى', p.level), row('الشعبة', p.section), row('مجموعة العملي', p.group))),
      h('div', { class: 'card' }, h('h3', {}, 'موادي'),
        p.courses.length ? h('div', { class: 'list' }, p.courses.map((c) => h('div', { class: 'item' }, h('span', {}, c)))) : h('p', { class: 'muted' }, 'لا مواد مسجَّلة بعد.')),
      h('div', { class: 'card' },
        h('button', { class: 'btn block', onclick: async (ev) => { ev.target.disabled = true; st.textContent = 'جارٍ التحديث…'; st.textContent = await refresh(); ev.target.disabled = false; render(); } }, '⟳ تحديث الجدول والبيانات'),
        st, h('p', { class: 'muted' }, 'كودك: ' + S.tag + '-••••-••••')),
      h('div', { class: 'card' },
        h('button', { class: 'btn danger block', onclick: async () => {
          if (await confirmDialog('سيُمسح كودك وبياناتك من هذا التطبيق (يبقى مفتاح الجهاز). ستحتاج لإدخال الكود من جديد. متابعة؟', 'مسح')) { S = null; store.del(KEY); render(); }
        } }, 'مسح بياناتي من هذا الجهاز'),
        h('p', { class: 'center' }, h('button', { class: 'link', onclick: () => { store.del('ua.role'); location.reload(); } }, 'تغيير الدور (طالب / دكتور)'))));
  }

  function render() {
    stopScan();
    if (!S) return renderActivate();
    const m = h('main');
    if (tab === 'attend') viewAttend(m); else if (tab === 'tt') viewTimetable(m); else viewMe(m);
    app.replaceChildren(h('header', { class: 'top' }, h('span', {}, 'حضور الجامعة'), h('span', { class: 'badge' }, S.profile.name)), m,
      tabsBar([['attend', '📷', 'الحضور'], ['tt', '🗓', 'جدولي'], ['me', '👤', 'بياناتي']], tab, (t) => { tab = t; render(); }));
  }

  return {
    mount(el) {
      app = el; render();
      if (S && navigator.onLine && Date.now() - (S.lastTry || 0) > 3600e3) refresh().then(() => { if (app.isConnected && tab !== 'attend') render(); });
    },
    unmount: stopScan,
  };
})();
