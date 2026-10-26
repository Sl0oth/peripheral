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
  look('north')             face a direction (north/south/east/west/up/down)
  look(90, -30)             set yaw/pitch directly (yaw: 0=S 90=W 180=N 270=E)
  jump()                    jump (works when on the ground)
  sprint(on=True)           toggle sprinting
  sneak(on=True)            toggle sneaking
  move(1.0, duration=2)     walk forward for 2 seconds
  attack()                  left-click at crosshair
  walk(10)                  walk 10 blocks forward
  walk(10, 'north')         walk 10 blocks north
  goto(x, z)                walk to map coordinates
  slot(1)                   select hotbar slot 1-9
  equip('cobblestone')      find item in inventory and hold it
  mine(x, y, z)             start breaking a block
  place(x, y, z)            right-click / place a block
  place(x,y,z, item='cobblestone')  auto-equip then place
  drop()                    drop 1 of held item
  drop(all=True)            drop whole stack
  say('hello')              send chat message
  msg('hello')              show message to yourself in-game
  wait(2)                   sleep 2 seconds
  chat_log(20)              list last 20 chat messages
  wait_for_chat(pat)        block until chat matches pattern (regex)
"""
import os, json, time, math, urllib.request

# Scripts are trusted local Python programs, with the same OS access as Minecraft.
# Peripheral does not sandbox Python or inject your AI provider key.
_LOCAL_TOKEN = os.environ.get('PERIPHERAL_LOCAL_TOKEN', '')

_PORT        = int(os.environ.get('PERIPHERAL_STATE_PORT', 25585))
_SOCKET_PORT = int(os.environ.get('PERIPHERAL_SOCKET_PORT', 25587))
_SOCKET_AVAILABLE = os.environ.get('PERIPHERAL_SOCKET_AVAILABLE', '1') == '1'
_SCRIPT_NAME = os.environ.get('PERIPHERAL_SCRIPT_NAME', '')
_SCRIPTS_DIR = os.environ.get('PERIPHERAL_SCRIPTS_DIR', os.getcwd())

# ── Transport: persistent socket with silent HTTP fallback ────────────────────
# On the first API call we try a newline-delimited JSON socket to the mod on
# _SOCKET_PORT. If it cannot connect we fall back to the plain HTTP API, so
# scripts keep working against older builds. Event callbacks (@on_chat / @on_gui
# / @on_tick / @on_death / @on_health) require the socket.
import socket as _socket
import threading as _threading
import queue as _queue

class _SocketDown(Exception):
    pass

_sock_lock = _threading.Lock()
_send_lock = _threading.Lock()
_sock      = None
_sock_file = None
_sock_dead = False
_next_id   = [0]
_pending   = {}            # id -> [Event, reply_holder]
_event_q   = _queue.Queue()
_reader_up = False

def _socket_connect():
    """Return a live socket, or None if the socket API is unavailable."""
    global _sock, _sock_file, _sock_dead, _reader_up
    if not _LOCAL_TOKEN:
        raise RuntimeError('[Peripheral] Local authorization is missing. Start this script with Run in Peripheral; external tools must configure the session token explicitly.')
    if not _SOCKET_AVAILABLE:
        return None
    if _sock is not None:
        return _sock
    if _sock_dead:
        return None
    with _sock_lock:
        if _sock is not None:
            return _sock
        if _sock_dead:
            return None
        try:
            s = _socket.create_connection(('127.0.0.1', _SOCKET_PORT), timeout=5)
            s.settimeout(None)
            _sock = s
            _sock_file = s.makefile('r', encoding='utf-8')
            if not _reader_up:
                _reader_up = True
                _threading.Thread(target=_reader_loop, daemon=True).start()
            return _sock
        except OSError:
            _sock_dead = True
            return None

def _reader_loop():
    global _sock, _sock_dead
    try:
        for line in _sock_file:
            line = line.strip()
            if not line:
                continue
            try:
                msg = json.loads(line)
            except ValueError:
                continue
            if 'id' in msg:
                holder = _pending.pop(msg['id'], None)
                if holder is not None:
                    holder[1] = msg
                    holder[0].set()
            elif 'event' in msg:
                _event_q.put(msg)
    except Exception:
        pass
    finally:
        _sock_dead = True
        _sock = None

def _call(method, params=None):
    """Send one request over the socket and block for its reply.
    Returns the result dict, or raises _SocketDown if the socket is unavailable.
    """
    s = _socket_connect()
    if s is None:
        raise _SocketDown()
    ev = _threading.Event()
    holder = [ev, None]
    with _send_lock:
        _next_id[0] += 1
        rid = _next_id[0]
        _pending[rid] = holder
        line = json.dumps({'id': rid, 'method': method, 'params': params or {}, 'token': _LOCAL_TOKEN}) + '\n'
        try:
            s.sendall(line.encode('utf-8'))
        except OSError:
            _pending.pop(rid, None)
            raise RuntimeError('[Peripheral] Connection lost; action outcome unknown. Reconnect before retrying.')
    if not ev.wait(10):
        _pending.pop(rid, None)
        raise RuntimeError('[Peripheral] Request timed out; outcome unknown. It was not repeated.')
    reply = holder[1]
    if isinstance(reply, dict) and 'error' in reply:
        return {'error': reply['error']}
    return reply.get('result', {}) if isinstance(reply, dict) else {}

def _path_to_method(path):
    q = ''
    if '?' in path:
        path, q = path.split('?', 1)
    method = path.strip('/').replace('/', '_')
    params = {}
    if q:
        from urllib.parse import parse_qs as _pqs
        for k, v in _pqs(q).items():
            val = v[0]
            try:
                params[k] = int(val)
            except ValueError:
                params[k] = val
    return method, params

def _http_get(path):
    req = urllib.request.Request(f'http://127.0.0.1:{_PORT}{path}', headers={'Authorization': 'Bearer ' + _LOCAL_TOKEN})
    with urllib.request.urlopen(req, timeout=5) as r:
        return json.loads(r.read())

def _http_post(path, data=None):
    body = json.dumps(data or {}).encode()
    req  = urllib.request.Request(
        f'http://127.0.0.1:{_PORT}{path}',
        data=body, headers={'Content-Type': 'application/json', 'Authorization': 'Bearer ' + _LOCAL_TOKEN})
    with urllib.request.urlopen(req, timeout=5) as r:
        return json.loads(r.read())

def _get(path):
    method, params = _path_to_method(path)
    try:
        return _call(method, params)
    except _SocketDown:
        return _http_get(path)

def _post(path, data=None):
    method, _ = _path_to_method(path)
    try:
        return _call(method, data or {})
    except _SocketDown:
        return _http_post(path, data)

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

def scripts_dir():
    """Return the absolute path to Peripheral's scripts folder."""
    return _SCRIPTS_DIR

