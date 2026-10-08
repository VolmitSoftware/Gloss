import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import zipfile


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument('--baseline', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    source: Path = Path(__file__).resolve().parent
    output: Path = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    api: Path = output / 'baseline-api.jar'
    with zipfile.ZipFile(args.baseline) as original, zipfile.ZipFile(api, 'w') as exported:
        for name in original.namelist():
            if name.startswith('art/arcane/gloss/api/') and '/internal/' not in name and name.endswith('.class'):
                exported.writestr(name, original.read(name))
    cache: Path = Path.home() / '.gradle/caches/modules-2/files-2.1'
    libraries: list[Path] = [api]
    for group, package, version in (
        ('io.papermc.paper', 'paper-api', '26.3.build.25-alpha'),
        ('net.kyori', 'adventure-api', '*'),
        ('net.kyori', 'adventure-key', '*'),
        ('net.kyori', 'examination-api', '*'),
        ('net.md-5', 'bungeecord-chat', '*'),
    ):
        candidates: list[Path] = sorted((cache / group / package).glob(version + '/*/*.jar'))
        libraries.append(next(p for p in reversed(candidates) if not p.name.endswith(('-sources.jar', '-javadoc.jar'))))
    classes: Path = output / 'classes'
    if classes.exists():
        shutil.rmtree(classes)
    classes.mkdir()
    subprocess.run(['javac', '-J-XX:ActiveProcessorCount=1', '--release', '25', '-parameters',
                    '-classpath', os.pathsep.join(str(p) for p in libraries), '-d', str(classes),
                    str(source / 'GlossIntegrationProbe.java')], check=True)
    shutil.copy2(source / 'plugin.yml', classes / 'plugin.yml')
    jar: Path = output / 'GlossIntegrationProbe.jar'
    subprocess.run(['jar', '--create', '--file', str(jar), '-C', str(classes), '.'], check=True)
    with zipfile.ZipFile(jar) as result:
        assert not any(n.startswith('art/arcane/gloss/api/') for n in result.namelist())
    provenance: dict = {'baseline': str(args.baseline.resolve()),
                        'baselineSha256': hashlib.sha256(args.baseline.read_bytes()).hexdigest(),
                        'probe': str(jar), 'probeSha256': hashlib.sha256(jar.read_bytes()).hexdigest(),
                        'compileClasspath': [str(p) for p in libraries], 'bundledApiClasses': False}
    (output / 'provenance.json').write_text(json.dumps(provenance, indent=2) + '\n')
    print(jar)


if __name__ == '__main__':
    main()
