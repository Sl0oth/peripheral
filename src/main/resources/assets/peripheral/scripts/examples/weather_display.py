from mc import *

# ── Real-World Weather in Minecraft ──────────────────────────────────────────
# Fetches your real-world weather and displays it in-game.
# Shows a title card on screen, then prints condition, temperature,
# wind speed, humidity, and precipitation to chat.
#
# Runs once and exits — re-run it to refresh the data.
# Uses Open-Meteo (https://open-meteo.com) — free, no API key needed.
#
# Setup: change LATITUDE, LONGITUDE, and CITY_NAME to your location.

LATITUDE  = 40.71
LONGITUDE = -74.01
CITY_NAME = 'New York'

WEATHER_CODES = {
    0:  ('Clear sky',       'Sun'),
    1:  ('Mainly clear',    'Sun'),
    2:  ('Partly cloudy',   'Clouds'),
    3:  ('Overcast',        'Overcast'),
    45: ('Foggy',           'Fog'),
    48: ('Freezing fog',    'Fog'),
    51: ('Light drizzle',   'Drizzle'),
    53: ('Drizzle',         'Drizzle'),
    55: ('Heavy drizzle',   'Drizzle'),
    61: ('Light rain',      'Rain'),
    63: ('Rain',            'Rain'),
    65: ('Heavy rain',      'Rain'),
    71: ('Light snow',      'Snow'),
    73: ('Snow',            'Snow'),
    75: ('Heavy snow',      'Snow'),
    80: ('Rain showers',    'Showers'),
    82: ('Violent showers', 'Showers'),
    95: ('Thunderstorm',    'Storm'),
    99: ('Thunderstorm',    'Storm'),
}


def describe_wind(speed_mph):
    if speed_mph < 5:  return 'Calm'
    if speed_mph < 15: return 'Light breeze'
    if speed_mph < 25: return 'Moderate wind'
    if speed_mph < 40: return 'Strong wind'
    return 'Storm-force wind'


msg(f'§7[Weather] Fetching weather for {CITY_NAME}...')

try:
    url = (
        f'https://api.open-meteo.com/v1/forecast'
        f'?latitude={LATITUDE}&longitude={LONGITUDE}'
        f'&current=temperature_2m,apparent_temperature,wind_speed_10m,'
        f'weather_code,relative_humidity_2m,precipitation'
        f'&temperature_unit=fahrenheit'
        f'&wind_speed_unit=mph'
        f'&precipitation_unit=inch'
    )
    data = http_get(url)
    cur  = data['current']

    temp       = cur['temperature_2m']
    feels_like = cur['apparent_temperature']
    wind       = cur['wind_speed_10m']
    humidity   = cur['relative_humidity_2m']
    precip     = cur['precipitation']
    code       = cur['weather_code']

    desc, icon = WEATHER_CODES.get(code, ('Unknown', '?'))
    wind_desc  = describe_wind(wind)

    say(f'/title @s times 10 100 20')
    say(f'/title @s title {{"text":"{CITY_NAME}","color":"yellow","bold":true}}')
    say(f'/title @s subtitle {{"text":"{int(temp)}F  {desc}  Wind {int(wind)} mph","color":"white"}}')

    msg(f'§e[{icon}] §f{CITY_NAME} Weather')
    msg(f'§7Condition  §f{desc}')
    msg(f'§7Temp       §f{int(temp)}F  §7(feels {int(feels_like)}F)')
    msg(f'§7Wind       §f{int(wind)} mph  §7— {wind_desc}')
    msg(f'§7Humidity   §f{humidity}%')
    if precip > 0:
        msg(f'§7Precip     §f{precip:.2f} in')

except Exception as e:
    msg(f'§c[Weather] Error: {e}')
