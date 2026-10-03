# Gloss native demonstration rig

Reusable current-format documents live in `configs/`; `shots.json` defines the stage, actual interactions, and camera positions. The fixture plugin invokes Gloss’s public beam API and sets subject permissions through Bukkit attachments. Identity shot changes wait for the actual document registry revision before proceeding. Retired authoring features are excluded.

The rig reuses the installed Wormholes 26.3 hidden SDL client bridge and Multiplexor. It creates one isolated Paper server in the permitted Minecraft Server workspace and uses only the synthetic GlossGuide and GlossVisitor profiles. Java 25, Python 3, FFmpeg, the Prism 26.3 client libraries, and the rebuilt client bridge are required.

From the Gloss root:

```sh
../WormholesPlugin/src/test/client/build.sh
./gradlew build
python3 src/test/demo/build_fixture.py
python3 src/test/demo/studio.py initialize
python3 src/test/demo/studio.py connect
python3 src/test/demo/studio.py visitor
python3 src/test/demo/director.py install
python3 src/test/demo/director.py record --only inventory-menus
python3 src/test/demo/director.py record --only damage-healing --perspective observer
```

`install` copies the fixtures, enables channels, connections and MOTD, stages the garden, and retains `/gloss check workspace` output. Authored document changes use the normal file watcher. Clips use actual native input and framebuffer rendering. World shots support POV and observer cameras; player-local interfaces use POV. No desktop window or pointer is captured. The client must report an advancing hidden 1920×1080 framebuffer with no window visibility, focus or mouse grab before recording.

All captures, credentials, logs, manifests, review frames and discarded takes remain ignored under `build/demo`. Review the start, action and end frames plus the continuous sequence. `accept --only <id> --evidence <review>` copies a reviewed WebM to the central documentation assets; completed captures remain pending until explicitly accepted. The manifest records the exact file hash and measured native frame counts. WebM exports are silent, fully decoded and bounded below 25 MB.

```sh
python3 src/test/demo/director.py accept --only inventory-menus --evidence 'Native inventory, tooltip, click result and close reviewed'
python3 src/test/demo/docs_sync.py
python3 src/test/demo/studio.py close
```

`docs_sync.py` embeds available accepted clips into their central usage pages. Repeating it leaves unchanged pages untouched; `--only <id>-pov` or `--only <id>-editor` limits the selected chapters.

`close` waits for owned clients to exit, stops the owned server, preserves its world and Gloss scene documents under `build/demo/scene-cache`, and deletes only that disposable instance. Persistent Gloss profiles and the installed client rig remain available.