def script_path(rel_path=''):
    """Resolve a path inside the Peripheral scripts folder."""
    if not rel_path:
        return _SCRIPTS_DIR
    return os.path.normpath(os.path.join(_SCRIPTS_DIR, rel_path))

def json_load_file(rel_path, default=None):
    """Load JSON from a file inside the scripts folder."""
    path = script_path(rel_path)
    if not os.path.exists(path):
        return default
    with open(path, 'r', encoding='utf-8') as f:
        return json.load(f)

def json_save_file(rel_path, data, indent=2):
    """Save JSON inside the scripts folder and return the absolute path written."""
    path = script_path(rel_path)
    parent = os.path.dirname(path)
    if parent:
        os.makedirs(parent, exist_ok=True)
    with open(path, 'w', encoding='utf-8') as f:
        json.dump(data, f, indent=indent, ensure_ascii=True)
        f.write('\n')
    return path

def waypoints_load(rel_path='data/waypoints.json'):
    """Load a saved waypoint list from disk."""
    data = json_load_file(rel_path, {'waypoints': []})
    if isinstance(data, dict):
        items = data.get('waypoints', [])
    elif isinstance(data, list):
        items = data
    else:
        items = []
    return [dict(item) for item in items if isinstance(item, dict)]

def waypoints_save(waypoints, rel_path='data/waypoints.json'):
    """Save a waypoint list to disk and return the normalized list."""
    clean = [dict(item) for item in (waypoints or []) if isinstance(item, dict)]
    json_save_file(rel_path, {'waypoints': clean})
    return clean

def waypoints_upsert(waypoint, rel_path='data/waypoints.json', key='label'):
    """Insert or replace a waypoint matched by key, then return the full list."""
    if not isinstance(waypoint, dict):
        raise TypeError('waypoint must be a dict')
    if key not in waypoint:
        raise KeyError(f'waypoint missing key: {key}')
    key_value = waypoint.get(key)
    items = waypoints_load(rel_path)
    for i, item in enumerate(items):
        if item.get(key) == key_value:
            merged = dict(item)
            merged.update(waypoint)
            items[i] = merged
            return waypoints_save(items, rel_path)
    items.append(dict(waypoint))
    return waypoints_save(items, rel_path)

def waypoints_remove(key_value, rel_path='data/waypoints.json', key='label'):
    """Remove all waypoints whose key matches key_value, then return the full list."""
    items = waypoints_load(rel_path)
    items = [item for item in items if item.get(key) != key_value]
    return waypoints_save(items, rel_path)

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
# ALL functions below require the Baritone mod:
#   https://modrinth.com/mod/baritone

def _baritone_check():
    """Raise a clear error if Baritone is not installed."""
    status = _get('/nav_status')
    if status.get('baritone') == False:
        raise RuntimeError(
            'Baritone is not installed. Install it from modrinth.com/mod/baritone'
        )

