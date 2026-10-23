# خادم تجريبي يحاكي واجهة Firebase Realtime Database REST (GET/PUT/DELETE على path.json)
import json, sys, time
from http.server import BaseHTTPRequestHandler, HTTPServer
store = {}
CLOCK_SKEW_MS = int(sys.argv[3]) if len(sys.argv) > 3 else 0   # إزاحة اختيارية لوقت الخادم (لاختبار ساعة هاتف خاطئة)
DEAD = len(sys.argv) > 2 and sys.argv[2] == 'dead'   # يحاكي عنوان قاعدة غير موجودة (Firebase يرد 404)
DEAD = len(sys.argv) > 2 and sys.argv[2] == 'dead'   # يحاكي عنوان قاعدة غير موجودة: 404 لكل شيء
class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def _path(self):
        p = self.path.split('?')[0]
        if not p.endswith('.json'): return None
        return p[1:-5]
    def _send(self, code, body):
        if DEAD: code, body = 404, 'Firebase error. Please ensure that you have the URL of your Firebase Realtime Database instance configured correctly.'
        b = body.encode('utf-8')
        self.send_response(code); self.send_header('Content-Type','application/json'); self.send_header('Content-Length',str(len(b))); self.end_headers(); self.wfile.write(b)
    def do_GET(self):
        if DEAD: return self._send(404, '{"error":"Firebase error. Please ensure that you have the URL of your Firebase Realtime Database instance configured correctly."}')
        p = self._path()
        if p is None or not p.startswith('m/'): return self._send(401, '{"error":"Permission denied"}')
        self._send(200, json.dumps(store[p]) if p in store else 'null')
    def do_PUT(self):
        if DEAD: return self._send(404, '{}')
        p = self._path()
        if p is None or not p.startswith('m/'): return self._send(401, '{"error":"Permission denied"}')
        if self.headers.get('If-Match') == 'null_etag' and p in store: return self._send(412, '{"error":"Precondition Failed"}')
        n = int(self.headers.get('Content-Length', 0)); v = json.loads(self.rfile.read(n).decode('utf-8'))
        if v == {'.sv': 'timestamp'}: v = int(time.time() * 1000 + CLOCK_SKEW_MS)   # ختم زمن من «الخادم» (يمكن إزاحته للاختبار)
        store[p] = v
        self._send(200, json.dumps(store[p]))
    def do_DELETE(self):
        if DEAD: return self._send(404, '{}')
        p = self._path()
        if p is None or not p.startswith('m/'): return self._send(401, '{"error":"Permission denied"}')
        for k in [k for k in store if k == p or k.startswith(p + '/')]: del store[k]
        self._send(200, 'null')
HTTPServer(('127.0.0.1', int(sys.argv[1])), H).serve_forever()
