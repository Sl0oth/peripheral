from mc import *

# ── Welcome ─────────────────────────────────────────────────────────────────
# A one-shot tour of reading game state. Run it once: it prints a report to
# the Log tab, drops a clickable summary in your chat, pops a toast, and
# plays a sound. Start here, then explore the other example folders.

s = state()
if 'health' not in s:
    # Not in a world yet (main menu or still loading a save)
    msg('§ePeripheral is loaded.§r Join a world, then run this again.')
    print('Peripheral is loaded, but you are not in a world yet.')
    raise SystemExit

x, y, z = pos()
biome = s.get('biome', 'minecraft:?').split(':')[-1]
light = s.get('light', {})

# ── 1. Read state and print a report to the Log tab ─────────────────────────
print("=== Peripheral is live ===")
print(f"Position : {x:.0f}, {y:.0f}, {z:.0f}")
print(f"Biome    : {biome}")
print(f"Health   : {s.get('health', 0):.0f}/{s.get('max_health', 20):.0f}    Food: {s.get('food', '?')}")
print(f"Weather  : {s.get('weather', '?')}    Time: {s.get('time_of_day', '?')} ticks")
print(f"Light    : sky {light.get('sky', '?')}  block {light.get('block', '?')}")

# ── 2. A clickable summary in chat (only you see it) ────────────────────────
coord_str = f"{x:.0f} {y:.0f} {z:.0f}"
rich_msg([
    {"text": "Peripheral ready. ", "color": "green", "bold": True},
    {"text": f"You're in {biome}. ", "color": "gray"},
    {
        "text": f"[copy {coord_str}]",
        "color": "aqua",
        "clickEvent": {"action": "copy_to_clipboard", "value": coord_str},
        "hoverEvent": {"action": "show_text", "contents": "Copy your coords"},
    },
])

# ── 3. A vanilla toast + a sound ────────────────────────────────────────────
notify("Peripheral", "Scripts are working ✓")
play_sound("minecraft:block.note_block.pling", pitch=1.5)

msg("§7Try the other examples: §fhud/ gui/ chat/ overlay/ automation/")
