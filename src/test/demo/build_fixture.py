import os
import shutil
import subprocess
from pathlib import Path

ROOT: Path = Path(__file__).resolve().parents[3]
OUTPUT: Path = ROOT / 'build/demo/fixture'
CACHE: Path = Path.home() / '.gradle/caches/modules-2/files-2.1'


def build() -> None:
    OUTPUT.mkdir(parents=True, exist_ok=True)
    libraries: list[Path] = [ROOT / 'build/libs/Gloss-3.2.0-26.2.jar']
    for group, package, version in (
        ('io.papermc.paper', 'paper-api', '26.3.build.25-alpha'),
        ('net.kyori', 'adventure-api', '*'),
        ('net.kyori', 'adventure-key', '*'),
        ('net.kyori', 'examination-api', '*'),
        ('net.md-5', 'bungeecord-chat', '*'),
    ):
        candidates: list[Path] = sorted((CACHE / group / package).glob(version + '/*/*.jar'))
        libraries.append(next(path for path in reversed(candidates) if not path.name.endswith(('-sources.jar', '-javadoc.jar'))))
    subprocess.run(['javac', '--release', '25', '-parameters', '-classpath', os.pathsep.join(str(path) for path in libraries), '-d', str(OUTPUT), str(ROOT / 'src/test/demo/plugin/GlossDemoPlugin.java')], check=True)
    shutil.copy2(ROOT / 'src/test/demo/plugin/plugin.yml', OUTPUT / 'plugin.yml')
    subprocess.run(['jar', '--create', '--file', str(OUTPUT.parent / 'GlossDemo.jar'), '-C', str(OUTPUT), '.'], check=True)


if __name__ == '__main__':
    build()
