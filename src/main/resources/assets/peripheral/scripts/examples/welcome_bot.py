from mc import *
import re

# ── Welcome Bot ───────────────────────────────────────────────────────────────
# Watches chat and greets players when they join the server.
# Leave this running in the background — it does nothing until someone joins.
# When a join message appears, it sends a greeting to public chat with say().
#
# This is the simplest example of reading chat and reacting to it.
# The greeting goes to public chat so the joining player sees it.
# For private responses (only visible to you), see chat_bot.py which uses msg().

msg('[WelcomeBot] Running — watching for joins...')

while True:
    m = wait_for_chat('joined the game', timeout=60)
    if m:
        match = re.match(r'^(.+) joined the game', m['text'])
        if match:
            player = match.group(1)
            say(f'Welcome, {player}! o/')
