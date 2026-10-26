# Peripheral Scripting Documentation

Peripheral lets you write Python scripts that read Minecraft state, control the player, draw overlays, build custom GUIs, and create persistent tools like waypoint systems.

This document is the public scripting reference. It focuses on the Python side of Peripheral and avoids internal repo or branch details.

---

## Contents

1. Getting Started
2. How Scripts Work
3. Core Concepts
4. State and World Data
5. Movement and Player Control
6. Items, Inventory, and Interaction
7. Chat and Messaging
8. GUI API
9. HUD API
10. World Overlay API
11. File and JSON Helpers
12. Waypoint Persistence
13. Optional Baritone Integration
14. Embedded HTTP Server
15. Event Callbacks (Push API)
16. Themes
17. Examples
18. Troubleshooting

---

## 1. Getting Started

Every Peripheral script starts with:

```python
from mc import *
```

Scripts live in:

```text
config/peripheral/scripts/
```

A minimal script:

```python
from mc import *

x, y, z = pos()
msg(f"You are at {x:.0f}, {y:.0f}, {z:.0f}")
```

---

## 2. How Scripts Work

Peripheral runs scripts as background Python processes.

Important behavior:

- scripts can run once and exit
- scripts can also loop forever
- `wait(seconds)` pauses the script without freezing the game
- `print(...)` writes to the log
- `msg(...)` shows a local in-game message

Long-running scripts usually look like this:

```python
from mc import *

while True:
    s = state()
    if s["health"] < 6:
        msg("Low health!")
    wait(1)
```

If a script owns overlays or HUD elements, use `try/finally`:

```python
from mc import *
import time

draw_live_tags(filter="hostile", id="hostiles")

try:
    while True:
        time.sleep(60)
finally:
    overlay_clear()
```

---

## 3. Core Concepts

Peripheral gives you a few major scripting systems:

- game-state reads
- movement and actions
- chat and messages
- custom in-game GUI screens
- always-on HUD widgets
- world-space overlays
- file-based persistence

The main convenience helpers are all in `mc.py`.

---

## 4. State and World Data

### `state() -> dict`

Returns a full snapshot of the current game state.

Common keys:

- `position`
- `health`
- `max_health`
- `hunger`, `food`, `saturation`
- `yaw`
- `pitch`
- `on_ground`, `speed`
- `dimension`
- `biome`
- `time_ticks`, `time_of_day`
- `is_day`
- `is_raining`
- `is_thundering`
- `weather` (`"clear"` / `"rain"` / `"thunder"`)
- `light` (`{block, sky}`, 0–15)
- `gamemode`
- `xp_level`, `xp_progress`, `xp` (`{level, progress, total}`)
- `is_dead`
- `nearby_entities`
- `nearby_blocks`
- `equipped`
- `current_screen`

Example:

```python
s = state()
msg(f"Health: {s['health']:.0f}/{s['max_health']:.0f}")
```

### Convenience state helpers

```python
biome()          # "minecraft:plains"
time_of_day()    # 0-23999 ticks
xp()             # {"level": 30, "progress": 0.45, "total": 1395}
hunger()         # {"food": 20, "saturation": 5.0}
```

### `find_blocks(block, radius=32, max=256) -> list[dict]`

Scan for nearby blocks by keyword, returning `{x, y, z}` dicts nearest-first.
The scan runs on the client thread with a time budget. `radius` caps at 64,
`max` at 512.

```python
for b in find_blocks("diamond_ore", radius=48):
    draw_esp(b["x"], b["y"], b["z"], color="blue", id=f"d{b['x']}_{b['y']}_{b['z']}")
```

### `notify(title, text='')`

Show a vanilla toast (top-right popup).

### `play_sound(sound_id, volume=1.0, pitch=1.0)`

Play a client-side sound, e.g. `play_sound("minecraft:block.note_block.pling")`.

### `pos() -> (x, y, z)`

Returns the player's position as floats.

```python
x, y, z = pos()
```

### `health() -> float`

Returns current health.

### `inventory() -> list[dict]`

Returns inventory slot data.

Each slot dict may include:

- `slot`
- `slot_type`
- `item`
- `count`
- `durability_pct`

Example:

```python
for item in inventory():
    if item["item"] != "empty":
        print(item["slot"], item["item"], item["count"])
```

### `nearby() -> list[dict]`

Returns nearby entity data.

Entity dicts can include:

- `type`
- `distance`
- `x`, `y`, `z`
- `eye_y`
- `health`
- `max_health`
- `hostile`
- `tamed`
- `owner`
- `custom_name`
- `baby`
- `height`
- `uuid`

Example:

```python
for e in nearby():
    if e["hostile"] and e["distance"] < 10:
        msg(f"Danger: {e['type']} at {e['distance']:.1f}m")
```

### `block_at(x, y, z) -> dict`

Returns block information at a world position.

### `targeted_block() -> dict`

Returns information about the block under the crosshair.

### `targeted_entity() -> dict`

Returns information about the entity under the crosshair.

### `status_effects() -> list[dict]`

Returns active effects on the player.

### `armor() -> dict`

Returns equipped armor grouped by slot.

### `offhand() -> dict`

Returns the item in the offhand slot.

---

## 5. Movement and Player Control

### `look(yaw_or_dir, pitch=0)`

Look in a direction or set exact yaw/pitch.

Named directions:

- `north`
- `south`
- `east`
- `west`
- `up`
- `down`

Examples:

```python
look("north")
look(180, 0)
```

### `look_at(x, y, z)`

Turns to face a world coordinate.

### `look_at_entity(entity_type, max_distance=16)`

Turns to face the nearest matching entity type.

### `jump()`

Makes the player jump if possible.

### `sprint(on=True)`

Toggles sprinting.

### `sneak(on=True)`

Toggles sneaking.

### `move(forward, strafe=0, duration=0)`

Applies movement input.

Example:

```python
move(1.0, duration=1.5)
```

### `goto(x, z, y=None)`

Uses Peripheral's built-in navigation helper.

### `walk(blocks, direction=None)`

Walk a number of blocks forward or in a named direction.

Example:

```python
walk(10)
walk(5, "north")
```

### `respawn()`

Triggers respawn when dead.

---

## 6. Items, Inventory, and Interaction

### `slot(n)`

Select hotbar slot `1` through `9`.

### `equip(item) -> bool`

Finds an item by name and equips it.

Example:

```python
if equip("bread"):
    use_item()
```

### `mine(x, y, z)`

Start breaking a block.

### `place(x, y, z, face='up', item=None)`

Place or interact with a block face.

Valid faces:

- `up`
- `down`
- `north`
- `south`
- `east`
- `west`

### `use_item()`

Right-click the held item.

Useful for:

- eating
- drinking
- bows
- buckets
- tools

### `attack()`

Left-click attack.

### `drop(all=False)`

Drop the held item or the whole stack.

---

## 7. Chat and Messaging

### `say(message)`

Send a normal chat message.

If the string starts with `/`, it runs as a command.

Examples:

```python
say("Hello world")
say("/time set day")
```

### `msg(message)`

Show a local-only message to yourself.

Useful for:

- status updates
- debugging
- notifications

### `rich_msg(json_component)`

Show a rich Minecraft text component.

Useful for:

- clickable links
- hover text
- copy-to-clipboard text
- clickable commands

Example:

```python
rich_msg({
    "text": "Open site",
    "color": "aqua",
    "underlined": True,
    "clickEvent": {"action": "open_url", "url": "https://example.com"}
})
```

### `chat_log(n=20) -> list[dict]`

Returns recent chat/system messages.

### `wait_for_chat(pattern, timeout=30) -> dict | None`

Blocks until a matching message appears.

Example:

```python
m = wait_for_chat("joined the game", timeout=60)
if m:
    say("Welcome!")
```

---

## 8. GUI API

Peripheral can open a custom in-game GUI that uses Minecraft's native rendering.

Press `H` to show or hide it.

### `open_gui(layout)`

Opens a GUI screen.

Basic layout fields:

- `title`
- `w`
- `h`
- `widgets`

