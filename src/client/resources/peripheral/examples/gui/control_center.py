from mc import *

# ── Control Center ──────────────────────────────────────────────────────────
# A custom in-game screen that shows off the widget system: tabs, buttons,
# a slider, a checkbox, a dropdown, a scrolling list, a live bar and an item
# icon. It is fully event-driven — @on_gui fires when you interact, so there
# is no polling loop. Press H in-game to show/hide the screen.

TIMES = ['day', 'noon', 'night', 'midnight']

open_gui({
    'title': 'Control Center',
    'w': 210, 'h': 168,
    'widgets': [
        # Tab bar switches which widgets are visible (via the 'tab' property)
        {'type': 'tabs', 'id': 'tab', 'x': 8, 'y': 6, 'w': 194, 'h': 14,
         'items': [{'id': 'Actions', 'label': 'Actions'}, {'id': 'Info', 'label': 'Info'}], 'selected': 'Actions'},

        # ── Actions tab ─────────────────────────────────────────────────────
        {'type': 'section', 'id': 's1', 'tab': 'Actions', 'x': 8, 'y': 28,
         'text': 'World'},
        {'type': 'flat_button', 'id': 'set_time', 'tab': 'Actions',
         'x': 8, 'y': 40, 'w': 92, 'h': 16, 'label': 'Set time'},
        {'type': 'dropdown', 'id': 'time_pick', 'tab': 'Actions',
         'x': 108, 'y': 40, 'w': 94, 'h': 16, 'options': TIMES, 'selected': 0},
        {'type': 'flat_button', 'id': 'clear_wx', 'tab': 'Actions',
         'x': 8, 'y': 60, 'w': 92, 'h': 16, 'label': 'Clear weather'},
        {'type': 'flat_button', 'id': 'ping', 'tab': 'Actions',
         'x': 108, 'y': 60, 'w': 94, 'h': 16, 'label': 'Play sound'},

        {'type': 'section', 'id': 's2', 'tab': 'Actions', 'x': 8, 'y': 84,
         'text': 'Options'},
        {'type': 'checkbox', 'id': 'sneak_lock', 'tab': 'Actions',
         'x': 8, 'y': 98, 'text': 'Hold sneak', 'checked': False},
        {'type': 'label', 'id': 'sp_lbl', 'tab': 'Actions', 'x': 8, 'y': 118,
         'text': 'Look speed', 'color': 'grey'},
        {'type': 'slider', 'id': 'speed', 'tab': 'Actions', 'x': 8, 'y': 130,
         'w': 194, 'h': 12, 'min': 0, 'max': 100, 'value': 50},

        # ── Info tab ────────────────────────────────────────────────────────
        {'type': 'label', 'id': 'hp_lbl', 'tab': 'Info', 'x': 8, 'y': 30,
         'text': 'Health', 'color': 'grey'},
        {'type': 'bar', 'id': 'hp_bar', 'tab': 'Info', 'x': 8, 'y': 42,
         'w': 194, 'h': 6, 'value': 1.0, 'color': 'red'},
        {'type': 'item', 'id': 'held', 'tab': 'Info', 'x': 8, 'y': 56, 'item_id': ''},
        {'type': 'label', 'id': 'held_lbl', 'tab': 'Info', 'x': 30, 'y': 60,
         'text': '(nothing held)', 'color': 'white'},
        {'type': 'list', 'id': 'entlist', 'tab': 'Info', 'x': 8, 'y': 80,
         'w': 194, 'h': 68, 'items': []},
    ],
})

# ── Event handlers ──────────────────────────────────────────────────────────
@on_gui('set_time')
def _(e):
    idx = int(gui_peek()['inputs'].get('time_pick', 0) or 0)
    say(f'/time set {TIMES[idx]}')
    msg(f'§7time → {TIMES[idx]}')

@on_gui('clear_wx')
def _(e):
    say('/weather clear')

@on_gui('ping')
def _(e):
    play_sound('minecraft:block.note_block.bell', pitch=1.2)

@on_gui('sneak_lock')
def _(e):
    on = str(e.get('value', 'false')).lower() == 'true' or gui_peek()['inputs'].get('sneak_lock') == 'true'
    sneak(on)
    msg(f'§7sneak {"on" if on else "off"}')

# Refresh the Info tab from live ticks
@on_tick(hz=2)
def _(s):
    if not gui_is_open():
        return
    gui_update('hp_bar', value=s['health'] / 20.0)
    held = state().get('equipped')
    if held and held['item'] != 'empty':
        gui_update('held', item_id=held['item'])
        gui_update('held_lbl', text=held['item'].split(':')[-1])
    else:
        gui_update('held', item_id='')
        gui_update('held_lbl', text='(nothing held)')
    ents = [f"{e['type'].split(':')[-1]}  {e['distance']:.0f}m"
            for e in nearby()][:12]
    gui_update('entlist', items=ents or ['(no entities nearby)'])

msg('§aControl Center open.§r Press §fH§r to toggle it.')
run()