def baritone(command):
    """Send any Baritone command. The '#' prefix is added automatically.
    Requires the Baritone mod — install from modrinth.com/mod/baritone
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

def look(yaw_or_dir, pitch=0):
    """Face a direction or set exact rotation angles.
    look('north')        face north
    look('up')           face straight up
    look(90)             set yaw to 90 (faces west in Minecraft)
    look(180, -30)       face north, angled 30° upward
    Yaw:   0=south  90=west  180=north  270=east
    Pitch: -90=straight up  0=level  90=straight down
    """
    _DIR_ANGLES = {
        'south': (0, 0), 'west': (90, 0), 'north': (180, 0), 'east': (270, 0),
        'up':    (0, -90), 'down': (0, 90),
    }
    if isinstance(yaw_or_dir, str):
        yaw, pitch = _DIR_ANGLES.get(yaw_or_dir.lower(), (0, 0))
    else:
        yaw = float(yaw_or_dir)
    _post('/action', {'type': 'look', 'yaw': float(yaw), 'pitch': float(pitch)})

def jump():
    """Make the player jump (only works when on the ground)."""
    _post('/action', {'type': 'jump'})

def sprint(on=True):
    """Toggle sprinting on or off. sprint() to start, sprint(False) to stop."""
    _post('/action', {'type': 'sprint', 'on': bool(on)})

def sneak(on=True):
    """Toggle sneaking on or off. sneak() to crouch, sneak(False) to stand."""
    _post('/action', {'type': 'sneak', 'on': bool(on)})

def move(forward, strafe=0, duration=0):
    """Move the player.
    forward: 1.0=forward  -1.0=backward  0=stop
    strafe:  1.0=right    -1.0=left
    duration: seconds to move — if given, blocks until done then stops.
    With no duration: sets movement and returns immediately.
      Use move(0) or move(0, 0) to stop.
    Example:
      move(1.0, duration=2)   # walk forward 2 seconds
      sprint(); move(1.0, duration=3); sprint(False)  # sprint forward 3s
    """
    _post('/action', {'type': 'move', 'forward': float(forward), 'strafe': float(strafe)})
    if duration > 0:
        wait(duration)
        _post('/action', {'type': 'move', 'forward': 0, 'strafe': 0})

def attack():
    """Left-click attack — hits entity or starts breaking block at crosshair."""
    _post('/action', {'type': 'attack'})

def block_at(x, y, z):
    """Get the block at a world position.
    Returns: {'block': 'minecraft:stone', 'x': 0, 'y': 64, 'z': 0, 'is_air': False}
    """
    return _get(f'/block?x={int(x)}&y={int(y)}&z={int(z)}')

def targeted_block():
    """Get info about the block the crosshair is pointing at.
    Returns dict with 'block', 'x', 'y', 'z', 'face', or None if not targeting a block.
    """
    t = _get('/target')
    return t if t.get('type') == 'block' else None

def targeted_entity():
    """Get info about the entity the crosshair is pointing at.
    Returns dict with 'entity_type', 'x', 'y', 'z', 'health', 'distance', or None.
    """
    t = _get('/target')
    return t if t.get('type') == 'entity' else None

def status_effects():
    """Return a list of active status effects.
    Each entry: {'id': 'minecraft:speed', 'duration_ticks': 1200, 'amplifier': 0}
    amplifier: 0 = level I, 1 = level II, etc.
    """
    return _get('/state').get('effects', [])

def look_at(x, y, z):
    """Turn to face a world coordinate. Yaw/pitch calculated automatically."""
    _post('/action', {'type': 'look_at', 'x': float(x), 'y': float(y), 'z': float(z)})

def look_at_entity(entity_type, max_distance=16):
    """Turn to look at the nearest entity matching the given type.
    entity_type: partial match, e.g. 'zombie', 'cow', 'creeper', 'player'
    Returns True if a match was found, False otherwise.
    """
    r = _post('/action', {'type': 'look_at_entity',
                          'entity_type': str(entity_type),
                          'max_distance': float(max_distance)})
    return r.get('ok', False)

def respawn():
    """Send the respawn packet (only has effect when dead)."""
    _post('/action', {'type': 'respawn'})

def armor():
    """Return equipped armor as a dict: {'head': {...}, 'chest': {...}, 'legs': {...}, 'feet': {...}}
    Each value is an item dict with 'item', 'count', and optionally 'durability_pct'.
    """
    return _get('/state').get('armor', {})

def offhand():
    """Return the item in the offhand slot.
    Returns an item dict: {'item': 'minecraft:shield', 'count': 1, 'durability_pct': 95}
    """
    return _get('/state').get('offhand', {'item': 'empty', 'count': 0})

def biome():
    """Return the biome id at the player's position, e.g. 'minecraft:plains'."""
    return _get('/state').get('biome', '')

def time_of_day():
    """Return the world time of day in ticks (0-23999). 0=dawn, 6000=noon, 13000=night."""
    return _get('/state').get('time_of_day', 0)

