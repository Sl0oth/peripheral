# Peripheral Scripting — AI Prompt

Paste this file into Claude, ChatGPT, or any AI and say:
**"Write me a Peripheral script that [does X]"**

---

## What is Peripheral?

Peripheral is a Fabric Minecraft mod that lets you write Python scripts to control the game.
Scripts run from `config/peripheral/scripts/` and are managed through an in-game GUI.

Every script starts with:
```python
from mc import *
```
This gives you the full `mc` API — no other imports needed for game functions.

---

## How Scripts Run

- Scripts run as background daemon threads — they keep running until you stop them or they finish.
- `wait(seconds)` pauses the script without blocking the game.
- `print(...)` writes to the in-game Log tab. `msg(...)` shows text in your chat (only visible to you).
- Scripts that loop forever with `while True: ... wait(N)` are always-running background scripts.
- Scripts that run once and exit are one-shot scripts.

---

## Core Functions

### Reading Game State
```python
s = state()          # full game state dict (see State Object below)
x, y, z = pos()     # player position as floats
hp = health()        # current health as float (0.0-20.0)
inv = inventory()    # list of inventory slot dicts (see Inventory below)
log = chat_log(n=20) # last N chat messages as list of dicts
```

### Sending Messages
```python
msg('hello')                   # system message — only you see it, no server spam
say('hello')                   # sends to chat / runs as command if starts with /
say('/time set day')           # execute a command
rich_msg([...])                # JSON text component — clickable links, hover text
```

### Controlling the Player
```python
look('north')          # face a direction: 'north' 'south' 'east' 'west' 'up' 'down'
look(yaw, pitch)       # exact angles
use_item()             # right-click held item (eat, drink, place, draw bow)
equip('diamond_sword') # move item matching name to main hand — True if found
equip('bread')         # partial match: 'bread' matches 'minecraft:bread'
drop_item()            # drop held item (Q)
attack()               # swing arm / attack entity in front
click_block(x, y, z, face='top', action='right')  # interact with a block face
jump()                 # press space once
sprint(True)           # toggle sprint
sneak(True)            # toggle sneak
move(forward, right, up)  # movement vector (-1.0 to 1.0)
```

### Navigation (requires Baritone mod)
```python
result = goto(x, z)          # walk to X, Z. Blocks until arrived. Returns 'done' or 'failed'
baritone('mine diamond_ore') # send any Baritone command (without the # prefix)
baritone_stop()              # cancel current task
status = nav_status()        # 'idle' | 'walking' | 'done' | 'failed'
```

### Waiting for Chat
```python
m = wait_for_chat('pattern', timeout=60)
# Blocks until a chat message matching the pattern arrives, or timeout passes.
# Returns the message dict or None on timeout.
if m:
    say(f'Hi {m["sender"]}!')
```

### HTTP Requests
```python
data = http_get('https://api.example.com/data')
data = http_post('https://api.example.com/', payload, headers={'Authorization': 'Bearer ...'})
```

### Utilities
```python
entities = nearby()    # list of nearby entity dicts
blocks = find_blocks('diamond_ore', radius=48)  # [{x,y,z},...] nearest first (radius<=64)
biome()                # 'minecraft:plains'
time_of_day()          # 0-23999 ticks
xp()                   # {'level','progress','total'}
hunger()               # {'food','saturation'}
notify('Title', 'body')                     # vanilla toast (top-right)
play_sound('minecraft:block.note_block.pling', volume=1.0, pitch=1.0)
wait(seconds)          # sleep without blocking the game
print('message')       # write to the Peripheral Log tab
```

---

### Macros, screens and packets
- `play_recording(name, loops=1, relative=False)` plays a macro the player recorded in the Macros tab;
  `loops=0` repeats until `stop_playback()`. `recordings()` lists them.
- `screen()` describes the open screen; `click_button('Done')`, `click_slot(n)`, `press_key('e')`,
  `type_text('hi')` drive any screen through real mouse and keyboard input.
- `packet.log(True)` then `packet.recent(name='move_player')` reads traffic. `packet.cancel`,
  `packet.hold`, `packet.release` and `packet.send` only work in singleplayer or on servers running
  Peripheral Testing; check `packet.allowed()` first.

## Event Callbacks (push, no polling)

