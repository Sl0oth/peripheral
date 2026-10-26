"""Read-only travel HUD. Start in a world; G -> Scripts -> Stop removes it.

The reference is where this run started, not a saved home or navigation target.
"""
from math import hypot
from mc import state, hud_set, hud_update, hud_clear, wait

s = state()
if "position" not in s:
    raise SystemExit("Join a world before starting the Trail HUD.")
home = s["position"]
home_dimension = s.get("dimension")
hud_set([
    {"type": "rect", "id": "panel", "x": 6, "y": 6, "w": 174, "h": 80,
     "color": "#DC18242E", "border": "#83CFFF"},
    {"type": "label", "id": "title", "x": 12, "y": 12, "text": "TRAIL / start of this run", "color": "#83CFFF"},
    {"type": "label", "id": "position", "x": 12, "y": 25, "text": "..."},
    {"type": "label", "id": "distance", "x": 12, "y": 37, "text": "..."},
    {"type": "label", "id": "clock", "x": 12, "y": 49, "text": "..."},
    {"type": "label", "id": "vitals", "x": 12, "y": 61, "text": "..."},
    {"type": "bar", "id": "hp", "x": 12, "y": 74, "w": 76, "h": 4, "color": "#83CFFF"},
    {"type": "bar", "id": "food", "x": 96, "y": 74, "w": 76, "h": 4, "color": "orange"},
])
print("Trail HUD started. Stop from Scripts to remove it.", flush=True)
try:
    while True:
        s = state()
        if "position" not in s or s.get("is_dead"):
            break
        p = s["position"]
        hud_update("position", text=f"XYZ {p['x']:.0f} {p['y']:.0f} {p['z']:.0f}")
        same_dimension = s.get("dimension") == home_dimension
        distance = hypot(p["x"] - home["x"], p["z"] - home["z"])
        hud_update("distance", text=f"From start: {distance:.0f} blocks" if same_dimension else "Start is in another dimension")
        hour = (int(s["time_of_day"]) // 1000 + 6) % 24
        hud_update("clock", text=f"{hour:02d}:00 / {'day' if s['is_day'] else 'night'}")
        hud_update("vitals", text=f"HP {s['health']:.0f}/{s['max_health']:.0f}   Food {s['food']}/20")
        hud_update("hp", value=s["health"] / max(1, s["max_health"]))
        hud_update("food", value=s["food"] / 20)
        wait(0.5)
finally:
    hud_clear()
