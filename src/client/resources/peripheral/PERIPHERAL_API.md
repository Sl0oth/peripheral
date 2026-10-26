# Peripheral Scripting API

> Paste this file into an AI (ChatGPT, Claude, etc.) and say:
> "Write me a Peripheral script that [does X]"

## Quick Start

Every script starts with:
```python
from mc import *
```
`mc.py` is in your scripts folder and is updated automatically by the mod.

## Reading Game State

### `state()` → dict
Returns a full snapshot of the game. Keys:
- `position` → `{x, y, z}` (floats)
- `health` → float (0–20)
- `max_health` → float
- `hunger` → int (0–20)
- `is_dead` → bool
- `xp_level` → int
- `yaw` → float (degrees, where player is looking horizontally)
- `pitch` → float (degrees, where player is looking vertically)
- `xp_progress` → float 0–1 (progress toward next level)
- `xp` → dict `{level, progress, total}`
- `dimension` → string e.g. `"minecraft:overworld"`
- `biome` → string e.g. `"minecraft:plains"`
- `time_ticks` / `time_of_day` → int (0–24000; 0=dawn, 6000=noon, 13000=night)
- `is_day` → bool
- `is_raining` → bool
- `is_thundering` → bool
- `weather` → string `"clear"` / `"rain"` / `"thunder"`
- `light` → dict `{block, sky}` (0–15 each)
- `food` → int (0–20), `saturation` → float
- `on_ground` → bool
- `speed` → float (blocks/tick horizontal speed)
- `in_water` / `in_lava` → bool
- `gamemode` → string e.g. `"survival"`
- `nearby_entities` → list (see below)
- `nearby_blocks` → list (see below)
- `equipped` → item dict (see below)
- `current_screen` → string (open GUI name, or `"none"`)
- `window_scaled_width`, `window_scaled_height` → game GUI size in pixels; script GUI coordinates use this space
- `window_width`, `window_height` → window size; `gui_scale` → current Minecraft GUI scale

### New state helpers
```python
biome()          # 'minecraft:plains'
time_of_day()    # 0–23999 ticks
xp()             # {'level': 30, 'progress': 0.45, 'total': 1395}
hunger()         # {'food': 20, 'saturation': 5.0}
```

### `find_blocks(block, radius=32, max=256)` → list of `{x,y,z}`
Scan for nearby blocks by keyword, nearest first (client-thread, time-budgeted).
`radius` max 64, `max` max 512.
```python
for b in find_blocks('diamond_ore', radius=48):
    print(b['x'], b['y'], b['z'])
```

### `pos()` → (x, y, z)
```python
x, y, z = pos()
msg(f'I am at {x:.0f}, {y:.0f}, {z:.0f}')
```

### `health()` → float
```python
if health() < 6:
    msg('Low health!')
```

### `nearby()` → list of entity dicts
Each entity dict has:
- `type` → string e.g. `"minecraft:zombie"`
- `distance` → float (blocks away)
- `x`, `y`, `z` → int coordinates
- `health` → float
- `hostile` → bool
- `tamed` → bool (if tameable)
- `owner` → string (owner name if tamed)
- `custom_name` → string (if named with name tag)
- `baby` → bool
```python
for e in nearby():
    if e['hostile'] and e['distance'] < 10:
        msg(f"DANGER: {e['type']} is {e['distance']:.0f}m away!")
```

### `inventory()` → list of item dicts
Returns 36 main inventory slots (hotbar + main), armor slots 100–103 and offhand slot 40. Use `slot_type` to distinguish `hotbar`, `main`, `armor_feet/legs/chest/head`, and `offhand`. No duplicate mainhand entry is returned. Read the held item with `state()['equipped']`. Each item dict:
- `slot` → int (0–35; 0–8 = hotbar)
- `item` → string e.g. `"minecraft:diamond_sword"` or `"empty"`
- `count` → int
- `durability_pct` → int 0–100 (tools/armor only)
```python
for item in inventory():
    if item['item'] != 'empty':
        print(item['slot'], item['item'], 'x', item['count'])
```

