# Changelog

## [Unreleased]

- Now targets **Minecraft 26.2** (Fabric Loader 0.19.3+, Fabric API 0.157.0+26.2). The screens use the published PaneLib library instead of a private copy, so PaneLib fixes reach Peripheral directly. **PaneLib is now a separate required download**: install PaneLib 1.0.0-beta.3 or newer alongside Peripheral.
- A script's output now follows the newest line while you're at the bottom. Scroll up to read, and a **Jump to latest** bar brings you back. This replaces the Follow latest switch.
- Scripts is now the first tab when you press G. It combines the old Scripts list and the separate Workspace screen into one view: the library on the left, and the picked script on the right with Run/Stop, Edit and its live output.
- You can now set a script's auto-run (off, singleplayer, multiplayer, any world) and its hotkey slot (1–5) from the Scripts tab. Hotkeys could only be set in the config file before. Keys for the slots are still bound in Options › Controls.
- New makes a script or a folder in the open folder; a new folder opens straight into Rename. Rename and Delete moved into a More menu, so they're no longer on every row.
- Renaming a script or folder now keeps its auto-run and hotkey settings.
- Delete and file-access prompts are PaneLib's native dialogs: keyboard focus starts on the safe choice (Cancel or Deny), Escape or clicking outside cancels, and the narrator reads them.
- Rename opens with just the name selected (not `.py`), so typing replaces it. It uses a normal PaneLib text field, so copy, paste, undo and IME input all work there.
- The tab remembers your open folder and picked script between visits.
- **Build** is rearranged. The header names the script, with a **Script** menu (open another, clear chat) and **Run**. The chat shows messages as cards, with the message box right under them and a **Copy** button on each reply. Code the AI writes shows as "Wrote 12 lines to the script". The code sits beside the chat with line numbers, and you can select and copy it. **Edit** opens the editor, and **Fix errors** sends the last run's output to the AI. Small windows switch between Chat and Code with tabs.
- **Build with AI** in a script's More menu opens Build on that script.
- Sending a message with no script open starts a new script instead of reusing `my_script.py`.
- Removed the Build **Save** button. It wrote the file back to itself and did nothing.
- **Log** is rearranged. **All**, **Scripts** and **Agent** filter it by where each line came from, and **Copy** copies the lines shown. It's a PaneLib text view now: drag to select any text (not just whole lines), it stays on the newest line while you're at the bottom, and errors stay red. Links ask before opening, like chat links. Before, they opened straight away.
- The external agent has its own card under the log, with its status, step and task, a task box with **Send**, and **Stop**. It also says which server it talks to, with a **Change** button that opens Settings.
- The "Agent · idle" line under the title is gone. While the agent is working, a pill in the header shows its status, and clicking it opens the agent's log.
- Script output no longer replaces the agent's last action on the HUD overlay. Only the agent's own steps show there.
- **Settings** is split into sections down the side: **AI** (API key and model), **Agent** (the external agent's server), **MCP** and **Appearance**. In small windows the sections are chips along the top.
- Each section with fields has its own **Save**, which lights up only when something changed and says "Unsaved changes" until you save. Enter in a field saves its section. Before, one Save covered every field.
- MCP is a switch, and the theme is picked from a list instead of stepping through **Next theme**. Both save as soon as you change them.
- **Add a key** on Build opens the AI section, and **Change** on the Log's agent card opens the Agent section.
- New **HUD** tab. It lists every script drawing on the HUD, with how many elements each has and whether you moved them. **Clear** removes one script's elements, **Reset positions** puts them back where the script places them, and **Move HUD** opens the editor. Edit HUD and Clear HUD left the Scripts tab.
- Script HUDs are now drawn and placed by PaneLib's HUD system, and moving them uses PaneLib's HUD editor. It draws an outline round each element, snaps to a 4 px grid, and lets arrow keys nudge the one you picked. A moved element now keeps to the nearest corner, edge or centre, so it stays put when the window changes size. Script coordinates still mean GUI pixels, and old saved positions still load.
- The agent's status line in the top-left is a PaneLib HUD too, and you can move it.
- New **Macros** tab: record what you do and play it back exactly.
  - Press **Record** (or **F7** in game), walk, look, click and use items, then stop.
  - **Play** runs it once and **Loop** repeats it until you press Stop or **F8**. F8 loops your newest macro from anywhere.
  - Recordings keep your position and where you look every tick, not just key presses. Playback steers back onto the path if anything pushes you, walks back to the start before each loop, and doesn't drift. In testing, three loops ended within 0.0002 blocks of each other.
  - Play where it was recorded, or from where you stand (shifted by whole blocks).
  - More has Rename, Trim, Record more on the end, Make a script and Delete.
  - A small status line shows while recording or playing.
- Scripts can drive any screen like a player: `screen()`, `click_button()`, `click_slot()`, `click()`, `press_key()`, `type_text()`, `scroll()` and more. They go through Minecraft's own mouse and keyboard handling, so other mods see normal input.
- Packet tools for scripts, for testing how a server copes with your traffic.
  - `packet.log()` turns on a packet log and `packet.recent()` reads it.
  - `packet.cancel(name)` and `packet.allow(name)` stop and restart a packet type, sent or received.
  - `packet.hold()` keeps what you send until `packet.release()` sends or drops it.
  - `packet.send(data)` sends a packet from its bytes. The log keeps each sent packet's bytes, so you can copy one, change it and send it.
  - Cancelling, holding and sending work in singleplayer, and on servers that install **Peripheral Testing** (a small plugin for Paper and Spigot, or a mod for Fabric servers). The log works everywhere.
  - Keep-alive packets always go through, a status line shows whenever anything is changed, and a script's changes end when it stops.
- Peripheral's key bindings now have readable names in Options › Controls.
- The panel's hand-drawn buttons now have names for the narrator and for scripts.

## [1.3.1] — 2026-10-17 (prepared, unpublished)

- Added an optional authenticated local MCP server, off by default, with saved enable/disable, persistent connection key and Copy connection in Settings. Includes game-state/player-control/navigation and installed-script Run/Stop tools.
- Rebuilt the control panel, script workspace, editor, Build picker, dialogs and script windows with the bundled PaneLib interface. Improved compact/fullscreen layouts and sharpness.
- Removed the offline Store. Existing installed scripts and ownership data remain.
- Added Save/Discard/Keep editing when leaving an edited script. Build waits for explicit Send, preserves rejected drafts and snapshots existing nonempty source before generation.
- Improved script cleanup/ownership and actionable failure feedback. Log Go preserves its draft if delivery fails.
- Updated the in-game AI builder to use the bundled current API guide instead of a stale duplicate.
- Fixed JSON/waypoint files ending in a literal backslash-n, which prevented loading saved data.
- Made GUI value/open checks non-consuming so they cannot steal queued clicks; kept existing event-polling behavior.
- Added a local terminal example and read-only Trail HUD; corrected Control Center tabs/dropdown and both reference examples' held-item reads. Fixed `block_at()` to return the documented namespaced block ID.
- Added GUI event/output recipes and `PERIPHERAL_API_CURRENT.md`, refreshed each launch while preserving the older starter reference and user-edited examples.
- **External API change:** HTTP/TCP access now requires a session capability. Bundled scripts receive theirs automatically; external tools must follow `LOCAL_API.md`. Browser-origin access is rejected. Provider keys are no longer injected into scripts or exposed in API config. Python itself remains trusted native code, not sandboxed.
- This artifact targets **Minecraft 26.1.2 / Fabric / Java 25**, tested with Loader 0.19.1 and Fabric API 0.145.4+26.1.2. Python 3 is required for scripts; Mod Menu and Baritone are optional. No separate PaneLib download is needed.

Survival wood harvesting is not added: a public `mine()` probe did not complete the log break. Paid AI generation, native narration, other operating systems and multiplayer are not verified by this release's local acceptance runs.

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
