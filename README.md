# Peripheral

**Script Minecraft with Python.**

Write `.py` scripts that read live game data, control your player, build custom GUIs, and render always-on HUD overlays — all without touching Java. Drop a script in a folder, hit run, and watch it go.

---

## What it does

Peripheral runs a lightweight HTTP server inside Minecraft (`localhost:25585`) and injects a helper module called `mc` into every script. Scripts run in the background as you play — reading your health, sending chat messages, drawing on your screen, or navigating with the built-in pathfinder.

Python 3 must be installed and available as `python3` (or Python 3 as `python`). No config files to hand-edit. Everything is managed through a clean in-game GUI (press **G**).

---

## Features

- **Python scripting** — scripts live in `config/peripheral/scripts/`, edit them in any text editor or in-game
- **Player control** — `say()` · `look()` · `use_item()` · `equip()` · `jump()` · `sprint()` · `sneak()` · `move()` · `place()` · `attack()` · `drop()`
- **Game data** — `state()` · `pos()` · `health()` · `inventory()` · `chat_log()`
- **HUD overlays** — persistent labels, bars, rects, and item sprites anchored to any screen corner
- **Custom in-game screens** — `open_gui()` builds real Minecraft screens from Python (press **H** to show/hide)
- **AI script builder** — built-in chat interface (Claude / OpenAI) that writes scripts directly to the file
- **Navigation** — `goto(x, z)` uses the built-in pathfinder; optional `baritone()` / `baritone_stop()` require Baritone
- **Optional MCP server** — off by default; turn it on in Settings › MCP, copy the local authenticated connection, and use Minecraft tools from a compatible AI app. The setting and connection key persist across launches. See [connection details](src/client/resources/peripheral/LOCAL_API.md#optional-mcp-connection).
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
| `basics/welcome.py` | One-shot tour of game state, rich chat, notifications and sounds |
| `gui/terminal.py` | Local command/log panel: help, position, health, inventory and echo |
| `hud/trail.py` | Read-only travel HUD with distance from this run's starting point |
| `hud/dashboard.py` | HUD elements, health/food bars and graph |
| `gui/control_center.py` | Interactive GUI widget showcase |
| `chat/commands.py` | Chat commands and waypoints |
| `overlay/radar.py` | Entity markers and labels |
| `automation/survival_assistant.py` | Survival event/callback example |


---

## Getting started

1. Install this build for Fabric / Minecraft 26.2 (PaneLib, which draws the screens, is included)
2. Install Python 3 if needed, then launch Minecraft — examples and `PERIPHERAL_API_CURRENT.md` are written to `config/peripheral/scripts/` automatically
3. Press **G** to open the Peripheral panel
4. Hit **Run** next to any example script
5. Edit an example or create a `.py` file to make your own tool. **No AI key is needed to run or write scripts.** The optional **Build** tab generates scripts using your own Anthropic/OpenAI API key and provider billing.

---

## Requirements

- **Minecraft** 26.2 for this checkout
- **Python 3** available to Minecraft
- **Fabric Loader** 0.19.3+
- **Fabric API**
- **Baritone** *(optional)* — only for functions prefixed `baritone_`, `baritone()` and `mine_auto()`

---

## Client-side only

Peripheral runs entirely on the client. No server installation needed. Works on singleplayer, multiplayer, and Realms.

---

## Disclaimer

Provided as-is with no warranty. The author is not liable for loss of items or game progress, save file corruption, server bans, or any consequences from scripts you run. You are solely responsible for any scripts you create or execute. Full terms in [TERMS.md](TERMS.md).

---

*Built with AI coding assistance, directed by Sl0oth.*

## PaneLib interface

Press **G** to open the control panel: **Scripts, Macros, Log, Build, HUD, Settings**. **Scripts** opens first. It shows your script library with folder search next to the picked script's Run/Stop, Edit, auto-run, hotkey slot and live output. In a compact window, picking a script shows its controls and output; **Library** goes back to the list. **Log** shows every line from your scripts and the external agent, filtered by source, with the agent's status, a task box and Stop underneath. **Macros** records what you do (F7) and plays it back exactly, once or on a loop (F8). **HUD** lists what scripts draw on screen and opens PaneLib's HUD editor to move it. **Settings** has sections for AI, Agent, MCP and Appearance. The editor, session picker, dialogs, HUD editor and script-created windows also use PaneLib. Script-authored coordinates, explicit themes and native Minecraft item/HUD rendering are preserved.

Build sends only when you press **Send**. Existing nonempty scripts are backed up under `config/peripheral/build_backups` before generation; leaving an edited document offers Save, Discard or Keep editing. This embeds an isolated PaneLib compatibility snapshot for 26.1.2; the separate PaneLib project is unchanged.

Scripts are trusted local Python programs with your normal computer access. Peripheral does not sandbox Python. The old File Access monkeypatch has been removed. Provider API keys are no longer injected into every script or returned by the local API.

Bundled scripts receive a private local API capability automatically. External tools must explicitly read `config/peripheral/local-api.token` in the running game directory and send `Authorization: Bearer <token>` over HTTP, or a `token` field in each TCP JSON frame. The token changes every session; never publish it, put it in URLs, or reuse a provider key. Browser-origin requests are rejected. The file is created private on POSIX systems. This boundary protects the local API from unauthorized requests; it cannot isolate malicious same-user programs.

Integration findings and remaining library work are tracked in `audit/PANELIB-INTEGRATION-LEDGER.txt`; whole-interface evidence is in `audit/2026-10-17-whole-ui/`. `./gradlew runClient -PpilotTest` runs the opt-in acceptance client in its reserved disposable `run-pilot-audit` directory, never a normal world. Normal builds exclude this harness.