### `chat_log(n=20)` → list of message dicts
Returns the last n chat messages. Each dict:
- `time` → epoch milliseconds
- `type` → `"chat"` (player) or `"system"` (server message)
- `sender` → player name (empty for system messages)
- `text` → full text e.g. `"Steve joined the game"`

### `wait_for_chat(pattern, timeout=30)` → dict or None
Blocks until a chat message matches a regex pattern, or timeout expires.
```python
import re
m = wait_for_chat('joined the game')
if m:
    match = re.match(r'^(.+) joined the game', m['text'])
    if match:
        say(f'Welcome, {match.group(1)}!')
```

## Movement

### `goto(x, z, y=None)` → status string
Walk to coordinates using the built-in A* navigator.
Returns `'done'`, `'failed'`, or `'timeout'`.
```python
result = goto(100, -200)
msg(f'Navigation result: {result}')
```

### `walk(blocks, direction=None)` → status
Walk `blocks` blocks forward (or in a named direction).
```python
walk(10)           # forward
walk(5, 'north')   # north
```

### `look(direction)`
Face a direction: `'north'`, `'south'`, `'east'`, `'west'`, `'up'`, `'down'`.

### `baritone_goto(x, y, z)`
Use Baritone's pathfinder (handles climbing, swimming, falling).
**Requires Baritone mod.**

## Macros (record and play back)

Recordings keep where you stood and looked every tick plus what you pressed, so they play back
exactly and steer back onto the path if anything pushes you off. Record from the Macros tab or with
F7; these let scripts do the same.

### `record(name=None, resume=False)` → name
Start recording. `resume=True` adds to the end of an existing macro.

### `stop_recording()`
Stop and save.

### `play_recording(name, loops=1, relative=False, wait=True)` → message
Play a macro. `loops=0` repeats until `stop_playback()`. `relative=True` starts it on the block you
stand on instead of where it was recorded. With `wait=True` it returns when playback ends, with a
message like `"Finished mine loop (3 loops)"`. Playback a script started stops when the script stops.

### `stop_playback()`, `recorder_status()`, `recordings()`

## Screens (click and type like a player)

These go through Minecraft's own mouse and keyboard handling, so every screen, vanilla or from
another mod, sees a normal click or key press. Coordinates are GUI pixels.

### `screen()` → dict
`{'open', 'title', 'kind', 'width', 'height', 'mouse_x', 'mouse_y', 'controls': [...], 'slots': [...]}`.
Each control has `label`, `kind`, `x`, `y`, `width`, `height`, `active`. Container screens list
`slots` with `slot`, `x`, `y`, `item`, `count`.

### `click_button(label)` → label clicked
Click a button by its text (exact match first, then one containing it).

### `click_slot(slot, button='left', shift=False)`
Click a container slot by its number in `screen()['slots']`.

### `click(x, y, button='left')`, `move_mouse(x, y)`, `mouse_down()`, `mouse_up()`, `scroll(amount, x=None, y=None)`

### `press_key(key)`, `key_down(key)`, `key_up(key)`, `type_text(text)`
Keys by name: `'e'`, `'enter'`, `'escape'`, `'tab'`, `'backspace'`, `'space'`, `'up'`, `'f3'`…

```python
press_key('e')                 # open the inventory, as a player would
click_slot(36); click_slot(9)  # move the first hotbar stack into the inventory
press_key('escape')
```

## Packets (test how a server copes)

Everything is on the `packet` object. The log works everywhere. Cancelling, holding and sending work
in singleplayer, and on servers that install **Peripheral Testing** (a plugin for Paper and Spigot,
or a mod for Fabric servers), which registers the `peripheral:testing` channel. Elsewhere they
raise `RuntimeError` with `not_allowed_here`. Keep-alive packets are never cancelled, held or sent
by hand, and whatever a script turns on is turned off when the script stops.

Packet names are Minecraft's own: `move_player_pos`, `swing`, `chat_command`, `set_entity_motion`…

### `packet.log(on=True, clear=False)`
Start or stop logging. `packet.recent()` reads the log.

