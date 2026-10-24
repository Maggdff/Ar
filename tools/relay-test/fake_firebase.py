# خادم اختبار يحاكي Firebase Authentication (REST) + Realtime Database بقواعد الترخيص الموجودة في docs/firebase-rules.json.
# ليس Firebase الحقيقي: يطبّق نموذجًا مبسّطًا للقواعد للتحقق من منطق العميل (الكتابة الذرّية، append-only، تجديد الرمز...).
# الاستخدام: python3 fake_firebase.py PORT [skewMs]    — المستخدم: dev@example.com / pass1234 (uid=UID_DEV) وهو في devAdmins،
# و other@example.com / pass1234 (uid=UID_OTHER) ليس في devAdmins.   مفتاح الـAPI: TESTKEY
import json, sys, time, uuid
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlparse, parse_qs, unquote

PORT = int(sys.argv[1]); SKEW = int(sys.argv[2]) if len(sys.argv) > 2 else 0
API_KEY = 'TESTKEY'
USERS = {'dev@example.com': ('pass1234', 'UID_DEV'), 'other@example.com': ('pass1234', 'UID_OTHER')}
db = {'devAdmins': {'UID_DEV': True}}
tokens = {}          # idToken -> (uid, email)
refresh = {}         # refreshToken -> (uid, email)
expired = set()
def now_ms(): return int(time.time() * 1000) + SKEW

def get_path(p):
    cur = db
    for k in [x for x in p.split('/') if x]:
        if not isinstance(cur, dict) or k not in cur: return None
        cur = cur[k]
    return cur
def set_path(p, v):
    ks = [x for x in p.split('/') if x]
    cur = db
    for k in ks[:-1]:
        if not isinstance(cur.get(k), dict): cur[k] = {}
        cur = cur[k]
    if v is None: cur.pop(ks[-1], None)
    else: cur[ks[-1]] = v
def resolve_sv(v):
    if isinstance(v, dict):
        if v == {'.sv': 'timestamp'}: return now_ms()
        return {k: resolve_sv(x) for k, x in v.items()}
    return v

def is_dev(uid): return uid is not None and get_path('devAdmins/' + uid) is True

def can_read(path, uid):
    p = path.strip('/')
    if p.startswith('m/') or p == 'license/doc' or p == 'license': return True
    if p.startswith('devAdmins/'): return uid is not None and p.split('/')[1] == uid
    if p == 'devlog' or p.startswith('devlog/'): return is_dev(uid)
    return False

def check_write(path, value, uid):
    """يعيد None إن سُمح، وإلا نص الرفض."""
    p = path.strip('/')
    if p.startswith('m/'): return None
    if p == 'license/doc':
        if not is_dev(uid): return 'Permission denied'
        if value is not None and not (isinstance(value, str) and value.startswith('UAL1|') and len(value) < 2000): return 'validation failed'
        return None
    if p.startswith('devlog/') and p.count('/') == 1:
        if not is_dev(uid): return 'Permission denied'
        if get_path(p) is not None or value is None: return 'Permission denied'      # append-only: لا تعديل ولا حذف
        v = resolve_sv(value)
        if not isinstance(v, dict) or not all(k in v for k in ('t', 'uid', 'action')) or v['uid'] != uid: return 'validation failed'
        if abs(v['t'] - now_ms()) > 5000: return 'validation failed'
        return None
    return 'Permission denied'

