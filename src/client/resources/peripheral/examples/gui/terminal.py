"""Local Minecraft command panel, not an operating-system shell.

Run from Scripts. Click Run for help/pos/health/inventory/echo TEXT.
H or Escape hides the panel; H shows it again. Stop exits and clears it.
"""
from collections import Counter
from textwrap import wrap
from mc import open_gui, gui_state, gui_update, gui_close, state, inventory, wait

lines = ["help | pos | health | inventory | echo TEXT", "Local commands only. Click Run."]
open_gui({"title": "Local terminal", "w": 220, "h": 158, "widgets": [
    {"type": "label", "id": "hint", "x": 6, "y": 5,
     "text": "Minecraft tools / no shell", "color": "#83CFFF"},
    {"type": "textbox", "id": "output", "x": 6, "y": 20,
     "w": 196, "h": 78, "line_h": 10, "lines": lines},
    {"type": "input", "id": "command", "x": 6, "y": 104,
     "w": 196, "h": 16, "placeholder": "help"},
    *[{"type": "button", "id": name.lower(), "label": name,
       "x": 6 + i * 66, "y": 126, "w": 64, "h": 14}
      for i, name in enumerate(["Run", "Clear", "Stop"])],
]})


def execute(command):
    name, _, argument = command.partition(" ")
    if name == "help":
        return ["pos: coordinates", "health: health and food",
                "inventory: item totals", "echo TEXT: print a line"]
    if name == "echo":
        return [argument]
    if name in ("pos", "health"):
        s = state()
        if "position" not in s:
            return ["Join a world first."]
        if name == "pos":
            p = s["position"]
            return [f"XYZ {p['x']:.1f} {p['y']:.1f} {p['z']:.1f}"]
        return [f"Health {s['health']:.0f}/{s['max_health']:.0f}; food {s['food']}/20"]
    if name == "inventory":
        totals = Counter()
        for item in inventory():
            # Mainhand is also a hotbar slot; count each actual slot only once.
            if item.get("slot", -1) in range(36) and item["item"] != "empty":
                totals[item["item"].removeprefix("minecraft:")] += item["count"]
        return [f"{name}: {count}" for name, count in sorted(totals.items())] or ["Inventory empty."]
    return ["Unknown command. Try help."]


try:
    while True:
        snapshot = gui_state()  # One read: event and input from the same snapshot.
        event = snapshot.get("event")
        if event == "stop":
            print("Terminal stopped.", flush=True)
            break
        if event == "clear":
            lines.clear()
        elif event == "run":
            command = snapshot["inputs"].get("command", "").strip()
            if command:
                lines.append("> " + command)
                try:
                    lines.extend(execute(command))
                except Exception as error:
                    lines.append("Request failed: " + str(error))
                gui_update("command", value="")
        if event in ("run", "clear"):
            lines = [part for line in lines for part in (wrap(line, 36) or [""])][-80:]
            gui_update("output", lines=lines, scroll=max(0, len(lines) - 7))
        wait(0.1)
finally:
    gui_close()
