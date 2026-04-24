from mc import *

# ── Auto Mine ────────────────────────────────────────────────
# Uses Baritone to automatically find and mine a target ore type.
# Baritone handles all pathfinding — it will dig, explore caves,
# and collect the ore. The script reports progress every 5 seconds.
#
# Setup: set TARGET to the ore you want (e.g. 'diamond_ore'),
# and COUNT to how many to collect (0 = mine forever).
# Requires: Baritone mod (modrinth.com/mod/baritone)

TARGET = 'diamond_ore'   # what to mine
COUNT  = 10              # how many to collect (0 = mine forever)

msg(f'[AutoMine] Starting — targeting {COUNT}x {TARGET}')
msg('[AutoMine] Baritone is now in control. Stop script to cancel.')

mine_auto(TARGET, COUNT)

# Poll every 5s and report progress
ore_name = TARGET.replace('_ore', '').replace('minecraft:', '')
while True:
    wait(5)
    inv = inventory()
    count = sum(i['count'] for i in inv if ore_name in i['item'])
    msg(f'[AutoMine] Inventory: {count} {ore_name}')
    if COUNT > 0 and count >= COUNT:
        baritone_stop()
        msg(f'[AutoMine] Done! Collected {count}.')
        break