Register handlers and the mod pushes events to you over a socket. All handlers
run on one background thread. End with `run()` to keep the script alive.
```python
from mc import *

@on_chat(r'^!home')       # regex optional; fn(msg_dict)
def _(m): say('/home')

@on_gui('start_btn')      # widget id optional; fn(event_dict)
def _(e): msg('clicked ' + e['id'])

@on_tick(hz=5)            # fn(snapshot: pos/health/food/yaw/pitch/dimension)
def _(s): hud_update('hp', text=f"HP {s['health']:.0f}")

@on_death                 # fn()
def _(): msg('you died')

@on_health                # fn(health, previous)
def _(hp, prev):
    if hp < prev: msg(f'took {prev-hp:.0f} dmg')

every(30, lambda: say('still here'))   # repeating timer
run()                                  # block; Ctrl-C / Stop exits cleanly
```
Prefer `@on_gui` and use event payloads for event values. Current `gui_input()`/`gui_is_open()` use non-consuming `gui_peek()`; older helpers consumed events. Callbacks need the socket transport; on an old mod build
the `@on_*` decorators raise a clear "update the mod" error.

---

## State Object

`state()` returns a dict:
```python
{
  'health': 18.0, 'max_health': 20.0,
  'hunger': 14, 'food': 14, 'saturation': 3.0,
  'position': {'x': 120.5, 'y': 64.0, 'z': -340.2},
  'yaw': 180.0, 'pitch': 0.0,
  'on_ground': True, 'speed': 0.21,
  'dimension': 'minecraft:overworld',
  'biome': 'minecraft:forest',
  'is_day': True,
  'time_ticks': 6000, 'time_of_day': 6000,  # 0=dawn 6000=noon 13000=night
  'is_raining': False, 'is_thundering': False, 'weather': 'clear',
  'light': {'block': 0, 'sky': 15},
  'gamemode': 'survival',
  'xp_level': 5, 'xp_progress': 0.3, 'xp': {'level': 5, 'progress': 0.3, 'total': 60},
  'is_dead': False,
  'nearby_entities': [
    {'type': 'minecraft:zombie', 'distance': 8.2, 'hostile': True},
    {'type': 'minecraft:cow',    'distance': 12.0, 'hostile': False},
  ],
  'nearby_blocks': ['minecraft:stone', 'minecraft:grass_block', ...],
}
```

---

## Inventory Object

`inventory()` returns a list of slot dicts:
```python
{
  'slot': 0,
  'slot_type': 'hotbar',   # hotbar / inventory / armor_head / armor_chest /
                           # armor_legs / armor_feet / offhand / mainhand
  'item': 'minecraft:diamond_sword',
  'count': 1,
  'durability_pct': 87,    # 0-100 for tools/armor, None otherwise
}
```
Empty slots have `'item': 'empty'`. Filter: `[i for i in inventory() if i['item'] != 'empty']`

---

## HUD Overlay

Always-on elements rendered on the game screen.
```python
hud_set(elements)           # set full layout (list of dicts)
hud_update(id, **props)     # update one element
hud_clear()                 # remove everything
```

Widget types:
```python
{'type': 'rect',    'id': 'bg',   'anchor': 'top_left', 'x': 4, 'y': 4, 'w': 100, 'h': 50,
                    'color': '#CC111111', 'border': '#FF2A2A2A'}
{'type': 'label',   'id': 'txt',  'anchor': 'top_left', 'x': 8, 'y': 8,
                    'text': 'Hello', 'color': 'green', 'shadow': True}
{'type': 'bar',     'id': 'bar',  'anchor': 'top_left', 'x': 8, 'y': 20, 'w': 100, 'h': 5,
                    'value': 0.75, 'color': 'red', 'bg_color': '#FF1A1A1A'}
{'type': 'divider', 'id': 'div',  'anchor': 'top_left', 'x': 4, 'y': 50, 'w': 100}
{'type': 'item',    'id': 'icon', 'anchor': 'top_left', 'x': 8, 'y': 8,
                    'item_id': 'minecraft:diamond_sword'}   # '' to hide
{'type': 'graph',   'id': 'tps',  'anchor': 'top_left', 'x': 8, 'y': 30, 'w': 100, 'h': 30,
                    'values': [19,20,18], 'color': 'green', 'min': 0, 'max': 20}
                    # push a sample: hud_update('tps', push=tps)  (omit min/max = autoscale)
{'type': 'compass', 'id': 'cmp',  'anchor': 'top_left', 'x': 8, 'y': 8, 'w': 120, 'h': 12,
                    'color': 'white'}   # horizontal yaw strip with N/E/S/W ticks
```

