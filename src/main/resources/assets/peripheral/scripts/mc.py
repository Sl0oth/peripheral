#!/usr/bin/env python3
"""
mc.py  -  Simple Peripheral API
Put this file in your scripts folder, then at the top of any script write:
    from mc import *

Available functions:
  pos()               -> (x, y, z)
  health()            -> float
  state()             -> dict
  inventory()         -> list
  nearby()            -> list
  look('north')       face a direction (north/south/east/west/up/down)
  walk(10)            walk 10 blocks forward
  walk(10, 'north')   walk 10 blocks north
  goto(x, z)          walk to map coordinates
  slot(1)             select hotbar slot 1-9
  equip('cobblestone')  find item in inventory and hold it
  mine(x, y, z)       start breaking a block
  place(x, y, z)      right-click / place a block
  place(x,y,z, item='cobblestone')  auto-equip then place
  drop()              drop 1 of held item
  drop(all=True)      drop whole stack
  say('hello')        send chat message
  msg('hello')        show message to yourself in-game
  wait(2)             sleep 2 seconds
  chat_log(20)        list last 20 chat messages
  wait_for_chat(pat)  block until chat matches pattern (regex)
"""
import os, json, time, math, urllib.request

# ── File access sandbox ──────────────────────────────────────────────────────────────────────────────────────────────────
import builtins as _builtins

_PERIPHERAL_FILE_ACCESS = os.environ.get('PERIPHERAL_FILE_ACCESS', '0') == '1'

if not _PERIPHERAL_FILE_ACCESS:
    _real_open = _builtins.open

    def open(file, mode='r', buffering=-1, **kwargs):
        if any(c in str(mode) for c in ('w', 'a', 'x', '+')):
            print('__PERIPHERAL_REQUEST_FILE_ACCESS__', flush=True)
            raise PermissionError(
                "[Peripheral] File write blocked. Allow file access in the Scripts tab."
            )
        return _real_open(file, mode, buffering, **kwargs)

    _builtins.open = open

    def _no_write(name):
        def _fn(*a, **k):
            print('__PERIPHERAL_REQUEST_FILE_ACCESS__', flush=True)
            raise PermissionError(
                f"[Peripheral] os.{name}() blocked. Allow file access in the Scripts tab."
            )
        return _fn

    os.remove = os.unlink = _no_write('remove')
    os.rename = os.replace = _no_write('rename')
    os.mkdir = os.makedirs = _no_write('mkdir')
    os.rmdir = _no_write('rmdir')

_PORT        = int(os.environ.get('PERIPHERAL_STATE_PORT', 25585))
_SCRIPT_NAME = os.environ.get('PERIPHERAL_SCRIPT_NAME', '')

def _get(path):
    with urllib.request.urlopen(f'http://localhost:{_PORT}{path}', timeout=5) as r:
        return json.loads(r.read())

def _post(path, data=None):
    body = json.dumps(data or {}).encode()
    req  = urllib.request.Request(
        f'http://localhost:{_PORT}{path}',
        data=body, headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(req, timeout=5) as r:
        return json.loads(r.read())

# ── External API helpers ──────────────────────────────────────────────────────

def http_get(url, headers=None):
    """GET any URL and return parsed JSON. Works with any web API."""
    req = urllib.request.Request(url, headers=headers or {})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read())

def http_post(url, body, headers=None):
    """POST JSON to any URL and return parsed JSON.
    Example (OpenAI):
        r = http_post(
            'https://api.openai.com/v1/chat/completions',
            {'model': 'gpt-4o-mini', 'messages': [{'role': 'user', 'content': 'hello'}]},
            headers={'Authorization': 'Bearer sk-YOUR_KEY_HERE'}
        )
        msg(r['choices'][0]['message']['content'])
    """
    h = {'Content-Type': 'application/json'}
    if headers:
        h.update(headers)
    data = json.dumps(body).encode()
    req  = urllib.request.Request(url, data=data, headers=h)
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read())

