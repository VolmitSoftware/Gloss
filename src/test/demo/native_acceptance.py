import argparse
import errno
import hashlib
import json
import math
import re
import shutil
import struct
import time
import tomllib
import uuid
import zipfile
import zlib
from collections.abc import Callable
from pathlib import Path
from urllib.request import urlopen

ROOT: Path = Path(__file__).resolve().parents[3]
PREFIX: str = 'nativeqa'


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def digest(path: Path, algorithm: str = 'sha256') -> str:
    return hashlib.new(algorithm, path.read_bytes()).hexdigest()


def png(kind: str) -> bytes:
    def chunk(name: bytes, data: bytes) -> bytes:
        return struct.pack('>I', len(data)) + name + data + struct.pack('>I', zlib.crc32(name + data))
    rows: bytearray = bytearray()
    for y in range(16):
        rows.append(0)
        for x in range(16):
            visible: bool = (x in (1, 14) or y in (1, 14)) if kind == 'frame' else (
                abs(x - 7) + abs(y - 7) <= 6 if kind == 'diamond' else 3 <= x <= 12 and 3 <= y <= 12)
            color: tuple[int, int, int] = (255, 196, 32) if kind == 'diamond' else (48, 220, 240)
            rows.extend((*color, 255 if visible else 0))
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', 16, 16, 8, 6, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(rows)) + chunk(b'IEND', b'')


def fixtures(position: dict[str, float], world: str) -> dict[str, bytes]:
    documents: dict[str, dict] = {
        'glyphs/nativeqa-a.json': {'schemaVersion': 1, 'revision': 1, 'namespace': PREFIX, 'font': 'gold',
            'glyphs': [{'id': 'nativeqa_gold', 'image': 'nativeqa/diamond.png', 'height': 12, 'ascent': 10, 'fallback': '[GOLD]'}],
            'overlays': [{'id': 'nativeqa_frame', 'image': 'nativeqa/frame.png', 'height': 16, 'ascent': 12, 'anchor': 'center'}],
            'space': {'enabled': True, 'range': [-32, 32]},
            'waypointStyles': [{'id': 'diamond', 'nearDistance': 8, 'farDistance': 64,
                'sprites': [{'id': 'gold', 'image': 'nativeqa/diamond.png'}, {'id': 'cyan', 'image': 'nativeqa/square.png'}]}]},
        'glyphs/nativeqa-b.json': {'schemaVersion': 1, 'revision': 1, 'namespace': PREFIX, 'font': 'cyan',
            'glyphs': [{'id': 'nativeqa_cyan', 'image': 'nativeqa/square.png', 'height': 12, 'ascent': 10, 'fallback': '[CYAN]'}],
            'overlays': [], 'space': {'enabled': False, 'range': [-32, 32]}},
        'boards/nativeqa.json': {'schemaVersion': 2, 'revision': 1, 'show': True,
            'select': {'priority': 100000, 'when': 'true'}, 'presentation': {'title': 'Native pack acceptance',
                'hideNumbers': True, 'lines': ["Gold {{ glyph('nativeqa_gold') }}", "Cyan {{ glyph('nativeqa_cyan') }}",
                    "Overlay {{ overlay('nativeqa_frame') }}", "A{{ shift(-8) }}B", 'Loaded {{ pack.loaded }}']}, 'variants': []},
        'inventories/nativeqa.json': {'schemaVersion': 1, 'revision': 1, 'show': 'true', 'title': 'Native pack status',
            'resolution': '9x1', 'slots': {str(index): {'type': 'decoration', 'icon': {'type': 'item',
                'item': 'minecraft:paper', 'name': text,
                'lore': ["Fonts: {{ glyph('nativeqa_gold') }} {{ glyph('nativeqa_cyan') }}"]}} for index, text in enumerate([
                    'LOADED={{ pack.loaded }}', 'HASH={{ pack.sha1 }}', 'STATUS={{ pack.status }}',
                    "GOLD={{ glyph('nativeqa_gold') }}", "CYAN={{ glyph('nativeqa_cyan') }}"])}},
        'waypoints/nativeqa.json': {'schemaVersion': 1, 'revision': 1, 'show': True,
            'anchor': {'world': world, 'x': position['x'], 'y': position['y'], 'z': position['z'] + 24},
            'color': '#ffffff', 'style': 'nativeqa:diamond', 'fallbackStyle': 'bowtie', 'range': 128}}
    result: dict[str, bytes] = {name: (json.dumps(value, indent=2) + '\n').encode() for name, value in documents.items()}
    result.update({'images/nativeqa/' + name + '.png': png(name) for name in ('diamond', 'square', 'frame')})
    return result


