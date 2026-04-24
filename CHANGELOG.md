# Changelog

## [1.1.0] – 2026-04-24

### Bug fixes
- **chat_bot.py** — regex pattern never matched incoming commands; fixed capture group and stripping of color codes before matching
- **Hotkey slots** — script slot assignments (keys 1–5) were not written to disk on change, causing them to reset after every restart
- **Navigator** — A\* pathfinder used 26/10/26-bit coordinate packing that overflowed and produced garbage paths at large world coordinates; widened to a lossless encoding
- **open_gui() race condition** — calling `open_gui()` from a script thread while the GUI was already closing could corrupt widget state; now marshalled onto the render thread with a lock
- **HTTP server starvation** — the server used a fixed single-thread executor, so a slow or blocked handler stalled every subsequent request; replaced with a virtual-thread-per-request executor
- **Block save thread storm** — `BlockTracker` called `save()` on every block-update event during mining, spawning hundreds of short-lived threads; replaced with a debounced `ScheduledExecutorService` that coalesces writes
- **Unknown HUD ids** — calling `hud_set()` with an id that was never registered created orphan elements with no owner, making them impossible to clear; unknown ids are now rejected before insertion

### New
- **Minecraft 1.21.4 support** — separate `mc/1.21.4` branch targeting Fabric API `0.119.4+1.21.4` and Mod Menu 13.0.0

---

## [1.0.0] — 2026-03-26

Initial release.

### Features
- **Python scripting** — run `.py` scripts from `config/peripheral/scripts/` that control Minecraft in real time
- **`mc` module** — injected into every script automatically; no extra imports needed
  - `state()`, `pos()`, `health()`, `inventory()` — read live game data
  - `say()`, `msg()`, `rich_msg()` — send chat messages and system messages (URLs auto-linked)
  - `look()`, `use_item()`, `equip()`, `drop_item()`, `jump()`, `sprint()`, `sneak()`, `move()` — control the player
  - `click_block()`, `attack()` — interact with the world
  - `open_gui()`, `gui_update()`, `gui_poll()`, `gui_input()`, `gui_close()` — build custom in-game screens
  - `hud_set()`, `hud_update()`, `hud_clear()` — always-on HUD overlays (labels, bars, rects, item icons)
  - `goto()`, `baritone()`, `baritone_stop()`, `nav_status()` — Baritone pathfinding integration
  - `wait()`, `print()` — timing and logging
- **HTTP API** — `localhost:25585` exposes `/state`, `/action`, `/inventory`, `/goto`, `/nav_status`, `/nav_stop`, `/nav_scan`, `/gui`, `/hud/*`, and more for external tool integration
- **In-game script manager** — browse, run, stop, and edit scripts without leaving the game (press the configured keybind)
- **Script editor** — edit `.py` files directly in-game
- **HUD overlay system** — render persistent elements on the game screen: labels, progress bars, rectangles, dividers, and Minecraft item sprites
- **Scriptable screen** — `open_gui()` creates fully interactive Minecraft screens with buttons, text fields, labels, sliders, and more; press H to toggle
- **Block tracker** — logs player-placed blocks to `config/peripheral/chuck_blocks.json`
- **Chat log** — scripts can read and react to in-game chat via `chat_log()`
- **AI script builder** — built-in Build tab with Claude/OpenAI chat that writes code directly to the script file; chat history saved per script, session picker, Fix button sends logs back to AI for error fixing
- **AI agent support** — configurable agent URL in settings for wiring up an external AI
- **Example scripts** — `hello_world`, `armor_hud`, `auto_eat`, `auto_mine`, `welcome_bot`, `follow_on_command`, `chat_bot`, `custom_gui`, `api_server`, `openai_chat`, `server_status`, `weather_display`, `web_dashboard`, `clickable_links`
- **Documentation** — `PERIPHERAL_API.md` written to the scripts folder on first launch; paste it into any AI to generate scripts
