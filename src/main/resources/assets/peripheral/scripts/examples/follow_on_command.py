from mc import *
import re

# ── Follow On Command ────────────────────────────────────────
# Watches chat and follows players on command.
# Any player (including yourself) can say 'follow me' in chat
# and the script will start following them using Baritone.
# Say 'stop following' in chat to cancel.
#
# Runs in the background — leave it running while you play.
# Requires: Baritone mod (modrinth.com/mod/baritone)

msg('[Follow] Watching chat. Say "follow me" to start.')

while True:
    m = wait_for_chat(r'follow me|stop following', timeout=60)
    if not m:
        continue
    text = m['text'].lower()
    sender = m['sender']
    if 'stop following' in text:
        baritone_stop()
        msg('[Follow] Stopped.')
    elif 'follow me' in text and sender:
        msg(f'[Follow] Following {sender}...')
        baritone(f'follow {sender}')