def xp():
    """Return experience info: {'level': 30, 'progress': 0.45, 'total': 1395}."""
    return _get('/state').get('xp', {'level': 0, 'progress': 0.0, 'total': 0})

def hunger():
    """Return hunger info: {'food': 20, 'saturation': 5.0}. food is 0-20."""
    s = _get('/state')
    return {'food': s.get('food', 0), 'saturation': s.get('saturation', 0.0)}

def find_blocks(block, radius=32, max=256):
    """Find blocks near the player. Returns a list of {'x','y','z'} sorted by distance.
    block  - id keyword, e.g. 'diamond_ore', 'chest', 'oak_log'
    radius - search radius in blocks (max 64)
    max    - maximum results returned (max 512)
    Example:
        for b in find_blocks('diamond_ore', radius=48):
            draw_esp(b['x'], b['y'], b['z'], color='blue', id=f"dia_{b['x']}_{b['y']}_{b['z']}")
    """
    r = _post('/find_blocks', {'block': str(block).replace('minecraft:', ''),
                               'radius': int(radius), 'max': int(max)})
    return r.get('blocks', [])

def notify(title, text=''):
    """Show a vanilla toast notification (top-right popup) with a title and optional text."""
    _post('/notify', {'title': str(title), 'text': str(text)})

def play_sound(sound_id, volume=1.0, pitch=1.0):
    """Play a client-side sound.
    sound_id - e.g. 'minecraft:entity.experience_orb.pickup', 'minecraft:block.note_block.pling'
    volume   - 0.0-1.0
    pitch    - 0.5-2.0 (1.0 = normal)
    """
    _post('/play_sound', {'sound_id': str(sound_id),
                          'volume': float(volume), 'pitch': float(pitch)})

# ── Recorder ─────────────────────────────────────────────────────────────────
# Recordings keep where you stood and looked every tick, plus what you pressed,
# and play back exactly, steering back onto the path if anything pushes you off.

def _recorder_call(path, data=None):
    r = _post(path, data or {})
    if isinstance(r, dict) and r.get('error'):
        raise RuntimeError('[Peripheral] ' + str(r['error']))
    return r

def record(name=None, resume=False):
    """Start recording what you do. Returns the recording's name. resume=True adds to an existing one."""
    data = {'resume': bool(resume)}
    if name:
        data['name'] = str(name)
    return _recorder_call('/record_start', data).get('name')

def stop_recording():
    """Stop recording and save it."""
    return _recorder_call('/record_stop')

def play_recording(name, loops=1, relative=False, wait=True):
    """Play a recording. loops=0 repeats until stop_playback(). relative=True starts it where you
    stand (shifted by whole blocks) instead of where it was recorded. With wait=True, returns once
    it finishes, with a message saying how it ended."""
    _recorder_call('/play_recording', {'name': str(name), 'loops': int(loops), 'relative': bool(relative)})
    if not wait:
        return None
    while True:
        time.sleep(0.25)
        status = _get('/recorder')
        if status.get('state') != 'playing':
            return status.get('message', '')

def stop_playback():
    """Stop a recording that is playing."""
    return _recorder_call('/stop_playback')

def recorder_status():
    """{'state': 'idle'|'recording'|'playing', 'name', 'tick', 'ticks', 'loop', 'loops', 'returning', ...}"""
    return _get('/recorder')

def recordings():
    """Names of saved recordings, newest first."""
    return _get('/recordings').get('recordings', [])

# ── Screens: click, type and press like a player ────────────────────────────
# These go through Minecraft's own mouse and keyboard handling, so any screen,
# vanilla or from another mod, sees a normal click or key press.
# Coordinates are GUI pixels, the same ones screen() reports.

def screen():
    """The open screen: {'open', 'title', 'kind', 'width', 'height', 'mouse_x', 'mouse_y',
    'controls': [{'label', 'kind', 'x', 'y', 'width', 'height', 'active'}],
    'slots': [{'slot', 'x', 'y', 'item', 'count'}] for containers}."""
    return _get('/screen')

def click(x, y, button='left', shift=False):
    """Click at GUI pixel x, y. button is 'left', 'right' or 'middle'."""
    return _recorder_call('/click', {'x': x, 'y': y, 'button': button, 'shift': bool(shift)})

def click_button(label, button='left'):
    """Click the button whose text is label (exact match first, then any button containing it)."""
    return _recorder_call('/click_button', {'label': str(label), 'button': button}).get('clicked')

def click_slot(slot, button='left', shift=False):
    """Click container slot number slot (as screen()['slots'] numbers them). shift=True shift-clicks."""
    return _recorder_call('/click_slot', {'slot': int(slot), 'button': button, 'shift': bool(shift)})

def move_mouse(x, y):
    """Move the game's pointer to GUI pixel x, y. With no screen open this turns the camera, like a real mouse."""
    return _recorder_call('/mouse_move', {'x': x, 'y': y})

