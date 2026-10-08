import argparse
import importlib.util
import json
import os
import secrets
import shutil
import subprocess
import time
from pathlib import Path
from launch_client import launch

ROOT: Path = Path(__file__).resolve().parents[3]
OUTPUT: Path = ROOT / "build/demo"
os.environ["WORMHOLES_DEMO_OUTPUT"] = str(OUTPUT)
os.environ["WORMHOLES_DEMO_PROFILE_PREFIX"] = "Gloss Demo"
spec = importlib.util.spec_from_file_location("minecraft_rig", ROOT.parent / "WormholesPlugin/src/test/demo/studio.py")
rig = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rig)
ACTOR: str = "GlossGuide"
VISITOR: str = "GlossVisitor"
def profile_running(instance: Path) -> bool:
    processes: str = subprocess.check_output(['ps', '-axo', 'args='], text=True)
    return any(str(instance) in line and ('org.prismlauncher.EntryPoint' in line or 'net.fabricmc.loader.impl.launch.knot.KnotClient' in line) for line in processes.splitlines())


def initialize() -> None:
    OUTPUT.mkdir(parents=True, exist_ok=True)
    name: str = "gloss-demo-" + str(int(time.time())) + "-" + secrets.token_hex(2)
    port: int = rig.free_port()
    rig.mux("server", "create", name, "--type", "paper", "--mc", "26.3", "--isolated")
    rig.mux("instance", "port", name, str(port))
    rig.mux("gameplay", "prepare", name)
    server: Path = Path(rig.mux("instance", "path", name).splitlines()[-1])
    properties: dict[str, str] = dict(line.split("=", 1) for line in (server / "server.properties").read_text().splitlines() if "=" in line and not line.startswith("#"))
    properties.update({"level-type": "minecraft:flat", "generate-structures": "false", "level-seed": "4817362", "view-distance": "8", "simulation-distance": "4", "motd": "Gloss demonstration studio", "enable-rcon": "true", "rcon.port": str(rig.free_port()), "rcon.password": secrets.token_hex(20), "generator-settings": json.dumps({"biome": "minecraft:plains", "layers": [{"block": "minecraft:bedrock", "height": 1}, {"block": "minecraft:stone", "height": 64}, {"block": "minecraft:dirt", "height": 4}, {"block": "minecraft:grass_block", "height": 1}]}, separators=(",", ":"))})
    (server / "server.properties").write_text("".join(key + "=" + value + "\n" for key, value in properties.items()))
    shutil.copy2(ROOT / "build/libs/Gloss-3.2.2-26.2.jar", server / "plugins/Gloss.jar")
    if (OUTPUT / "GlossDemo.jar").is_file():
        shutil.copy2(OUTPUT / "GlossDemo.jar", server / "plugins/GlossDemo.jar")
    state: dict = {"instance": name, "path": str(server), "port": port}
    (OUTPUT / "server.json").write_text(json.dumps(state))
    rig.mux("runtime", "start", name, "--no-console")
    deadline: float = time.monotonic() + 180
    log: Path = server / "logs/latest.log"
    while time.monotonic() < deadline:
        text: str = log.read_text(errors="replace") if log.is_file() else ""
        if "Done (" in text:
            print("Server ready: " + name, flush=True)
            return
        time.sleep(1)
    raise TimeoutError(text[-5000:])


