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
wait(seconds)          # sleep without blocking the game
print('message')       # write to the Peripheral Log tab
```

---

## State Object

`state()` returns a dict:
```python
{
  'health': 18.0, 'max_health': 20.0,
  'hunger': 14,
  'position': {'x': 120.5, 'y': 64.0, 'z': -340.2},
  'yaw': 180.0, 'pitch': 0.0,
  'dimension': 'minecraft:overworld',
  'biome': 'minecraft:forest',
  'is_day': True,
  'time_ticks': 6000,   # 0=midnight  6000=sunrise  13000=sunset  24000=midnight
  'is_raining': False, 'is_thundering': False,
  'gamemode': 'survival',
  'xp_level': 5, 'xp_progress': 0.3,
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
```

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
event = gui_poll()                   # id of last button click, or None
text  = gui_input('inp')             # read a text field
gui_is_open()                        # True if screen is visible
gui_close()                          # close the screen
```
Press H in-game to show/hide the screen.

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
