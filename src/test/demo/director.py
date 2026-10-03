import argparse
import fcntl
import hashlib
import json
import os
import re
import shutil
import subprocess
import time
from contextlib import contextmanager
from collections.abc import Iterator
from datetime import datetime, timezone
from pathlib import Path
from urllib.error import HTTPError
from studio import ACTOR, OUTPUT, ROOT, rig, runtime

CONFIGS: Path = ROOT / 'src/test/demo/configs'
ASSETS: Path = ROOT.parent / 'docs/gloss-assets/demos'


@contextmanager
def manifest_update() -> Iterator[dict]:
    path: Path = OUTPUT / 'manifest.json'
    with (OUTPUT / 'manifest.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        takes: dict = json.loads(path.read_text()) if path.exists() else {}
        yield takes
        temporary: Path = path.with_suffix('.tmp')
        temporary.write_text(json.dumps(takes, indent=2))
        os.replace(temporary, path)


def install() -> None:
    server, rcon, bridge = runtime()
    for source in CONFIGS.rglob('*.json'):
        target: Path = server / 'plugins/Gloss' / source.relative_to(CONFIGS)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
    config: Path = server / 'plugins/Gloss/gloss.toml'
    text: str = config.read_text()
    for feature in ('channels', 'motd', 'connections', 'nametags', 'nameplates'):
        text = re.sub(r'(?m)^' + feature + r' = false$', feature + ' = true', text)
    config.write_text(text)
    for command in ('op ' + ACTOR, 'gamemode creative ' + ACTOR, 'gamerule send_command_feedback false', 'gamerule advance_time false', 'gamerule advance_weather false', 'gamerule spawn_mobs false', 'time set 6000', 'weather clear', 'fill -14 70 -14 14 70 14 minecraft:smooth_stone', 'fill -10 70 -10 10 70 10 minecraft:oak_planks', 'fill -14 71 -14 14 71 -14 minecraft:stone_brick_wall', 'fill -14 71 14 14 71 14 minecraft:stone_brick_wall', 'fill -14 71 -13 -14 71 13 minecraft:stone_brick_wall', 'fill 14 71 -13 14 71 13 minecraft:stone_brick_wall', 'fill -13 71 -13 -11 71 13 minecraft:azalea_leaves', 'fill 11 71 -13 13 71 13 minecraft:azalea_leaves', 'tp ' + ACTOR + ' 0.5 71 0.5 0 0', 'gloss status'):
        print(rcon.command(command), flush=True)
    (OUTPUT / 'setup-commands.json').write_text(json.dumps(rcon.history, indent=2))
    time.sleep(3)
    print(rcon.command('gloss check workspace'), flush=True)
    (OUTPUT / 'fixture-check.json').write_text(json.dumps(rcon.history[-1:], indent=2))
    bridge.command('screenshot', name='pilot')


def beat(bridge, rcon, data: dict) -> None:
    op: str = data['op']
    values: dict = {key: value for key, value in data.items() if key not in ('op', 'wait')}
    if op == 'visitor':
        session: dict = next(session for session in json.loads((OUTPUT / 'clients.json').read_text()) if session['player'] == 'GlossVisitor')
        visitor = rig.Bridge(session['port'], session['token'])
        action: str = values.pop('action')
        if action == 'connect' and values.get('address') == 'managed':
            values['address'] = '127.0.0.1:' + str(json.loads((OUTPUT / 'server.json').read_text())['port'])
        visitor.command(action, **values)
    elif op == 'rcon':
        print(rcon.command(values['command']), flush=True)
    elif op == 'document':
        server: Path = Path(json.loads((OUTPUT / 'server.json').read_text())['path'])
        path: Path = server / 'plugins/Gloss' / values['path']
        document: dict = json.loads(path.read_text())
        for key, value in values['values'].items():
            cursor: dict = document
            names: list[str] = key.split('.')
            for name in names[:-1]:
                cursor = cursor[name]
            cursor[names[-1]] = value
        document['revision'] = document.get('revision', 0) + 1
        pending: Path = path.with_suffix('.tmp')
        pending.write_text(json.dumps(document, indent=2) + '\n')
        os.replace(pending, path)
        kind: str = Path(values['path']).parts[0]
        if kind in ('nameplates', 'nametags'):
            deadline: float = time.monotonic() + 30
            while 'loaded=' + str(document['revision']) + ' ' not in rcon.command('glossdemo loaded ' + kind + ' ' + path.stem):
                if time.monotonic() >= deadline:
                    raise TimeoutError('Identity registry did not apply ' + values['path'])
                time.sleep(0.5)
    elif op == 'wait-document':
        server: Path = Path(json.loads((OUTPUT / 'server.json').read_text())['path'])
        path: Path = server / 'plugins/Gloss' / values['path']
        deadline: float = time.monotonic() + values.get('timeoutSeconds', 60)
        while values['contains'] not in path.read_text():
            if time.monotonic() >= deadline:
                raise TimeoutError('Expected authored document update: ' + values['path'])
            time.sleep(0.25)
    elif op == 'assert-state':
        state: dict = bridge.state()
        for key, expected in values.items():
            actual = state
            for name in key.split('.'):
                actual = actual[name]
            if actual != expected:
                raise AssertionError(key + ': expected ' + str(expected) + ', got ' + str(actual))
    elif op == 'gui-widget':
        widgets: list[dict] = [widget for widget in bridge.state()['guiWidgets'] if widget['active']
            and ('label' not in values or widget['label'] == values['label'])
            and ('type' not in values or widget['type'] == values['type'])]
        if not widgets:
            raise AssertionError('No active GUI widget matches ' + str(values))
        widget: dict = max(widgets, key=lambda widget: widget['x']) if values.get('rightmost') else widgets[0]
        bridge.command('gui', action='click', x=widget['x'] + widget['width'] / 2,
                       y=widget['y'] + widget['height'] / 2, ticks=values.get('ticks', 18))
    elif op != 'wait':
        if op in ('connect', 'server-list') and values.get('address') == 'managed':
            values['address'] = '127.0.0.1:' + str(json.loads((OUTPUT / 'server.json').read_text())['port'])
        try:
            bridge.command(op, **values)
        except HTTPError as failure:
            raise RuntimeError(op + ': ' + failure.read().decode()) from failure
    time.sleep(data.get('wait', 0.5))


def record(identifier: str, perspective: str = 'pov') -> None:
    shot: dict = next(shot for shot in json.loads((ROOT / 'src/test/demo/shots.json').read_text()) if shot['id'] == identifier)
    if perspective == 'observer' and 'observer' not in shot:
        raise ValueError('This shot has no observer camera: ' + identifier)
    server, rcon, bridge = runtime()
    for data in shot['setup']:
        beat(bridge, rcon, data)
    time.sleep(3)
    capture_bridge = bridge
    if perspective == 'observer':
        session: dict = next(session for session in json.loads((OUTPUT / 'clients.json').read_text()) if session['player'] == 'GlossVisitor')
        capture_bridge = rig.Bridge(session['port'], session['token'])
        x, y, z = shot['observer']['position']
        print(rcon.command('tp GlossVisitor ' + ' '.join(str(value) for value in (x, y, z))), flush=True)
        x, y, z = shot['observer']['target']
        capture_bridge.command('look-at', x=x, y=y, z=z, ticks=0)
        time.sleep(1)
    state: dict = rig.fit_hidden_renderer(capture_bridge)
    if perspective == 'observer':
        capture_bridge.command('hud', visible=False)
    take: str = identifier + '-' + perspective
    manifest: Path = OUTPUT / 'manifest.json'
    takes: dict = json.loads(manifest.read_text()) if manifest.exists() else {}
    previous: dict | None = takes.get(take) or (takes.get(identifier) if perspective == 'pov' else None)
    if previous is not None:
        archive: Path = OUTPUT / 'takes' / (take + '-' + str(time.time_ns()))
        archive.mkdir(parents=True)
        for path in [OUTPUT / (take + extension) for extension in ('.mp4', '.webm', '-start.png', '-action.png', '-end.png')]:
            if not path.exists():
                continue
            shutil.move(str(path), archive / path.name)
        (archive / 'manifest.json').write_text(json.dumps(previous, indent=2))
    recorded_at: str = datetime.now(timezone.utc).isoformat()
    server_jar_hash: str = hashlib.sha256((server / 'plugins/Gloss.jar').read_bytes()).hexdigest()
    raw: Path = OUTPUT / (take + '.mp4')
    capture_bridge.command('capture', action='start', path=str(raw), ffmpeg=str(rig.FFMPEG), width=1920, height=1080, fps=30)
    print(take + ': native capture started', flush=True)
    success: bool = False
    try:
        for data in shot['beats']:
            beat(bridge, rcon, data)
        success = True
    finally:
        final: dict = capture_bridge.command('capture', action='stop', request_timeout=60)
        if perspective == 'observer':
            capture_bridge.command('hud', visible=True)
        with manifest_update() as takes:
            takes[take] = {'raw': str(raw), 'recordedAt': recorded_at, 'serverJarSha256': server_jar_hash, 'completed': success, 'accepted': False, 'status': 'pending-review' if success else 'rejected', 'mode': 'live-framebuffer', 'perspective': perspective, 'shot': shot, 'capture': rig.capture_metrics(final), 'commands': rcon.history}
    rig.capture_evidence(final)
    ASSETS.mkdir(parents=True, exist_ok=True)
    target: Path = OUTPUT / (take + '.webm')
    subprocess.run([str(rig.FFMPEG), '-y', '-i', str(raw), '-an', '-c:v', 'libvpx-vp9', '-crf', '35', '-b:v', '0', '-row-mt', '1', '-deadline', 'good', '-cpu-used', '4', '-pix_fmt', 'yuv420p', str(target)], check=True, stdout=subprocess.DEVNULL, stderr=(OUTPUT / (identifier + '-encode.log')).open('w'))
    subprocess.run([str(rig.FFMPEG), '-v', 'error', '-i', str(target), '-f', 'null', '-'], check=True)
    if target.stat().st_size >= 25_000_000:
        raise AssertionError('Clip exceeds 25 MB')
    duration: float = final['captureSeconds']
    for label, seconds in (('start', 0.5), ('action', duration / 2), ('end', duration - 0.5)):
        subprocess.run([str(rig.FFMPEG), '-y', '-ss', str(seconds), '-i', str(target), '-frames:v', '1', str(OUTPUT / (take + '-' + label + '.png'))], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    print(identifier + ': native 1920x1080, ' + str(final['captureFrames']) + ' real frames / ' + str(duration) + 's; silent; exported ' + str(target.stat().st_size) + ' bytes', flush=True)


def review(identifier: str, perspective: str, accepted: bool, evidence: str) -> None:
    key: str = identifier + '-' + perspective
    with manifest_update() as takes:
        take: dict = takes.get(key) or takes[identifier]
        if accepted and not take['completed']:
            raise AssertionError('An incomplete take cannot be accepted')
        take['accepted'] = accepted
        take['status'] = 'accepted' if accepted else 'rejected'
        take['review'] = evidence
        if accepted:
            target: Path = OUTPUT / (key + '.webm')
            take['sha256'] = hashlib.sha256(target.read_bytes()).hexdigest()
            ASSETS.mkdir(parents=True, exist_ok=True)
            shutil.copy2(target, ASSETS / target.name)
        takes[key] = take


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=('install', 'record', 'accept', 'reject'))
    parser.add_argument('--only', action='append')
    parser.add_argument('--perspective', choices=('pov', 'observer'), default='pov')
    parser.add_argument('--evidence')
    args = parser.parse_args()
    if args.action == 'install':
        install()
    elif args.action == 'record':
        for identifier in args.only or [shot['id'] for shot in json.loads((ROOT / 'src/test/demo/shots.json').read_text())]:
            record(identifier, args.perspective)
    else:
        if not args.only or not args.evidence:
            parser.error('Review requires --only and --evidence describing actual frame and motion checks')
        for identifier in args.only:
            review(identifier, args.perspective, args.action == 'accept', args.evidence)
