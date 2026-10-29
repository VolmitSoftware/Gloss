import argparse
import fcntl
import hashlib
import json
import os
import re
import shutil
import subprocess
import tempfile
import time
from contextlib import contextmanager
from collections.abc import Iterator
from datetime import datetime, timezone
from pathlib import Path
from urllib.error import HTTPError
from studio import ACTOR, OUTPUT, ROOT, rig, runtime

CONFIGS: Path = ROOT / 'src/test/demo/configs'
ASSETS: Path = ROOT.parent / 'docs/gloss-assets/demos'
TAKE_EXTENSIONS: tuple[str, ...] = ('.mp4', '.webm', '-start.png', '-action.png', '-end.png', '-encode.log')


def take_key(identifier: str, perspective: str) -> str:
    if re.fullmatch(r'[a-z0-9]+(?:-[a-z0-9]+)*', identifier) is None or perspective not in ('pov', 'observer'):
        raise ValueError('Invalid demonstration identifier or perspective')
    return identifier + '-' + perspective


def local_outputs(key: str) -> list[Path]:
    if re.fullmatch(r'[a-z0-9]+(?:-[a-z0-9]+)*-(?:pov|observer)', key) is None:
        raise ValueError('Invalid demonstration take')
    if OUTPUT.resolve() != OUTPUT.absolute():
        raise ValueError('Demonstration output must not contain symlinks')
    paths: list[Path] = [OUTPUT / (key + extension) for extension in TAKE_EXTENSIONS]
    for path in paths:
        if path.is_symlink() or (path.exists() and not path.is_file()):
            raise ValueError('Expected a regular demonstration output: ' + str(path))
    return paths


def discard_outputs(key: str) -> None:
    for path in local_outputs(key):
        path.unlink(missing_ok=True)


@contextmanager
def take_lock(key: str) -> Iterator[None]:
    local_outputs(key)
    OUTPUT.mkdir(parents=True, exist_ok=True)
    path: Path = OUTPUT / (key + '.lock')
    if path.is_symlink() or (path.exists() and not path.is_file()):
        raise ValueError('Expected a regular demonstration lock: ' + str(path))
    with path.open('a') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as failure:
            raise RuntimeError('Demonstration take is in use: ' + key) from failure
        yield


def digest(path: Path) -> str:
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def validate_clip(path: Path) -> None:
    if not 0 < path.stat().st_size < 25_000_000:
        raise ValueError('Clip must be nonempty and below 25 MB')
    probe: dict = json.loads(subprocess.check_output([str(rig.FFPROBE), '-v', 'error', '-show_streams',
        '-show_format', '-of', 'json', str(path)], text=True))
    streams: list[dict] = probe['streams']
    if (len(streams) != 1 or streams[0].get('codec_type') != 'video'
            or streams[0].get('codec_name') != 'vp9' or streams[0].get('width') != 1920
            or streams[0].get('height') != 1080 or float(probe['format'].get('duration', 0)) <= 0):
        raise ValueError('Clip must be silent 1920x1080 VP9 video with a positive duration')
    subprocess.run([str(rig.FFMPEG), '-v', 'error', '-xerror', '-i', str(path), '-f', 'null', '-'], check=True)


def publish_clip(key: str) -> str:
    source: Path = OUTPUT / (key + '.webm')
    if ASSETS.resolve() != ASSETS.absolute():
        raise ValueError('Documentation assets must not contain symlinks')
    ASSETS.mkdir(parents=True, exist_ok=True)
    destination: Path = ASSETS / source.name
    if destination.is_symlink():
        raise ValueError('Documentation clip must not be a symlink')
    expected: str = digest(source)
    temporary: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(prefix='.' + key + '-', suffix='.webm', dir=ASSETS, delete=False) as staging:
            temporary = Path(staging.name)
            with source.open('rb') as recording:
                shutil.copyfileobj(recording, staging)
            staging.flush()
            os.fsync(staging.fileno())
        if digest(temporary) != expected:
            raise ValueError('Documentation clip copy failed its SHA-256 check')
        validate_clip(temporary)
        temporary.chmod(0o644)
        os.replace(temporary, destination)
        return expected
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


