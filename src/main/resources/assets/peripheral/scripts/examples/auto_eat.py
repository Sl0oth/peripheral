from mc import *

# ── Auto Eat ─────────────────────────────────────────────────
# Automatically eats food when your hunger drops below EAT_BELOW.
# Runs in the background indefinitely — start it and forget it.
#
# Setup: make sure you have food in your inventory.
# The script checks every second and eats the first food it finds
# from the FOODS list. Edit that list to match what you carry.

FOODS     = ['bread', 'cooked_beef', 'cooked_porkchop', 'cooked_chicken',
             'apple', 'golden_apple', 'carrot', 'baked_potato']
EAT_BELOW = 16  # hunger level (out of 20) to trigger eating

msg(f'[AutoEat] Running. Will eat when hunger < {EAT_BELOW}/20')

while True:
    hunger = state().get('hunger', 20)
    if hunger < EAT_BELOW:
        for food in FOODS:
            if equip(food):
                use_item()
                wait(2)   # eating animation takes ~1.6s
                break
    wait(1)