def validate_files(files: dict[str, bytes]) -> None:
    require(len(files) == 8, 'Expected five documents and three textures')
    documents: dict[str, dict] = {name: json.loads(data) for name, data in files.items() if name.endswith('.json')}
    ids: set[str] = set()
    fonts: set[str] = set()
    for name, document in documents.items():
        require(document['schemaVersion'] == (2 if name.startswith('boards/') else 1), name)
        require(document['revision'] > 0, name)
        if not name.startswith('glyphs/'):
            continue
        fonts.add(document['namespace'] + ':' + document['font'])
        for entry in document['glyphs'] + document['overlays']:
            require(entry['id'] not in ids, 'Duplicate glyph identifier')
            ids.add(entry['id'])
            require('images/' + entry['image'] in files, 'Missing image')
            require(1 <= entry['height'] <= 256 and entry['ascent'] <= entry['height'], 'Invalid bitmap metrics')
        for style in document.get('waypointStyles', []):
            require(style['farDistance'] > style['nearDistance'] >= 0, 'Invalid waypoint distances')
            require(all('images/' + sprite['image'] in files for sprite in style['sprites']), 'Missing waypoint image')
    require(fonts == {'nativeqa:gold', 'nativeqa:cyan'}, 'Expected two independent fonts')
    for name, data in files.items():
        if name.endswith('.png'):
            require(data.startswith(b'\x89PNG\r\n\x1a\n'), name)
            require(struct.unpack('>II', data[16:24]) == (16, 16), name)
            offset: int = 8
            pixels: bytes = b''
            while offset < len(data):
                size: int = struct.unpack('>I', data[offset:offset + 4])[0]
                kind: bytes = data[offset + 4:offset + 8]
                value: bytes = data[offset + 8:offset + 8 + size]
                require(zlib.crc32(kind + value) == struct.unpack('>I', data[offset + 8 + size:offset + 12 + size])[0], name)
                if kind == b'IDAT':
                    pixels += value
                offset += size + 12
            require(len(zlib.decompress(pixels)) == 16 * 65, name)


def configure(text: str, port: int, serve: bool) -> str:
    values: dict[str, dict[str, object]] = {'features': {'forge': True, 'camera': True, 'waypoints': True,
        'inventories': True, 'boards': True}, 'forge': {'serve': serve, 'serveBind': '127.0.0.1', 'servePort': port,
        'url': '', 'required': False, 'buildDebounceTicks': 20}}
    parsed: dict = tomllib.loads(text)
    for section, entries in values.items():
        for key in entries:
            require(key in parsed.get(section, {}), 'Missing configuration field ' + section + '.' + key)
    section: str = ''
    lines: list[str] = []
    for line in text.splitlines():
        match: re.Match | None = re.fullmatch(r'\s*\[([^]]+)\]\s*', line)
        if match:
            section = match.group(1)
        key: str = line.split('=', 1)[0].strip()
        lines.append(key + ' = ' + json.dumps(values[section][key]) if section in values and key in values[section] else line)
    result: str = '\n'.join(lines) + '\n'
    updated: dict = tomllib.loads(result)
    require(all(updated[section][key] == value for section, entries in values.items() for key, value in entries.items()), 'Configuration rewrite failed')
    return result


