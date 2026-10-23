// اختبار شامل لواجهة PWA: طالب ودكتور في متصفحَين افتراضيَين (jsdom) مع خادم Firebase وهمي، وتمرير رموز QR بينهما.
// يتطلب:  npm i jsdom fake-indexeddb   (خارج المشروع)  ثم  NODE_PATH=.../node_modules node e2e.test.js
const fs = require('fs'), path = require('path'), { spawn } = require('child_process');
const { JSDOM } = require('jsdom');
const { IDBFactory } = require('fake-indexeddb');
const PWA = path.resolve(__dirname, '../../pwa');
const UA = require(PWA + '/core.js');
const PORT = 18900 + Math.floor(Math.random() * 500), URL_ = `http://127.0.0.1:${PORT}`;
let pass = 0, fail = 0;
const ok = (c, n) => { if (c) { pass++; console.log('  PASS  ' + n); } else { fail++; console.log('  FAIL  ' + n); } };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function until(fn, ms = 6000, step = 40) { const t0 = Date.now(); for (;;) { try { const v = fn(); if (v) return v; } catch (e) { /* انتظر */ } if (Date.now() - t0 > ms) return false; await sleep(step); } }

function newBrowser() {
  const html = fs.readFileSync(PWA + '/index.html', 'utf8').replace(/<script[^>]*><\/script>/g, '');
  const dom = new JSDOM(html, { url: 'http://localhost/', pretendToBeVisual: true, runScripts: 'dangerously' });
  const w = dom.window;
  Object.defineProperty(w, 'crypto', { value: require('node:crypto').webcrypto });
  Object.assign(w, { fetch, TextEncoder, TextDecoder, indexedDB: new IDBFactory(), matchMedia: () => ({ matches: false }) });
  w.HTMLCanvasElement.prototype.getContext = () => ({ fillRect() {}, drawImage() {}, getImageData: () => ({ data: [] }), set fillStyle(v) {} });
  w.HTMLDialogElement.prototype.showModal = function () { this.setAttribute('open', ''); };
  w.HTMLDialogElement.prototype.close = function () { this.removeAttribute('open'); };
  const run = (src) => { const el = w.document.createElement('script'); el.textContent = src; w.document.head.appendChild(el); };   // نطاق عام مشترك كالمتصفح
  run('window.__errors = []; window.addEventListener("error", e => window.__errors.push(String(e.message)));');
  for (const f of ['vendor/jsQR.js', 'vendor/qrcode.js', 'core.js', 'ui.js', 'student.js', 'doctor.js']) run(fs.readFileSync(`${PWA}/${f}`, 'utf8'));
  // كاميرا وهمية ورسم QR يلتقط النص بدل الرسم
  run(`Scanner = class { constructor(t){ this.onText=t; this.el=document.createElement('div'); window.__scan=this; } async start(){ return true; } stop(){ if (window.__scan===this) window.__scan=null; } };
       window.__qr = []; drawQr = (c, text) => { window.__qr.push(text); };`);
  run(`UA.Relay.DEFAULT_URL = ${JSON.stringify(URL_)};`);
  run(fs.readFileSync(`${PWA}/main.js`, 'utf8'));
  return w;
}
const text = (w) => w.document.body.textContent;
const click = (w, label, root) => { const b = [...(root || w.document).querySelectorAll('button')].find((x) => x.textContent.includes(label)); if (!b) throw new Error('no button: ' + label); b.click(); return b; };
const lastQr = (w) => w.__qr[w.__qr.length - 1];

