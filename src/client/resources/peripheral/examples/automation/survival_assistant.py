from mc import *
import time

# ── Survival Assistant ──────────────────────────────────────────────────────
# A background helper built entirely from push events (no polling loop):
#
#   @on_health – plays a hurt sound and flashes the HUD when you take damage
#   @on_tick   – auto-eats when your food drops below a threshold
#   @on_death  – toasts and auto-respawns
#
# Put some food in your inventory and leave it running.

FOODS = ['cooked_beef', 'cooked_porkchop', 'cooked_chicken', 'cooked_mutton',
         'cooked_cod', 'bread', 'baked_potato', 'carrot', 'apple', 'golden_apple']
EAT_BELOW = 16      # food level (out of 20) that triggers eating
_last_eat = 0.0

hud_set([
    {'type': 'label', 'id': 'assist', 'anchor': 'bottom_left', 'x': 6, 'y': 6,
     'text': 'Survival assistant on', 'color': 'green', 'shadow': True},
])


@on_health
def _(hp, prev):
    if hp < prev:
        play_sound('minecraft:entity.player.hurt')
        hud_update('assist', text=f'-{prev - hp:.0f} HP  ({hp:.0f} left)', color='red')
        if hp <= 6:
            notify('Low health!', f'{hp:.0f} HP remaining')


@on_tick(hz=2)
def _(s):
    global _last_eat
    if s.get('food', 20) < EAT_BELOW and time.time() - _last_eat > 3:
        for food in FOODS:
            if equip(food):
                use_item()
                wait(2)                 # eating animation ~1.6s
                _last_eat = time.time()
                hud_update('assist', text=f'ate {food}', color='yellow')
                break


@on_death
def _():
    notify('You died', 'Auto-respawning...')
    msg('§cYou died.§r Respawning...')
    wait(2)
    respawn()


# Reset the HUD banner back to idle a few seconds after any alert
every(15, lambda: hud_update('assist', text='Survival assistant on', color='green'))

msg('§aSurvival assistant running.§r Auto-eat, damage alerts, auto-respawn.')
run()
