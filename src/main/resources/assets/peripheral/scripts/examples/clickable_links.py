from mc import *

# ── Clickable Links in Chat ───────────────────────────────────────────────────
# Reference script — runs once and sends four example messages to chat,
# each demonstrating a different kind of interactive text via rich_msg().
#
# What it demonstrates:
#   1. Clickable URL   — opens a website in your browser
#   2. Run command     — clicking executes a chat command
#   3. Suggest command — clicking pre-fills text in your chat bar
#   4. Copy to clipboard — clicking copies your current coordinates
#
# Use this as a reference when building scripts that send rich chat messages.

msg('§a--- Clickable Link Examples ---')

# ── 1. Clickable URL
rich_msg([
    {"text": "Click here -> ", "color": "gray"},
    {
        "text": "open-meteo.com",
        "color": "aqua",
        "underlined": True,
        "clickEvent": {"action": "open_url",  "url": "https://open-meteo.com"},
        "hoverEvent": {"action": "show_text", "contents": "Open in browser"}
    }
])

# ── 2. Clickable command
rich_msg([
    {"text": "Click to run a command -> ", "color": "gray"},
    {
        "text": "[Show my coords]",
        "color": "green",
        "bold": True,
        "clickEvent": {"action": "run_command",  "command": "/tell @s Hello!"},
        "hoverEvent": {"action": "show_text", "contents": "Runs /tell @s Hello!"}
    }
])

# ── 3. Suggest command
rich_msg([
    {"text": "Click to pre-fill a command -> ", "color": "gray"},
    {
        "text": "[Type something]",
        "color": "yellow",
        "clickEvent": {"action": "suggest_command", "command": "/say "},
        "hoverEvent": {"action": "show_text",        "contents": "Fills /say into chat bar"}
    }
])

# ── 4. Copy to clipboard
coords = pos()
coord_str = f'{coords[0]:.0f} {coords[1]:.0f} {coords[2]:.0f}'
rich_msg([
    {"text": "Click to copy your coords -> ", "color": "gray"},
    {
        "text": f"[{coord_str}]",
        "color": "aqua",
        "clickEvent": {"action": "copy_to_clipboard", "value": coord_str},
        "hoverEvent": {"action": "show_text", "contents": "Copy to clipboard"}
    }
])

msg('§a--- Done. Click any message above! ---')
