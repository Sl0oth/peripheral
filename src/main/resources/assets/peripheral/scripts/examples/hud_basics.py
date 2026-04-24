from mc import *

# ── HUD Basics ────────────────────────────────────────────────────────────────
# Reference script — demonstrates all 5 HUD widget types with live data.
# Shows a panel in the top-left with health/hunger bars, a divider,
# and the item currently held in your main hand.
#
# Read through this script before building your own HUD overlay.
# It covers every widget type, all anchors, and the color system.
# For a real-world example, see armor_hud.py.
# Runs in the background. Stop the script to remove the overlay.
#
# WIDGET TYPES:
#   rect      background panel, optional border
#   label     text, with optional shadow
#   bar       progress bar, 0.0 to 1.0
#   divider   horizontal line
#   item      Minecraft item sprite (16x16), set item_id='' to hide
#
# ANCHORS (x/y are pixel offsets FROM that corner):
#   top_left      top_right
#   bottom_left   bottom_right   center
#
# COLORS: 'red' 'green' 'yellow' 'blue' 'white' 'grey' 'orange' 'purple' 'pink'
#         '#RRGGBB' or '#AARRGGBB'  (AA = alpha: 00=transparent, FF=solid)
#
# hud_set(elements)          — set the full layout (list of dicts)
# hud_update(id, **props)    — change one widget's properties live
# hud_clear()                — remove everything

hud_set([

    # ── rect: background panel ───────────────────────────────────────────────
    # x, y = offset from anchor corner. w, h = pixel size.
    # 'border' is optional — omit it for no outline.
    {'type': 'rect',    'id': 'panel',
     'anchor': 'top_left', 'x': 4, 'y': 4, 'w': 130, 'h': 80,
     'color': '#CC111111', 'border': '#FF2A2A2A'},

    # ── label: text ──────────────────────────────────────────────────────────
    # 'shadow': True adds a drop shadow
    {'type': 'label',   'id': 'title',
     'anchor': 'top_left', 'x': 8, 'y': 8,
     'text': 'HUD Basics', 'color': 'green', 'shadow': True},

    # ── bar: progress bars ───────────────────────────────────────────────────
    # 'value' goes from 0.0 (empty) to 1.0 (full)
    # 'bg_color' is the empty portion behind the bar
    {'type': 'label',   'id': 'hp_lbl',
     'anchor': 'top_left', 'x': 8, 'y': 24, 'text': 'HP', 'color': 'grey'},
    {'type': 'bar',     'id': 'hp_bar',
     'anchor': 'top_left', 'x': 26, 'y': 26, 'w': 96, 'h': 5,
     'value': 1.0, 'color': 'red', 'bg_color': '#FF1A1A1A'},

    {'type': 'label',   'id': 'hg_lbl',
     'anchor': 'top_left', 'x': 8, 'y': 36, 'text': 'HG', 'color': 'grey'},
    {'type': 'bar',     'id': 'hg_bar',
     'anchor': 'top_left', 'x': 26, 'y': 38, 'w': 96, 'h': 5,
     'value': 1.0, 'color': 'yellow', 'bg_color': '#FF1A1A1A'},

    # ── divider: horizontal line ─────────────────────────────────────────────
    {'type': 'divider', 'id': 'div',
     'anchor': 'top_left', 'x': 8, 'y': 52, 'w': 114, 'color': '#FF333333'},

    # ── item: Minecraft item sprite ──────────────────────────────────────────
    # Always 16x16 pixels. Use the full namespaced ID: 'minecraft:diamond_sword'
    # Set item_id='' to render nothing (hide the slot).
    {'type': 'item',    'id': 'held_icon',
     'anchor': 'top_left', 'x': 8, 'y': 58, 'item_id': ''},

    {'type': 'label',   'id': 'held_lbl',
     'anchor': 'top_left', 'x': 28, 'y': 62, 'text': 'empty', 'color': 'grey'},
])

msg('[HUDBasics] Running. Stop the script to remove the overlay.')

# ── Update loop ──────────────────────────────────────────────────────────────
# hud_update() changes individual widgets without redrawing everything.
# Only update what changed — more efficient than calling hud_set() each tick.

while True:
    try:
        s  = state()
        hp = s.get('health', 20)
        mh = s.get('max_health', 20) or 20
        hg = s.get('hunger', 20)

        hud_update('hp_bar', value=hp / mh,
                   color='red' if hp > 10 else 'yellow' if hp > 6 else 'red')
        hud_update('hg_bar', value=hg / 20)

        # Show the item currently held in the main hand
        inv      = inventory()
        mainhand = next((i for i in inv if i.get('slot_type') == 'mainhand'), None)
        if mainhand and mainhand.get('item') != 'empty':
            hud_update('held_icon', item_id=mainhand['item'])
            name = mainhand['item'].replace('minecraft:', '').replace('_', ' ')
            hud_update('held_lbl', text=name, color='white')
        else:
            hud_update('held_icon', item_id='')
            hud_update('held_lbl', text='empty', color='grey')

    except Exception as e:
        print(f'[HUDBasics] Error: {e}')
    wait(0.5)