(async () => {
  const srv = spawn('python3', [path.resolve(__dirname, '../relay-test/fake_rtdb.py'), String(PORT)], { stdio: 'ignore' });
  await sleep(700);
  const relay = new UA.Relay.Client(URL_);
  try {
    // ---------- جهة المسؤول (محاكاة): طالب بكود، ودكتور بكشف ----------
    const rb = UA.rand(11); let body = ''; for (const b of rb) body += UA.Codes.ALPHABET[b % 32];
    const code = body + UA.Codes.check(body), keyHex = UA.hex(await UA.studentKey(code)), tag = UA.Codes.tag(code);
    const dow = new Date().getDay();
    const ttLine = [1, 'علوم الحاسب', 'الأول', 6, 480, 600, 'برمجة', 0, 0, 'أ', '', 'د. علي', 'قاعة1', ''].join('\t');
    const ttToday = [2, 'علوم الحاسب', 'الأول', dow, 0, 1440, 'مختبر', 0, 0, 'أ', '', 'د. علي', 'قاعة1', ''].join('\t');   // «لا تحضير إلا حسب الجدول»: محاضرة اليوم كله
    const stuText = `USP1\nP\tS1\tأحمد علي\tكلية\tعلوم الحاسب\tالأول\tأ\tمج1\nC\tبرمجة (نظري — شعبة أ)\nT\t${ttLine}\nT\t${ttToday}\n`;
    await relay.putBytes(await UA.StudentBox.path(keyHex), await UA.StudentBox.seal(keyHex, UA.utf8(stuText)));
    const dcode = (() => { const r = UA.rand(11); let b = ''; for (const x of r) b += UA.Codes.ALPHABET[x % 32]; return b + UA.Codes.check(b); })();
    const mbox = UA.hex(UA.rand(16));
    const nowMin = new Date().getHours() * 60 + new Date().getMinutes();
    const stuLine = `S\tS1\tأحمد علي\tكلية\tعلوم الحاسب\tالأول\tأ\t${tag}\t${keyHex}\t1\n`;
    const mkRoster = (epoch, day) => `UAR1\nE\t${epoch}\nD\tD1\tد. علي\nC\t1\tنظري\tبرمجة\tأ\nT\t1\t${day}\t0\t1440\tقاعة1\n${stuLine}`;
    const rosterText = mkRoster(5, dow);
    await relay.putBytes(`m/${mbox}/roster`, await UA.Box.seal(UA.Box.MAGIC_ROSTER, 'D1', UA.utf8(rosterText), dcode));
    await relay.putString(`m/${mbox}/rv`, '5');
    const pairing = `UAQ1|${URL_}|${mbox}|D1|${dcode}`;

    // ---------- الطالب: التفعيل ----------
    console.log('[الطالب: التفعيل]');
    const sw = newBrowser();
    ok(await until(() => text(sw).includes('من أنت؟')), 'شاشة اختيار الدور');
    click(sw, 'طالب');
    ok(await until(() => text(sw).includes('تفعيل التطبيق')), 'شاشة التفعيل');
    const inp = sw.document.querySelector('input.code');
    inp.value = UA.Codes.format(code).slice(0, 14) + 'X'; click(sw, 'تفعيل');   // خطأ في الكود
    ok(await until(() => text(sw).includes('الكود غير صحيح')), 'كود بخانة تحقق خاطئة يُرفض محليًا');
    inp.value = UA.Codes.format(UA.rand(11).reduce((a, b) => a + UA.Codes.ALPHABET[b % 32], '') + '0'); click(sw, 'تفعيل');
    await sleep(100);
    inp.value = UA.Codes.format(code).toLowerCase(); click(sw, 'تفعيل');
    ok(await until(() => text(sw).includes('تسجيل الحضور') && text(sw).includes('أحمد علي'), 8000), 'التفعيل بكود صحيح (بحروف صغيرة وشرطات) يفتح الشاشة الرئيسية');
    const sst = JSON.parse(sw.localStorage.getItem('ua.student'));
    ok(sst.tag === tag && sst.keyHex === keyHex && sst.profile.group === 'مج1' && !JSON.stringify(sst).includes(code), 'يُحفظ المفتاح المشتق وليس الكود نفسه');
    click(sw, 'جدولي');
    ok(await until(() => text(sw).includes('برمجة') && text(sw).includes('السبت') && text(sw).includes('08:00–10:00')), 'جدول الطالب بتفاصيله');
    click(sw, 'بياناتي');
    ok(await until(() => text(sw).includes('علوم الحاسب') && text(sw).includes('برمجة (نظري — شعبة أ)')), 'بيانات الطالب ومواده');
    click(sw, 'الحضور');

    // ---------- الدكتور: القفل والربط والمزامنة ----------
    console.log('[الدكتور: الربط والمزامنة]');
    const dw = newBrowser();
    click(dw, 'دكتور');
    ok(await until(() => text(dw).includes('إنشاء رمز دخول')), 'يطلب إنشاء رمز دخول');
    const [p1, p2] = dw.document.querySelectorAll('input'); p1.value = '1234'; p2.value = '9999'; click(dw, 'حفظ ودخول');
    ok(await until(() => text(dw).includes('غير متطابقين')), 'رمزان مختلفان يُرفضان');
    p2.value = '1234'; click(dw, 'حفظ ودخول');
    ok(await until(() => text(dw).includes('الربط بالمسؤول')), 'بعد الرمز تظهر شاشة الربط');
    dw.document.querySelector('textarea').value = 'UAQ1|http://evil.example|' + mbox + '|D1|' + dcode; click(dw, 'ربط بالنص');
    ok(await until(() => dw.document.getElementById('toast').textContent.includes('ليس رمز ربط صالحًا')), 'رمز ربط بعنوان غير آمن يُرفض');
    dw.document.querySelector('textarea').value = pairing; click(dw, 'ربط بالنص');
    ok(await until(() => text(dw).includes('بدء الحضور'), 8000), 'الربط ثم نزول الكشف وفتحه');
    const dst = JSON.parse(dw.localStorage.getItem('ua.doctor'));
    ok(dst.epoch === 5 && dst.name === 'د. علي' && dst.students.length === 1 && dst.courses[0].name === 'برمجة' && dst.ackEpoch === 5, 'الكشف محفوظ ومعه إشعار الاستلام');
    ok(await relay.getLong(`m/${mbox}/ack`) === 5, 'الإشعار وصل إلى الترحيل');
    ok(await until(() => text(dw).includes('حسب الجدول الآن')), 'يقترح المحاضرة الجارية حسب الجدول (بالوقت الموثوق من الخادم)');

    // ---------- الحضور بالـQR ----------
    console.log('[الحضور بالـQR]');
    click(dw, 'بدء الحضور بالـQR');
    ok(await until(() => text(dw).includes('رمز الحضور') && dw.__scan), 'شاشة الحضور المباشر والكاميرا');
    const ch1 = lastQr(dw);
    ok(ch1 && ch1.startsWith('UAC1|'), 'يعرض تحدّي QR');
    const scanStudent = async (challenge) => {            // الطالب يمسح تحدّي الدكتور ويعرض إجابته
      click(sw, 'الحضور'); await until(() => [...sw.document.querySelectorAll('button')].some((b) => b.textContent.includes('مسح رمز الدكتور')));
      click(sw, 'مسح رمز الدكتور'); await until(() => sw.__scan);
      const before = sw.__qr.length; sw.__scan.onText(challenge);
      return (await until(() => sw.__qr.length > before)) ? lastQr(sw) : null;
    };
    const rep = await scanStudent(ch1);
    ok(rep && rep.startsWith('UAR1|'), 'الطالب يمسح التحدّي ويعرض رمز إجابة');
    ok(sw.document.querySelector('dialog[open]') !== null, 'نافذة رمز الإجابة مفتوحة عند الطالب');
    sw.document.querySelector('dialog[open] button').click();
    dw.__scan.onText(rep);
    ok(await until(() => text(dw).includes('✓ تم تسجيل حضور أحمد علي')), 'الدكتور يسجّل الحضور بعد التحقق من الكود والتوقيع');
    ok(await until(() => text(dw).includes('المسجَّلون: 1')), 'عدّاد المسجَّلين = 1');
    dw.__scan.onText(rep);
    await sleep(150);
    ok((text(dw).match(/تم تسجيل حضور أحمد/g) || []).length === 1, 'إعادة عرض الإجابة نفسها لا تكرّر الحدث');
    ok(JSON.parse(dw.localStorage.getItem('ua.doctor')).students[0].publicKeyHex.length === 130, 'ربط جهاز الطالب عند أول حضور');

    console.log('[رفض الحالات الخاطئة]');
    dw.__scan.onText('UAR1|' + UA.b64u(UA.rand(16)) + '|' + UA.b64u(UA.rand(120)));
    dw.__scan.onText('hello');
    await sleep(100);
    ok(!text(dw).includes('✗'), 'نص QR عشوائي (ليس إجابة) يُتجاهل بلا ضجيج');
    const fakeReq = { sessionId: UA.rand(16), nonce: UA.rand(16), timeMs: Date.now() };
    const forged = await UA.studentReply({ tag, keyHex }, await UA.newDeviceKey(), fakeReq);
    dw.__scan.onText(forged);
    ok(await until(() => text(dw).includes('رمز قديم أو لم يصدر من هذا الهاتف')), 'إجابة لتحدٍّ لم يصدر من هذا الدكتور تُرفض');
    const wrongKey = await UA.studentReply({ tag, keyHex: '22'.repeat(32) }, await UA.newDeviceKey(), UA.QrProtocol.parseChallenge(ch1));
    dw.__scan.onText(wrongKey);
    ok(await until(() => text(dw).includes('كود الطالب غير صحيح')), 'طالب بكود خاطئ (انتحال) يُرفض: BAD_PROOF');

    console.log('[بوابة الجدول: الطالب]');
    let k = 1; while ([dow, 6].includes((dow + k) % 7)) k++;
    const offReq = { sessionId: UA.rand(16), nonce: UA.rand(16), timeMs: Date.now() + k * 86400000 };
    const qrBefore = sw.__qr.length;
    click(sw, 'الحضور'); await until(() => [...sw.document.querySelectorAll('button')].some((b) => b.textContent.includes('مسح رمز الدكتور')));
    click(sw, 'مسح رمز الدكتور'); await until(() => sw.__scan);
    sw.__scan.onText(UA.QrProtocol.challengeText(offReq));
    ok(await until(() => text(sw).includes('ليس الآن وقت محاضرة في جدولك')) && sw.__qr.length === qrBefore, 'تحدٍّ بوقت خارج أي محاضرة في جدول الطالب ⇒ يُرفض بلا رمز إجابة');

    // ---------- العدّ ----------
    console.log('[عدّ القاعة]');
    const cnt = dw.document.querySelector('input[type=number]'); cnt.value = '3';
    const saveBtn = [...dw.document.querySelectorAll('button')].filter((b) => b.textContent === 'حفظ').pop(); saveBtn.click();
    ok(await until(() => text(dw).includes('ناقص 2')), 'مسجَّل 1 ومعدود 3 ⇒ ناقص 2');
    cnt.value = '1'; saveBtn.click();
    ok(await until(() => text(dw).includes('العدد مطابق')), 'يتطابق العدد');

    // ---------- التحضير اليدوي والإلغاء ----------
    console.log('[الإلغاء والتحضير اليدوي]');
    click(dw, 'إلغاء', dw.document.querySelector('.list'));
    ok(await until(() => text(dw).includes('لا حاضرون بعد') && text(dw).includes('⊘ ألغيتَ تحضير أحمد علي')), 'إلغاء تحضير طالب');
    const rep2 = await scanStudent(lastQr(dw));
    dw.__scan.onText(rep2);
    ok(await until(() => text(dw).includes('ألغيتَ تحضيره في هذه المحاضرة')), 'الطالب الملغى لا يُعاد تسجيله تلقائيًا');
    const q = dw.document.querySelector('input[type=search]'); q.value = 'أحمد'; q.dispatchEvent(new dw.Event('input'));
    ok(await until(() => [...dw.document.querySelectorAll('button')].some((b) => b.textContent === 'حضّر')), 'البحث يظهر الطالب للتحضير اليدوي');
    click(dw, 'حضّر');
    ok(await until(() => text(dw).includes('✓ حضّرتَ أحمد علي يدويًا')), 'تحضير يدوي يعيد الطالب');

    // ---------- جهاز آخر لنفس الكود ----------
    console.log('[جهاز آخر]');
    const sw2 = newBrowser(); click(sw2, 'طالب'); await until(() => sw2.document.querySelector('input.code'));
    sw2.document.querySelector('input.code').value = code; click(sw2, 'تفعيل');
    await until(() => text(sw2).includes('أحمد علي'), 8000);
    // الحماية: الكود محجوز على الخادم لهاتف الطالب الأول، فالهاتف الثاني يُرفض عند التفعيل نفسه
    ok(await until(() => !text(sw2).includes('أحمد علي') && text(sw2).includes('مرتبط بهاتف آخر'), 8000), 'نفس الكود على هاتف آخر ⇒ يُرفض عند التفعيل (مرتبط بهاتف آخر)');

    // ---------- الرفع للمسؤول ----------
    console.log('[رفع الحضور]');
    ok(await until(async () => true) && await (async () => {
      for (let i = 0; i < 40; i++) { const b = await relay.getBytes(`m/${mbox}/att`); if (b) { const f = await UA.AttendanceFile.open(b, dcode); if (f && f.sessions[0] && f.sessions[0].counted === 1 && f.records.length === 1) { ok(f.records[0].studentId === 'S1' && f.bindings.length === 1 && f.bindings[0].tag === tag && f.epoch === 5, 'ملف الحضور المرفوع صحيح (حاضر واحد + ربط + عدّ 1)'); return true; } } await sleep(300); }
      return false;
    })(), 'رُفع ملف الحضور مشفّرًا بعد المزامنة التلقائية');

    // ---------- الكشف والإنهاء ----------
    console.log('[الكشف]');
    click(dw, 'الكشف');
    ok(await until(() => text(dw).includes('كشف الحضور') && dw.document.querySelector('.sheet table')), 'جدول الكشف');
    ok(text(dw).includes('أحمد علي') && text(dw).includes('1/1 (100%)'), 'الطالب حاضر 1/1 في الكشف');
    click(dw, 'الحضور');
    ok(await until(() => text(dw).includes('إنهاء الحضور')), 'العودة لشاشة الحضور الجارية');
    click(dw, 'إنهاء الحضور');
    click(dw, 'إنهاء', dw.document.querySelector('dialog'));
    ok(await until(() => text(dw).includes('بدء الحضور')), 'إنهاء الجلسة');
    click(dw, 'استئناف محاضرة اليوم');
    ok(await until(() => text(dw).includes('استُؤنفت محاضرة اليوم نفسها')), 'استئناف محاضرة اليوم نفسها بلا تكرار');
    ok(JSON.parse(dw.localStorage.getItem('ua.doctor')).sessions.length === 1, 'جلسة واحدة فقط لليوم');

    // ---------- محاضرة متتالية ثانية في اليوم نفسه ----------
    console.log('[محاضرة متتالية]');
    click(dw, 'إنهاء الحضور'); click(dw, 'إنهاء', dw.document.querySelector('dialog'));
    ok(await until(() => text(dw).includes('بدء محاضرة متتالية جديدة')), 'يظهر زر المحاضرة المتتالية بعد وجود محاضرة اليوم');
    click(dw, 'بدء محاضرة متتالية جديدة');
    ok(await until(() => dw.document.querySelector('dialog[open]')), 'نافذة تأكيد');
    click(dw, 'نعم، ابدأ', dw.document.querySelector('dialog'));
    ok(await until(() => text(dw).includes('بدأت محاضرة متتالية جديدة (2)')), 'بدء المحاضرة (2)');
    const ss = JSON.parse(dw.localStorage.getItem('ua.doctor')).sessions;
    ok(ss.length === 2 && ss[0].part === 0 && ss[1].part === 1 && ss[0].sessionId !== ss[1].sessionId, 'جلسة جديدة بـpart = 1');
    click(dw, 'الكشف');
    ok(await until(() => dw.document.querySelector('.sheet table')) && [...dw.document.querySelectorAll('.sheet th')].some((t) => t.textContent.trim() === '2(2)'), 'عمود مستقل بوسم (2) في الكشف');
    ok(text(dw).includes('1/2 (50%)'), 'الطالب حضر المحاضرة الأولى فقط: 1/2');
    let printed = '', didPrint = false;
    dw.open = () => ({ document: { open() {}, write(x) { printed += x; }, close() {} }, focus() {}, print() { didPrint = true; } });
    click(dw, 'تصدير PDF');
    if (process.env.DUMP_PRINT) fs.writeFileSync(process.env.DUMP_PRINT, printed);
    ok(printed.includes('size:A4 landscape') && printed.includes('أحمد علي') && printed.includes('2(2)') && printed.includes('<th>1<br>'), 'تصدير PDF: صفحة طباعة A4 أفقي بالكشف وعمود (2)');
    ok(await until(() => didPrint, 2000), 'تُفتح نافذة الطباعة');

    console.log('[بوابة الجدول: الدكتور]');
    click(dw, 'الحضور'); await until(() => text(dw).includes('إنهاء الحضور'));
    click(dw, 'إنهاء الحضور'); click(dw, 'إنهاء', dw.document.querySelector('dialog'));
    ok(await until(() => text(dw).includes('بدء الحضور') || text(dw).includes('استئناف محاضرة اليوم')), 'العودة لشاشة البدء');
    // تقديم ساعة الهاتف 3 ساعات: يُكشف العبث ويبقى الختم الزمني من الخادم
    const realNow = dw.Date.now.bind(dw.Date); dw.Date.now = () => realNow() + 3 * 3600e3;
    click(dw, 'استئناف محاضرة اليوم');
    ok(await until(() => text(dw).includes('ساعة هاتفك لا تطابق الوقت الحقيقي')), 'ساعة هاتف مقدَّمة ⇒ تنبيه، والقرار بوقت الخادم');
    dw.Date.now = realNow;
    click(dw, 'إنهاء الحضور'); click(dw, 'إنهاء', dw.document.querySelector('dialog'));
    await until(() => text(dw).includes('استئناف محاضرة اليوم'));
    // الكشف الجديد: موعد المادة في يوم آخر ⇒ لا بدء اليوم
    await relay.putBytes(`m/${mbox}/roster`, await UA.Box.seal(UA.Box.MAGIC_ROSTER, 'D1', UA.utf8(mkRoster(6, (dow + 3) % 7)), dcode));
    await relay.putString(`m/${mbox}/rv`, '6');
    click(dw, 'الإعدادات'); await until(() => [...dw.document.querySelectorAll('button')].some((b) => b.textContent.includes('مزامنة الآن')));
    click(dw, 'مزامنة الآن');
    ok(await until(() => JSON.parse(dw.localStorage.getItem('ua.doctor')).epoch === 6, 8000), 'وصل كشف جديد بجدول مختلف');
    click(dw, 'الحضور'); await until(() => text(dw).includes('استئناف محاضرة اليوم'));
    click(dw, 'استئناف محاضرة اليوم');
    ok(await until(() => dw.document.getElementById('toast').textContent.includes('ليس الآن موعد هذه المادة')) && !text(dw).includes('رمز الحضور'), 'خارج موعد المادة في الجدول ⇒ لا تبدأ الجلسة');

    ok(sw.__errors.length === 0 && dw.__errors.length === 0, 'لا أخطاء جافاسكربت غير ملتقطة في الواجهتين' + (dw.__errors.concat(sw.__errors).join('|')));
  } catch (e) { fail++; console.log('  FAIL  استثناء: ' + (e.stack || e)); }
  finally { srv.kill(); }
  console.log(`\nالنتيجة: نجح ${pass}، فشل ${fail}`);
  process.exit(fail ? 1 : 0);
})();