@contextmanager
def manifest_update(discard: str | None = None) -> Iterator[dict]:
    if OUTPUT.resolve() != OUTPUT.absolute():
        raise ValueError('Demonstration output must not contain symlinks')
    OUTPUT.mkdir(parents=True, exist_ok=True)
    path: Path = OUTPUT / 'manifest.json'
    if any(candidate.is_symlink() for candidate in (path, OUTPUT / 'manifest.lock', path.with_suffix('.tmp'))):
        raise ValueError('Demonstration manifest must not contain symlinks')
    with (OUTPUT / 'manifest.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        takes: dict = json.loads(path.read_text()) if path.exists() else {}
        yield takes
        temporary: Path = path.with_suffix('.tmp')
        temporary.write_text(json.dumps(takes, indent=2))
        os.replace(temporary, path)
        if discard is not None:
            discard_outputs(discard)


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
    take: str = take_key(identifier, perspective)
    with take_lock(take):
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
        recorded_at: str = datetime.now(timezone.utc).isoformat()
        server_jar_hash: str = hashlib.sha256((server / 'plugins/Gloss.jar').read_bytes()).hexdigest()
        with manifest_update() as takes:
            discard_outputs(take)
            takes[take] = {'completed': False, 'accepted': False, 'status': 'recording'}
        raw: Path = OUTPUT / (take + '.mp4')
        success: bool = False
        try:
            capture_bridge.command('capture', action='start', path=str(raw), ffmpeg=str(rig.FFMPEG), width=1920, height=1080, fps=30)
            print(take + ': native capture started', flush=True)
            try:
                for data in shot['beats']:
                    beat(bridge, rcon, data)
            finally:
                final: dict = capture_bridge.command('capture', action='stop', request_timeout=60)
            rig.capture_evidence(final)
            target: Path = OUTPUT / (take + '.webm')
            with (OUTPUT / (take + '-encode.log')).open('w') as encode_log:
                subprocess.run([str(rig.FFMPEG), '-y', '-i', str(raw), '-an', '-c:v', 'libvpx-vp9', '-crf', '35', '-b:v', '0', '-row-mt', '1', '-deadline', 'good', '-cpu-used', '4', '-pix_fmt', 'yuv420p', str(target)], check=True, stdout=subprocess.DEVNULL, stderr=encode_log)
            validate_clip(target)
            duration: float = final['captureSeconds']
            for label, seconds in (('start', 0.5), ('action', duration / 2), ('end', duration - 0.5)):
                subprocess.run([str(rig.FFMPEG), '-y', '-ss', str(seconds), '-i', str(target), '-frames:v', '1', str(OUTPUT / (take + '-' + label + '.png'))], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            print(identifier + ': native 1920x1080, ' + str(final['captureFrames']) + ' real frames / ' + str(duration) + 's; silent; exported ' + str(target.stat().st_size) + ' bytes', flush=True)
            success = True
        finally:
            with manifest_update(discard=take if not success else None) as takes:
                takes[take] = {'recordedAt': recorded_at, 'serverJarSha256': server_jar_hash, 'completed': success, 'accepted': False, 'status': 'pending-review' if success else 'rejected', 'mode': 'live-framebuffer', 'perspective': perspective, 'shot': shot, 'capture': rig.capture_metrics(final) if success else {}, 'commands': rcon.history}
            if perspective == 'observer':
                capture_bridge.command('hud', visible=True)


def review(identifier: str, perspective: str, accepted: bool, evidence: str) -> None:
    key: str = take_key(identifier, perspective)
    if not evidence.strip():
        raise ValueError('Review evidence is required')
    with take_lock(key):
        with manifest_update(discard=key) as takes:
            local_outputs(key)
            take: dict = takes[key]
            if accepted and not take['completed']:
                raise AssertionError('An incomplete take cannot be accepted')
            if accepted:
                if take['accepted']:
                    published: Path = ASSETS / (key + '.webm')
                    if published.is_symlink() or digest(published) != take['sha256']:
                        raise ValueError('Published clip does not match the accepted SHA-256')
                else:
                    take['sha256'] = publish_clip(key)
                take['published'] = str(ASSETS / (key + '.webm'))
            take['accepted'] = accepted
            take['status'] = 'accepted' if accepted else 'rejected'
            take['review'] = evidence


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
