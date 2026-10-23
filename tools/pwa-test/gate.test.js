// «لا تحضير إلا حسب الجدول» في نسخة الويب: البوابتان + الساعة الموثوقة + وقت الخادم من Firebase الوهمي.
// لا يحتاج jsdom:  node tools/pwa-test/gate.test.js   (يحتاج python3 لتشغيل الخادم الوهمي)
const path = require('path'), { spawn } = require('child_process');
const UA = require(path.resolve(__dirname, '../../pwa/core.js'));
const { AttendancePolicy: P, TrustedClock } = UA, R = P.Reason, TZ = 180;   // GMT+3
let pass = 0, fail = 0;
const ok = (c, n) => { if (c) { pass++; console.log('  PASS  ' + n); } else { fail++; console.log('  FAIL  ' + n); } };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
/** 2026-10-18 يوم أحد (day = 0). */
const at = (day, h, m, s = 0) => Date.UTC(2026, 9, 18 + day, h, m, s) - TZ * 60000;
const slots = [
  { courseId: 1, day: 0, startMin: 480, endMin: 570 }, { courseId: 1, day: 0, startMin: 600, endMin: 690 },   // المادة 1: الأحد 08:00–09:30 و10:00–11:30
  { courseId: 2, day: 1, startMin: 600, endMin: 720 },                                                         // المادة 2: الإثنين 10:00–12:00
];
const start = (c, now, req = 30) => P.checkDoctorStart(slots, c, now, req, TZ);

