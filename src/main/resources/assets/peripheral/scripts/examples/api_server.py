from mc import *

# ── Custom API Server ────────────────────────────────────────
# Starts an HTTP server on port 8080 so external apps —
# a phone, browser tab, another script, or any tool that
# speaks HTTP — can read your game state and send commands.
#
# Runs in the background until you stop it.
#
# Endpoints while this script is running:
#   GET  http://localhost:8080/status    → pos + health + hunger
#   GET  http://localhost:8080/inventory → your inventory
#   POST http://localhost:8080/say       → {"message": "hello"} → chat
#   POST http://localhost:8080/goto      → {"x":100, "z":-200}  → walk there
#
# This is a minimal example. Add more @server.route() endpoints
# to expose whatever data or actions you need.

server = Server(8080)

@server.route('/status')
def status(req):
    x, y, z = pos()
    s = state()
    return {'x': x, 'y': y, 'z': z,
            'health': s['health'], 'hunger': s.get('hunger', 20),
            'dimension': s.get('dimension', '?')}

@server.route('/inventory')
def inv(req):
    return {'slots': inventory()}

@server.route('/say')
def say_handler(req):
    say(req.get('message', ''))
    return {'ok': True}

@server.route('/goto')
def goto_handler(req):
    result = goto(float(req['x']), float(req['z']))
    return {'result': result}

msg('[ApiServer] Listening on http://localhost:8080')
server.serve_forever()
