from mc import *

# ── Entity Radar ────────────────────────────────────────────────────────────
# Shows off the world-overlay system. Two "live" overlays are resolved on the
# render thread every frame, so they track moving entities at full FPS with no
# polling from Python:
#
#   draw_live_esp   – wireframe boxes on hostiles, tinted by health
#   draw_live_tags  – floating name + distance cards on every creature
#
# A tick handler drives a small HUD threat readout on top of that.

draw_live_esp(
    filter='hostile', color='auto',
    show_lines=True, show_labels=True, radius=64, id='hostiles',
)

draw_live_tags(
    filter='all', radius=48, id='tags',
    lines=[
        {'text': '{name}',        'color': 'white'},
        {'text': '{distance:.0f}m', 'color': 'grey'},
    ],
    show_bar=True,
)

hud_set([
    {'type': 'label', 'id': 'threat', 'anchor': 'top_right', 'x': 6, 'y': 6,
     'text': 'Radar on', 'color': 'green', 'shadow': True},
])

@on_tick(hz=2)
def _(s):
    hostiles = [e for e in nearby() if e.get('hostile')]
    if hostiles:
        nearest = min(hostiles, key=lambda e: e['distance'])
        hud_update('threat',
                   text=f"! {len(hostiles)} hostile  {nearest['distance']:.0f}m",
                   color='red')
    else:
        hud_update('threat', text='clear', color='green')

msg('§aEntity radar active.§r ESP + nametags render at full FPS. Stop to clear.')
run()