(async () => {
  console.log('[1] الدكتور: لا بدء إلا داخل موعد المادة');
  ok(start(1, at(0, 8, 0, 0)).ok && start(1, at(0, 9, 29, 0)).ok && start(1, at(0, 10, 30)).ok, 'داخل الموعد الأول والثاني يُسمح');
  ok(start(1, at(0, 7, 59, 59)).reason === R.OUTSIDE_SLOT, 'قبل البداية بثانية: مرفوض');
  ok(start(1, at(0, 9, 30, 0)).reason === R.OUTSIDE_SLOT, 'عند النهاية تمامًا: مرفوض (النهاية غير شاملة)');
  ok(start(1, at(0, 9, 45)).reason === R.OUTSIDE_SLOT, 'بين الموعدين: مرفوض');
  ok(start(1, at(1, 8, 30)).reason === R.OUTSIDE_SLOT, 'يوم آخر: مرفوض');
  ok(start(2, at(0, 10, 30)).reason === R.OUTSIDE_SLOT && start(2, at(1, 10, 30)).ok, 'موعد مادة أخرى لا يخص هذه المادة');
  ok(start(3, at(0, 8, 30)).reason === R.NO_SLOTS && !P.checkDoctorStart([], 1, at(0, 8, 30), 30, TZ).ok, 'مادة بلا مواعيد: مرفوضة');
  ok(start(1, at(2, 8, 30)).message.includes(UA.Schedule.describeAll(slots, 1, false)), 'رسالة الرفض تعرض مواعيد المادة');

  console.log('[2] قصّ مدة الجلسة عند نهاية الموعد');
  ok(start(1, at(0, 9, 0, 0), 60).minutes === 30, 'طلب 60 والمتبقي 30 ⇒ 30');
  ok(start(1, at(0, 9, 0, 0), 15).minutes === 15, 'الأقصر يبقى');
  ok(start(1, at(0, 9, 0, 30), 60).minutes === 29, 'تُقرَّب للأسفل (لا تتجاوز النهاية)');
  ok(start(1, at(0, 9, 28, 30), 60).minutes === 1, 'قبل النهاية بدقيقة ونصف ⇒ دقيقة');
  ok(start(1, at(0, 9, 0), 0).minutes === 1, 'طلب غير صالح ⇒ 1');
  ok(start(1, at(0, 9, 29, 30)).reason === R.TOO_LATE, 'آخر نصف دقيقة: متأخر جدًا');
  let never = true;
  for (let sec = 0; sec < 90 * 60; sec += 37) { const now = at(0, 8, 0) + sec * 1000, d = start(1, now, 240); if (d.ok && now + d.minutes * 60000 > at(0, 9, 30)) never = false; }
  ok(never, 'الجلسة لا تتجاوز نهاية الموعد في أي ثانية من الموعد');
  ok(P.currentSlot(slots, at(0, 8, 0), TZ).courseId === 1 && P.currentSlot(slots, at(0, 7, 59, 59), TZ) === null && P.currentSlot(slots, at(0, 9, 30), TZ) === null && P.currentSlot(slots, at(1, 11, 0), TZ).courseId === 2, 'الموعد الجاري بحدود دقيقة');

  console.log('[3] الطالب: لا إرسال إلا داخل محاضرة من جدوله');
  const te = (day, a, b, kind = 0) => ({ day, startMin: a, endMin: b, kind });
  const tt = [te(0, 480, 600), te(0, 720, 780, 2), te(1, 600, 720, 1)];
  const st = (t) => P.checkStudent(tt, t, TZ);
  ok(st(at(0, 9, 0)).ok && st(at(0, 8, 0, 0)).ok && st(at(1, 10, 30)).ok, 'داخل محاضرة (والتعويضية محاضرة) ⇒ مسموح');
  ok(st(at(0, 10, 0, 0)).reason === R.OUTSIDE_LECTURE && st(at(0, 7, 59, 59)).reason === R.OUTSIDE_LECTURE, 'حدود الموعد: النهاية غير شاملة');
  ok(st(at(0, 12, 30)).reason === R.OUTSIDE_LECTURE, 'وقت الاختبار ليس محاضرة');
  ok(st(at(2, 9, 0)).reason === R.OUTSIDE_LECTURE, 'يوم بلا محاضرة');
  ok(P.checkStudent([], at(0, 9, 0), TZ).reason === R.NO_TIMETABLE && P.checkStudent([te(0, 480, 600, 2)], at(0, 9, 0), TZ).reason === R.NO_TIMETABLE, 'بلا جدول (أو اختبارات فقط) ⇒ مرفوض');

  console.log('[4] الساعة الموثوقة');
  const mk = (f) => TrustedClock(() => f.wall, () => f.mono);
  { const f = { wall: 0, mono: 0 }, c = mk(f); ok(!c.read().trusted, 'قبل المزامنة: غير موثوقة'); }
  { const f = { wall: 5e6, mono: 5000 }, c = mk(f);
    await c.syncWith(async () => { f.mono += 400; return 1000000; });
    ok(c.read().ms === 1000200, 'وقت الخادم + نصف زمن الرحلة'); f.mono += 60000; ok(c.read().ms === 1060200, 'ثم يتقدّم بالساعة الرتيبة'); }
  { const f = { wall: 1e6, mono: 10000 }, c = mk(f); c.anchorNow(1e6); f.mono += 30000;
    ok(c.read().ms === 1030000 && !c.read().tampered, 'ساعة سليمة');
    f.wall += 3 * 3600000; ok(c.read().ms === 1030000 && c.read().tampered, 'تقديم ساعة الهاتف 3 ساعات لا يحرّك الوقت الموثوق ويُكشف');
    f.wall -= 6 * 3600000; ok(c.read().ms === 1030000 && c.read().tampered, 'وتأخيرها كذلك'); }
  { const f = { wall: 1e6, mono: 0 }, c = mk(f); c.anchorNow(1e6); f.mono += 600000; f.wall += 630000;
    ok(!c.read().tampered, 'انحراف 30 ثانية ليس عبثًا'); f.wall += P.CLOCK_TOLERANCE_MS; ok(c.read().tampered, 'تجاوز المسموح ⇒ عبث'); }
  { const f = { wall: 1e6, mono: 900000 }, c = mk(f); c.anchorNow(1e6); f.mono = 1000; ok(!c.read().trusted, 'رجوع الساعة الرتيبة (إعادة تشغيل) ⇒ تلزم مزامنة'); }
  { const f = { wall: 0, mono: 100 }, c = mk(f); let threw = false;
    try { await c.syncWith(async () => { throw new UA.Relay.OfflineError(); }); } catch (e) { threw = true; }
    ok(threw && !c.read().trusted, 'فشل المزامنة لا يغيّر المرساة');
    c.anchorNow(500); f.mono += 10; try { await c.syncWith(async () => { throw new Error('x'); }); } catch (e) { /* متوقع */ }
    ok(c.read().ms === 510, 'والمرساة القديمة تبقى'); }
  { // الدكتور يضبط ساعة هاتفه على 08:30 والحقيقي 07:00
    const f = { wall: at(0, 8, 30), mono: 0 }, c = mk(f); c.anchorNow(at(0, 7, 0));
    const r = c.read(), d = start(1, r.ms);
    ok(r.tampered && !d.ok && d.reason === R.OUTSIDE_SLOT, 'ساعة مقدَّمة لدخول الموعد: القرار بالوقت الموثوق فيُرفض');
    f.mono += 90 * 60000; f.wall = at(0, 6, 0); ok(start(1, c.read().ms).ok, 'وفي الموعد الحقيقي يُسمح حتى لو أخّر ساعته'); }

  console.log('[5] وقت الخادم من Firebase الوهمي ({".sv":"timestamp"})');
  const PORT = 19600 + Math.floor(Math.random() * 300);
  const srv = spawn('python3', [path.resolve(__dirname, '../relay-test/fake_rtdb.py'), String(PORT), 'live', '3600000'], { stdio: 'ignore' });
  try {
    await sleep(800);
    const cl = new UA.Relay.Client(`http://127.0.0.1:${PORT}`), box = new UA.Relay.Mailbox(cl, 'a'.repeat(32));
    const t = await box.serverTimeMs();
    ok(Math.abs(t - (Date.now() + 3600000)) < 5000, 'ختم الزمن من الخادم (وقته متقدم ساعة)');
    let forbidden = null; try { await cl.serverTimeMs('zzz/x'); } catch (e) { forbidden = e; }
    ok(forbidden instanceof UA.Relay.RelayError && forbidden.code === 401, 'مسار خارج m/ يُرفض');
    let off = null; try { await new UA.Relay.Client('http://127.0.0.1:1').serverTimeMs('m/x/clk'); } catch (e) { off = e; }
    ok(off instanceof UA.Relay.OfflineError, 'بلا اتصال ⇒ OfflineError');
    const clock = TrustedClock(); await clock.syncWith(() => box.serverTimeMs());
    const r = clock.read();
    ok(r.trusted && Math.abs(r.ms - (Date.now() + 3600000)) < 5000 && r.tampered, 'الساعة الموثوقة تتبع الخادم وتكشف أن ساعة الجهاز متأخرة ساعة');
  } finally { srv.kill(); }

  console.log(`\nالنتيجة: نجح ${pass}، فشل ${fail}`);
  process.exit(fail ? 1 : 0);
})();
