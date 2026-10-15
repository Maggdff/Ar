const V='ua-student-v1',F=['./','index.html','app.js','manifest.webmanifest','icon-180.png','../shared/core.js','../shared/vendor/jsQR.js','../shared/vendor/qrcode.js'];
self.addEventListener('install',e=>e.waitUntil(caches.open(V).then(c=>c.addAll(F)).then(()=>self.skipWaiting())));
self.addEventListener('activate',e=>e.waitUntil(caches.keys().then(k=>Promise.all(k.filter(x=>x!==V).map(x=>caches.delete(x)))).then(()=>self.clients.claim())));
self.addEventListener('fetch',e=>{if(e.request.url.includes('firebase'))return;e.respondWith(caches.match(e.request).then(r=>r||fetch(e.request)));});