Example:

```python
open_gui({
    "title": "Stats",
    "w": 240,
    "h": 140,
    "widgets": [
        {"id": "hp", "type": "bar", "x": 10, "y": 10, "value": 1.0, "w": 200, "color": "red"},
        {"id": "label", "type": "label", "x": 10, "y": 30, "text": "Ready"},
        {"id": "heal", "type": "button", "x": 10, "y": 60, "label": "Heal", "w": 80},
    ]
})
```

### `gui_update(widget_id, **props)`

Update a widget while the GUI is open.

Example:

```python
gui_update("label", text="Updated", color="green")
```

### `gui_close()`

Close the GUI.

### `gui_state() -> dict`

Returns:

```python
{
    "open": True,
    "event": "button_id_or_none",
    "inputs": {"widget_id": "value"}
}
```

### `gui_is_open() -> bool`

Whether the GUI is currently visible.

### `gui_poll() -> str | None`

Returns the latest event ID.

### `gui_input(widget_id) -> str`

Returns the current text or state value of a widget.

### Widget types

| Type | Required props | Common optional props |
|---|---|---|
| `label` | `text` | `color`, `shadow`, `align`, `w` |
| `bar` | `value` | `w`, `h`, `color`, `bg_color` |
| `flat_button` | `label` | `w`, `h`, `color`, `bg_color` |
| `button` | `label` | `w`, `h` |
| `input` | none | `w`, `h`, `placeholder`, `value` |
| `checkbox` | `label` | `checked`, `color` |
| `slider` | `value`, `min`, `max` | `step`, `w`, `h`, `color`, `show_value` |
| `list` | `items` | `w`, `h`, `row_h`, `selected`, `bg_color` |
| `textbox` | `lines` | `w`, `h`, `color`, `line_h`, `bg_color` |
| `tabs` | `items` | `w`, `selected` |
| `dropdown` | `options` | `w`, `h`, `selected` |
| `item` | `item` | `count`, `label` |
| `section` | `text` | `w`, `color` |
| `divider` | none | `w`, `color` |
| `rect` | none | `w`, `h`, `color`, `border` |

### Tabs

Any widget can be attached to a tab:

```python
{"type": "label", "tab": "settings", "id": "lbl", "x": 0, "y": 0, "text": "Settings"}
```

### Dropdowns

Dropdown clicks fire events like:

- `dropdown_id:0`
- `dropdown_id:1`

### Item widgets

Item widgets render a 16x16 Minecraft item icon.

Example:

```python
{"type": "item", "id": "icon", "x": 0, "y": 0, "item": "diamond_sword", "label": "Sword"}
```

---

## 9. HUD API

HUD elements render directly on the game screen while playing.

### `hud_set(elements)`

Set the whole HUD layout.

### `hud_update(element_id, **props)`

Update a single HUD element.

### `hud_clear()`

Remove this script's HUD.

### HUD element types

- `label`
- `bar`
- `rect`
- `divider`
- `item`
- `graph` — rolling line/area chart. Props: `w`, `h`, `color`, `min`, `max`
  (omit `min`/`max` to autoscale). Push a new sample with
  `hud_update(id, push=value)`, or replace the whole `values` list.
- `compass` — horizontal yaw strip with N/E/S/W ticks. Props: `w`, `h`, `color`.

```python
hud_set([
    {"type": "graph", "id": "tps", "anchor": "top_left", "x": 8, "y": 8,
     "w": 120, "h": 30, "values": [], "color": "green", "min": 0, "max": 20},
    {"type": "compass", "id": "cmp", "anchor": "top_left", "x": 8, "y": 44,
     "w": 120, "h": 12, "color": "white"},
])
hud_update("tps", push=19.8)   # append a sample to the graph
```

### Edit mode

Open the Peripheral panel (**G**) and toggle HUD edit to reposition elements by
dragging with the mouse. Positions snap to a 4px grid and are saved to
`config/peripheral/hud_layouts.json` (`{script: {elementId: {x, y, anchor}}}`).
Stored overrides are re-applied on top of whatever a script passes to
`hud_set`, so hand-placed elements keep their position across runs.

