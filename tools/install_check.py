#!/usr/bin/env python3
"""Real-install check: start the game with the release jars in a mods folder and drive it.

Runs ./gradlew prodClient -PprodTest (Peripheral, PaneLib and Fabric API as separate mods, no dev
classpath), then, through Peripheral's own local API: creates a world, opens the panel with G, runs a
Python script, takes a screenshot and quits. Prints PASS/FAIL lines and exits non-zero on failure.

    python3 tools/install_check.py            # normal install
    python3 tools/install_check.py --no-panelib   # PaneLib missing: expect Fabric's clear error
    python3 tools/install_check.py --xvfb     # Linux without a display
"""
import argparse, importlib.util, json, os, re, shutil, subprocess, sys, time, urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RUN = ROOT / 'run-prod'
results = []


def check(name, ok, detail=''):
    results.append(ok)
    print(('PASS ' if ok else 'FAIL ') + name + (f' ({detail})' if detail else ''), flush=True)
    return ok


def wait(what, fn, timeout, proc=None):
    end = time.time() + timeout
    while time.time() < end:
        if proc is not None and proc.poll() is not None:
            return None
        try:
            v = fn()
            if v:
                return v
        except Exception:
            pass
        time.sleep(1)
    print(f'... timed out waiting for {what}', flush=True)
    return None


def prepare():
    shutil.rmtree(RUN / 'config' / 'peripheral', ignore_errors=True)
    shutil.rmtree(RUN / 'saves', ignore_errors=True)
    (RUN / 'logs' / 'latest.log').unlink(missing_ok=True)
    RUN.mkdir(exist_ok=True)
    # Silent, no first-run accessibility screen, no tutorial toasts.
    # Throwaway profile: skip the first-run terms and Baritone screens (the dev UI test covers both)
    # so G opens the panel.
    (RUN / 'config').mkdir(parents=True, exist_ok=True)
    (RUN / 'config' / 'peripheral.json').write_text('{"terms_accepted": true, "baritone_warn_dismissed": true}\n')
    (RUN / 'options.txt').write_text('soundCategory_master:0.0\nonboardAccessibility:false\n'
                                     'tutorialStep:none\nnarrator:0\npauseOnLostFocus:false\n')


def launch(extra):
    gradlew = str(ROOT / ('gradlew.bat' if os.name == 'nt' else 'gradlew'))
    (ROOT / 'build').mkdir(exist_ok=True)
    log = open(ROOT / 'build' / 'install-check-gradle.log', 'w')
    return subprocess.Popen([gradlew, 'prodClient', '-PprodTest', '--console=plain', *extra],
                            cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)


def latest_log():
    p = RUN / 'logs' / 'latest.log'
    return p.read_text(errors='replace') if p.exists() else ''


def stop(proc):
    if proc.poll() is None:
        if os.name == 'nt':
            subprocess.run(['taskkill', '/T', '/F', '/PID', str(proc.pid)], capture_output=True)
        else:
            proc.terminate()
            try:
                proc.wait(30)
            except subprocess.TimeoutExpired:
                proc.kill()
    # Gradle forks the game; make sure no java child keeps the window open.
    if os.name != 'nt':
        subprocess.run(['pkill', '-f', str(RUN)], capture_output=True)


def load_mc():
    token = (RUN / 'config' / 'peripheral' / 'local-api.token').read_text().strip()
    os.environ['PERIPHERAL_LOCAL_TOKEN'] = token
    spec = importlib.util.spec_from_file_location('mc', RUN / 'config' / 'peripheral' / 'scripts' / 'mc.py')
    mc = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mc)
    return mc


def screen_kind(mc):
    s = mc.screen()
    return s.get('kind') if s.get('open') else '(none)'


def click(mc, label):
    """Click until the screen changes; a click during a screen's fade-in is ignored by the game."""
    before = screen_kind(mc)
    for _ in range(20):
        try:
            mc.click_button(label)
        except Exception:
            pass
        time.sleep(1.5)
        if screen_kind(mc) != before:
            return True
    return False


