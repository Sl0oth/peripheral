from mc import *

# ── Health Monitor ────────────────────────────────────────────────────────────
# Passive background watcher — alerts you when things go wrong.
# Leave this running while you play. It checks every 2 seconds
# and sends a colored chat message when it detects a problem.
#
# Pair with auto_eat.py for complete hands-off survival coverage.
#
# What it watches:
#   Low health       warns in red when HP falls below LOW_HEALTH
#   Low hunger       warns in orange when hunger falls below LOW_HUNGER
#   Creeper nearby   warns immediately when one is within CREEPER_DIST blocks
#   Night arriving   single alert when the day/night cycle flips
#   Death            tells you your death coordinates so you can go back

LOW_HEALTH   = 6      # hearts (out of 20)
LOW_HUNGER   = 6      # hunger points (out of 20)
CREEPER_DIST = 10.0   # blocks

prev_health    = 20
was_day        = True
was_dead       = False
hunger_alerted = False

msg('[Monitor] Running. Will alert on low health, hunger, creepers, and night.')

while True:
    try:
        s = state()

        # ── Death ────────────────────────────────────────────────────────────
        dead = s.get('is_dead', False)
        if dead and not was_dead:
            p = s.get('position', {})
            x, y, z = int(p.get('x',0)), int(p.get('y',0)), int(p.get('z',0))
            msg(f'\u00a74[Monitor] You died! Last position: {x}, {y}, {z}')
        was_dead = dead

        if dead:
            prev_health = 20
            hunger_alerted = False
            was_day = True
            wait(1)
            continue

        hp     = s.get('health', 20)
        hunger = s.get('hunger', 20)
        is_day = s.get('is_day', True)

        # ── Low health ───────────────────────────────────────────────────────
        if hp <= LOW_HEALTH and hp < prev_health - 1:
            color = '\u00a74' if hp <= 3 else '\u00a7c'
            msg(f'{color}[Monitor] Low health! {int(hp)}/20 — eat something!')
        prev_health = hp

        # ── Low hunger ───────────────────────────────────────────────────────
        if hunger <= LOW_HUNGER and not hunger_alerted:
            msg(f'\u00a76[Monitor] Hungry! {hunger}/20')
            hunger_alerted = True
        elif hunger > LOW_HUNGER + 2:
            hunger_alerted = False

        # ── Creeper nearby ───────────────────────────────────────────────────
        for e in s.get('nearby_entities', []):
            if 'creeper' in e.get('type', '') and e.get('distance', 999) < CREEPER_DIST:
                msg(f'\u00a74[Monitor] CREEPER {e["distance"]:.0f}m away — RUN!')
                break

        # ── Night arriving ───────────────────────────────────────────────────
        if was_day and not is_day:
            mobs = [e for e in s.get('nearby_entities', []) if e.get('hostile')]
            msg(f'\u00a78[Monitor] Night. {len(mobs)} hostile mob{"s" if len(mobs) != 1 else ""} nearby.')
        was_day = is_day

    except Exception as e:
        print(f'[Monitor] Error: {e}')
    wait(2)