def mouse_down(button='left'):
    """Press and hold a mouse button where the pointer is (pair with mouse_up for drags)."""
    return _recorder_call('/mouse_button', {'button': button, 'action': 'down'})

def mouse_up(button='left'):
    """Release a mouse button."""
    return _recorder_call('/mouse_button', {'button': button, 'action': 'up'})

def scroll(amount, x=None, y=None):
    """Scroll the wheel by amount notches (positive is up), at x, y when given."""
    data = {'amount': amount}
    if x is not None and y is not None:
        data.update({'x': x, 'y': y})
    return _recorder_call('/scroll', data)

def press_key(key, shift=False, ctrl=False, alt=False):
    """Press and release a key: 'enter', 'escape', 'tab', 'backspace', 'space', 'up', 'f3', 'e', '1'..."""
    return _recorder_call('/key', {'key': str(key), 'shift': shift, 'ctrl': ctrl, 'alt': alt})

def key_down(key):
    """Hold a key down until key_up(key)."""
    return _recorder_call('/key', {'key': str(key), 'action': 'down'})

def key_up(key):
    """Release a held key."""
    return _recorder_call('/key', {'key': str(key), 'action': 'up'})

def type_text(text):
    """Type text into whatever has focus, one character at a time, like a keyboard."""
    return _recorder_call('/type_text', {'text': str(text)})

# ── Packets: see, cancel, hold and send what your game sends ────────────────
# packet.recent() works everywhere. Cancelling, holding and sending work in
# singleplayer, and on servers that turn them on for testing by installing
# Peripheral Testing. Keep-alive packets always go through, and anything a
# script turns on is turned off again when the script stops.

class _Packets:
    """Packet tools. Packet names are Minecraft's: 'move_player_pos', 'swing', 'chat', 'set_entity_motion'..."""

    def log(self, on=True, clear=False):
        """Start or stop logging packets. packet.recent() reads the log."""
        return _recorder_call('/packet_log', {'on': bool(on), 'clear': bool(clear)})

    def recent(self, limit=100, direction=None, name=None, since=0):
        """Logged packets, oldest first: [{'time', 'direction', 'name', 'summary', 'blocked', 'held', 'data'}].
        direction is 'sent' or 'received'; name matches part of a packet name like 'move_player'.
        'data' is a sent packet's bytes in hex, ready for packet.send()."""
        data = {'limit': int(limit), 'since': int(since)}
        if direction:
            data['direction'] = direction
        if name:
            data['name'] = name
        return _get_with('/packets', data).get('packets', [])

    def cancel(self, name, direction='sent'):
        """Stop every packet named name, 'sent' to the server or 'received' from it, until packet.allow(name)."""
        return _recorder_call('/packet_block', {'name': str(name), 'direction': direction, 'on': True})

    def allow(self, name, direction='sent'):
        """Let packets named name through again."""
        return _recorder_call('/packet_block', {'name': str(name), 'direction': direction, 'on': False})

    def cancelled(self):
        """{'sent': [...], 'received': [...]}: the packet names being cancelled."""
        r = _get('/packets')
        return {'sent': r.get('blocked_sent', []), 'received': r.get('blocked_received', [])}

    def hold(self):
        """Keep everything the game sends (except keep-alives) until packet.release()."""
        return _recorder_call('/packet_hold', {'on': True})

    def release(self, send=True):
        """Stop holding. send=True sends the held packets in order; send=False drops them."""
        return _recorder_call('/packet_hold', {'on': False, 'send': bool(send)})

    def send(self, data):
        """Send a packet from its bytes in hex, id first, the way packet.recent() gives them in 'data'.
        Copy one from the log, change it, and send it. Returns {'name', 'summary'} of what was sent."""
        return _recorder_call('/packet_send', {'data': str(data)})

    def allowed(self):
        """Whether cancelling, holding and sending work here."""
        return bool(_get('/packets').get('allowed_here'))

packet = _Packets()

def _get_with(path, data):
    try:
        return _call(path.strip('/'), data)
    except _SocketDown:
        return _http_post(path, data)

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
# Open a PaneLib-backed Minecraft screen defined from Python.
# Coordinates use Minecraft GUI pixels; explicit author themes/colors survive.
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
    _post('/action', {'type': 'open_gui', 'layout': layout, '_script': _SCRIPT_NAME})

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
    """True if this script's GUI is displayed; does not consume an event."""
    return gui_peek().get('open', False)

def gui_peek():
    """Read this script's GUI open/inputs state without removing queued events."""
    return _get('/gui_peek')

def gui_poll():
    """Return the ID of the last clicked button, or None.""",
    return _get('/gui').get('event')

def gui_input(widget_id):
    """Return the current text value of an input field.

    Does not remove queued events. For a button and its input at the same
    instant, use one gui_state() snapshot instead of separate requests.
    """
    return gui_peek().get('inputs', {}).get(widget_id, '')

