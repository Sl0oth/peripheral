from mc import *

# ── Custom In-Game GUI ────────────────────────────────────────────────────────
# Opens a live player stats screen built entirely from Python.
# Shows health, hunger, XP, position, dimension, time, and nearby mobs.
# Press H at any time to open or close the screen.
#
# Runs in the background — the data updates every 0.5 seconds.
# The Heal / Feed / Set Day buttons run /effect and /time commands,
# so they only work in singleplayer or if you have cheats / op on a server.

open_gui({
    'title': 'Player Stats',
    'w': 260,
    'h': 185,
    'widgets': [
        {'type': 'label', 'id': 'hp_lbl',  'x': 0,   'y': 4,  'text': 'Health', 'color': 'red'},
        {'type': 'bar',   'id': 'hp_bar',  'x': 52,  'y': 6,  'w': 155, 'h': 5, 'value': 1.0, 'color': 'red'},
        {'type': 'label', 'id': 'hp_val',  'x': 212, 'y': 4,  'text': '20/20',  'color': 'white'},

        {'type': 'label', 'id': 'hg_lbl',  'x': 0,   'y': 20, 'text': 'Hunger', 'color': 'yellow'},
        {'type': 'bar',   'id': 'hg_bar',  'x': 52,  'y': 22, 'w': 155, 'h': 5, 'value': 1.0, 'color': 'yellow'},
        {'type': 'label', 'id': 'hg_val',  'x': 212, 'y': 20, 'text': '20/20',  'color': 'white'},

        {'type': 'label', 'id': 'xp_lbl',  'x': 0,   'y': 36, 'text': 'XP',     'color': 'green'},
        {'type': 'label', 'id': 'xp_val',  'x': 52,  'y': 36, 'text': 'Level 0', 'color': 'white'},

        {'type': 'divider', 'id': 'div1',  'x': 0,   'y': 50, 'w': 242},

        {'type': 'label', 'id': 'pos_lbl', 'x': 0,   'y': 58, 'text': 'Position',  'color': 'grey'},
        {'type': 'label', 'id': 'pos_val', 'x': 58,  'y': 58, 'text': '0  64  0',  'color': 'white'},

        {'type': 'label', 'id': 'dim_lbl', 'x': 0,   'y': 70, 'text': 'Dimension', 'color': 'grey'},
        {'type': 'label', 'id': 'dim_val', 'x': 58,  'y': 70, 'text': 'overworld', 'color': 'blue'},

        {'type': 'label', 'id': 'time_lbl','x': 0,   'y': 82, 'text': 'Time',      'color': 'grey'},
        {'type': 'label', 'id': 'time_val','x': 58,  'y': 82, 'text': 'Day',        'color': 'yellow'},

        {'type': 'label', 'id': 'mob_lbl', 'x': 0,   'y': 94, 'text': 'Hostiles',  'color': 'grey'},
        {'type': 'label', 'id': 'mob_val', 'x': 58,  'y': 94, 'text': '0 nearby',  'color': 'white'},

        {'type': 'divider', 'id': 'div2',  'x': 0,   'y': 108, 'w': 242},

        {'type': 'button', 'id': 'btn_heal','x': 0,   'y': 116, 'w': 76, 'h': 14, 'label': 'Heal'},
        {'type': 'button', 'id': 'btn_feed','x': 82,  'y': 116, 'w': 76, 'h': 14, 'label': 'Feed'},
        {'type': 'button', 'id': 'btn_day', 'x': 164, 'y': 116, 'w': 76, 'h': 14, 'label': 'Set Day'},

        {'type': 'label', 'id': 'status',  'x': 0,   'y': 138, 'text': 'Press H to open  |  ESC to close', 'color': 'grey'},
    ]
})

msg('[GUI] Loaded. Press H to open the screen.')

status_timer = 0

while True:
    try:
        s  = state()
        hp = float(s.get('health',     20))
        mh = float(s.get('max_health', 20)) or 20
        hg = float(s.get('hunger',     20))
        xp = int(s.get('xp_level',     0))
        p  = s.get('position', {})
        x, y, z = int(p.get('x', 0)), int(p.get('y', 64)), int(p.get('z', 0))
        dim    = s.get('dimension', 'overworld').replace('minecraft:', '')
        is_day = s.get('is_day', True)
        rain   = ' · Rain' if s.get('is_raining') else ''
        mobs   = sum(1 for e in s.get('nearby_entities', []) if e.get('hostile'))

        gui_update('hp_bar',  value=hp / mh)
        gui_update('hp_val',  text=f'{int(hp)}/{int(mh)}')
        gui_update('hg_bar',  value=hg / 20)
        gui_update('hg_val',  text=f'{int(hg)}/20')
        gui_update('xp_val',  text=f'Level {xp}')
        gui_update('pos_val', text=f'{x}  {y}  {z}')
        gui_update('dim_val', text=dim)
        gui_update('time_val',
                   text=('Day' if is_day else 'Night') + rain,
                   color='yellow' if is_day else 'blue')
        gui_update('mob_val',
                   text=f'{mobs} nearby',
                   color='red' if mobs > 0 else 'grey')

        if status_timer > 0:
            status_timer -= 1
            if status_timer == 0:
                gui_update('status', text='Press H to open  |  ESC to close', color='grey')

    except Exception as e:
        gui_update('status', text=f'Error: {e}', color='red')

    event = gui_poll()
    if event == 'btn_heal':
        say('/effect give @s regeneration 5 10')
        gui_update('status', text='Healing...', color='green')
        status_timer = 4
    elif event == 'btn_feed':
        say('/effect give @s saturation 5 10')
        gui_update('status', text='Feeding...', color='yellow')
        status_timer = 4
    elif event == 'btn_day':
        say('/time set day')
        gui_update('status', text='Set to day.', color='green')
        status_timer = 4

    wait(0.5)
