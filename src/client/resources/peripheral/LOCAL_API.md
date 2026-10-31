# Local API and trusted scripts

Run Python 3 scripts from Peripheral's Scripts screen. Its bundled `mc` module automatically authenticates to the local HTTP/TCP services using a private, per-execution capability. You do not need to paste any token for normal Run/Stop use.

Python scripts are trusted native programs with your normal OS file/process access. Peripheral does not sandbox Python. The legacy File Access monkeypatch has been removed. Existing saved permission flags are preserved but do not grant/restrict OS access. Run only code you trust.

Your provider key remains in Settings for the AI builder. It is not returned by `/config` or injected into every Python script. Scripts making external AI/API calls must arrange their own credentials explicitly. Review them before running or sharing.

For a deliberately connected external local tool, read `config/peripheral/local-api.token` inside the running game directory. Use `Authorization: Bearer <token>` over HTTP. TCP uses newline-delimited JSON with a `token` field on every request/subscription. The token changes when Minecraft restarts. Keep it private; never put it in URLs, screenshots, repositories, logs or browser pages. Provider keys cannot authenticate the local API. On macOS and Linux only your user can read it. On Windows it sits in your game folder with your other private game files.

Both listeners bind to 127.0.0.1. Browser Origin requests, unexpected Host headers, non-JSON mutations and unsafe script paths/symlinks are rejected. This does not isolate malicious programs already running as your user; trusted Python can access local files and processes.

Stopping a script revokes its per-run capability, terminates its process and observed descendants, and clears its owned movement/navigation/GUI/HUD resources. Other scripts' current controls and HUD elements stay intact. Disconnect/shutdown stops scripts. Do not automatically repeat an action after an ambiguous timeout; it may already have executed.

Press **G** and use the **Scripts** tab to run and stop scripts. Logs show stdout, errors and exit status; the bounded file log sits beside each script. Missing Python or an unavailable port is shown as an actionable error. If API startup reports a port conflict, close the other Peripheral client and restart this one; never connect your scripts to an unintended game instance.

## Optional MCP connection

In **G → Settings → MCP**, turn on **Allow MCP connections**. It is off on new installs. Changes take effect immediately and are saved across Minecraft launches. Minecraft must remain running; join a world before using player tools. The existing **External agent URL** is for sending tasks to a separate agent and is unrelated to this connection.

Choose **Copy connection** and add the copied server entry to a local AI application's MCP configuration. The client must support **Streamable HTTP with custom Authorization headers**. The JSON contains `mcpServers.peripheral.url` (`http://127.0.0.1:25585/mcp`) and an `Authorization: Bearer ...` header. Some applications use their own settings format: copy the URL and header into the matching fields. No Python installation is needed for direct MCP tools; Python is required for running Python scripts. A cloud-only connector cannot reach your computer's localhost endpoint without a separate bridge; Peripheral does not provide or require a public tunnel.

The endpoint implements MCP protocol versions 2025-03-26, 2025-06-18 and 2025-11-25, with initialization, ping, tool discovery and tool calls. It returns JSON responses over HTTP; it does not offer SSE notifications, stdio, OAuth or the legacy SSE transport. MCP clients supporting newer versions can negotiate one of these versions. It uses the same game functions as the Python API, not a second implementation of player controls.

Tools: `get_state`, `get_inventory`, `get_target`, `get_block`, `get_navigation`, `navigate_to`, `player_action`, `stop_controls`, `list_scripts`, `run_script`, `stop_script`. Navigation uses Peripheral's built-in pathfinder. `player_action` exposes a limited set of player controls; it does not execute arbitrary code or chat commands. `run_script` starts existing trusted Python scripts; MCP does not create or edit script files. World and chat text should be treated as untrusted data by the connected AI.

Movement, sprint and sneak stay active until changed, `stop_controls` is called, MCP is turned off, or the world is left. Use local Python scripts for precise timed actions rather than asking an AI to send every movement tick. Starting a block break is an initial hit, not verified continuous survival mining. All MCP clients share one MCP control owner; use one controlling client at a time. `stop_controls` releases MCP-owned controls only; use `stop_script` or the Scripts screen for Python processes, which remain independent of the MCP toggle.

The separate MCP key is stored privately in `config/peripheral/mcp.token` and stays the same across launches. Keep copied connection details private. Turning MCP off immediately rejects requests but preserves the key so re-enabling does not require reconnecting. To replace the key, turn MCP off, delete `mcp.token`, then enable MCP and copy the new details. The ordinary `local-api.token` remains session-only and cannot authenticate MCP; the MCP key cannot authenticate ordinary HTTP/TCP API routes. Neither key is an AI provider key.
