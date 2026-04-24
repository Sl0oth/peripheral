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
- `dimension` → string e.g. `"minecraft:overworld"`
- `time_ticks` → int (0–24000)
- `is_day` → bool
- `is_raining` → bool
- `is_thundering` → bool
- `gamemode` → string e.g. `"survival"`
- `nearby_entities` → list (see below)
- `nearby_blocks` → list (see below)
- `equipped` → item dict (see below)
- `current_screen` → string (open GUI name, or empty)

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
Returns all 36 main inventory slots (hotbar + main). Each item dict:
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

## Environment Variables

Every script has these env vars available:
- `PERIPHERAL_STATE_PORT` — HTTP server port (default 25585)
- `PERIPHERAL_API_KEY` — API key from Settings tab
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