def connect(number: int = 1, player: str = ACTOR) -> None:
    server: dict = json.loads((OUTPUT / "server.json").read_text())
    sessions: list[dict] = json.loads((OUTPUT / "clients.json").read_text()) if (OUTPUT / "clients.json").exists() else []
    sessions = [session for session in sessions if session["player"] != player]
    for number, player in ((number, player),):
        port, token = rig.client_credentials(number)
        bridge = rig.Bridge(port, token)
        try:
            state: dict = bridge.state(timeout=2)
            if state.get('player') == player and state.get('connected'):
                rig.fit_hidden_renderer(bridge)
                print('Existing hidden client ready: ' + player, flush=True)
                return
        except OSError:
            pass
        existing: Path = rig.PRISM / 'instances' / ('Gloss Demo - ' + str(number))
        if profile_running(existing):
            raise RuntimeError('Owned profile process must exit before replacing its loaded mod jars')
        instance: Path = rig.prepare_client(number, port, token, "standard")
        disabled: Path = OUTPUT / 'client-mod-cache' / ('Gloss Demo - ' + str(number))
        disabled.mkdir(parents=True, exist_ok=True)
        for mod in (instance / '.minecraft/mods').glob('*.jar'):
            if mod.name != 'InstanceAutomator.jar' and not mod.name.startswith(('fabric-api-', 'sodium-fabric-')):
                shutil.move(str(mod), disabled / mod.name)
        rig.update_config(instance / 'instance.cfg', {
            'OverrideJavaLocation': 'true',
            'JavaPath': '/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home/bin/java',
            'JavaVersion': '25.0.2'})
        options: Path = instance / ".minecraft/options.txt"
        options.write_text("\n".join("chatOpacity:1.0" if line.startswith("chatOpacity:") else "chatBackgroundOpacity:0.5" if line.startswith("chatBackgroundOpacity:") else line for line in options.read_text().splitlines()) + "\n")
        shaders: Path = instance / '.minecraft/config/iris.properties'
        if shaders.exists():
            shaders.write_text('\n'.join('enableShaders=false' if line.startswith('enableShaders=') else line for line in shaders.read_text().splitlines()) + '\n')
        sessions.append({"instance": str(instance), "port": port, "token": token, "player": player})
        (OUTPUT / "clients.json").write_text(json.dumps(sessions))
        launch(rig.PRISM, instance, player, port, token, OUTPUT)
        deadline: float = time.monotonic() + 180
        while time.monotonic() < deadline:
            try:
                current: dict = bridge.state(timeout=2)
                if current.get('connected'):
                    break
                if current.get('screen', '').endswith('TitleScreen'):
                    bridge.command('connect', address='127.0.0.1:' + str(server['port']))
            except (OSError, ValueError):
                pass
            time.sleep(0.5)
        else:
            raise TimeoutError("Hidden client did not connect")
        state: dict = rig.fit_hidden_renderer(bridge)
        start_frames: int = state["frames"]
        time.sleep(1)
        state = bridge.state()
        rig.verify_hidden_renderer(state)
        if state["frames"] <= start_frames:
            raise AssertionError("Hidden renderer is not advancing")
        (OUTPUT / "hidden-renderer.json").write_text(json.dumps({key: value for key, value in state.items() if key in rig.HIDDEN_RENDERER_STATE or key in ("frames", "frameWidth", "frameHeight")}))
        print("Hidden 1920x1080 client ready: " + player, flush=True)


def runtime():
    state: dict = json.loads((OUTPUT / "server.json").read_text())
    session: dict = next(session for session in json.loads((OUTPUT / "clients.json").read_text()) if session['player'] == ACTOR)
    return Path(state["path"]), rig.Rcon(Path(state["path"])), rig.Bridge(session["port"], session["token"])


def close() -> None:
    state: dict = json.loads((OUTPUT / "server.json").read_text())
    for session in json.loads((OUTPUT / "clients.json").read_text()):
        bridge = rig.Bridge(session["port"], session["token"])
        try:
            if bridge.state(timeout=2).get("capturing"):
                bridge.command("capture", action="stop")
            bridge.command("quit")
        except OSError:
            pass
    deadline: float = time.monotonic() + 30
    instances: list[Path] = [Path(session["instance"]) for session in json.loads((OUTPUT / "clients.json").read_text())]
    while any(profile_running(instance) for instance in instances) and time.monotonic() < deadline:
        time.sleep(0.5)
    if any(profile_running(instance) for instance in instances):
        raise RuntimeError("Owned client process did not exit before cleanup")
    rig.mux("runtime", "stop", state["instance"])
    shutil.copy2(Path(state["path"]) / "logs/latest.log", OUTPUT / "server-latest.log")
    scene: Path = OUTPUT / 'scene-cache'
    scene.mkdir(parents=True, exist_ok=True)
    for world in ('world', 'world_nether', 'world_the_end'):
        source: Path = Path(state['path']) / world
        if source.is_dir():
            shutil.make_archive(str(scene / world), 'gztar', root_dir=state['path'], base_dir=world)
    shutil.copytree(Path(state['path']) / 'plugins/Gloss', scene / 'Gloss', dirs_exist_ok=True)
    (scene / 'identity.json').write_text(json.dumps(state, indent=2))
    rig.mux("instance", "delete", state["instance"])
    print("Owned runtime removed; framebuffer feeds closed", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=("initialize", "connect", "visitor", "close"))
    arguments = parser.parse_args()
    {"initialize": initialize, "connect": connect, "visitor": lambda: connect(2, VISITOR), "close": close}[arguments.action]()