### Anchors

- `top_left`
- `top_right`
- `bottom_left`
- `bottom_right`
- `center`

### Example

```python
hud_set([
    {"type": "rect", "id": "bg", "anchor": "top_left", "x": 4, "y": 4, "w": 120, "h": 40,
     "color": "#CC111111", "border": "#FF2A2A2A"},
    {"type": "label", "id": "title", "anchor": "top_left", "x": 10, "y": 10,
     "text": "Peripheral", "color": "green", "shadow": True},
    {"type": "bar", "id": "hp", "anchor": "top_left", "x": 10, "y": 24, "w": 100, "h": 5,
     "value": 0.75, "color": "red", "bg_color": "#FF1A1A1A"},
])
```

---

## 10. World Overlay API

Peripheral supports both simple world overlays and smooth render-thread-driven live overlays.

### Basic overlays

#### `draw_esp(x, y, z, w=1, h=2, d=1, color='green', id='')`

Draw a wireframe box.

#### `draw_world_text(x, y, z, text, color='white', id='', face_pitch=False)`

Draw a world-space text label.

`face_pitch=True` makes the label tilt vertically toward the player.

#### `draw_line(x1, y1, z1, x2, y2, z2, color='green', id='')`

Draw a line between two points.

#### `draw_waypoint(x, y, z, label='', color='green', id='', face_pitch=False)`

Draw a vertical waypoint stem and optional label.

#### `draw_player_line(x, y, z, color='green', id='')`

Draw a line from the player's live eye position to a world point.

#### `overlay_clear()`

Clear the current script's world overlays.

---

### Live overlays

Live overlays are resolved on the render thread and stay locked to movement smoothly.

#### `draw_live_esp(filter='hostile', color='auto', w=0.8, h=1.9, d=0.8, show_lines=True, show_labels=True, radius=64, id='', see_through=True, face_pitch=False)`

Draw live ESP boxes for nearby entities.

Options:

- `filter`
  - `hostile`
  - `passive`
  - `players`
  - `all`

- `color`
  - named color
  - hex color
  - `auto`

- `show_lines`
  - eye-to-entity line

- `show_labels`
  - show entity label

- `see_through`
  - render labels through blocks

- `face_pitch`
  - tilt labels vertically toward the player

Example:

```python
draw_live_esp(filter="hostile", color="auto", show_lines=True, show_labels=True, id="hostiles")
```

#### `draw_live_tags(...)`

High-level smooth name-card system for entities.

Signature:

```python
draw_live_tags(
    filter="all",
    radius=48,
    id="live_tags",
    require_custom_name=False,
    include_players=False,
    lines=None,
    show_bar=True,
    show_box=False,
    label_offset_x=0,
    label_offset_y=0.55,
    label_offset_z=0,
    bar_offset_x=0,
    bar_offset_y=0.2,
    bar_offset_z=0,
    side_offset=0,
    bar_side_offset=None,
    scale=1.0,
    bar_scale=1.0,
    line_spacing=1.0,
    bar_width_chars=10,
    bar_color="auto",
    bar_show_text=False,
    bar_text="",
    see_through=True,
    face_pitch=False,
)
```

Supported text tokens:

- `{name}`
- `{distance:.1f}`
- `{health:.0f}`
- `{max_health:.0f}`
- `{health_percent:.0f}`
- `{type}`
- `{custom_name}`

Special color values:

- `auto`
- `auto_health`

Example:

```python
draw_live_tags(
    filter="hostile",
    lines=[
        {"text": "{name}", "color": "auto_health"},
        {"text": "{health:.0f}hp  {distance:.1f} m", "color": "grey"},
    ],
    show_bar=True,
    see_through=False,
    face_pitch=True,
    id="hostile_tags",
)
```

#### `draw_billboard(x, y, z, lines, id='', see_through=True, face_pitch=False)`

Draw a multi-line camera-facing panel.

Example:

```python
draw_billboard(
    x, y + 2, z,
    [
        {"text": "Zombie", "color": "red"},
        {"text": "18hp  3.2m", "color": "grey"},
    ],
    face_pitch=True,
)
```

