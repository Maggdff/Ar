/* Service Worker: يعمل التطبيق بلا إنترنت بعد أول فتح (ما عدا المزامنة مع Firebase). stale-while-revalidate للملفات المحلية. */
const VERSION = 'ua-pwa-v2';
const ASSETS = ['./', 'index.html', 'app.css', 'core.js', 'ui.js', 'student.js', 'doctor.js', 'main.js',
  'vendor/jsQR.js', 'vendor/qrcode.js', 'manifest.webmanifest', 'icons/icon-192.png', 'icons/icon-512.png', 'icons/apple-touch-icon.png'];
self.addEventListener('install', (e) => { e.waitUntil(caches.open(VERSION).then((c) => c.addAll(ASSETS)).then(() => self.skipWaiting())); });
self.addEventListener('activate', (e) => {
  e.waitUntil(caches.keys().then((ks) => Promise.all(ks.filter((k) => k !== VERSION).map((k) => caches.delete(k)))).then(() => self.clients.claim()));
});
self.addEventListener('fetch', (e) => {
  const req = e.request, url = new URL(req.url);
  if (req.method !== 'GET' || url.origin !== location.origin) return;            // Firebase وغيره يمرّ مباشرة
  e.respondWith(caches.open(VERSION).then(async (cache) => {
    const hit = await cache.match(req, { ignoreSearch: true });
    const net = fetch(req).then((r) => { if (r && r.ok) cache.put(req, r.clone()); return r; }).catch(() => hit || (req.mode === 'navigate' ? cache.match('index.html') : undefined));
    return hit || net;
  }));
});
