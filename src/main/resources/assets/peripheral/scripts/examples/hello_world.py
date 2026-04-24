from mc import *

# ── Hello World ─────────────────────────────────────────────
# Runs once and prints your current position, health, and nearby
# entities — both to the Log tab and to your in-game chat.
# Then it looks in each cardinal direction and exits.
#
# Run this first to confirm Peripheral is set up correctly.
# If you see your coordinates in chat, everything is working.

x, y, z = pos()
print(f'Position: {x:.0f}, {y:.0f}, {z:.0f}')   # shows in Log tab
msg(f'Position: {x:.0f}, {y:.0f}, {z:.0f}')      # shows in chat
print(f'Health: {health():.0f} / 20')
msg(f'Health: {health():.0f} / 20')

entities = nearby()
if entities:
    msg(f'{len(entities)} entities nearby:')
    for e in entities[:5]:  # show up to 5
        label = 'HOSTILE' if e['hostile'] else 'passive'
        msg(f"  {label}: {e['type']} @ {e['distance']:.0f}m")
else:
    msg('No entities nearby.')

# Look in each direction
for d in ('north', 'east', 'south', 'west'):
    look(d)
    wait(0.4)

msg('Done!')
