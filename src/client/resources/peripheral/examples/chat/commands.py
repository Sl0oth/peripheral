from mc import *
import re

# ── Chat Commands ───────────────────────────────────────────────────────────
# A command bot driven by pushed chat events. Type any of these in chat:
#
#   !pos                 – report your coordinates
#   !wp set <name>       – save a waypoint here (persists to data/waypoints.json)
#   !wp go <name>        – walk to a saved waypoint (needs Baritone)
#   !wp list             – list saved waypoints and mark them in the world
#   !find <block>        – find the nearest matching blocks and mark them
#   !clear               – clear all world markers
#
# Everything runs off @on_chat — no polling. `run()` keeps it alive.

WP_FILE = 'data/waypoints.json'


def _report_pos():
    x, y, z = pos()
    msg(f'§a{x:.0f} §7/ §a{y:.0f} §7/ §a{z:.0f}')


@on_chat(r'^!pos\b')
def _(m):
    _report_pos()


@on_chat(r'^!wp\b')
def _(m):
    parts = m['text'].split()
    sub = parts[1].lower() if len(parts) > 1 else 'list'
    name = ' '.join(parts[2:]).strip()

    if sub == 'set' and name:
        x, y, z = pos()
        waypoints_upsert({'label': name, 'x': round(x), 'y': round(y), 'z': round(z),
                          'color': 'aqua'}, WP_FILE)
        msg(f'§aSaved waypoint §f{name}§a at {x:.0f} {y:.0f} {z:.0f}')
        notify('Waypoint saved', name)

    elif sub == 'go' and name:
        wps = waypoints_load(WP_FILE)
        wp = next((w for w in wps if w.get('label', '').lower() == name.lower()), None)
        if not wp:
            msg(f'§cNo waypoint named §f{name}')
            return
        msg(f'§7Walking to §f{name}§7...')
        goto(wp['x'], wp['z'])

    else:  # list
        wps = waypoints_load(WP_FILE)
        overlay_clear()
        if not wps:
            msg('§7No waypoints saved yet. Use §f!wp set <name>')
            return
        for i, w in enumerate(wps):
            draw_waypoint(w['x'], w.get('y', 64), w['z'], label=w.get('label', ''),
                          color=w.get('color', 'green'), id=f'wp_{i}', face_pitch=True)
        msg(f'§aMarked §f{len(wps)}§a waypoint(s) in the world.')


@on_chat(r'^!find\b')
def _(m):
    parts = m['text'].split(maxsplit=1)
    if len(parts) < 2:
        msg('§7Usage: §f!find <block>')
        return
    block = parts[1].strip()
    hits = find_blocks(block, radius=48)
    overlay_clear()
    if not hits:
        msg(f'§cNo §f{block}§c within 48 blocks.')
        return
    for i, b in enumerate(hits[:20]):
        draw_esp(b['x'], b['y'], b['z'], color='yellow', id=f'find_{i}')
    nearest = hits[0]
    msg(f'§aFound §f{len(hits)}§a — nearest at {nearest["x"]} {nearest["y"]} {nearest["z"]}')
    play_sound('minecraft:entity.experience_orb.pickup')


@on_chat(r'^!clear\b')
def _(m):
    overlay_clear()
    msg('§7Cleared world markers.')


msg('§aChat commands ready.§r Try §f!pos§r, §f!wp set home§r, §f!find diamond_ore§r.')
run()