# ── Script HTTP Server ────────────────────────────────────────────────────────

class Server:
    """Spin up a mini HTTP server inside your script.
    Handlers receive GET query params or a parsed JSON POST body as a dict.
    Return any dict and it is sent back as JSON automatically.

    Usage:
        server = Server(8080)

        @server.route('/status')
        def status(req):
            x, y, z = pos()
            return {'x': x, 'y': y, 'z': z, 'health': health()}

        @server.route('/say')          # POST {"message": "hello"}
        def say_handler(req):
            say(req.get('message', ''))
            return {'ok': True}

        server.start()                 # background thread
        # server.serve_forever()       # or block here
    """

    def __init__(self, port=25586):
        self.port = port
        self._routes = {}

    def route(self, path):
        def decorator(fn):
            self._routes[path] = fn
            return fn
        return decorator

    def _make_handler(self):
        routes = self._routes
        import http.server as _hs
        from urllib.parse import urlparse, parse_qs as _pqs

        class _Handler(_hs.BaseHTTPRequestHandler):
            def _dispatch(self, body):
                parsed = urlparse(self.path)
                fn = routes.get(parsed.path)
                if fn is None:
                    self._reply(404, {'error': 'not_found', 'path': parsed.path})
                    return
                params = body if body is not None else {k: v[0] for k, v in _pqs(parsed.query).items()}
                try:
                    result = fn(params)
                    self._reply(200, result if result is not None else {'ok': True})
                except Exception as e:
                    self._reply(500, {'error': str(e)})

            def _reply(self, code, data):
                b = json.dumps(data).encode()
                self.send_response(code)
                self.send_header('Content-Type', 'application/json')
                self.end_headers()
                self.wfile.write(b)

            def do_GET(self):
                self._dispatch(None)

            def do_POST(self):
                n = int(self.headers.get('Content-Length', 0))
                self._dispatch(json.loads(self.rfile.read(n)) if n else {})

            def log_message(self, *_):
                pass

        return _Handler

    def start(self):
        import http.server as _hs, threading
        srv = _hs.HTTPServer(('0.0.0.0', self.port), self._make_handler())
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        return srv

    def serve_forever(self):
        import http.server as _hs
        _hs.HTTPServer(('0.0.0.0', self.port), self._make_handler()).serve_forever()

# ── Baritone integration ──────────────────────────────────────────────────────
# Baritone listens for '#' prefixed chat messages and intercepts them before
# they reach the server — so these never appear in public chat.

def baritone(command):
    """Send any Baritone command. The '#' prefix is added automatically.
    Requires the Baritone mod to be installed.
    Examples:
        baritone('mine diamond_ore')
        baritone('goto 100 64 -200')
        baritone('follow')
        baritone('stop')
    """
    cmd = command.strip()
    if not cmd.startswith('#'):
        cmd = '#' + cmd
    _post('/action', {'type': 'send_chat', 'message': cmd})

def mine_auto(block, count=0):
    """Use Baritone to automatically find and mine a block type.
    block  - block id, e.g. 'diamond_ore', 'coal_ore', 'iron_ore', 'log'
    count  - stop after collecting this many (0 = mine indefinitely)
    Requires Baritone mod.
    """
    block = block.replace('minecraft:', '')
    cmd = f'mine {block}'
    if count > 0:
        cmd += f' {count}'
    baritone(cmd)

def baritone_goto(x, y, z):
    """Use Baritone's pathfinder to walk to exact coordinates.
    More powerful than goto() — handles obstacles, swimming, climbing.
    Requires Baritone mod.
    """
    baritone(f'goto {int(x)} {int(y)} {int(z)}')

def baritone_stop():
    """Stop whatever Baritone is currently doing."""
    baritone('stop')

_DIRS = {
    'north': (0, 0, -1), 'south': (0, 0,  1),
    'east':  (1, 0,  0), 'west':  (-1, 0, 0),
    'up':    (0, 1,  0), 'down':  (0, -1, 0),
}

def state():
    return _get('/state')