**Edit mode:** open the panel (G) and toggle HUD edit to drag elements; they
snap to a 4px grid and persist to `config/peripheral/hud_layouts.json`,
re-applied on top of what a script sets.

Anchors: `top_left` `top_right` `bottom_left` `bottom_right` `center`
x/y are offsets FROM the anchor corner — top_right x=4 means 4px from the RIGHT edge.

Colors: `'red'` `'green'` `'yellow'` `'blue'` `'white'` `'grey'` `'orange'` `'purple'` `'pink'`
or hex `'#RRGGBB'` / `'#AARRGGBB'` (AA=alpha: 00=transparent FF=solid)

---

## Custom In-Game Screen

```python
open_gui({'title': 'My Screen', 'w': 200, 'h': 150, 'widgets': [
    {'type': 'label',   'id': 'lbl', 'x': 0, 'y': 0,  'text': 'Hello', 'color': 'white'},
    {'type': 'bar',     'id': 'bar', 'x': 0, 'y': 14, 'w': 150, 'h': 5, 'value': 0.5, 'color': 'green'},
    {'type': 'button',  'id': 'btn', 'x': 0, 'y': 30, 'w': 80,  'h': 14, 'label': 'Click me'},
    {'type': 'input',   'id': 'inp', 'x': 0, 'y': 50, 'w': 150, 'h': 14, 'placeholder': 'Type here...'},
]})
gui_update('lbl', text='New text')   # update a widget
snapshot = gui_state()              # ONE read pops one event
event = snapshot.get('event')        # button id, or None
text = snapshot['inputs'].get('inp', '')
is_open = snapshot['open']
gui_close()                          # close the screen
```
Press H in-game to show/hide the screen.
Add a top-level `'theme': 'vanilla'` to the layout to theme just this screen.
The whole Peripheral UI is themed too — pick `terminal` (default) / `vanilla` /
a custom `config/peripheral/themes/<name>.json` from the Scripts tab header.

---

## Tips and Gotchas

- Call `state()` once per loop and store it: `s = state()` — it makes an HTTP call.
- `equip(name)` uses partial match: `equip('sword')` finds `minecraft:diamond_sword`.
- Minecraft chat has a 256 char limit: `say(text[:250])`
- `say('/command')` runs commands silently.
- `msg()` is only visible to you, not other players.
- `print()` goes to the Log tab in the Peripheral GUI.
- Always wrap long loops in `try/except` so one error doesn't kill the script.
- `wait_for_chat` returns `None` on timeout — always check `if m:` before using.
- `item` HUD widgets use full namespaced IDs: `'minecraft:iron_sword'` not `'iron_sword'`
- For `top_right` anchor: `x=4` means 4px from the RIGHT edge.
- Baritone functions do nothing if Baritone is not installed.



### Local authorization and script trust

Use the bundled `mc` module: it passes per-run HTTP/TCP authorization automatically. Never print or share `PERIPHERAL_LOCAL_TOKEN`. Provider keys are not available through `/config` or injected into scripts; arrange any external API credentials explicitly. Python runs as trusted local code with normal OS file/process access, not a sandbox. The legacy File Access toggle/monkeypatch does not apply. Stopping a script revokes its local capability and releases its owned input/navigation/HUD resources. Do not retry actions after an ambiguous timeout: the original operation may have executed. External local clients require the session capability in `config/peripheral/local-api.token`; reject browser-origin use.


Small templates: `examples/gui/terminal.py` (allowlisted local command panel, no shell/eval) and `examples/hud/trail.py` (read-only travel/vitals HUD). `textbox` is a read-only scrollable output widget with `lines`, `scroll`, `w`, `h`, `line_h`; wrap long lines in Python. Keep history bounded. One `gui_state()` snapshot provides both `event` and `inputs`; gui_state()/gui_poll() consume one event, while gui_peek()/gui_input()/gui_is_open() do not. Use one snapshot when event/input values must match. `inventory()` includes main slots0–35, armor100–103 and offhand40; filter slot_type and do not expect a duplicate mainhand record; use state()['equipped'] for the held item. `mine(x,y,z)` starts one break attempt, not complete survival harvesting; `mine_auto` requires Baritone. State includes window_scaled_width/height in authored GUI pixels.

The installed-version full reference is `PERIPHERAL_API_CURRENT.md`. It refreshes on startup; the older `PERIPHERAL_API.md` starter is preserved in case the user edited it.

GUI item widgets accept item or item_id (same ID convention as HUD); use namespaced IDs such as minecraft:iron_axe.