def normal(args):
    prepare()
    proc = launch(['-PprodXvfb'] if args.xvfb else [])
    try:
        token = RUN / 'config' / 'peripheral' / 'local-api.token'
        if not check('game starts and Peripheral writes its API token', wait('token', token.exists, 900, proc) is not None):
            return
        log = latest_log()
        check('PaneLib and Peripheral load as separate mods', 'panelib' in log.lower() and 'peripheral' in log.lower())
        mc = load_mc()
        title = wait('title screen', lambda: screen_kind(mc) == 'TitleScreen' and 'TitleScreen', 300, proc)
        check('local API answers on the title screen', title is not None, screen_kind(mc) if title is None else title)

        # A fresh world through the real menus. With no saves, Singleplayer goes straight to Create.
        time.sleep(3)
        click(mc, 'Singleplayer')
        if screen_kind(mc) != 'CreateWorldScreen':
            click(mc, 'Create New World')
        click(mc, 'Create New World')
        in_world = wait('world', lambda: mc.state().get('in_world', True) and screen_kind(mc) == '(none)', 600, proc)
        check('creates and joins a world', in_world is not None, screen_kind(mc))
        time.sleep(5)

        mc.press_key('g')
        panel = wait('panel', lambda: screen_kind(mc) not in ('(none)', 'PauseScreen') and screen_kind(mc), 20)
        s = mc.screen()
        labels = [c['label'] for c in s.get('controls', [])]
        check('G opens the Peripheral panel', panel == 'PeripheralScreen' and len(labels) > 3, f'{panel}, {len(labels)} controls')
        time.sleep(3)
        mc.press_key('f2')

        # Python must start from the installed game, which is the part most likely to differ by OS.
        scripts = RUN / 'config' / 'peripheral' / 'scripts'
        (scripts / 'install_check_hello.py').write_text(
            'import mc\ns = mc.state()\nprint("INSTALL_CHECK_OK", sorted(s)[:5])\n')
        # run_script is an HTTP-only route, so call it directly with the session token.
        req = urllib.request.Request('http://127.0.0.1:25585/run_script', method='POST',
                                     data=json.dumps({'name': 'install_check_hello.py'}).encode(),
                                     headers={'Authorization': 'Bearer ' + os.environ['PERIPHERAL_LOCAL_TOKEN'],
                                              'Content-Type': 'application/json'})
        started = urllib.request.urlopen(req, timeout=10).read().decode()
        out = scripts / 'install_check_hello.py.log'
        ran = wait('script output', lambda: out.exists() and 'INSTALL_CHECK_OK' in out.read_text(errors='replace'), 60)
        check('runs a Python script that talks to the game', ran is not None,
              out.read_text(errors='replace')[-300:] if out.exists() else 'no log; run_script said ' + started)

        shots = list((RUN / 'screenshots').glob('*.png')) if (RUN / 'screenshots').exists() else []
        check('F2 screenshot saved', len(shots) > 0, str(max(shots, key=lambda p: p.stat().st_mtime)) if shots else '')

        mc.press_key('escape')
        time.sleep(1)
        mc.press_key('escape')
        click(mc, 'Save and Quit to Title')
        wait('title', lambda: screen_kind(mc) == 'TitleScreen', 60)
        time.sleep(3)
        try:
            mc.click_button('Quit Game')
        except Exception:
            pass  # the API goes away as the game closes
        check('quits cleanly', wait('exit', lambda: proc.poll() is not None, 90) is not None)
        log = latest_log()
        ours = re.compile(r'slooth\.peripheral|dev\.pane|\((peripheral|panelib)\)|Mixin apply|MixinTransformerError|InvalidMixin')
        bad = [l[:200] for l in log.splitlines() if ('ERROR' in l or 'Exception' in l) and ours.search(l)]
        check('no Peripheral, PaneLib or mixin errors in latest.log', not bad, ' | '.join(bad[:3]))
    finally:
        stop(proc)


def no_panelib(args):
    prepare()
    proc = launch(['-PprodNoPanelib'] + (['-PprodXvfb'] if args.xvfb else []))
    try:
        gradle_log = ROOT / 'build' / 'install-check-gradle.log'
        seen = wait('dependency error', lambda: 'panelib' in (latest_log() + gradle_log.read_text(errors='replace')).lower()
                    and 'requires' in (latest_log() + gradle_log.read_text(errors='replace')).lower(), 600, proc)
        text = latest_log() + gradle_log.read_text(errors='replace')
        line = next((l.strip() for l in text.splitlines() if 'panelib' in l.lower() and 'requires' in l.lower()), '')
        check('missing PaneLib gives a clear "requires PaneLib" message', seen is not None, line[:200])
        check('Peripheral did not start without PaneLib', not (RUN / 'config' / 'peripheral' / 'local-api.token').exists())
    finally:
        stop(proc)


if __name__ == '__main__':
    ap = argparse.ArgumentParser()
    ap.add_argument('--no-panelib', action='store_true')
    ap.add_argument('--xvfb', action='store_true')
    a = ap.parse_args()
    (no_panelib if a.no_panelib else normal)(a)
    print(f'install check: {results.count(True)} passed, {results.count(False)} failed', flush=True)
    sys.exit(0 if results and all(results) else 1)