### `packet.recent(limit=100, direction=None, name=None, since=0)`
Logged packets, oldest first: `{'time', 'direction', 'name', 'summary', 'blocked', 'held', 'data'}`.
`direction` is `'sent'` or `'received'`; `name` matches part of a name. `data` is a sent packet's
bytes in hex, id first.

### `packet.cancel(name, direction='sent')`, `packet.allow(name, direction='sent')`, `packet.cancelled()`
Stop every packet with that name until `allow`. `cancelled()` returns `{'sent': [...], 'received': [...]}`.

### `packet.hold()`, `packet.release(send=True)`
Keep everything the game sends until `release`, which sends it in order, or drops it with `send=False`.

### `packet.send(data)`
Send a packet from its bytes in hex, the way `packet.recent()` gives them. Returns `{'name', 'summary'}`.

### `packet.allowed()`
Whether cancelling, holding and sending work here.

```python
packet.log(True)
say('/help')
last = packet.recent(direction='sent', name='chat_command')[-1]
packet.send(bytes.fromhex(last['data']).replace(b'help', b'list').hex())  # same length, so no other bytes change
```

## Events (push callbacks)

Instead of polling in a `while` loop, register handlers and let the mod push
events to you over a persistent socket (localhost, default port 25587; falls
back to HTTP silently). All handlers run on **one** background dispatcher
thread, so your code is never racy. If only the older HTTP transport is
available, the `@on_*` decorators raise a clear "update the mod" error.

```python
from mc import *

@on_chat(r'^!home')          # regex optional; fn(msg_dict)
def go_home(m):
    say('/home')

@on_gui('start_btn')         # widget id optional (omit = all); fn(event_dict)
def clicked(e):
    msg('started')

@on_tick(hz=5)               # 0–20 Hz; fn(snapshot) with pos/health/food/yaw/pitch/dimension
def update(s):
    hud_update('hp', text=f"HP {s['health']:.0f}")

@on_death                    # fn() — no args
def died():
    msg('you died!')

@on_health                   # fn(health, previous)
def hurt(hp, prev):
    if hp < prev: msg(f'took {prev-hp:.0f} damage')

every(30, lambda: say('still here'))   # repeating timer on the dispatcher thread

run()   # block main thread so callbacks keep firing; Ctrl-C / Stop exits cleanly
```

Decorators auto-subscribe over the socket. Prefer event payloads for event values. `gui_input()` and `gui_is_open()` use the non-consuming `gui_peek()` path in 1.3.1; older versions consumed queued events.

## Notifications & Sound

### `notify(title, text='')`
Show a vanilla toast (top-right popup).

### `play_sound(sound_id, volume=1.0, pitch=1.0)`
Play a client-side sound.
```python
play_sound('minecraft:entity.experience_orb.pickup')
```

## Inventory & Items

### `slot(n)` — select hotbar slot 1–9
```python
slot(1)  # select first hotbar slot
```

### `equip(item)` → bool
Find an item by keyword and put it in your hand.
Searches hotbar first, then moves from main inventory if needed.
Returns `True` if found and equipped.
```python
if equip('diamond_sword'):
    msg('Sword equipped!')
else:
    msg('No sword in inventory.')
```

### `drop(all=False)`
Drop 1 of held item, or the whole stack if `all=True`.

## Actions

### `place(x, y, z, face='up', item=None)`
Right-click a block face to place a block.
`face` can be `'up'`, `'down'`, `'north'`, `'south'`, `'east'`, `'west'`.
If `item` is given, auto-equips it first.
```python
x, y, z = pos()
place(int(x), int(y)-1, int(z), item='cobblestone')
```

### `mine(x, y, z)`
Start breaking a block at coordinates.

### `use_item()`
Right-click with held item — eat food, draw bow, use tool, etc.
```python
equip('bread')
use_item()
wait(2)   # eating takes ~1.6 seconds
```

### `say(message)` — send chat to server
Prefix with `/` to run commands: `say('/gamemode creative')`