def pos():
    p = _get('/state').get('position', {})
    return p.get('x', 0.0), p.get('y', 64.0), p.get('z', 0.0)

def health():
    return _get('/state').get('health', 20.0)

def inventory():
    return _get('/inventory').get('slots', [])

def nearby():
    return _get('/state').get('nearby_entities', [])

def look(direction):
    x, y, z = pos()
    dx, dy, dz = _DIRS.get(str(direction).lower(), (0, 0, -1))
    _post('/action', {'type': 'look_at',
                      'x': x + dx * 10,
                      'y': y + 1.6 + dy * 10,
                      'z': z + dz * 10})

def goto(x, z, y=None):
    px, py, pz = pos()
    _post('/goto', {'x': int(x), 'y': int(py if y is None else y), 'z': int(z)})
    for _ in range(240):
        time.sleep(0.5)
        status = _get('/nav_status').get('status', 'idle')
        if status in ('done', 'idle', 'failed'):
            return status
    _post('/nav_stop', {})
    return 'timeout'

def walk(blocks, direction=None):
    x, y, z = pos()
    if direction:
        dx, _, dz = _DIRS.get(str(direction).lower(), (0, 0, -1))
    else:
        s   = _get('/state')
        yaw = math.radians(s.get('yaw', 0))
        dx  = -math.sin(yaw)
        dz  =  math.cos(yaw)
    return goto(round(x + dx * blocks), round(z + dz * blocks))

def slot(n):
    _post('/action', {'type': 'set_slot', 'slot': max(0, min(8, int(n) - 1))})

def equip(item):
    """Find item in inventory and put it in hand.
    Pass a keyword like 'cobblestone', 'sword', 'torch'.
    Searches hotbar first, then main inventory (swaps to current hotbar slot).
    Returns True if found and equipped, False if not in inventory.
    """
    r = _post('/action', {'type': 'equip_item', 'item': str(item)})
    return r.get('ok', False)

def mine(x, y, z):
    _post('/action', {'type': 'start_break', 'x': int(x), 'y': int(y), 'z': int(z)})

def place(x, y, z, face='up', item=None):
    """Right-click / place a block.
    If item is given (e.g. item='cobblestone'), auto-equips it from inventory first.
    """
    if item is not None:
        equip(item)
    _post('/action', {'type': 'place_block', 'x': int(x), 'y': int(y), 'z': int(z), 'face': face})

def use_item():
    """Right-click the held item (eat food, drink potion, draw bow, etc.).
    For food: equip('bread') then use_item() then wait(2).
    """
    _post('/action', {'type': 'use_item'})

def drop(all=False):
    _post('/action', {'type': 'drop_item', 'all': bool(all)})

def say(message):
    m = str(message)
    # Commands (/...) run silently via send_command — no chat echo.
    # Plain text goes through send_chat (visible in chat).
    action = 'send_command' if m.startswith('/') else 'send_chat'
    _post('/action', {'type': action, 'message': m})

def msg(message):
    _post('/action', {'type': 'display_message', 'message': str(message)})

def rich_msg(json_component):
    import json as _json
    s = _json.dumps(json_component) if not isinstance(json_component, str) else json_component
    _post('/action', {'type': 'send_rich', 'json': s})

def wait(seconds):
    time.sleep(float(seconds))

# ── Scriptable GUI ───────────────────────────────────────────────────────────
# Open a native Minecraft screen defined entirely from Python.
# The screen uses Minecraft's own renderer — same font, same look as any MC GUI.
#
# Widget types:
#   label   — text.  props: text, color, shadow
#   bar     — progress bar.  props: value (0.0-1.0), w, h, color, bg_color
#   button  — clickable button.  props: label, w, h
#   input   — text field.  props: w, h, placeholder, value
#   divider — horizontal line.  props: w, color
#   rect    — filled rectangle.  props: w, h, color
#
# All x/y are relative to the content area (0,0 = top-left inside the panel).
# Colors: named ('red','green','yellow','blue','white','grey','orange','purple')
#         or hex '#RRGGBB' / '#AARRGGBB'.

