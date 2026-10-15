'use strict';
const $ = s => document.querySelector(s), app = $('#app'), nav = $('#nav');
const DAYS = ['الأحد', 'الاثنين', 'الثلاثاء', 'الأربعاء', 'الخميس', 'الجمعة', 'السبت'];
const esc = s => String(s ?? '').replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
const load = () => { try { return JSON.parse(localStorage.getItem('ua.student')); } catch (e) { return null; } };
const save = d => localStorage.setItem('ua.student', JSON.stringify(d));
let state = load(), tab = 'home', stream = null;

// مفتاح الجهاز: غير قابل للاستخراج، محفوظ في IndexedDB ولا يُحذف عند «تسجيل الخروج»
const idb = () => new Promise((res, rej) => { const r = indexedDB.open('ua-device', 1); r.onupgradeneeded = () => r.result.createObjectStore('k'); r.onsuccess = () => res(r.result); r.onerror = () => rej(r.error); });
async function deviceKey() {
  const db = await idb(), get = () => new Promise(r => { const q = db.transaction('k').objectStore('k').get('kp'); q.onsuccess = () => r(q.result); q.onerror = () => r(null); });
  let kp = await get(); if (kp) return kp;
  kp = await UA.DeviceKey.generate();
  await new Promise((res, rej) => { const t = db.transaction('k', 'readwrite'); t.objectStore('k').put(kp, 'kp'); t.oncomplete = res; t.onerror = () => rej(t.error); });
  return kp;
}
function qrSvg(text) { const q = qrcode(0, 'L'); q.addData(text); q.make(); return q.createSvgTag({ scalable: true, margin: 2 }); }

function render() {
  stopCam();
  if (!state) { nav.hidden = true; return renderActivate(); }
  nav.hidden = false;
  nav.innerHTML = [['home', 'الجدول'], ['attend', 'تسجيل الحضور'], ['me', 'بياناتي']].map(([k, v]) => `<a href="#" data-t="${k}" class="${tab === k ? 'on' : ''}">${v}</a>`).join('');
  nav.querySelectorAll('a').forEach(a => a.onclick = e => { e.preventDefault(); tab = a.dataset.t; render(); });
  ({ home: renderTimetable, attend: renderAttend, me: renderMe })[tab]();
}

function renderActivate(msg = '') {
  app.innerHTML = `<h2>تفعيل التطبيق</h2><div class="card"><p>اكتب كودك (XXXX-XXXX-XXXX). يلزم إنترنت لأول تفعيل فقط.</p>
  <input id="code" placeholder="XXXX-XXXX-XXXX" autocapitalize="characters" autocomplete="off"><button id="go">تفعيل</button><p id="msg" class="${msg ? 'err' : 'm'}">${esc(msg)}</p></div>`;
  $('#go').onclick = async () => {
    const code = UA.Codes.normalize($('#code').value), m = $('#msg'); m.className = 'm';
    if (!code) { m.className = 'err'; m.textContent = 'الكود غير صحيح — تأكد من كتابته.'; return; }
    m.textContent = 'جارٍ التحقق…'; $('#go').disabled = true;
    const keyHex = UA.hex(await UA.studentKey(code)), r = await UA.fetchStudent(keyHex);
    $('#go').disabled = false;
    if (r.ok) { state = { code, keyHex, pub: r.pub, at: Date.now() }; save(state); tab = 'home'; render(); }
    else { m.className = 'err'; m.textContent = r.notFound ? 'الكود غير مسجَّل عند الإدارة (أو لم تتم مزامنته بعد).' : r.offline ? 'لا يوجد اتصال بالإنترنت.' : r.error; }
  };
}

function renderTimetable() {
  const p = state.pub, mine = p.timetable.filter(e => (!e.section || e.section === p.section) && (!e.group || e.group === p.group));
  const byDay = d => mine.filter(e => e.day === d).sort((a, b) => a.startMin - b.startMin);
  app.innerHTML = `<div class="row"><h2>جدولي</h2><button class="s" id="rf" style="width:auto">تحديث</button></div><p class="m" id="st">آخر تحديث: ${new Date(state.at).toLocaleString('ar')}</p>` +
    (mine.length ? DAYS.map((n, d) => byDay(d).length ? `<div class="card"><b>${n}</b>${byDay(d).map(e => `<div class="row" style="margin-top:8px"><span>${esc(e.title)}${e.practical ? ' (عملي)' : ''}${e.kind ? ' — ' + ['', 'تعويضية', 'اختبار'][e.kind] : ''}<br><span class="m">${esc(e.teacher)} ${esc(e.room)}</span></span><span class="m" dir="ltr">${UA.Timetable.fmt(e.startMin)}–${UA.Timetable.fmt(e.endMin)}</span></div>`).join('')}</div>` : '').join('') : '<div class="card m">لا توجد محاضرات في جدولك.</div>');
  $('#rf').onclick = async () => { $('#st').textContent = 'جارٍ التحديث…'; const r = await UA.fetchStudent(state.keyHex); if (r.ok) { state.pub = r.pub; state.at = Date.now(); save(state); render(); } else $('#st').textContent = r.offline ? 'لا يوجد إنترنت' : (r.notFound ? 'لم يعد كودك مسجَّلًا — راجع الإدارة' : r.error); };
}

