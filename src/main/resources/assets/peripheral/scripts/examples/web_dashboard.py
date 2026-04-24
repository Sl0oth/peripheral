from mc import *
import json, threading
from http.server import HTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse

# ── Browser Dashboard ─────────────────────────────────────────────────────────
# Starts a local web server and serves a live game dashboard.
# Open http://localhost:25587/ in any browser (on this machine) to see
# your position, health, hunger, XP, world state, and nearby mobs —
# all updating automatically every 2 seconds.
#
# Also lets you send chat messages and walk to coordinates from the browser.
# Runs in the background until stopped.
#
# API endpoints (for external tools / other scripts):
#   GET  /              HTML dashboard (auto-refreshes every 2s)
#   GET  /api/state     JSON: position, health, hunger, XP, mobs, etc.
#   GET  /api/inventory JSON: full inventory slot list
#   POST /api/say       {"message": "hello"} sends a chat message
#   POST /api/goto      {"x": 100, "z": -200} walks there (needs Baritone)

PORT = 25587
_nav_status = {'status': 'idle'}

PAGE = """<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <title>Peripheral Dashboard</title>
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <style>
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body { background: #0d0d0d; color: #dddddd; font-family: 'Courier New', monospace; padding: 20px; }
    h1   { color: #00ffaa; font-size: 1.4em; margin-bottom: 16px; }
    .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 12px; margin-bottom: 20px; }
    .card { background: #131313; border: 1px solid #1a3a28; border-radius: 6px; padding: 14px; }
    .card h2 { color: #00aa66; font-size: 0.75em; text-transform: uppercase; letter-spacing: 1px; margin-bottom: 8px; }
    .val { font-size: 1.3em; color: #ffffff; }
    .sub { color: #666; font-size: 0.8em; margin-top: 4px; }
    .danger .val { color: #ff4444; }
    .warning .val { color: #ffaa00; }
    .good .val { color: #00ffaa; }
    .bar { background: #222; border-radius: 3px; height: 6px; margin-top: 6px; overflow: hidden; }
    .bar-fill { height: 100%; border-radius: 3px; transition: width 0.4s; }
    .bar-hp { background: #ff4444; }
    .bar-hunger { background: #ffaa00; }
    .actions { background: #131313; border: 1px solid #1a3a28; border-radius: 6px; padding: 14px; margin-bottom: 12px; }
    .actions h2 { color: #00aa66; font-size: 0.75em; text-transform: uppercase; letter-spacing: 1px; margin-bottom: 10px; }
    input[type=text] { background: #0d0d0d; color: #dddddd; border: 1px solid #333; border-radius: 4px; padding: 6px 10px; font-family: inherit; width: 260px; }
    button { background: #153020; color: #00ffaa; border: 1px solid #1a3a28; border-radius: 4px; padding: 6px 14px; font-family: inherit; cursor: pointer; margin-left: 6px; }
    button:hover { background: #1d4a30; }
    .coord { color: #00ccff; }
    #status { color: #555; font-size: 0.75em; margin-top: 12px; }
  </style>
</head>
<body>
  <h1>Peripheral Dashboard</h1>
  <div class="grid">
    <div class="card"><h2>Position</h2><div class="val coord" id="pos">—</div><div class="sub" id="dim">—</div></div>
    <div class="card" id="card-hp"><h2>Health</h2><div class="val" id="health">—</div><div class="bar"><div class="bar-fill bar-hp" id="hp-bar" style="width:100%"></div></div></div>
    <div class="card" id="card-hunger"><h2>Hunger</h2><div class="val" id="hunger">—</div><div class="bar"><div class="bar-fill bar-hunger" id="hunger-bar" style="width:100%"></div></div></div>
    <div class="card"><h2>World</h2><div class="val" id="tod">—</div><div class="sub" id="weather">—</div></div>
    <div class="card"><h2>XP Level</h2><div class="val" id="xp">—</div></div>
    <div class="card"><h2>Nearby Mobs</h2><div class="val danger" id="mob-count">—</div><div id="mob-list" style="font-size:0.85em;color:#ff8888;margin-top:6px"></div></div>
  </div>
  <div class="actions">
    <h2>Send Chat</h2>
    <input type="text" id="chat-input" placeholder="message or /command...">
    <button onclick="sendChat()">Send</button>
  </div>
  <div class="actions">
    <h2>Walk To</h2>
    X <input type="text" id="goto-x" placeholder="X" style="width:80px">
    Z <input type="text" id="goto-z" placeholder="Z" style="width:80px">
    <button onclick="sendGoto()">Go</button>
    <span id="goto-status" style="color:#555;font-size:0.8em;margin-left:8px"></span>
  </div>
  <div id="status">Connecting...</div>
  <script>
    function fetchState() {
      fetch('/api/state').then(r => r.json()).then(d => {
        document.getElementById('pos').textContent = Math.round(d.x) + ', ' + Math.round(d.y) + ', ' + Math.round(d.z);
        document.getElementById('dim').textContent = d.dimension;
        const hp = Math.round(d.health);
        document.getElementById('health').textContent = hp + ' / ' + Math.round(d.max_health);
        document.getElementById('hp-bar').style.width = (d.health / d.max_health * 100).toFixed(0) + '%';
        document.getElementById('card-hp').className = 'card ' + (hp <= 6 ? 'danger' : hp <= 12 ? 'warning' : 'good');
        document.getElementById('hunger').textContent = d.hunger + ' / 20';
        document.getElementById('hunger-bar').style.width = (d.hunger / 20 * 100) + '%';
        document.getElementById('tod').textContent = d.is_day ? 'Day' : 'Night';
        document.getElementById('weather').textContent = d.is_raining ? 'Raining' + (d.is_thundering ? ' (Thunder)' : '') : 'Clear';
        document.getElementById('xp').textContent = 'Level ' + d.xp_level;
        const mobs = d.nearby_mobs || [];
        document.getElementById('mob-count').textContent = mobs.length > 0 ? mobs.length + ' hostile' : 'None';
        document.getElementById('mob-list').textContent = mobs.slice(0,4).join('  ');
        document.getElementById('status').textContent = 'Updated ' + new Date().toLocaleTimeString();
      }).catch(() => { document.getElementById('status').textContent = 'Connection lost...'; });
    }
    function sendChat() {
      const msg = document.getElementById('chat-input').value.trim();
      if (!msg) return;
      fetch('/api/say', {method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({message:msg})})
        .then(() => { document.getElementById('chat-input').value = ''; });
    }
    function sendGoto() {
      const x = parseFloat(document.getElementById('goto-x').value);
      const z = parseFloat(document.getElementById('goto-z').value);
      if (isNaN(x) || isNaN(z)) { alert('Enter valid X and Z'); return; }
      document.getElementById('goto-status').textContent = 'Walking...';
      fetch('/api/goto', {method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({x,z})})
        .then(() => pollNav());
    }
    function pollNav() {
      fetch('/api/nav').then(r => r.json()).then(d => {
        const el = document.getElementById('goto-status');
        if (d.status === 'walking') { el.textContent = 'Walking...'; setTimeout(pollNav, 500); }
        else { el.textContent = 'Result: ' + d.status; }
      });
    }
    fetchState();
    setInterval(fetchState, 2000);
  </script>
</body>
</html>"""


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        path = urlparse(self.path).path
        if path == '/':
            body = PAGE.encode('utf-8')
            self._reply(200, 'text/html; charset=utf-8', body)
        elif path == '/api/state':
            s = state()
            p = s.get('position', {})
            mobs = [e['type'].replace('minecraft:','') + f'({int(e["distance"])}m)'
                    for e in s.get('nearby_entities', []) if e.get('hostile')]
            data = {'x': p.get('x',0), 'y': p.get('y',64), 'z': p.get('z',0),
                    'health': s.get('health',20), 'max_health': s.get('max_health',20),
                    'hunger': s.get('hunger',20), 'xp_level': s.get('xp_level',0),
                    'dimension': s.get('dimension','?').replace('minecraft:',''),
                    'is_day': s.get('is_day',True), 'is_raining': s.get('is_raining',False),
                    'is_thundering': s.get('is_thundering',False), 'nearby_mobs': mobs}
            self._reply(200, 'application/json', json.dumps(data).encode())
        elif path == '/api/inventory':
            self._reply(200, 'application/json', json.dumps({'slots': inventory()}).encode())
        elif path == '/api/nav':
            self._reply(200, 'application/json', json.dumps(_nav_status).encode())
        else:
            self._reply(404, 'text/plain', b'not found')

    def do_POST(self):
        n    = int(self.headers.get('Content-Length', 0))
        body = self.rfile.read(n).decode('utf-8') if n else '{}'
        try: req = json.loads(body)
        except Exception: req = {}
        path = urlparse(self.path).path
        if path == '/api/say':
            message = req.get('message', '')
            if message: say(message)
            self._reply(200, 'application/json', b'{"ok":true}')
        elif path == '/api/goto':
            try:
                tx, tz = float(req['x']), float(req['z'])
                def _nav():
                    _nav_status['status'] = 'walking'
                    result = goto(tx, tz)
                    _nav_status['status'] = result
                threading.Thread(target=_nav, daemon=True).start()
                self._reply(200, 'application/json', b'{"result":"walking"}')
            except Exception as e:
                self._reply(500, 'application/json', json.dumps({'error': str(e)}).encode())
        else:
            self._reply(404, 'text/plain', b'not found')

    def _reply(self, code, content_type, body):
        self.send_response(code)
        self.send_header('Content-Type', content_type)
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Access-Control-Allow-Origin', '*')
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *_): pass


class _Server(HTTPServer):
    allow_reuse_address = True

msg(f'§a[Dashboard] Open §fhttp://localhost:{PORT}/§a in your browser')
print(f'[Dashboard] Listening on http://localhost:{PORT}/')
_Server(('0.0.0.0', PORT), Handler).serve_forever()