def open_gui(layout):
    """Open a custom Minecraft GUI screen.
    layout = {'title': 'My Screen', 'w': 300, 'h': 200, 'widgets': [...]}
    """
    _post('/action', {'type': 'open_gui', 'layout': layout})

def gui_update(widget_id, **props):
    """Update one widget's properties while the screen is open.
    gui_update('hp_bar', value=0.5)
    gui_update('status', text='Hello', color='green')
    """
    _post('/action', {'type': 'update_gui', 'id': widget_id, 'props': props})

def gui_close():
    """Close the scriptable GUI screen.""",
    _post('/action', {'type': 'close_gui'})

def gui_state():
    """Return current GUI state: {'open': bool, 'event': btn_id_or_None, 'inputs': {id: text}}
    Each call pops one button-click event from the queue.
    """
    return _get('/gui')

def gui_is_open():
    """True if the scriptable GUI screen is currently displayed.""",
    return _get('/gui').get('open', False)

def gui_poll():
    """Return the ID of the last clicked button, or None.""",
    return _get('/gui').get('event')

def gui_input(widget_id):
    """Return the current text value of an input field."""
    return _get('/gui').get('inputs', {}).get(widget_id, '')

# ── HUD overlay (always-on screen overlay) ───────────────────────────────────

def hud_set(elements):
    """Set the HUD overlay elements. These render directly on the game screen
    while playing — no need to press H.

    elements is a list of dicts. Each dict has:
      type    – 'label' | 'bar' | 'rect' | 'divider' | 'item'
      id      – string (used with hud_update)
      x, y    – position in pixels from the anchor corner
      anchor  – 'top_left' (default) | 'top_right' | 'bottom_left'
                'bottom_right' | 'center'

      label:   text, color, shadow (bool)
      bar:     w, h, value (0.0-1.0), color, bg_color
      rect:    w, h, color, border (color string, omit for no border)
      divider: w, color

    Colors: 'red' 'green' 'yellow' 'blue' 'white' 'grey' 'orange' 'purple'
            or hex '#RRGGBB' / '#AARRGGBB'
    """
    _post('/hud/set', {'elements': elements, '_script': _SCRIPT_NAME})

def hud_update(element_id, **props):
    """Update a single HUD element by id. Same keyword args as hud_set elements.

    Example:
        hud_update('fps', text=f'FPS: {get_fps()}')
        hud_update('hp_bar', value=health()/20, color='red')
    """
    _post('/hud/update', {'id': element_id, **props})

def hud_clear():
    """Remove this script's HUD overlay elements."""
    _post('/hud/clear', {'_script': _SCRIPT_NAME})

# ─────────────────────────────────────────────────────────────────────────────

def chat_log(n=20):
    """Return the last n chat messages.
    Each is {'time':epoch_ms, 'type':'chat'/'system', 'sender':'name', 'text':'...'}
    """
    data = _get(f'/chat_log?limit={n}')
    return data.get('messages', [])

def wait_for_chat(pattern, timeout=30):
    """Block until a chat message matching pattern arrives, or timeout seconds pass.
    Returns the matching message dict, or None on timeout.
    msg['type']   -> 'chat' (player) or 'system' (server/join/leave)
    msg['sender'] -> player name for chat, '' for system messages
    msg['text']   -> full text, e.g. 'Sl0oth joined the game'
    Example (welcome bot):
        import re
        msg = wait_for_chat('joined the game')
        if msg:
            m = re.match(r'^(.+) joined the game', msg['text'])
            if m:
                say(f'wb {m.group(1)}!')
    """
    import re as _re
    seen = {m['time'] for m in chat_log(50)}
    deadline = time.time() + timeout
    while time.time() < deadline:
        time.sleep(0.3)
        for m in chat_log(50):
            if m['time'] not in seen:
                seen.add(m['time'])
                if _re.search(pattern, m['text'], _re.IGNORECASE):
                    return m
    return None