#### `draw_health_bar(x, y, z, value, max=20, color='green', show_text=True, id='', see_through=True, face_pitch=False)`

Draw a camera-facing progress bar.

---

### Overlay colors

Named colors supported by the parser include:

- `red`
- `green`
- `yellow`
- `blue`
- `white`
- `grey`
- `gray`
- `dark_grey`
- `dark_gray`
- `orange`
- `purple`
- `pink`
- `dark_red`
- `dark_green`

Hex formats:

- `#RRGGBB`
- `#AARRGGBB`

---

## 11. File and JSON Helpers

Peripheral includes simple public file helpers inside `mc.py`.

### `scripts_dir() -> str`

Returns the scripts folder path.

### `script_path(rel_path='') -> str`

Resolve a path under the scripts folder.

### `json_load_file(rel_path, default=None)`

Load a JSON file under the scripts folder.

### `json_save_file(rel_path, data, indent=2) -> str`

Save JSON under the scripts folder.

Scripts run with normal OS file access. Run only scripts you trust; Peripheral does not sandbox Python.

Example:

```python
json_save_file("data/example.json", {"hello": "world"})
data = json_load_file("data/example.json", {})
```

---

## 12. Waypoint Persistence

Peripheral includes waypoint helpers built on top of the JSON file helpers.

### `waypoints_load(rel_path='data/waypoints.json') -> list[dict]`

Load waypoints from disk.

### `waypoints_save(waypoints, rel_path='data/waypoints.json') -> list[dict]`

Save a full waypoint list.

### `waypoints_upsert(waypoint, rel_path='data/waypoints.json', key='label') -> list[dict]`

Insert or replace a waypoint using a key like `label`.

### `waypoints_remove(key_value, rel_path='data/waypoints.json', key='label') -> list[dict]`

Remove matching waypoints.

### Recommended file

```text
data/waypoints.json
```

### Example

```python
waypoints_upsert({
    "label": "Home",
    "x": 100,
    "y": 64,
    "z": -200,
    "color": "green",
    "see_through": True,
})
```

### Bundled waypoint tool

The bundled waypoint tool:

- opens a GUI with `H`
- saves and loads waypoints from `data/waypoints.json`
- supports saved color and through-block settings
- uses `face_pitch=True` for its waypoint labels

---

## 13. Optional Baritone Integration

If Baritone is installed, Peripheral can use it for advanced pathfinding and automation.

### `baritone(command)`

Send a raw Baritone command.

### `mine_auto(block, count=0)`

Find and mine a block type.

### `baritone_goto(x, y, z)`

Pathfind to exact coordinates.

### `baritone_stop()`

Stop the current Baritone action.

---

## 14. Embedded HTTP Server

Peripheral includes a small Python-side `Server` class for scripts that want to expose their own local HTTP endpoints.

Example:

```python
server = Server(8080)

@server.route("/status")
def status(req):
    x, y, z = pos()
    return {"x": x, "y": y, "z": z, "health": health()}

@server.route("/say")
def say_handler(req):
    say(req.get("message", ""))
    return {"ok": True}

server.start()
```

This is useful for:

- dashboards
- local tooling
- browser control panels
- integrations with other local apps

---

## 15. Event Callbacks (Push API)

Instead of polling game state in a `while` loop, you can register handlers and
let Peripheral push events to your script over a persistent local socket
(default port 25587). If the socket is unavailable, `mc.py` falls back to HTTP
for regular calls, and the `@on_*` decorators raise a clear error asking you to
update the mod.

All callbacks run on a single background dispatcher thread, so your handler code
is never run concurrently with itself.

