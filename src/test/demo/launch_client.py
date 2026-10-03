import hashlib
import json
import os
import subprocess
import uuid
from pathlib import Path


def maven_path(root: Path, coordinate: str) -> Path:
    fields: list[str] = coordinate.split(':')
    group, artifact, version = fields[:3]
    classifier: str = '-' + fields[3] if len(fields) > 3 else ''
    return root / group.replace('.', '/') / artifact / version / (artifact + '-' + version + classifier + '.jar')


def launch(prism: Path, instance: Path, player: str, port: int, token: str, output: Path) -> None:
    libraries: list[Path] = []
    pack: dict = json.loads((instance / 'mmc-pack.json').read_text())
    minecraft: dict = {}
    for component in pack['components']:
        document: dict = json.loads((prism / 'meta' / component['uid'] / (component['version'] + '.json')).read_text())
        if component['uid'] == 'net.minecraft':
            minecraft = document
        for library in document.get('libraries', []) + ([document['mainJar']] if 'mainJar' in document else []):
            if '-natives-macos:' in library['name']:
                continue
            rules: list[dict] = library.get('rules', [])
            allowed: bool = not rules
            for rule in rules:
                name: str = rule.get('os', {}).get('name', '')
                if name in ('', 'osx', 'osx-arm64'):
                    allowed = rule['action'] == 'allow'
            if not allowed:
                continue
            path: Path = maven_path(prism / 'libraries', library['name'])
            if not path.is_file():
                if '-natives-' in library['name'] or '-native-' in library['name']:
                    continue
                raise FileNotFoundError(path)
            if path not in libraries:
                libraries.append(path)
    java_home: str = subprocess.check_output(['/usr/libexec/java_home', '-v', '25'], text=True).strip()
    java: Path = Path(java_home) / 'bin/java'
    player_uuid: str = str(uuid.UUID(bytes=hashlib.md5(('OfflinePlayer:' + player).encode()).digest(), version=3))
    command: list[str] = [str(java), '-XstartOnFirstThread', '-Xms512m', '-Xmx3072m', '-XX:StackShadowPages=32', '-XX:TieredStopAtLevel=1', '--enable-native-access=ALL-UNNAMED',
        '-Dautomator.hidden=true', '-Dautomator.port=' + str(port), '-Dautomator.token=' + token,
        '-Dautomator.output=' + str(output), '-Djava.library.path=' + str(instance / 'natives'),
        '-cp', os.pathsep.join(str(path) for path in libraries), 'net.fabricmc.loader.impl.launch.knot.KnotClient',
        '--username', player, '--version', '26.3', '--gameDir', str(instance / '.minecraft'),
        '--assetsDir', str(prism / 'assets'), '--assetIndex', minecraft['assetIndex']['id'],
        '--uuid', player_uuid, '--accessToken', '0', '--versionType', 'release', '--width', '1920', '--height', '1080']
    with (output / ('client-' + player + '.log')).open('w') as log:
        subprocess.Popen(command, cwd=instance / '.minecraft', stdout=log, stderr=subprocess.STDOUT,
                         start_new_session=True)
