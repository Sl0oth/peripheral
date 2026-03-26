# Peripheral

**Script Minecraft with Python.**

Write `.py` scripts that read live game data, control your player, build custom GUIs, and render always-on HUD overlays — all without touching Java. Drop a script in a folder, hit run, and watch it go.

---

## What it does

Peripheral runs a lightweight HTTP server inside Minecraft (`localhost:25585`) and injects a helper module called `mc` into every script. Scripts run in the background as you play — reading your health, sending chat messages, drawing on your screen, or navigating with Baritone.

No extra dependencies. No config files to hand-edit. Everything is managed through a clean in-game GUI (press **G**).

---

## Features

- **Python scripting** — scripts live in `config/peripheral/scripts/`, edit them in any text editor or in-game
- **Player control** — `say()` · `look()` · `use_item()` · `equip()` · `jump()` · `sprint()` · `sneak()` · `move()` · `click_block()` · `attack()` · `drop_item()`
- **Game data** — `state()` · `pos()` · `health()` · `inventory()` · `chat_log()`
- **HUD overlays** — persistent labels, bars, rects, and item sprites anchored to any screen corner
- **Custom in-game screens** — `open_gui()` builds real Minecraft screens from Python (press **H** to show/hide)
- **AI script builder** — built-in chat interface (Claude / OpenAI) that writes scripts directly to the file
- **Navigation** — `goto(x, z)` · `baritone()` · `baritone_stop()` (requires Baritone)
- **HTTP API** — every endpoint is also available externally on `localhost:25585`

---

## Code examples

```python
from mc import *

# Warn you when health drops low
while True:
    if health() < 6:
        msg("§cLow health! Eat something.")
    wait(2)
```

```python
from mc import *

# Always-on armor HUD — item icons + durability bars on the right side of the screen
hud_set([
    {'type': 'item', 'id': 'head_icon', 'anchor': 'top_right', 'x': 7, 'y': 7,  'item_id': ''},
    {'type': 'bar',  'id': 'head_bar',  'anchor': 'top_right', 'x': 7, 'y': 23, 'w': 16, 'h': 4, 'value': 0.0},
    # ... repeat for chest, legs, feet
])

while True:
    inv = inventory()
    helmet = next((i for i in inv if i.get('slot_type') == 'armor_head'), None)
    if helmet and helmet.get('item') != 'empty':
        hud_update('head_icon', item_id=helmet['item'])
        hud_update('head_bar', value=helmet.get('durability_pct', 100) / 100.0)
    wait(0.5)
```

---

## Included example scripts

| Script | What it does |
|---|---|
| `hello_world.py` | Shows position, health, nearby entities — verify everything works |
| `armor_hud.py` | Vertical item-icon + durability HUD on the right side of the screen |
| `auto_eat.py` | Automatically eats food when hunger drops |
| `auto_mine.py` | Uses Baritone to mine a target ore automatically |
| `welcome_bot.py` | Greets players when they join |
| `follow_on_command.py` | Follows a player when they say "follow me" in chat |
| `chat_bot.py` | Responds to `.commands` in chat with live game data |
| `custom_gui.py` | Full interactive GUI example — buttons, inputs, live updates |
| `api_server.py` | Starts a local HTTP server so external apps can read game state |
| `openai_chat.py` | Players whisper questions to a configured AI API, it answers in chat |
| `server_status.py` | Pings Minecraft servers and shows player counts and MOTD |
| `weather_display.py` | Fetches real-world weather and displays it in-game |
| `web_dashboard.py` | Browser dashboard — view game state from any tab on your local network |
| `clickable_links.py` | Shows how to send rich messages with clickable URLs and hover text |

---

## Getting started

1. Install the mod (Fabric, 1.21.11)
2. Launch Minecraft — example scripts are written to `config/peripheral/scripts/` automatically
3. Press **G** to open the Peripheral panel
4. Hit **Run** next to any example script
5. To write your own: open the **Build** tab, type a script name, and describe what you want

---

## Requirements

- **Minecraft** 1.21.11
- **Fabric Loader** 0.18.4+
- **Fabric API**
- **Baritone** *(optional)* — required only for navigation functions

---

## Client-side only

Peripheral runs entirely on the client. No server installation needed. Works on singleplayer, multiplayer, and Realms.

---

## Disclaimer

Provided as-is with no warranty. The author is not liable for loss of items or game progress, save file corruption, server bans, or any consequences from scripts you run. You are solely responsible for any scripts you create or execute. Full terms in [TERMS.md](TERMS.md).