### `msg(message)` — show message to yourself only (no server)

### `wait(seconds)` — sleep

## Baritone (Requires Baritone Mod)

Download: https://modrinth.com/mod/baritone

### `mine_auto(block, count=0)`
Auto-find and mine a block type. `count=0` = mine forever.
```python
mine_auto('diamond_ore', 10)
```

### `baritone_goto(x, y, z)`
Pathfind to exact coordinates.

### `baritone(command)`
Send any raw Baritone command (the `#` prefix is added automatically).
```python
baritone('follow Steve')    # follow a player
baritone('mine oak_log')    # mine logs
```

### `baritone_stop()`
Stop whatever Baritone is doing.

## External HTTP / APIs

### `http_get(url, headers=None)` → dict
```python
data = http_get('https://api.example.com/data')
```

### `http_post(url, body, headers=None)` → dict
```python
# OpenAI example:
r = http_post(
    'https://api.openai.com/v1/chat/completions',
    {'model': 'gpt-4o-mini', 'messages': [{'role':'user','content':'hello'}]},
    headers={'Authorization': 'Bearer sk-YOUR_KEY'}
)
say(r['choices'][0]['message']['content'])
```

## Custom HTTP Server (for your script)

```python
server = Server(8080)   # port number

@server.route('/status')
def status(req):
    x, y, z = pos()
    return {'x': x, 'y': y, 'z': z, 'health': health()}

@server.route('/say')   # POST {"message": "hello"}
def say_handler(req):
    say(req.get('message', ''))
    return {'ok': True}

server.start()         # background thread, script keeps running
# or: server.serve_forever()   # blocks here
```

## HUD Overlay

Always-on elements drawn on the game screen (no need to press H).
```python
hud_set(elements)         # full layout: list of dicts
hud_update(id, **props)   # update one element
hud_clear()               # remove this script's elements
```
Element `type`s: `label`, `bar`, `rect`, `divider`, `item`, and:
- `graph` — rolling line/area chart. Props `w, h, color, min, max` (omit
  min/max to autoscale). Push samples with `hud_update(id, push=<float>)`
  or replace the whole `values` list.
- `compass` — horizontal yaw strip with N/E/S/W ticks. Props `w, h, color`.

Anchors: `top_left` (default) `top_right` `bottom_left` `bottom_right` `center`.
x/y are offsets from the anchor corner.

**Edit mode:** open the Peripheral panel (**G**) and toggle HUD edit to drag
elements with the mouse; positions snap to a 4px grid and persist to
`config/peripheral/hud_layouts.json`. Stored overrides are re-applied on top of
whatever a script passes to `hud_set`, so hand-placed elements stay put.

## Themes

The whole Peripheral UI is themed. Pick a theme from the Scripts tab header
(persists to config); built-ins are `terminal` (default) and `vanilla`. Drop
custom themes as `config/peripheral/themes/<name>.json` mapping role names to
`"#AARRGGBB"` colors — they appear in the picker by filename. A script can theme
just its own `open_gui` screen with a top-level `"theme": "vanilla"` in the
layout.

## Environment Variables

Every script has these env vars available:
- `PERIPHERAL_STATE_PORT` — HTTP server port (default 25585)
- `PERIPHERAL_SOCKET_PORT` — event socket port (default 25587)
- `PERIPHERAL_LOCAL_TOKEN` — private per-run local API authorization (not an AI provider key)
- `PERIPHERAL_AGENT_URL` — AI agent URL from Settings tab
- `PERIPHERAL_SCRIPTS_DIR` — Absolute path to the scripts folder

## Script Lifecycle

- Scripts run as a subprocess when you press **Run** in the Scripts tab.
- Stop them with **Stop**, or they stop when they exit naturally.
- `print()` / stdout goes to `scriptname.log` in the scripts folder.
- You can view logs in-game via the Log tab (click the script name).
- Assign scripts to hotkeys (1–5) via the Scripts tab for quick launch.

## Patterns

### Loop forever watching chat:
```python
while True:
    m = wait_for_chat('some pattern', timeout=60)
    if m:
        # do something
        pass
```

