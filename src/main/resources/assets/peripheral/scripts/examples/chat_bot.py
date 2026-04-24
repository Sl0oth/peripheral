from mc import *
import re

# ── Chat Command Bot ──────────────────────────────────────────────────────────
# Watches chat for dot-commands and responds with live game data.
# Runs in the background. Responses appear only in YOUR chat (not public).
#
# Available commands (type them in chat):
#   .help     list all available commands
#   .pos      show your current coordinates
#   .health   show your health and hunger levels
#   .time     show in-game time, dimension, and weather
#   .inv      list what you're carrying (first 8 stacks)
#   .weather  fetch real-world weather for your location (no API key needed)
#   .mobs     list nearby hostile mobs sorted by distance
#
# Setup: change WEATHER_LAT / WEATHER_LON to your real-world location.
# Extend it by adding more elif branches to the handle() function.

WEATHER_LAT  = 40.71    # ← Change to your location
WEATHER_LON  = -74.01   # ← Change to your location

WEATHER_CODES = {
    0: 'Clear', 1: 'Mainly clear', 2: 'Partly cloudy', 3: 'Overcast',
    45: 'Foggy', 48: 'Foggy', 51: 'Drizzle', 53: 'Drizzle', 55: 'Drizzle',
    61: 'Rain', 63: 'Rain', 65: 'Heavy rain',
    71: 'Snow', 73: 'Snow', 75: 'Heavy snow',
    80: 'Showers', 81: 'Showers', 82: 'Heavy showers',
    95: 'Thunderstorm', 96: 'Thunderstorm', 99: 'Thunderstorm',
}


def handle(cmd, sender):
    cmd = cmd.strip().lower()

    if cmd == '.help':
        msg('Commands: .pos  .health  .time  .inv  .weather  .mobs')

    elif cmd == '.pos':
        x, y, z = pos()
        msg(f'{sender} is at {x:.0f}, {y:.0f}, {z:.0f}')

    elif cmd == '.health':
        s = state()
        h  = s['health']
        hg = s.get('hunger', 20)
        msg(f'Health {int(h)}/20  Hunger {hg}/20')

    elif cmd == '.time':
        s    = state()
        tod  = 'Day' if s.get('is_day', True) else 'Night'
        rain = '  Raining' if s.get('is_raining') else ''
        dim  = s.get('dimension', '?').replace('minecraft:', '')
        t    = s.get('time_ticks', 0)
        hour = int(((t + 6000) % 24000) / 1000)
        msg(f'{tod}  ~{hour}:00  {dim}{rain}')

    elif cmd == '.inv':
        items = {}
        for i in inventory():
            if i['item'] != 'empty':
                name = i['item'].replace('minecraft:', '')
                items[name] = items.get(name, 0) + i['count']
        if items:
            text = '  '.join(f'{v}x {k}' for k, v in list(items.items())[:8])
            msg(text[:250])
        else:
            msg('Inventory is empty.')

    elif cmd == '.weather':
        try:
            url = (
                f'https://api.open-meteo.com/v1/forecast'
                f'?latitude={WEATHER_LAT}&longitude={WEATHER_LON}'
                f'&current=temperature_2m,wind_speed_10m,weather_code'
                f'&temperature_unit=fahrenheit&wind_speed_unit=mph'
            )
            cur  = http_get(url)['current']
            temp = int(cur['temperature_2m'])
            wind = int(cur['wind_speed_10m'])
            desc = WEATHER_CODES.get(cur['weather_code'], 'Unknown')
            msg(f'IRL Weather: {temp}F  {desc}  Wind {wind} mph')
        except Exception as e:
            msg(f'Weather error: {e}')

    elif cmd == '.mobs':
        mobs = [e for e in nearby() if e.get('hostile')]
        if not mobs:
            msg('No hostile mobs nearby.')
        else:
            mobs.sort(key=lambda e: e['distance'])
            lines = [f'{e["type"].replace("minecraft:","")} ({e["distance"]:.0f}m)' for e in mobs[:6]]
            msg('Nearby mobs: ' + '  '.join(lines))


msg('[ChatBot] Running. Commands: .help .pos .health .time .inv .weather .mobs')
print('[ChatBot] Watching chat for . commands...')

while True:
    m = wait_for_chat(r'^\.\w', timeout=60)
    if not m:
        continue

    text   = m['text'].strip()
    sender = m.get('sender', '?')

    match = re.match(r'(\.\w+)', text)
    if not match:
        continue

    cmd = match.group(1).lower()
    print(f'[ChatBot] Command from {sender}: {cmd}')

    try:
        handle(cmd, sender)
    except Exception as e:
        msg(f'[ChatBot] Error handling {cmd}: {e}')
        print(f'[ChatBot] Error: {e}')
