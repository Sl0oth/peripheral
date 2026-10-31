# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

## What this project is

**Peripheral** is a Fabric Minecraft mod (MC 26.2) that exposes game state over a local HTTP API so Python scripts can read and control the game. It ships an in-game control panel, a scriptable overlay GUI, a HUD overlay system.

## Build & run commands

```bash
# Run Minecraft client with the mod loaded (primary dev command)
./gradlew runClient

# Compile only (check for errors without launching)
./gradlew compileClientJava

# Full build (produces a distributable JAR)
./gradlew build
```

**Java 25 is required.** The toolchain is pinned in `build.gradle` (`JavaLanguageVersion.of(25)`). On macOS use the Zulu JDK 25 distribution — the IntelliJ run configuration expects a JDK named `zulu-25`.

There are no unit tests. In-game checks are opt-in and never part of a release build:

```bash
# Real-client audits (the whole-UI one stays open after "WHOLE UI READY"; close it yourself)
JAVA_TOOL_OPTIONS="-Dperipheral.wholeUiAudit=true" ./gradlew runClient -PpilotTest
JAVA_TOOL_OPTIONS="-Dperipheral.recorderAudit=true" ./gradlew runClient -PpilotTest

# Real install: release jar, PaneLib and Fabric API as separate mods (--no-panelib, --xvfb on Linux)
python3 tools/install_check.py
```

## Architecture

### Startup sequence

`PeripheralClient.onInitializeClient()` is the single entry point. It runs in order:
1. `PeripheralConfig.load()` — reads/writes `config/peripheral.json`
2. `ScriptRunner.init()` — prepares the scripts directory, writes example scripts
3. `PeripheralHttpServer.start()` — starts `localhost:25585` with a 4-thread pool
4. Key bindings registered: **G** opens `PeripheralScreen`, **H** opens `ScriptableScreen`, keys 1–5 are script hotkeys

### The HTTP server is the seam between Java and Python

`PeripheralHttpServer` (Java) and `mc.py` (Python) are two halves of the same interface. Every `mc.py` function is a thin wrapper over an HTTP call to `localhost:25585`. When adding a new scriptable capability:
- Add the endpoint handler in `PeripheralHttpServer`
- Add the Python wrapper in `run/config/peripheral/scripts/mc.py`
- Document in both `AI_PROMPT.md` and `PERIPHERAL_API.md` (in the scripts folder)

Key endpoints: `GET /state` (game snapshot), `POST /action` (all player actions), `GET /gui` (scriptable GUI poll — **pops one event per call**), `GET /inventory`, `POST /goto`.

### Script execution model

`ScriptRunner` launches each script as an external `python3` process in a daemon thread. Scripts receive the HTTP port, scripts directory, and API credentials via environment variables (`PERIPHERAL_STATE_PORT`, `PERIPHERAL_SCRIPTS_DIR`, etc.). stdout/stderr are captured to `<script>.log` and forwarded to the in-game Log tab. The `finally` block in the reader thread is where cleanup on script exit happens — that's where `PeripheralHud.clearIfOwner` and `ScriptableScreen.clearIfOwner` are called.

### `mc.py` linter caveat

A linter periodically reverts `mc.py` to a canonical form. If you add a line to `mc.py` (e.g. adding `'_script': _SCRIPT_NAME` to a `_post` call), check after each session whether it survived. The `_SCRIPT_NAME` variable is always available from `os.environ.get('PERIPHERAL_SCRIPT_NAME', '')`.

### Rendering: PaneLib and native game overlays

Application screens extend PaneLib's `UiScreen` (`dev.pane:panelib` from Maven; players install it as a separate required mod; `-PpanelibRepo=<dir> -PpanelibVersion=<v>` builds against a local PaneLib checkout). Use `PeripheralUi` for shared fonts, colors, document scaling and raster settings. **Use PaneLib's own controls instead of hand-drawing them**: `ui.button(id, label, UiIcon…, …)` for icon buttons, `ui.menuButton(…, icon, …)` for menus, `ui.iconButton` with a label, `screen.showDialog(id, new UiDialog(…, PeripheralUi.DANGER_DIALOG))` for confirmations, `pushFocusScope` + `ui.textField` + `requestFocus(id, anchor, cursor)` for custom sheets like rename, `UiScrollFollow.WHEN_AT_END` with `scrollToEnd`/`scrollAtEnd`/`scrollOffset` for logs. If PaneLib can't do something cleanly, add it to PaneLib (with tests) rather than working around it here, and keep the addition small and easy to use. Scroll positions are not `scroll:<id>` values any more; use `scrollTo`/`scrollSnapshot`. Keep the Scripts design (`ScriptsPanel`, charcoal with lime for Run and the current item) consistent. `LineEdit` remains only in `ScriptableScreen` (script-authored inputs) and is still to be replaced by PaneLib text input.

Native Minecraft items and world HUD editing still use `GuiGraphicsExtractor` in the screen's native overlay hooks. Scriptable layouts retain Minecraft GUI coordinates through a scale transform; do not silently reinterpret author coordinates or explicit theme/color choices. Popup overlays must remain above native item sprites.

### ScriptableScreen widget system

`ScriptableScreen` is a JSON-driven GUI that Python scripts build at runtime via `open_gui(layout)`. Key design details:
- All widget state lives in `widgetMap` (a `ConcurrentHashMap<String, JsonObject>`). The render thread reads it under `synchronized(widgetOrder)`; the HTTP thread writes via `update()` which also holds that lock.
- `openDropdownId` tracks which dropdown popup is open; the popup is rendered last (after `super.extractRenderState`) so it floats above everything.
- Tabs work via a `'tab': 'tab_id'` property on any widget. `buildActiveTabs()` collects currently-selected tab IDs; `isTabVisible()` gates rendering and mouse handling.
- Each `GET /gui` / `gui_state()` / `gui_poll()` call pops one event. Use one snapshot for event and input values. `GET /gui_peek` / `gui_peek()` reads open/inputs without consuming events; `gui_input()` and `gui_is_open()` use this safe path since 2.0.0. Older helper versions consumed events, so keep existing handlers on the single-snapshot recipe.
- `clearIfOwner(scriptName)` closes the screen and wipes the layout when the owning script stops.

### PeripheralScreen tabs

`PeripheralScreen` has four PaneLib tabs (Scripts, Log, Build, Settings); Scripts is the default. The Scripts tab is `ScriptsPanel`: library, selected script (Run/Stop, Edit, auto-run, hotkey slot, More) and its output. Rename/delete/new sheets stay in `PeripheralScreen`, which the panel reaches through ordinary actions (`rename:`, `del:`, `edit:`, `new-script`, `new-folder`). The retired standalone Workspace screen is kept in `.retired/workspace-2026-10-25/`. Build uses `BuildSession` and `BuildPickerScreen`; opening a session must not send a prompt. The system prompt reads bundled `peripheral/AI_PROMPT.md`; keep the guide aligned with public APIs instead of adding a duplicate Java reference. Explicit Send snapshots existing nonempty source with `BuildDraftSafety` before dispatch. `UnsavedChangesScreen` protects editor changes when leaving.

### Config & persistence

| What | Where |
|---|---|
| Mod config (API keys, URLs) | `config/peripheral.json` via `PeripheralConfig` |
| Chat/build session history | `config/peripheral/chats/<script>.json` |
| HUD layouts | in-memory only; rebuilt each script run |
| Script auto-run settings | within `config/peripheral.json` |