# ── HUD overlay (always-on screen overlay) ───────────────────────────────────

def hud_set(elements):
    """Set the HUD overlay elements. These render directly on the game screen
    while playing — no need to press H.

    elements is a list of dicts. Each dict has:
      type    – 'label' | 'bar' | 'rect' | 'divider' | 'item' | 'graph' | 'compass'
      id      – string (used with hud_update)
      x, y    – position in pixels from the anchor corner
      anchor  – 'top_left' (default) | 'top_right' | 'bottom_left'
                'bottom_right' | 'center'

      label:   text, color, shadow (bool)
      bar:     w, h, value (0.0-1.0), color, bg_color
      rect:    w, h, color, border (color string, omit for no border)
      divider: w, color
      graph:   w, h, values (list of floats), color, min, max
               (min/max omitted = autoscale; push new samples with
                hud_update(id, push=value))
      compass: w, h, color – horizontal yaw strip with N/E/S/W ticks

    Colors: 'red' 'green' 'yellow' 'blue' 'white' 'grey' 'orange' 'purple'
            or hex '#RRGGBB' / '#AARRGGBB'
    """
    _post('/hud/set', {'elements': elements, '_script': _SCRIPT_NAME})

def hud_update(element_id, **props):
    """Update a single HUD element by id. Same keyword args as hud_set elements.

    Example:
        hud_update('fps', text=f'FPS: {get_fps()}')
        hud_update('hp_bar', value=health()/20, color='red')
        hud_update('tps_graph', push=tps)   # append a sample to a graph element
    """
    _post('/hud/update', {'id': element_id, **props})

def hud_clear():
    """Remove this script's HUD overlay elements."""
    _post('/hud/clear', {'_script': _SCRIPT_NAME})

def draw_esp(x, y, z, w=1, h=2, d=1, color='green', id=''):
    """Draw a world-space wireframe box centred on (x, y, z).
    w/h/d are width/height/depth in blocks. id replaces any prior command with the same id.
    """
    _post('/overlay/set', {'type': 'esp_box', 'x': x, 'y': y, 'z': z,
                           'w': w, 'h': h, 'd': d, 'color': color, 'id': id,
                           '_script': _SCRIPT_NAME})

def draw_world_text(x, y, z, text, color='white', id='', face_pitch=False):
    """Draw a billboard text label at world position (x, y, z).
    face_pitch=True also tilts the label up/down toward the camera.
    """
    _post('/overlay/set', {'type': 'world_text', 'x': x, 'y': y, 'z': z,
                           'text': text, 'color': color, 'id': id,
                           'face_pitch': face_pitch,
                           '_script': _SCRIPT_NAME})

def draw_line(x1, y1, z1, x2, y2, z2, color='green', id=''):
    """Draw a line between two world positions."""
    _post('/overlay/set', {'type': 'line', 'x1': x1, 'y1': y1, 'z1': z1,
                           'x2': x2, 'y2': y2, 'z2': z2, 'color': color, 'id': id,
                           '_script': _SCRIPT_NAME})

def draw_waypoint(x, y, z, label='', color='green', id='', face_pitch=False):
    """Draw a vertical line + label at world position (x, y, z)."""
    _post('/overlay/set', {'type': 'waypoint', 'x': x, 'y': y, 'z': z,
                           'label': label, 'color': color, 'id': id,
                           'face_pitch': face_pitch,
                           '_script': _SCRIPT_NAME})

def overlay_clear():
    """Remove this script's world-space overlay commands."""
    _post('/overlay/clear', {'_script': _SCRIPT_NAME})

def draw_player_line(x, y, z, color='green', id=''):
    """Draw a line from the player's live eye position to world point (x, y, z).
    The origin is resolved each frame on the render thread, so it stays
    perfectly attached to the player as they move.
    """
    _post('/overlay/set', {'type': 'player_line', 'x': x, 'y': y, 'z': z,
                           'color': color, 'id': id,
                           '_script': _SCRIPT_NAME})

def draw_live_esp(filter='hostile', color='auto', w=0.8, h=1.9, d=0.8,
                  show_lines=True, show_labels=True, radius=64, id='',
                  see_through=True, face_pitch=False):
    """True 60-fps ESP — entity positions are looked up on the render thread
    every frame, so boxes track movement with zero lag.

    filter     - 'hostile' (default) | 'passive' | 'players' | 'all'
    color      - 'auto' (health-based green/yellow/red) or any named/hex color
    w, h, d    - wireframe box dimensions  (default 0.8 x 1.9 x 0.8)
    show_lines - draw a line from your crosshair to each entity  (default True)
    show_labels - draw name + hp + distance label above each box (default True)
    radius     - search radius in blocks  (default 64)
    id         - unique id; call again with the same id to update options
    """
    _post('/overlay/set', {'type': 'live_esp', 'filter': filter, 'color': color,
                           'w': w, 'h': h, 'd': d,
                           'show_lines': show_lines, 'show_labels': show_labels,
                           'radius': radius, 'id': id,
                           'see_through': see_through,
                           'face_pitch': face_pitch,
                           '_script': _SCRIPT_NAME})

