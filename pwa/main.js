/* نقطة الدخول: اختيار الدور (طالب/دكتور) وتسجيل الـService Worker */
(function () {
  const app = $('#app');
  const iosStandalone = window.navigator.standalone === true || matchMedia('(display-mode: standalone)').matches;
  const isIos = /iphone|ipad|ipod/i.test(navigator.userAgent);

  function chooseRole() {
    app.replaceChildren(
      h('header', { class: 'top' }, h('span', {}, 'حضور الجامعة')),
      h('main', {},
        h('div', { class: 'card center' }, h('h2', {}, 'من أنت؟'),
          h('p', { class: 'muted' }, 'اختر مرة واحدة (يمكنك تغييرها لاحقًا).'),
          h('button', { class: 'btn block', onclick: () => { store.set('ua.role', 'student'); start(); } }, '🎓 طالب'),
          h('div', { style: 'height:10px' }),
          h('button', { class: 'btn ghost block', onclick: () => { store.set('ua.role', 'doctor'); start(); } }, '👨‍🏫 دكتور')),
        isIos && !iosStandalone ? h('div', { class: 'hint' }, 'لتثبيت التطبيق على الآيفون: اضغط زر المشاركة في Safari ثم «إضافة إلى الشاشة الرئيسية». افتحه بعدها من الأيقونة.') : null));
  }
  function start() {
    const role = store.get('ua.role');
    if (role === 'student') Student.mount(app); else if (role === 'doctor') Doctor.mount(app); else chooseRole();
  }
  window.addEventListener('online', () => { if (store.get('ua.role') === 'doctor') Doctor.onOnline(); });
  if ('serviceWorker' in navigator && location.protocol === 'https:') navigator.serviceWorker.register('sw.js').catch(() => { /* اختياري */ });
  start();
})();