def dry_run(output: Path) -> None:
    files: dict[str, bytes] = fixtures({'x': 0.5, 'y': 71.0, 'z': 0.5}, 'world')
    validate_files(files)
    for name, data in files.items():
        target: Path = output / 'fixtures' / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
    sample: str = '[features]\nforge=false\ncamera=false\nwaypoints=false\ninventories=false\nboards=false\n[forge]\nserve=false\nserveBind="0.0.0.0"\nservePort=8085\nurl=""\nrequired=true\nbuildDebounceTicks=100\n'
    configure(sample, 48085, False)
    configure(sample, 48085, True)
    (output / 'dry-run.json').write_text(json.dumps({'status': 'offline-validated', 'documents': 5, 'textures': 3,
        'runtimeExecuted': False, 'backendSchemaValidation': 'pending /gloss check workspace on target'}, indent=2))


def run(output: Path, approved_jar: Path, expected_sha256: str) -> None:
    from studio import ACTOR, OUTPUT, rig, runtime
    server, rcon, bridge = runtime()
    expected_root: Path = ROOT.parent.parent / '[Minecraft Server]/consumers/plugin-consumers/instances'
    require(server.resolve().parent == expected_root.resolve() and server.name.startswith('gloss-demo-'), 'Only owned studio instances are eligible')
    require('isolated=true' in (server / '.server-source').read_text().splitlines(), 'An isolated studio is required')
    require(digest(server / 'plugins/Gloss.jar') == expected_sha256 == digest(approved_jar), 'Current/deployed jar hash mismatch')
    original: dict = bridge.state()
    rig.verify_hidden_renderer(original)
    require((original.get('frameWidth'), original.get('frameHeight')) == (1920, 1080), 'Expected native 1920x1080 framebuffer')
    require(original.get('connected') and original.get('player') == ACTOR and not original.get('capturing'), 'Expected an idle connected studio actor')
    root: Path = server / 'plugins/Gloss'
    config: Path = root / 'gloss.toml'
    original_config: bytes = config.read_bytes()
    require(not tomllib.loads(original_config.decode())['forge']['serve'], 'Start with pack serving disabled')
    require(not tomllib.loads(original_config.decode())['forge']['url'], 'Start without an external pack URL')
    require(not original.get('screen'), 'Close the current screen before acceptance')
    world_reply: str = rcon.command('data get entity ' + ACTOR + ' Dimension')
    dimension: str = re.findall(r'"([^"\n]+)"', world_reply)[-1]
    require(dimension == 'minecraft:overworld', 'The studio actor must be in the overworld')
    files: dict[str, bytes] = fixtures(original['position'], 'world')
    validate_files(files)
    require(all(not (root / name).exists() for name in files), 'Fixture target already exists; nothing will be overwritten')
    require(not (root / 'images/nativeqa').exists(), 'Texture directory already exists')
    mode_reply: str = rcon.command('data get entity ' + ACTOR + ' playerGameType')
    mode: int = int(re.search(r':\s*(\d+)\s*$', mode_reply).group(1))
    was_op: bool = any(entry['uuid'] == original['uuid'] for entry in json.loads((server / 'ops.json').read_text()))
    port: int = rig.free_port()
    token: str = uuid.uuid4().hex[:10]
    media: Path = OUTPUT / ('native-acceptance-' + token)
    media.mkdir(parents=True)
    (output / 'original-gloss.toml').write_bytes(original_config)
    (output / 'restore.json').write_text(json.dumps({'server': str(server), 'configBackup': str(output / 'original-gloss.toml'),
        'ownedFiles': {name: hashlib.sha256(data).hexdigest() for name, data in files.items()},
        'originalPosition': original['position'], 'originalYaw': original['yaw'], 'originalPitch': original['pitch'],
        'originalGameMode': mode, 'originalOperator': was_op, 'generatedForgeOutputs': 'Leave for disposal of this owned instance.'}, indent=2))
    report: dict = {'status': 'running', 'jarSha256': expected_sha256, 'approvedJar': str(approved_jar), 'instance': server.name, 'before': original,
        'visualReview': 'pending', 'captureInput': {'width': 1920, 'height': 1080, 'fps': 30, 'scaling': False}, 'generatedForgeOutputs': 'Retained on the disposable owned server; remove through studio.close after evidence is saved.'}
    written: list[Path] = []
    created_directories: list[Path] = []
    samples: list[dict] = []
    capture_started: bool = False
    failure: Exception | None = None
    cleanup_failures: list[Exception] = []

    def state() -> dict:
        current: dict = bridge.state(timeout=5)
        rig.verify_hidden_renderer(current)
        require((current.get('frameWidth'), current.get('frameHeight')) == (1920, 1080), 'Capture requires a native 1920x1080 framebuffer, without scaling')
        require(current.get('connected') and not current.get('lastError') and not current.get('windowError'), 'Native connection/bridge error')
        return current

    def wait(predicate: Callable[[dict], bool], label: str, timeout: float = 30) -> dict:
        deadline: float = time.monotonic() + timeout
        previous_frames: int = state()['frames']
        progress: float = time.monotonic()
        while time.monotonic() < deadline:
            current: dict = state()
            if current['frames'] > previous_frames:
                progress = time.monotonic()
                previous_frames = current['frames']
            require(time.monotonic() - progress < 8, 'Hidden renderer stopped advancing')
            samples.append({'time': time.time(), 'frames': current['frames'], 'position': current.get('position'), 'yaw': current.get('yaw'), 'pitch': current.get('pitch')})
            if predicate(current):
                return current
            time.sleep(0.2)
        raise TimeoutError(label)

    def screenshot(label: str) -> None:
        name: str = 'nativeqa-' + token + '-' + label
        bridge.command('screenshot', name=name)
        captured: dict = wait(lambda value: str(value.get('screenshotPath', '')).endswith(name + '.png'), 'screenshot ' + label)
        shutil.copy2(captured['screenshotPath'], output / (label + '.png'))

    def pack_ready() -> bool:
        path: Path = root / 'forge/out/gloss-pack.zip'
        if not path.is_file():
            return False
        try:
            with zipfile.ZipFile(path) as archive:
                return all(name in archive.namelist() for name in ('assets/nativeqa/font/gold.json', 'assets/nativeqa/font/cyan.json'))
        except (OSError, zipfile.BadZipFile):
            return False

    def inventory(label: str = '') -> dict[str, str]:
        rcon.command('gloss inventory open nativeqa player=' + ACTOR)
        current: dict = wait(lambda value: value.get('container', {}).get('title') == 'Native pack status' if value.get('container') else False, 'status inventory')
        result: dict[str, str] = {}
        for item in current['container']['slots']:
            name: str = item.get('name', '')
            if '=' in name:
                key, value = name.split('=', 1)
                result[key] = value
        if label:
            screenshot(label + '-inventory')
            for key in ('GOLD', 'CYAN'):
                item: dict = next(slot for slot in current['container']['slots'] if slot.get('name', '').startswith(key + '='))
                bridge.command('cursor', x=item['screenX'], y=item['screenY'], ticks=6, containerId=current['container']['id'])
                hovered: dict = wait(lambda value: not value.get('cursorMoving') and (value.get('container') or {}).get('id') == current['container']['id'],
                    label + ' tooltip cursor ' + key)
                wait(lambda value: value['frames'] >= hovered['frames'] + 6, label + ' tooltip frames ' + key)
                screenshot(label + '-tooltip-' + key.lower())
        bridge.command('gui', action='close', containerId=current['container']['id'])
        wait(lambda value: not value.get('screen'), 'close status inventory')
        return result

    def prompt(accept: bool) -> None:
        current: dict = wait(lambda value: any(widget.get('label') in ('Yes', 'Proceed', 'No') for widget in value.get('guiWidgets', [])), 'resource pack prompt')
        require('pack' in str(current.get('screenTitle', '')).lower() or 'pack' in str(current.get('screen', '')).lower() or str(current.get('screen', '')).endswith('ConfirmScreen'), 'Unexpected confirmation screen')
        labels: tuple[str, ...] = ('Yes', 'Proceed') if accept else ('No',)
        widget: dict = next(widget for widget in current['guiWidgets'] if widget['label'] in labels and widget['active'])
        screenshot('accept-prompt' if accept else 'decline-prompt')
        bridge.command('gui', action='click', screen=current['screen'], x=widget['x'] + widget['width'] / 2,
            y=widget['y'] + widget['height'] / 2, ticks=2)
        wait(lambda value: not value.get('screen'), 'pack choice completed', 60)

    def pose_distance(current: dict, before: dict) -> float:
        return math.dist([current['position'][axis] for axis in ('x', 'y', 'z')], [before['position'][axis] for axis in ('x', 'y', 'z')])

    def restored(current: dict, before: dict) -> bool:
        return pose_distance(current, before) < 0.15 and abs((current['yaw'] - before['yaw'] + 180) % 360 - 180) < 1 and abs(current['pitch'] - before['pitch']) < 1

    try:
        for name, data in files.items():
            target: Path = root / name
            if not target.parent.exists():
                target.parent.mkdir(parents=True)
                created_directories.append(target.parent)
            target.write_bytes(data)
            written.append(target)
        config.write_text(configure(original_config.decode(), port, False))
        rcon.command('gloss reload')
        if not was_op:
            rcon.command('op ' + ACTOR)
        rcon.command('gamemode creative ' + ACTOR)
        wait(lambda value: pack_ready() and re.search(r'Waypoint\s+nativeqa\s+at\s+world', re.sub(r'§.', '', rcon.command('gloss waypoint info nativeqa'))) is not None, 'fixture load', 45)
        report['workspaceChecks'] = {}
        for name in files:
            if name.endswith('.json'):
                kind, document = name.split('/')
                result: str = rcon.command('gloss check workspace kind=' + kind + ' id=' + Path(document).stem)
                report['workspaceChecks'][name] = result
                require('Nothing to report.' in result, 'Fixture validation failed: ' + name + ': ' + result)
        deadline: float = time.monotonic() + 45
        unavailable: dict[str, str] = {}
        while time.monotonic() < deadline:
            unavailable = inventory()
            if unavailable.get('LOADED') == 'false' and unavailable.get('GOLD') == '[GOLD]' and unavailable.get('CYAN') == '[CYAN]':
                break
            time.sleep(0.5)
        report['unavailable'] = inventory('unavailable')
        require(report['unavailable'].get('LOADED') == 'false' and report['unavailable'].get('GOLD') == '[GOLD]' and report['unavailable'].get('CYAN') == '[CYAN]', 'Unavailable pack did not use text fallbacks')
        screenshot('unavailable-fallback')
        config.write_text(configure(original_config.decode(), port, True))
        rcon.command('gloss reload')
        prompt(False)
        report['declined'] = inventory('declined')
        require(report['declined'].get('LOADED') == 'false' and report['declined'].get('STATUS') == 'declined' and report['declined'].get('GOLD') == '[GOLD]', 'Declined pack did not preserve fallback')
        screenshot('declined-fallback')
        bridge.command('disconnect')
        rig.verify_hidden_renderer(bridge.state())
        address: str = '127.0.0.1:' + str(json.loads((OUTPUT / 'server.json').read_text())['port'])
        bridge.command('connect', address=address)
        bridge.wait(lambda value: value.get('connected'), 'native reconnect', timeout=60)
        state()
        prompt(True)
        deadline: float = time.monotonic() + 60
        loaded: dict[str, str] = {}
        while time.monotonic() < deadline:
            loaded = inventory()
            if loaded.get('LOADED') == 'true':
                break
            time.sleep(0.5)
        pack_hash: str = (root / 'forge/out/gloss-pack.sha1').read_text().strip()
        require(re.fullmatch('[0-9a-f]{40}', pack_hash) is not None, 'Invalid pack hash')
        require(loaded.get('LOADED') == 'true' and loaded.get('STATUS') == 'successfully_loaded' and loaded.get('HASH') == pack_hash, 'Client did not load the current pack')
        require(loaded.get('GOLD') != '[GOLD]' and loaded.get('CYAN') != '[CYAN]', 'Loaded pack still uses fallback text')
        require(all('<font:' not in loaded.get(key, '') and '</font>' not in loaded.get(key, '') for key in ('GOLD', 'CYAN')),
            'Glyph item components contain literal font markup')
        pack: Path = root / 'forge/out/gloss-pack.zip'
        require(digest(pack, 'sha1') == pack_hash, 'Pack ZIP hash mismatch')
        with urlopen('http://127.0.0.1:' + str(port) + '/gloss-pack-' + pack_hash + '.zip', timeout=10) as response:
            require(hashlib.sha1(response.read(16 * 1024 * 1024)).hexdigest() == pack_hash, 'Served pack hash mismatch')
        with zipfile.ZipFile(pack) as archive:
            for entry in ('assets/nativeqa/font/gold.json', 'assets/nativeqa/font/cyan.json', 'assets/nativeqa/waypoint_style/diamond.json'):
                json.loads(archive.read(entry))
        shutil.copy2(pack, output / 'accepted-pack.zip')
        report['loaded'] = loaded
        report['packSha1'] = pack_hash
        bridge.command('look', yaw=0, pitch=0, ticks=0)
        settled_from: dict = wait(lambda value: abs(value['yaw']) < 1, 'face waypoint')
        settled_at: float = time.monotonic()
        wait(lambda value: value['frames'] >= settled_from['frames'] + 90 and time.monotonic() - settled_at >= 3,
            'resource reload fade completion', 20)
        require(inventory('loaded') == loaded, 'Pack inventory changed during appearance capture')
        screenshot('loaded-glyphs-overlay-waypoint')
        camera_pose: dict = state()
        waypoint: dict = json.loads(files['waypoints/nativeqa.json'])['anchor']
        report['waypointViews'] = {}
        for label, distance in (('near', 4), ('far', 80)):
            target: dict = {'position': {'x': waypoint['x'], 'y': waypoint['y'], 'z': waypoint['z'] - distance}, 'yaw': 0, 'pitch': 0}
            rcon.command('tp ' + ACTOR + ' ' + ' '.join(str(target['position'][axis]) for axis in ('x', 'y', 'z')) + ' 0 0')
            acknowledged: dict = wait(lambda value: restored(value, target), label + ' waypoint position')
            settled: dict = wait(lambda value: restored(value, target) and value['frames'] >= acknowledged['frames'] + 30,
                label + ' waypoint frame progress')
            actual_distance: float = math.dist([settled['position'][axis] for axis in ('x', 'y', 'z')],
                [waypoint[axis] for axis in ('x', 'y', 'z')])
            require(actual_distance < 8 if label == 'near' else 64 < actual_distance < 128, 'Waypoint sprite distance is outside its declared band')
            screenshot('loaded-waypoint-' + label)
            report['waypointViews'][label] = {'distance': actual_distance, 'state': settled, 'appearanceReview': 'pending'}
        rcon.command('tp ' + ACTOR + ' ' + ' '.join(str(camera_pose['position'][axis]) for axis in ('x', 'y', 'z'))
            + ' ' + str(camera_pose['yaw']) + ' ' + str(camera_pose['pitch']))
        before: dict = wait(lambda value: restored(value, camera_pose), 'restore camera-test pose after waypoint captures')
        capture_started = True
        bridge.command('capture', action='start', path=str(media / 'camera.mp4'), ffmpeg=str(rig.FFMPEG), width=1920, height=1080, fps=30)
        for label, duration in (('natural', 6), ('stopped', 12)):
            bridge.command('chat', command='gloss camera test value=' + str(duration))
            during: dict = wait(lambda value: pose_distance(value, before) > 2, label + ' camera movement', 8)
            require(re.search(r':\s*3\s*$', rcon.command('data get entity ' + ACTOR + ' playerGameType')) is not None, 'Camera did not enter spectator')
            screenshot(label + '-during')
            if label == 'stopped':
                wait(lambda value: value['frames'] > during['frames'] + 60, 'moving camera frames', 10)
                bridge.command('chat', command='gloss camera stop')
            after: dict = wait(lambda value: restored(value, before), label + ' camera restoration', duration + 12)
            require(re.search(r':\s*1\s*$', rcon.command('data get entity ' + ACTOR + ' playerGameType')) is not None, 'Camera did not restore creative mode')
            screenshot(label + '-after')
            report[label] = {'before': before, 'during': during, 'after': after}
        captured: dict = bridge.command('capture', action='stop', request_timeout=60)
        capture_started = False
        require(captured['captureFrames'] > 60 and captured['captureSeconds'] > 6, 'Insufficient continuous camera capture')
        report['capture'] = rig.capture_metrics(captured)
        shutil.copy2(media / 'camera.mp4', output / 'camera.mp4')
        require(digest(server / 'plugins/Gloss.jar') == expected_sha256, 'Jar changed during acceptance')
        require((root / 'forge/out/gloss-pack.sha1').read_text().strip() == pack_hash, 'Pack changed during acceptance')
        report['status'] = 'automated-assertions-passed-visual-review-pending'
    except Exception as error:
        failure = error
        report['status'] = 'failed'
        report['error'] = repr(error)
    finally:
        def cleanup(action: Callable[[], object]) -> None:
            try:
                action()
            except Exception as error:
                cleanup_failures.append(error)
        if capture_started:
            cleanup(lambda: bridge.command('capture', action='stop', request_timeout=60))
        cleanup(lambda: rcon.command('gloss camera stop player=' + ACTOR))
        cleanup(lambda: rcon.command('gamemode ' + ('survival', 'creative', 'adventure', 'spectator')[mode] + ' ' + ACTOR))
        position: dict = original['position']
        cleanup(lambda: rcon.command('execute in ' + dimension + ' run tp ' + ACTOR + ' ' + ' '.join(str(position[axis]) for axis in ('x', 'y', 'z')) + ' ' + str(original['yaw']) + ' ' + str(original['pitch'])))
        if not was_op:
            cleanup(lambda: rcon.command('deop ' + ACTOR))
        def remove_owned(target: Path) -> None:
            require(target.read_bytes() == files[str(target.relative_to(root))], 'Fixture changed externally; preserving ' + str(target))
            target.unlink()
        for target in reversed(written):
            cleanup(lambda target=target: remove_owned(target))
        def remove_empty(directory: Path) -> None:
            try:
                directory.rmdir()
            except OSError as error:
                if error.errno != errno.ENOTEMPTY:
                    raise
        for directory in reversed(created_directories):
            cleanup(lambda directory=directory: remove_empty(directory))
        cleanup(lambda: config.write_bytes(original_config))
        cleanup(lambda: rcon.command('gloss reload'))
        cleanup(lambda: rcon.socket.close())
        if cleanup_failures:
            report['status'] = 'cleanup-failed'
        report['cleanupErrors'] = [repr(error) for error in cleanup_failures]
        report['commands'] = rcon.history
        report['samples'] = samples
        (output / 'report.json').write_text(json.dumps(report, indent=2))
        if cleanup_failures:
            raise ExceptionGroup('Native acceptance cleanup failed', ([failure] if failure else []) + cleanup_failures)
    if failure:
        raise failure


def main() -> None:
    parser: argparse.ArgumentParser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=('dry-run', 'run'))
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--expected-jar-sha256')
    parser.add_argument('--jar', type=Path)
    args: argparse.Namespace = parser.parse_args()
    output: Path = args.output.resolve()
    require(not output.is_relative_to(ROOT.parent.resolve()), 'Evidence must live outside repositories')
    require(not output.exists(), 'Use a fresh evidence directory')
    if args.mode == 'run':
        require(re.fullmatch('[0-9a-f]{64}', args.expected_jar_sha256 or '') is not None, 'Supply the approved jar SHA-256')
        require(args.jar is not None and args.jar.is_file(), 'Supply the immutable approved jar path')
    output.mkdir(parents=True)
    if args.mode == 'dry-run':
        dry_run(output)
    else:
        run(output, args.jar.resolve(), args.expected_jar_sha256)
    print(output)


if __name__ == '__main__':
    main()