def draw_live_tags(filter='all', radius=48, id='live_tags',
                   require_custom_name=False, include_players=False,
                   lines=None, show_bar=True, show_box=False,
                   label_offset_x=0, label_offset_y=0.55, label_offset_z=0,
                   bar_offset_x=0, bar_offset_y=0.2, bar_offset_z=0,
                   side_offset=0, bar_side_offset=None,
                   scale=1.0, bar_scale=1.0, line_spacing=1.0,
                   bar_width_chars=10, bar_color='auto', bar_show_text=False,
                   bar_text='', see_through=True, face_pitch=False,
                   distance_scale=True, label_background=True):
    """Render smooth entity-bound name cards / bars on the render thread.

    Positions, distance tokens, and health bars are resolved every frame in Java,
    so labels stay locked to moving entities at full render FPS.

    lines:
        list of {'text': template, 'color': color}
        Tokens: {name} {distance:.1f} {health:.0f} {max_health:.0f}
                {health_percent:.0f} {type} {custom_name}
        Use color='auto_health' to tint a line by the entity's HP ratio.
    """
    payload = {
        'type': 'live_tags',
        'filter': filter,
        'radius': radius,
        'id': id,
        'require_custom_name': require_custom_name,
        'include_players': include_players,
        'show_bar': show_bar,
        'show_box': show_box,
        'label_offset_x': label_offset_x,
        'label_offset_y': label_offset_y,
        'label_offset_z': label_offset_z,
        'bar_offset_x': bar_offset_x,
        'bar_offset_y': bar_offset_y,
        'bar_offset_z': bar_offset_z,
        'side_offset': side_offset,
        'bar_side_offset': side_offset if bar_side_offset is None else bar_side_offset,
        'scale': scale,
        'bar_scale': bar_scale,
        'line_spacing': line_spacing,
        'bar_width_chars': bar_width_chars,
        'bar_color': bar_color,
        'bar_show_text': bar_show_text,
        'bar_text': bar_text,
        'see_through': see_through,
        'face_pitch': face_pitch,
        'distance_scale': distance_scale,
        'label_background': label_background,
        '_script': _SCRIPT_NAME,
    }
    if lines is not None:
        payload['lines'] = lines
    _post('/overlay/set', payload)

def draw_billboard(x, y, z, lines, id='', see_through=True, face_pitch=False):
    """Draw a camera-facing billboard panel at world position (x, y, z).
    face_pitch=True also tilts the panel up/down toward the camera.
    lines – list of dicts, each with 'text' and 'color' keys.
    Example:
        draw_billboard(x, y+2, z, [
            {'text': 'Zombie', 'color': 'red'},
            {'text': '18hp  3.2m', 'color': 'grey'},
        ], id='lbl_zombie')
    id replaces any prior command with the same id.
    """
    _post('/overlay/set', {'type': 'billboard', 'x': x, 'y': y, 'z': z,
                           'lines': lines, 'id': id, 'see_through': see_through,
                           'face_pitch': face_pitch,
                           '_script': _SCRIPT_NAME})

def draw_health_bar(x, y, z, value, max=20, color='green', show_text=True, id='',
                    see_through=True, face_pitch=False):
    """Draw a camera-facing health/progress bar at world position (x, y, z).
    value     – current value
    max       – maximum value (bar is full when value == max)
    color     – bar fill color; 'auto' = green/yellow/red by ratio
    show_text – if True, shows 'value / max' text above the bar
    id        – replaces any prior command with the same id
    face_pitch – if True, tilts the bar up/down toward the camera
    """
    _post('/overlay/set', {'type': 'health_bar', 'x': x, 'y': y, 'z': z,
                           'value': value, 'max': max, 'color': color,
                           'show_text': show_text, 'id': id,
                           'see_through': see_through,
                           'face_pitch': face_pitch,
                           '_script': _SCRIPT_NAME})

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

# ── Event callbacks (push over the socket) ────────────────────────────────────
# The mod pushes chat / gui / tick / health / death events to your script.
# Decorate handlers with @on_chat / @on_gui / @on_tick / @on_death / @on_health,
# then call run() to keep the script alive. Handlers all run on ONE background
# dispatcher thread, so your callbacks never race each other.
#
# Example:
#   from mc import *
#   @on_chat(r'^!home')
#   def go_home(m): say('/home')
#   @on_gui('start')
#   def start(e): msg('started')
#   @on_tick(hz=5)
#   def hud(s): hud_update('hp', text=f"HP {s['health']:.0f}")
#   run()
#
# These require a mod build with the socket API. On an HTTP-only build the
# decorators raise a clear error asking you to update the mod.

