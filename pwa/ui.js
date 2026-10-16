/* مساعدات الواجهة: عناصر DOM آمنة (نصوص فقط)، تخزين، كاميرا، رسم QR */
const $ = (s, r = document) => r.querySelector(s);
function h(tag, attrs, ...kids) {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (k === 'class') e.className = v;
    else if (k.startsWith('on') && typeof v === 'function') e.addEventListener(k.slice(2), v);
    else if (v !== false && v != null) e.setAttribute(k, v === true ? '' : v);
  }
  for (const c of kids.flat(Infinity)) if (c !== null && c !== undefined && c !== false) e.append(c instanceof Node ? c : document.createTextNode(String(c)));
  return e;
}
const store = {
  get(k, d = null) { try { const v = localStorage.getItem(k); return v === null ? d : JSON.parse(v); } catch (e) { return d; } },
  set(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); return true; } catch (e) { toast('تعذّر الحفظ على هذا الجهاز (المساحة ممتلئة؟)'); return false; } },
  del(k) { try { localStorage.removeItem(k); } catch (e) { /* لا شيء */ } },
};
let toastTimer;
function toast(msg, ms = 3500) {
  const t = $('#toast'); t.textContent = msg; t.classList.add('show');
  clearTimeout(toastTimer); toastTimer = setTimeout(() => t.classList.remove('show'), ms);
}
const pad2 = (n) => String(n).padStart(2, '0');
const fmtClock = (ms) => { const d = new Date(ms); return pad2(d.getHours()) + ':' + pad2(d.getMinutes()); };
const fmtDateTime = (ms) => { const d = new Date(ms); return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())} ${fmtClock(ms)}`; };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** يرسم رمز QR أبيض/أسود دائمًا (يُمسح بسهولة حتى في الوضع الداكن). */
function drawQr(canvas, text, px = 360) {
  const qr = qrcode(0, 'L'); qr.addData(text, 'Byte'); qr.make();
  const n = qr.getModuleCount(), margin = 2, cell = Math.max(2, Math.floor(px / (n + margin * 2)));
  const size = cell * (n + margin * 2);
  canvas.width = canvas.height = size;
  const g = canvas.getContext('2d'); g.fillStyle = '#fff'; g.fillRect(0, 0, size, size); g.fillStyle = '#000';
  for (let r = 0; r < n; r++) for (let c = 0; c < n; c++) if (qr.isDark(r, c)) g.fillRect((c + margin) * cell, (r + margin) * cell, cell, cell);
}
function qrBox(text) { const c = document.createElement('canvas'); drawQr(c, text); return h('div', { class: 'qrbox' }, c); }

/** ماسح QR بالكاميرا (HTTPS مطلوب). onText يُستدعى لكل قراءة؛ المستدعي يتجاهل المكرر. */
class Scanner {
  constructor(onText) {
    this.onText = onText; this.running = false; this.stream = null;
    this.video = h('video', { playsinline: true, muted: true, autoplay: true });
    this.video.muted = true;
    this.canvas = document.createElement('canvas'); this.ctx = this.canvas.getContext('2d', { willReadFrequently: true });
    this.el = h('div', { class: 'cam' }, this.video);
  }
  async start() {
    if (this.running) return true;
    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) { toast('هذا المتصفح لا يدعم الكاميرا — افتح الصفحة من Safari عبر HTTPS'); return false; }
    try {
      this.stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: { ideal: 'environment' } }, audio: false });
    } catch (e) { toast('لم يُسمح باستخدام الكاميرا. فعّلها من إعدادات Safari ثم أعد المحاولة.'); return false; }
    this.video.srcObject = this.stream;
    try { await this.video.play(); } catch (e) { /* iOS قد يؤجّل التشغيل */ }
    this.running = true; this.tick(); return true;
  }
  tick() {
    if (!this.running) return;
    const v = this.video;
    if (v.readyState >= 2 && v.videoWidth) {
      const scale = Math.min(1, 640 / v.videoWidth), w = Math.round(v.videoWidth * scale), hh = Math.round(v.videoHeight * scale);
      this.canvas.width = w; this.canvas.height = hh; this.ctx.drawImage(v, 0, 0, w, hh);
      const res = jsQR(this.ctx.getImageData(0, 0, w, hh).data, w, hh, { inversionAttempts: 'dontInvert' });
      if (res && res.data) this.onText(res.data);
    }
    this.timer = setTimeout(() => requestAnimationFrame(() => this.tick()), 130);
  }
  stop() {
    this.running = false; clearTimeout(this.timer);
    if (this.stream) this.stream.getTracks().forEach((t) => t.stop());
    this.stream = null; this.video.srcObject = null;
  }
}

/** مخزن مفتاح الجهاز (IndexedDB): المفتاح الخاص غير قابل للاستخراج. */
const DeviceKeys = (() => {
  const open = () => new Promise((res, rej) => {
    const r = indexedDB.open('uniatt', 1);
    r.onupgradeneeded = () => r.result.createObjectStore('keys');
    r.onsuccess = () => res(r.result); r.onerror = () => rej(r.error);
  });
  const get = async (k) => { const db = await open(); return new Promise((res, rej) => { const q = db.transaction('keys').objectStore('keys').get(k); q.onsuccess = () => res(q.result || null); q.onerror = () => rej(q.error); }); };
  const put = async (k, v) => { const db = await open(); return new Promise((res, rej) => { const tx = db.transaction('keys', 'readwrite'); tx.objectStore('keys').put(v, k); tx.oncomplete = () => res(); tx.onerror = () => rej(tx.error); }); };
  /** يعيد مفتاح الجهاز، وينشئه أول مرة فقط. */
  const device = async () => { let p = await get('device'); if (!p) { p = await UA.newDeviceKey(); await put('device', p); } return p; };
  return { device, has: async () => !!(await get('device')) };
})();

const DAY_ORDER = [6, 0, 1, 2, 3, 4, 5];   // السبت أولًا كجدول الكلية

function tabsBar(items, current, onPick) {
  return h('nav', { class: 'tabs' }, items.map(([id, icon, label]) =>
    h('button', { class: id === current ? 'on' : '', onclick: () => onPick(id) }, h('span', { class: 'ic' }, icon), label)));
}
function confirmDialog(msg, okLabel = 'تأكيد') {
  return new Promise((resolve) => {
    const d = h('dialog', {}, h('p', {}, msg), h('div', { class: 'row between' },
      h('button', { class: 'btn danger', onclick: () => { d.close(); d.remove(); resolve(true); } }, okLabel),
      h('button', { class: 'btn ghost', onclick: () => { d.close(); d.remove(); resolve(false); } }, 'إلغاء')));
    document.body.append(d); d.showModal();
  });
}
