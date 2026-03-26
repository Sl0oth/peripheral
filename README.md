# Peripheral

**Script Minecraft with Python.**

Write `.py` scripts that read live game data, control your player, build custom GUIs, and render always-on HUD overlays — all without touching Java. Drop a script in a folder, hit run, and watch it go.

---

## What it does

Peripheral runs a lightweight HTTP server inside Minecraft (`localhost:25585`) and injects a helper module called `mc` into every script. Scripts run in the background as you play — reading your health, sending chat messages, drawing on your screen, or navigating with Baritone.

No extra dependencies. No config files to hand-edit. Everything is managed through a clean in-game GUI (press **G**).

---

## Quick example

```python
from mc import *

# Warn you when health drops low
while True:
    if health() < 6:
        msg("§cLow health! Eat something.")
    wait(2)
```

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