### Poll state every second:
```python
while True:
    s = state()
    if s['health'] < 6:
        equip('cooked_beef')
        use_item()
        wait(2)
    wait(1)
```

### Auto-equip before placing:
```python
x, y, z = pos()
place(int(x)+1, int(y), int(z), face='north', item='stone')
```



### Local authorization and script trust

Use the bundled `mc` module: it passes per-run HTTP/TCP authorization automatically. Never print or share `PERIPHERAL_LOCAL_TOKEN`. Provider keys are not available through `/config` or injected into scripts; arrange any external API credentials explicitly. Python runs as trusted local code with normal OS file/process access, not a sandbox. The legacy File Access toggle/monkeypatch does not apply. Stopping a script revokes its local capability and releases its owned input/navigation/HUD resources. Do not retry actions after an ambiguous timeout: the original operation may have executed. External local clients require the session capability in `config/peripheral/local-api.token`; reject browser-origin use.


## Small working examples

Run `examples/gui/terminal.py` for a local Minecraft command/log panel (`help`, `pos`, `health`, `inventory`, `echo TEXT`). This is not a Python or operating-system shell. Click Run; Stop exits. H/Escape hides the panel without ending the script; H restores it.

Run `examples/hud/trail.py` for coordinates, distance from where this run started, health, food and a coarse game clock. It reads state only. Stop from G → Scripts to remove its HUD. Starting again chooses a new reference; this does not save a home or navigate.

### GUI event and output recipe

`gui_state()` returns `{'open': bool, 'event': 'button_id' or absent, 'inputs': {'field_id': 'text'}}`. Each call removes one pending event. Read once per loop and use **that same snapshot** for the event and input:

```python
snapshot = gui_state()
if snapshot.get('event') == 'run':
    command = snapshot['inputs'].get('command', '')
    gui_update('output', lines=['You entered: ' + command], scroll=0)
    gui_update('command', value='')
wait(0.1)
```

`gui_peek()` returns `open` and `inputs` without an event or queue removal. `gui_input(id)` and `gui_is_open()` use this read-only path, so checks cannot steal clicks. `gui_state()` and `gui_poll()` still remove one event each for compatibility. A single `gui_state()` snapshot is best when a click and its input must be read together. With older installed helpers, value/open reads consumed events; update the mod and generated mc.py together.

Use a read-only scrollable output widget for logs:

```python
{'type': 'textbox', 'id': 'output', 'x': 6, 'y': 20,
 'w': 196, 'h': 78, 'line_h': 10, 'lines': ['Ready.']}
```

`textbox` supports `lines` (list of strings) or `text` (newline string), `scroll` (first visible line index), `color`, `bg_color`, `w`, `h`, `line_h`. Mouse wheel scrolls. Lines clip horizontally; wrap long text in Python, for example with standard-library `textwrap.wrap`. `gui_update` changes these properties; retain your output history locally and bound its length. Coordinates are Minecraft GUI pixels, relative to the panel content area. Reserve the panel's title height (15px), left padding (6px), and room at the bottom when sizing your layout. Explicit themes/colors stay author-controlled.

`input` supports `value`, `placeholder`, `w`, `h`; text has a fixed 256-character limit. Read its value from snapshot inputs; `gui_update(id, value='...')` changes the actual visible input. Buttons emit their IDs. Custom input Enter submission is not a documented action; provide an explicit Run button.

### Mining boundary

`mine(x,y,z)` starts one block-break attempt; it is not a blocking harvest operation and does not promise completion or item collection. `mine_auto` requires the separate optional Baritone mod. A robust base-API wood-gathering script needs verified continuous breaking, bounded cancellation, reach/obstacle checks and navigation/pickup handling; do not infer those from `mine` alone.

GUI `item` widgets accept `item` or `item_id` (HUD-compatible alias), a namespaced item ID, plus optional `count` and `label`. For example: `{'type': 'item', 'id': 'held', 'x': 6, 'y': 56, 'item_id': 'minecraft:iron_axe'}`.