class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def _send(self, code, body, ctype='application/json'):
        b = body.encode('utf-8')
        self.send_response(code); self.send_header('Content-Type', ctype); self.send_header('Content-Length', str(len(b)))
        self.send_header('Date', time.strftime('%a, %d %b %Y %H:%M:%S GMT', time.gmtime(now_ms() / 1000)))
        self.end_headers(); self.wfile.write(b)
    def _body(self):
        n = int(self.headers.get('Content-Length', 0)); return self.rfile.read(n).decode('utf-8') if n else ''
    def _auth(self, q):
        t = (q.get('auth') or [None])[0]
        if t is None: return None, None
        if t in expired: return 'EXPIRED', None
        return (None, tokens[t][0]) if t in tokens else ('BAD', None)

    def do_POST(self):
        u = urlparse(self.path); q = parse_qs(u.query); path = u.path
        if path == '/v1/accounts:signInWithPassword':
            if (q.get('key') or [''])[0] != API_KEY: return self._send(400, json.dumps({'error': {'message': 'API key not valid'}}))
            j = json.loads(self._body()); e = j.get('email'); pw = j.get('password')
            if e not in USERS or USERS[e][0] != pw: return self._send(400, json.dumps({'error': {'message': 'INVALID_LOGIN_CREDENTIALS'}}))
            uid = USERS[e][1]; t = 'idt-' + uuid.uuid4().hex; r = 'rft-' + uuid.uuid4().hex
            tokens[t] = (uid, e); refresh[r] = (uid, e)
            return self._send(200, json.dumps({'idToken': t, 'refreshToken': r, 'localId': uid, 'email': e, 'expiresIn': '3600'}))
        if path == '/v1/token':
            f = parse_qs(self._body()); r = (f.get('refresh_token') or [''])[0]
            if r not in refresh: return self._send(400, json.dumps({'error': {'message': 'INVALID_REFRESH_TOKEN'}}))
            uid, e = refresh[r]; t = 'idt-' + uuid.uuid4().hex; tokens[t] = (uid, e)
            return self._send(200, json.dumps({'id_token': t, 'refresh_token': r, 'user_id': uid, 'expires_in': '3600'}))
        if path == '/__expire':
            expired.update(tokens.keys()); return self._send(200, '{}')
        if path == '/__state':
            return self._send(200, json.dumps(db))
        # RTDB: POST + X-HTTP-Method-Override
        m = self.headers.get('X-HTTP-Method-Override') or (q.get('x-http-method-override') or [''])[0]
        if m: return self._rtdb(m.upper(), u, q)
        return self._send(400, '{"error":"unsupported"}')
    def do_GET(self): self._rtdb('GET', urlparse(self.path), parse_qs(urlparse(self.path).query))
    def do_PUT(self): u = urlparse(self.path); self._rtdb('PUT', u, parse_qs(u.query))
    def do_PATCH(self): u = urlparse(self.path); self._rtdb('PATCH', u, parse_qs(u.query))
    def do_DELETE(self): u = urlparse(self.path); self._rtdb('DELETE', u, parse_qs(u.query))

    def _rtdb(self, method, u, q):
        if not u.path.endswith('.json'): return self._send(404, '{}')
        path = unquote(u.path[1:-5]); err, uid = self._auth(q)
        if err == 'EXPIRED': return self._send(401, json.dumps({'error': 'Auth token is expired'}))
        if err == 'BAD': return self._send(401, json.dumps({'error': 'Invalid token in path'}))
        if method == 'GET':
            if not can_read(path, uid): return self._send(401, json.dumps({'error': 'Permission denied'}))
            v = get_path(path)
            if v is not None and isinstance(v, dict) and (q.get('orderBy') or [None])[0]:
                key = json.loads(q['orderBy'][0]); lim = int((q.get('limitToLast') or ['1000'])[0])
                items = sorted(v.items(), key=lambda kv: (kv[1].get(key) if isinstance(kv[1], dict) else 0) or 0)[-lim:]
                v = dict(items)
            return self._send(200, json.dumps(v))
        body = self._body(); val = json.loads(body) if body else None
        if method == 'PATCH':
            changes = {(path.strip('/') + '/' + k if path.strip('/') else k): x for k, x in val.items()}
            for p, x in changes.items():             # كل المسارات أو لا شيء
                r = check_write(p, x, uid)
                if r: return self._send(401 if r == 'Permission denied' else 400, json.dumps({'error': r}))
            for p, x in changes.items(): set_path(p, resolve_sv(x))
            return self._send(200, json.dumps(val))
        if method in ('PUT', 'DELETE'):
            v = None if method == 'DELETE' else val
            r = check_write(path, v, uid)
            if r: return self._send(401 if r == 'Permission denied' else 400, json.dumps({'error': r}))
            set_path(path, resolve_sv(v)); return self._send(200, json.dumps(v))
        self._send(405, '{}')

HTTPServer(('127.0.0.1', PORT), H).serve_forever()
