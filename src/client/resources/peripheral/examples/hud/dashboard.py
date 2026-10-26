from mc import *

# ── Dashboard HUD ───────────────────────────────────────────────────────────
# A live on-screen dashboard that shows off every HUD element type:
# rect (panel), label, bar, divider, item, graph, and compass. It updates
# itself from pushed tick events — no polling loop. Open the panel (G) and
# use "HUD Edit" to drag any of these around; positions persist.

PANEL_W = 132

hud_set([
    # Background panel + accent divider
    {'type': 'rect',    'id': 'bg',   'anchor': 'top_left', 'x': 4,  'y': 4,
     'w': PANEL_W, 'h': 96, 'color': '#CC0D0D0D', 'border': '#FF00FFAA'},
    {'type': 'label',   'id': 'ttl',  'anchor': 'top_left', 'x': 10, 'y': 9,
     'text': 'DASHBOARD', 'color': 'green', 'shadow': True},
    {'type': 'divider', 'id': 'div',  'anchor': 'top_left', 'x': 10, 'y': 19, 'w': PANEL_W - 12},

    # Held item icon + coordinate label
    {'type': 'item',    'id': 'hand', 'anchor': 'top_left', 'x': 10, 'y': 24, 'item_id': ''},
    {'type': 'label',   'id': 'pos',  'anchor': 'top_left', 'x': 30, 'y': 28,
     'text': '...', 'color': 'white', 'shadow': True},

    # Health + food bars
    {'type': 'label',   'id': 'hplbl','anchor': 'top_left', 'x': 10, 'y': 44,
     'text': 'HP',   'color': 'grey'},
    {'type': 'bar',     'id': 'hp',   'anchor': 'top_left', 'x': 28, 'y': 45,
     'w': PANEL_W - 34, 'h': 5, 'value': 1.0, 'color': 'red', 'bg_color': '#FF1A1A1A'},
    {'type': 'label',   'id': 'fdlbl','anchor': 'top_left', 'x': 10, 'y': 53,
     'text': 'FD',   'color': 'grey'},
    {'type': 'bar',     'id': 'fd',   'anchor': 'top_left', 'x': 28, 'y': 54,
     'w': PANEL_W - 34, 'h': 5, 'value': 1.0, 'color': 'orange', 'bg_color': '#FF1A1A1A'},

    # Rolling health graph
    {'type': 'graph',   'id': 'hpg',  'anchor': 'top_left', 'x': 10, 'y': 64,
     'w': PANEL_W - 12, 'h': 20, 'color': 'green', 'min': 0, 'max': 20},

    # Compass strip
    {'type': 'compass', 'id': 'cmp',  'anchor': 'top_left', 'x': 10, 'y': 88,
     'w': PANEL_W - 12, 'h': 10, 'color': 'white'},
])

_last_item = None

@on_tick(hz=5)
def refresh(s):
    global _last_item
    p = s['position']
    hud_update('pos', text=f"{p['x']:.0f} {p['y']:.0f} {p['z']:.0f}")
    hud_update('hp', value=s['health'] / 20.0)
    hud_update('fd', value=s.get('food', 20) / 20.0)
    hud_update('hpg', push=s['health'])
    # (the compass reads the player's facing automatically — nothing to push)

    # Held-item icon: only re-read inventory when it actually changes
    held = state().get('equipped')
    item_id = held['item'] if held and held['item'] != 'empty' else ''
    if item_id != _last_item:
        hud_update('hand', item_id=item_id)
        _last_item = item_id

msg('§aDashboard HUD running.§r Stop the script to clear it.')
run()
