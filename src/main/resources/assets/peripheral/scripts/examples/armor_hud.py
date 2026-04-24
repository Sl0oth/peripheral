from mc import *

# ── Armor HUD ─────────────────────────────────────────────────────────────────
# Adds a persistent armor overlay to the top-right of your screen.
# Shows all 4 armor pieces (head → boots) as item icons with a
# durability bar under each one. Bar turns yellow at 25%, red below.
#
# Runs in the background — the overlay stays on screen while you play.
# Stop the script to remove it.

SLOTS = [
    'armor_head',
    'armor_chest',
    'armor_legs',
    'armor_feet',
]

SLOT_W = 22   # bg width
SLOT_H = 26   # bg height: 3 pad + 16 icon + 3 gap + 4 bar
GAP    = 3    # gap between slots
PAD_R  = 4    # pixels from right screen edge
PAD_T  = 4    # pixels from top screen edge
BAR_W  = 16
BAR_H  = 4

def dur_color(pct):
    if pct > 60: return 'green'
    if pct > 25: return 'yellow'
    return 'red'

elements = []
for i, slot_type in enumerate(SLOTS):
    oy = PAD_T + i * (SLOT_H + GAP)
    elements.append({'type': 'rect', 'id': f'{slot_type}_bg',
                     'x': PAD_R, 'y': oy, 'w': SLOT_W, 'h': SLOT_H,
                     'anchor': 'top_right',
                     'color': '#CC111111', 'border': '#FF2A2A2A'})
    elements.append({'type': 'item', 'id': f'{slot_type}_icon',
                     'x': PAD_R + 3, 'y': oy + 3,
                     'anchor': 'top_right',
                     'item_id': ''})
    elements.append({'type': 'bar', 'id': f'{slot_type}_bar',
                     'x': PAD_R + 3, 'y': oy + 22,
                     'anchor': 'top_right',
                     'w': BAR_W, 'h': BAR_H,
                     'value': 0.0, 'color': 'grey', 'bg_color': '#FF1A1A1A'})

hud_set(elements)
msg('[ArmorHUD] Running. Stop the script to remove the overlay.')

while True:
    try:
        inv   = inventory()
        armor = {item['slot_type']: item
                 for item in inv
                 if item.get('slot_type', '').startswith('armor_')}
        for slot_type in SLOTS:
            piece = armor.get(slot_type)
            if not piece or piece.get('item') == 'empty':
                hud_update(f'{slot_type}_icon', item_id='')
                hud_update(f'{slot_type}_bar',  value=0.0, color='grey')
            else:
                pct   = piece.get('durability_pct', 100)
                color = dur_color(pct)
                hud_update(f'{slot_type}_icon', item_id=piece['item'])
                hud_update(f'{slot_type}_bar',  value=pct / 100.0, color=color)
    except Exception as e:
        print(f'[ArmorHUD] Error: {e}')
    wait(0.5)