_chat_handlers   = []      # (compiled_regex_or_None, fn)
_gui_handlers    = []      # (widget_id_or_None, fn)
_tick_handlers   = []      # fn
_death_handlers  = []      # fn
_health_handlers = []      # fn
_timers          = []      # [interval, next_due, fn]
_sub_events      = set()
_sub_hz          = 0
_dispatch_up     = False

def _require_socket():
    if _socket_connect() is None:
        raise RuntimeError(
            '[Peripheral] Event callbacks need the socket API. '
            'Check the local API status and restart this script.'
        )

def _start_dispatch():
    global _dispatch_up
    if _dispatch_up:
        return
    _dispatch_up = True
    _threading.Thread(target=_dispatch_loop, daemon=True).start()

def _resubscribe():
    with _send_lock:
        s = _sock
        if s is None:
            return
        payload = {'method': 'subscribe', 'token': _LOCAL_TOKEN,
                   'params': {'events': sorted(_sub_events), 'state_hz': _sub_hz}}
        try:
            s.sendall((json.dumps(payload) + '\n').encode('utf-8'))
        except OSError:
            pass

def _subscribe(event, hz=0):
    global _sub_hz
    _sub_events.add(event)
    if hz > _sub_hz:
        _sub_hz = hz
    _start_dispatch()
    _resubscribe()

def _dispatch_loop():
    while True:
        timeout = None
        if _timers:
            timeout = max(0, min(t[1] for t in _timers) - time.time())
        try:
            msg = _event_q.get(timeout=timeout)
        except _queue.Empty:
            msg = None
        if msg is not None:
            _dispatch_event(msg)
        now = time.time()
        for t in _timers:
            if now >= t[1]:
                t[1] = now + t[0]
                try:
                    t[2]()
                except Exception as e:
                    print(f'[mc] timer error: {e}', flush=True)

def _dispatch_event(msg):
    ev   = msg.get('event')
    data = msg.get('data', {}) or {}
    try:
        if ev == 'chat':
            text = data.get('text', '')
            for pat, fn in _chat_handlers:
                if pat is None or pat.search(text):
                    fn(data)
        elif ev == 'gui':
            wid = data.get('id')
            for want, fn in _gui_handlers:
                if want is None or want == wid:
                    fn(data)
        elif ev == 'tick':
            for fn in _tick_handlers:
                fn(data)
        elif ev == 'death':
            for fn in _death_handlers:
                fn()
        elif ev == 'health':
            for fn in _health_handlers:
                fn(data.get('health'), data.get('previous'))
    except Exception as e:
        print(f'[mc] handler error: {e}', flush=True)

def on_chat(pattern=None):
    """Run a handler when a chat message arrives. Optional regex pattern.
    The handler receives the message dict {'time','type','sender','text'}.
        @on_chat(r'^!home')
        def go_home(m):
            say('/home')
    """
    import re as _re2
    compiled = _re2.compile(pattern) if pattern else None
    def deco(fn):
        _require_socket()
        _chat_handlers.append((compiled, fn))
        _subscribe('chat')
        return fn
    return deco

def on_gui(widget_id=None):
    """Run a handler when a GUI widget fires. Optional id (omit = every widget).
    The handler receives {'id','event','value'}.
        @on_gui('start_btn')
        def clicked(e):
            msg('started')
    """
    def deco(fn):
        _require_socket()
        _gui_handlers.append((widget_id, fn))
        _subscribe('gui')
        return fn
    return deco

def on_tick(hz=5):
    """Run a handler ~hz times per second (0-20) with a compact state snapshot:
    {position, health, food, yaw, pitch, dimension}.
        @on_tick(hz=5)
        def update(s):
            hud_update('hp', text=f"HP {s['health']:.0f}")
    """
    def deco(fn):
        _require_socket()
        _tick_handlers.append(fn)
        _subscribe('tick', hz=max(0, min(20, int(hz))))
        return fn
    return deco

def on_death(fn):
    """Run a handler when the player dies. The handler takes no arguments.
        @on_death
        def died():
            msg('you died!')
    """
    _require_socket()
    _death_handlers.append(fn)
    _subscribe('death')
    return fn

def on_health(fn):
    """Run a handler when health changes. Signature: fn(health, previous).
        @on_health
        def hurt(hp, prev):
            if hp < prev: msg(f'took {prev-hp:.0f} damage')
    """
    _require_socket()
    _health_handlers.append(fn)
    _subscribe('health')
    return fn

def every(seconds, fn):
    """Call fn() every `seconds` on the dispatcher thread. Returns fn.
        every(30, lambda: say('still here'))
    """
    _require_socket()
    _start_dispatch()
    _timers.append([float(seconds), time.time() + float(seconds), fn])
    return fn

def run():
    """Block the main thread so event callbacks keep firing.
    Returns cleanly on Ctrl-C or when the script is stopped.
    """
    _start_dispatch()
    try:
        while True:
            time.sleep(0.5)
    except KeyboardInterrupt:
        pass