```python
from mc import *

@on_chat(r'^!home')          # regex optional; handler gets the message dict
def go_home(m):
    say('/home')

@on_gui('start_btn')         # widget id optional (omit = every widget)
def clicked(e):
    msg('clicked ' + e['id'])

@on_tick(hz=5)               # 0-20 Hz; handler gets a compact state snapshot
def tick(s):                 # {position, health, food, yaw, pitch, dimension}
    hud_update('hp', text=f"HP {s['health']:.0f}")

@on_death                    # handler takes no arguments
def died():
    msg('you died!')

@on_health                   # handler signature: fn(health, previous)
def hurt(hp, prev):
    if hp < prev:
        msg(f'took {prev - hp:.0f} damage')

every(30, lambda: say('still here'))   # repeating timer on the dispatcher thread

run()   # block the main thread so callbacks keep firing; Ctrl-C / Stop exits
```

Prefer `@on_gui` over calling `gui_input()` inside a handler — a `gui_input()`
call polls `/gui` and consumes the next queued event.

---

## 16. Themes

The whole Peripheral UI is themed. Choose a theme from the picker in the Scripts
tab header; the choice persists to config. Built-in themes:

- `terminal` — the default dark look.
- `vanilla` — classic Minecraft panels and buttons (bevels, shadowed text).

Custom themes are JSON files in `config/peripheral/themes/<name>.json` that map
role names (`bg`, `panel`, `accent`, `text`, `button`, …) to `"#AARRGGBB"`
colors. They are loaded at startup and appear in the picker by filename.

A script can theme just its own GUI by adding a top-level `theme` to the layout:

```python
open_gui({'title': 'Vanilla look', 'theme': 'vanilla', 'w': 200, 'h': 120,
          'widgets': [...]})
```

---

## 17. Examples

### Low-health auto-eat

```python
from mc import *

while True:
    if health() < 6:
        if equip("bread"):
            use_item()
            wait(2)
    wait(1)
```

### Simple local HUD

```python
from mc import *

hud_set([
    {"type": "label", "id": "title", "anchor": "top_left", "x": 8, "y": 8,
     "text": "Peripheral", "color": "green", "shadow": True},
])
```

### Hostile entity markers

```python
from mc import *
import time

draw_live_tags(
    filter="hostile",
    lines=[
        {"text": "{name}", "color": "auto_health"},
        {"text": "{health:.0f}hp  {distance:.1f} m", "color": "grey"},
    ],
    show_bar=True,
    see_through=False,
    face_pitch=True,
    id="hostile_tags",
)

try:
    while True:
        time.sleep(60)
finally:
    overlay_clear()
```

### Saved waypoint

```python
from mc import *

x, y, z = pos()
waypoints_upsert({
    "label": "Current Position",
    "x": x,
    "y": y,
    "z": z,
    "color": "cyan",
    "see_through": True,
})
msg("Saved waypoint.")
```

### GUI button

```python
from mc import *

open_gui({
    "title": "Example",
    "w": 180,
    "h": 90,
    "widgets": [
        {"id": "btn", "type": "button", "x": 10, "y": 10, "label": "Say Hi", "w": 80},
    ]
})

while True:
    if gui_poll() == "btn":
        say("Hello!")
    wait(0.1)
```

---

## 18. Troubleshooting

### "File write blocked" or `os.mkdir() blocked`

Peripheral has a script file-access permission system.

File saving uses normal Python permissions. The legacy File Access monkeypatch has been removed; only run trusted scripts.

### Overlays do not change after editing only the Python script

Some overlay behavior depends on Java-side renderer code.

If the underlying mod code changed, restart with the updated build loaded.

### Labels are smooth but not pitched toward the player

Enable:

```python
face_pitch=True
```

on the relevant overlay call.

### Script loops are too heavy

Avoid calling expensive functions too often.

Good pattern:

```python
while True:
    s = state()
    # use s several times
    wait(0.25)
```

### GUI events seem inconsistent

`gui_state()` and related polling APIs consume queued events one at a time.

Read once per loop and structure your GUI logic around that.

---

## Quick Reference

### Start every script with

```python
from mc import *
```

### Main categories

- state reads
- movement
- interaction
- chat
- GUI
- HUD
- overlays
- persistence

### Useful first functions

- `state()`
- `pos()`
- `health()`
- `inventory()`
- `nearby()`
- `say()`
- `msg()`
- `open_gui()`
- `hud_set()`
- `draw_live_tags()`
- `waypoints_upsert()`

