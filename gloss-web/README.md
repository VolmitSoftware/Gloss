# Gloss Editor

Visual web editor for [Gloss](https://github.com/VolmitSoftware/Gloss) menu and display configurations. Build holographic menus on a calibrated canvas, edit every field the plugin actually parses, and export documents and images that drop straight into `plugins/Gloss/`.

Version 4.0.1 — a from-scratch rewrite in [arcane_jaspr](https://github.com/ArcaneArts/arcane_jaspr) (Dart, compiled to a fully static site). Replaces the retired Next.js editor.

## Features

- **Visual canvas** in block space: zoom/pan, grid + snapping, drag components, selection, keyboard nudge. Uses the runtime's documented text-line scale, `uiScale` semantics and hitbox geometry while clearly separating approximations that depend on the Minecraft client.
- **Linked button hitboxes**: text and image click planes follow the visible render, while an optional custom width and height can replace automatic icon-derived sizing without creating an independent position that can drift away.
- **Rendered world and rigs**: every preview stage draws in a WebGL2 block world built from real block and item models and textures extracted from the 26.2 client into `web/assets/mc/26.2` by `tool/extract_minecraft_world_assets.dart`. The player renders as a textured rig wearing Magic_Psycho's skin and cape; zombies, skeletons, creepers, pigs, cows and sheep are rigged too; every other mob is a catalog sprite billboard. Animated textures show their first frame, block-entity items (chests, banners, heads, shulker boxes, decorated pots, tridents, shields, conduits) draw as their sprite, and tints use plains constants. A browser without WebGL2 shows a rendered still of the world and sprites in place of models.
- **Runtime 3D preview**: the Preview view's game-screen frame renders inside the same block world every stage shares, simulating the open pose, `followPlayer` position and yaw, fixed / vertical / horizontal / center icon billboards, click planes, hover push and nearest-click behavior. Client text wrapping, culling, brightness, effects, obstruction and competition between personal menus and boards still require in-game validation. PlaceholderAPI tokens remain literal because the browser has no server context; the preview reports their `refreshTicks` cadence instead.
- **Full format coverage** as the plugin's Gson actually reads it — all three component types (button / decoration / toggle), all eight authorable icon types (text / textImage / animatedTextImage / item / block / customItem / entity / playerHead), all six actions (command / sound / message / teleport / connect / navigate), toggle conditions, `maxDistance`, `closeOnDeath`, `closeOnTeleport`, `customModelValue` (the real key — the `customModelData` older files carry is migrated on import).
- **Kind modes**: top-bar tabs — `All` plus one per document kind — scope the shell to a single kind, narrowing the library rail and its heading count, defaulting creation to that kind and naming the kind in empty states. The mode persists and follows the documents you open. Workspace-bundle import/export and erase-all live in a labelled Workspace actions menu on the rail instead of the creation row.
- **Four views, every kind**: Visual (the kind's own editing surface), Preview (that surface framed in a Minecraft game screen — sidebar against the client's right edge, tablist overlay, server-list MOTD, bubbles and damage indicators over an entity, emoji in chat, holograms and animations in-world), Code (two-way JSON sync) and Split. The choice is remembered per kind, and a kind that cannot use a view says why rather than hiding the button. Validated menu handoffs retain their exact source formatting and extension keys through storage and export until the first visual edit or explicit Format action.
- **Damage-indicator stage**: authors the complete `damage-indicators/default.json` singleton and previews damage or healing independently on an interactive target. Each selected type loops automatically, advances through deterministic seeded trajectories at the authored lifetime, and retains replay and pause controls while using the runtime's same closed-form trajectory, spin, scale and fade equations. A damage-only Critical hit control previews the runtime's exact Paper critical flag and its conditional variants. The sample supplies the complete event, subject, source and viewer role context, and a false condition produces a readable state instead of an unexplained blank stage. Randomize procedurally replaces the complete singleton JSON with valid varied admission limits, conditional base styles, critical-damage and large-heal variants and a preview-visible per-viewer audience condition as one undoable edit. The stage validates the document math and presentation contract; Minecraft TextDisplay rasterization, depth, occlusion, tracking and packet interpolation still require in-game validation.
- **Drop stage**: dropped stacks render in the shared block world, thrown forward, tumbling, bouncing and settling under the settings document through a port of the plugin's own `RealDropModel` — displays per stack from the stack size, the offset table, the scale family a material belongs to, the configured tumble rates with their variance and bounce re-rolls, the settled landing pose, the authored ground clearances, and the `&7{count}x {type}` label, drawn in the CSS-3D text layer, with its real background, shadow and see-through depth. Resting geometry follows `RealDropModel.blockGeometry` for every material: a cube draws the block's real model, a flat item the item's extruded texture. Pose changes are handed to the client's own interpolation windows, so a coarse `updateIntervalTicks` reads as coarse here too. Left alone the stage cycles one stack per model family — a cube, a flat item and a slab — changing on every completed drop; the shuffle button steps out of that rotation and through the whole themed sample table.
- **Scripted and physical drops**: the two optional document blocks both run on the stage, and the stage keeps them apart the way the format does. `physics` moves the item — `gravityMultiplier`, `bounce`, `waterBuoyancy` and `waterDrag` are applied to the stage's trajectory with the plugin's own constants, so gravity 0 really does hang the stack and bounce 0 really does land and stick. `script` moves only the picture: expressions are compiled with the same engine the container previews use, `vars` run first in declaration order, and the result composes onto what the document already computed — `offset` adds blocks, `rotation` composes onto the pose before the resting-height clearance is recomputed, `scale` multiplies the family per axis, `glow` traces an outline and `visible` stops drawing a display without removing the drop. A broken expression is refused with the server's own sentence, field and character position included, whether or not the script is switched on — because that is when the server refuses it. The inspector edits expressions in a monospace field that shows the parse error under the character that caused it, and the `vars` list adds, removes and reorders without ever sorting, because declaration order decides what each entry can read.
- **What the drop stage approximates**, all four named in its readout: the ballistics (Minecraft's `Item` entity owns the real ones; the throw keeps the shape of `Player.drop` but at a distance the stage can frame, and the `physics` block bends that arc rather than the server's); the models, drawn from the client's own asset pack the same way every stage draws them — a placeable sample is its block model, everything else the item model at the fixed pose, animated textures show their first frame, and a material the resolver has no geometry for (block-entity items among them) draws as its sprite; the four script variables a browser has no world to read — `inWater` is a water button that floods the stage so a buoyancy script has something to act on, `inLava` is always false, and both light levels are 15; and the glow outline, a second full-mesh outline pass rather than the client's own silhouette-through-blocks pass.
- **Drop stage camera**: every stage shares one orbit-and-fly camera — drag to orbit, right-drag to pan, wheel to dolly, WASD to fly with space and shift for height while the stage is focused, and a Reset view control that puts the default framing back. Pitch is clamped above the ground and short of straight down, and every offset is bounded, so the subject cannot be lost off screen. The stage takes the keyboard only while it is focused and drops held keys on blur, so typing elsewhere is never stolen. The same controller drives the game-screen frame with flying disabled: that frame stands in for the client's own view.
- **Themed sample content**: every generated document, mock tablist and canned chat line comes from one themed pool in `config/showcase_flavor.dart`, so a screenshot reads as somebody's server rather than as lorem ipsum. Randomizing picks a mood and derives the whole document's colour, glyphs and motion from it, over an effect library — breathing and sweeping gradients, stepped palettes, strobes, tickers, a scanline, a typewriter and a per-character wave — all written as pipeline text a person could have typed. Menu randomization chooses a complete network hub, named-destination wayfinder, player-tools panel or workspace navigator, with one consistent style and labels tied to exact actions instead of assembling unrelated field samples. Tablist randomization chooses among compact, welcome, status, event, community and staff structures, then independently composes bounded header/footer rows, animation recipes and group-name formats instead of repainting one preset.
- **Inspector help**: every field carries a note — generated from the plugin's JSON schemas, hand-written where a schema is silent or hides a runtime trap, each hand-written body citing the Gloss source line that proves it. Defaults show with one-click reset, sections collapse and remember it, colours get a picker, and tick/millisecond fields show a live seconds readout.
- **Validation** engine aligned with the plugin's authoring contract (lowercase registry keys, action source spellings, silent-zero pitfalls like `volume: 0`, legacy and MiniMessage formatting, hitbox overlap warnings).
- **Image library**: upload validated PNG pixel art, preview with the plugin's rasterization rules, export `images.zip` laid out for `plugins/Gloss/images/`. Player heads import either from a skin PNG you pick or from a typed username, fetched from a public skin CDN and composited through the same face-plus-hat path so both routes produce the identical 8x8 asset. Server sync preserves captured PNG, JPEG, GIF, WebP and BMP bytes losslessly.
- **Workspace**: folders, multiple menus and menu flow maps, atomic IndexedDB autosave with previous-transaction recovery and cross-tab conflict protection, undo/redo, templates, and searchable catalogs. Renaming a runtime id updates navigation, linked world-panel roots and sync scope as one checked save; dropped JSON opens as a uniquely named document instead of replacing the active one. Existing `holoui.workspace.v1` / `v2` localStorage data migrates once without deleting the rollback copy.
- **Optional server sync**: `/gloss web edit <kind> <id>` opens a focused capability and `/gloss web workspace` connects the complete server workspace through the configured v3 relay. Workspace sync round-trips all twelve runtime document kinds and every image asset; explicit publication creates, updates, and deletes server resources to match the browser while unlinked flow maps remain local. A labelled **Publish to Server** action appears during a live session, and revision conflicts never overwrite local work. Links use the configured HTTPS relay at `https://sync.gloss.volmitsoftware.com/v3` or a localhost HTTP development relay.

The editor itself remains a static client with no accounts, and it makes outbound requests in exactly two situations. Asking the image manager for a head by username sends that username, and nothing else, to `https://minotar.net/skin/<name>.png` — or to `https://mc-heads.net/skin/<name>` when minotar does not answer; no request is made until you type a name and press **Fetch head**. Opening a server-issued capability link enables bounded HTTPS requests to that link's relay until the tab disconnects. Everything else — authoring, autosave, import, export and the whole document workspace — stays local to the browser. The 3D previews' player rig wears Magic_Psycho's skin and cape, resolved once through the Mojang profile API when the asset pack is built and baked into `web/assets/mc/`; the running site makes no request for it. Capability tokens stay in the URL fragment and tab `sessionStorage`, never in workspace documents or exported bundles. If tab storage is blocked, the capability stays in the fragment and the sync bar tells you to copy the link before reloading.

Workspace sync is an exact create/update/delete mirror. Focused sessions retain their immutable v3 resource constraints. The editor validates kind-specific runtime ids, typed panel JSON, image bytes, constraints, and document/content revisions before publishing. Workspace assets allow up to 4096 pixels per dimension, 16,777,216 decoded pixels per image, and 67,108,864 decoded pixels in aggregate; only menu-referenced text-image assets use the stricter 16×16 and render-work budgets. The protocol safety ceiling is 32 MiB per project; a relay deployment may enforce a lower configured limit and return `413`.

Workspace bundles and server projects persist documents and workspace-scoped images in IndexedDB. Import and refresh use checked compensation when either queued write fails.
- **Component motion**: the menu inspector edits a component's `motion` block, validates its expressions and steps it once per tick in True render preview, matching where the Gloss runtime places the component.

## Development

Run editor commands from `gloss-web/` inside a Gloss checkout. The plugin and
editor build independently; Gradle does not invoke Dart. Use Dart 3.13.5 and
the `arcane_jaspr` revision pinned in
[the Hosting workflow](../.github/workflows/gloss-web-firebase-hosting.yml),
checked out at `gloss-web/.deps/arcane_jaspr`. That checkout supplies the core,
Ascii theme, and shared renderers. A symlink to a local clone at the same
revision also works.

```bash
cd gloss-web
git clone https://github.com/ArcaneArts/arcane_jaspr .deps/arcane_jaspr
ARCANE_JASPR_REF="$(sed -n 's/^  ARCANE_JASPR_REF: //p' ../.github/workflows/gloss-web-firebase-hosting.yml)"
git -C .deps/arcane_jaspr checkout "$ARCANE_JASPR_REF"
dart pub get --enforce-lockfile
dart analyze
dart test                           # editor logic and parent Gloss contracts
node --test test/channel_filters_engine_test.cjs
dart run jaspr_cli:jaspr serve      # dev server on :8080
dart run jaspr_cli:jaspr build      # static output in build/jaspr/
dart run tool/extract_minecraft_world_assets.dart --jar=/path/to/client.jar --version=26.2 --player=Magic_Psycho
```

Contract tests and catalog generators read the parent Gloss repository (`..`)
by default. Set `GLOSS_REPOSITORY` to an absolute path to test against another
Gloss checkout. The Node contract/browser scripts also accept the legacy
`GLOSS_REPO` alias. No sibling `HUI-Web-Editor` checkout is needed.

The relay is a separate Dart package. From `gloss-web/`:

```bash
cd sync-relay
dart pub get --enforce-lockfile
dart analyze
dart test
```

For browser smoke tests, install Playwright outside the source tree or in an
ignored `node_modules/`, install its Chromium browser, and set
`PLAYWRIGHT_MODULE` if it is not available as `playwright`. Serve the static
build, then run, for example:

```bash
# In a separate terminal, from gloss-web:
python3 -m http.server 8098 --bind 127.0.0.1 --directory build/jaspr
# From gloss-web, with Node 22.23.1:
EDITOR_URL=http://127.0.0.1:8098 node test/transient_policy_browser.cjs
```

The browser scripts exercise document import/export and editing. The
[demo scripts](test/demo/README.md) also check preview interactions and live
server sync with a private test-server session.

## Releasing

[gloss.volmitsoftware.com](https://gloss.volmitsoftware.com/) is deployed from
the Firebase project and Hosting site `gloss-editor`.
[`.github/workflows/gloss-web-firebase-hosting.yml`](../.github/workflows/gloss-web-firebase-hosting.yml)
validates pull requests to `master` and deploys pushes to `master` when any of
these paths change:

- `gloss-web/**`
- `.github/workflows/gloss-web-firebase-hosting.yml`
- `src/main/**`
- `src/test/resources/**`
- `schema/**`

Unrelated root documentation changes do not trigger it. Manual dispatch
validates any selected branch and deploys only `master`. Validation resolves
locked dependencies, analyzes and tests both Dart packages, runs the Node
channel-filter contract suite, and builds the static editor. Tests read the
same Gloss commit as the editor. Deployment downloads that verified build,
publishes Hosting, and verifies its JavaScript SHA-256 through the custom
domain. Tool versions remain pinned in the workflow, including Node 22.23.1
and Firebase CLI 15.25.1.

The workflow requires `FIREBASE_SERVICE_ACCOUNT_GLOSS_EDITOR` in the **Gloss**
repository secrets or its `production` environment. Its value is the existing
Firebase service-account JSON authorized to deploy Hosting for `gloss-editor`.
GitHub cannot reveal a stored secret; use the original JSON file or a replacement
key for the deployment service account. With that file stored outside Git:

```bash
gh secret set FIREBASE_SERVICE_ACCOUNT_GLOSS_EDITOR \
  --repo VolmitSoftware/Gloss < /secure/path/gloss-editor-service-account.json
```

For the initial cutover:

1. Configure that secret, then push the migration to Gloss's `master` branch.
2. Confirm the Gloss workflow completes, including **Verify deployed bundle**.
   Smoke-test loading, document import/export, previews, and an owned server's
   sync connection at the existing domain.
3. After the successful deployment, disable the old repository's Hosting
   workflow to prevent competing deployments:

   ```bash
   gh workflow disable firebase-hosting.yml --repo VolmitSoftware/HUI-Web-Editor
   ```

The old local directory can be deleted after verification. The migration does
not redeploy the relay: its hosting, credentials, persistent data, and
`https://sync.gloss.volmitsoftware.com/v3` endpoint remain in place. Its source
now lives in [`sync-relay/`](sync-relay/README.md). No editor release artifact is
required: `/gloss web edit` and `/gloss web workspace` use the hosted editor.


## Architecture

```
lib/model/        Gloss menu JSON data model + codec (runtime semantics, unknown-key preserving)
lib/doctype/      per-kind DocumentTypeAdapter registry (codec, views, capabilities, templates, inspector)
lib/logic/        validation rules, Minecraft text parser, viewport math, hitbox geometry
lib/mc/           Minecraft asset pack index and model resolver, block/item meshers, entity rigs, world scene and the shared camera
lib/services/     browser storage, image library, catalogs, file transfer, clipboard
lib/state/        EditorStore, IndexedDB persistence, multi-doc workspace and menu flow maps
lib/components/   shell (top bar, shortcuts, palette), canvas, inspector, panels, dialogs
web/assets/       fonts (Geist, lucide, Minecraft), item/sound catalogs, brand
web/assets/mc/    extracted Minecraft asset pack — block/item atlases, models, entity, player and HUD textures
tool/             asset extraction scripts
```

## Plugin contract

The parent **Gloss** plugin is authoritative for formats: every contract is
derived from its Java parsing code (`art.arcane.gloss.*` via VolmLib's Gson
setup). When prose and code disagree, the editor follows the code. Plugin and
editor changes can now land in the same commit, with both suites checking the
same repository.

[`test/cross_repo_fixture_pins_test.dart`](test/cross_repo_fixture_pins_test.dart)
inventories shared expression vectors, canonical sync JSON, preview variables,
and golden snapshots. Defaults, preview cards, templates, and generated
catalogs are additionally checked by `preview_templates_test.dart`,
`templates_test.dart`, `gloss_templates_test.dart`, and `emoji_catalog_test.dart`.
Change the authoritative plugin resource first, then refresh or regenerate its
editor copy and embedded Dart constant. The tests compare against the parent
repository; do not edit mirrored fixtures just to make a test pass.