function renderMe() {
  const p = state.pub, rows = [['الاسم', p.name], ['الرقم', p.studentId], ['الكلية', p.faculty], ['التخصص', p.major], ['المستوى', p.level], ['الشعبة', p.section], ['المجموعة', p.group], ['المواد', p.courses.join('، ')]];
  app.innerHTML = `<h2>بياناتي</h2><div class="card">${rows.map(([k, v]) => `<div class="row"><span class="m">${k}</span><span>${esc(v) || '—'}</span></div>`).join('')}</div>
  <button class="s" id="out">تسجيل الخروج من هذا الجهاز</button><p class="m">مفتاح جهازك يبقى مرتبطًا بكودك حتى بعد الخروج، ولا يمكن نقل الكود إلى هاتف آخر دون الإدارة.</p>`;
  $('#out').onclick = () => { if (confirm('تسجيل الخروج؟')) { localStorage.removeItem('ua.student'); state = null; render(); } };
}

function stopCam() { if (stream) { stream.getTracks().forEach(t => t.stop()); stream = null; } }

function renderAttend() {
  app.innerHTML = `<h2>تسجيل الحضور بالـQR</h2><div class="card"><p>١) اضغط «مسح رمز الدكتور» ووجّه الكاميرا نحو الرمز المتغيّر على شاشة الدكتور.<br>٢) سيظهر عندك رمز؛ أرِه لكاميرا الدكتور وأبقِه ثابتًا.</p><button id="scan">مسح رمز الدكتور</button></div><div id="out"></div>`;
  $('#scan').onclick = startScan;
}

async function startScan() {
  const out = $('#out'); out.innerHTML = '<video id="v" playsinline muted></video><p class="m" id="hint">وجّه الكاميرا نحو رمز الدكتور…</p>';
  try { stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment' }, audio: false }); }
  catch (e) { out.innerHTML = '<p class="err">تعذّر فتح الكاميرا — اسمح بها من إعدادات Safari للموقع.</p>'; return; }
  const v = $('#v'); v.srcObject = stream; await v.play();
  const cv = document.createElement('canvas'), cx = cv.getContext('2d', { willReadFrequently: true });
  const tick = async () => {
    if (!stream) return;
    if (v.videoWidth) {
      cv.width = v.videoWidth; cv.height = v.videoHeight; cx.drawImage(v, 0, 0);
      const q = jsQR(cx.getImageData(0, 0, cv.width, cv.height).data, cv.width, cv.height), ch = q && UA.QrProtocol.parseChallenge(q.data);
      if (ch) {
        stopCam();
        if (Math.abs(Date.now() - ch.timeMs) > 5 * 60 * 1000) { out.innerHTML = '<p class="err">ساعة هاتفك أو هاتف الدكتور غير مضبوطة (فرق كبير في الوقت).</p>'; return; }
        const text = await UA.QrProtocol.reply({ tag: UA.Codes.tag(state.code), keyBytes: UA.unhex(state.keyHex) }, await deviceKey(), ch);
        let left = 60; out.innerHTML = `<div class="card"><div id="qr">${qrSvg(text)}</div><p class="ok">اعرض هذا الرمز للدكتور وأبقِه ثابتًا.</p><p class="m" id="t"></p><button class="s" id="again">مسح من جديد</button></div>`;
        $('#again').onclick = renderAttend;
        const iv = setInterval(() => { const t = $('#t'); if (!t) return clearInterval(iv); t.textContent = 'صالح لمدة ' + left + ' ثانية'; if (--left < 0) { clearInterval(iv); out.innerHTML = '<p class="err">انتهت صلاحية الرمز — امسح رمز الدكتور من جديد.</p>'; } }, 1000);
        return;
      }
    }
    requestAnimationFrame(tick);
  };
  tick();
}

if ('serviceWorker' in navigator) navigator.serviceWorker.register('sw.js').catch(() => {});
render();
