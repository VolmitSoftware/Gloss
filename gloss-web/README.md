# Gloss Editor

Visual web editor for [Gloss](https://github.com/VolmitSoftware/Gloss) menu and display documents. It edits every field the plugin parses, previews each document the way the game draws it, and either exports files for `plugins/Gloss/` or publishes straight to a paired server.

The editor is a static site written in Dart with [arcane_jaspr](https://github.com/ArcaneArts/arcane_jaspr) and compiled to JavaScript. It has no accounts and no backend of its own.

## The editor

- **Look.** arcane_jaspr's shadcn Midnight theme (`GlossStylesheet` in `lib/config/gloss_stylesheet.dart`) with the library's radius and the Geist font. Gloss purple is the primary and ring colour (`#A354FF` in dark, `#7000EF` in light). The theme button switches light and dark.
- **Controls.** Every button, field, menu, dialog, sheet, tab and tooltip is an arcane_jaspr component, wrapped by the small kit in `lib/ui` (fields, sections, action rows, the stage frame, the save bar). Controls come in two heights: 32 px in the top bar, library, toolbars, inspector and status bar, 36 px in dialogs and sheets. A row of actions reads left to right as content controls, then `...`, secondary, Cancel and one primary button last.
- **Frame.** One top bar holds the File menu, the document title (rename in place), the view controls, Undo and Redo, Search (Mod+K), the Server button, the Simple and Advanced switch and the Help menu. The library is on the left, the inspector on the right, the status bar at the bottom (autosave, problems, zoom). At 1024 px and below the library and inspector open as drawers.
- **Library.** Search, a kind filter, folders, and documents under six kind groups: menus and screens, HUD and server list, chat, world, players, and shared resources. Each row has one actions menu, also on right-click; drag a row onto a folder to move it. Only the rows near the view are in the page, so a workspace of thousands of documents stays quick to type in. Arrow keys, Home and End move between rows; Left and Right close and open folders and groups.
- **Views.** Edit and Preview for menus and container previews, where the preview differs from the editing surface. Every other kind shows its stage once. Code shows the JSON with two-way sync and validation; Split shows the stage and the code together (Advanced, desktop widths). Names, strings, waypoints, glyphs, behaviors, presets and leaderboards edit as a form in the centre and have no inspector.
- **Simple and Advanced.** Simple hides expert fields, raw JSON blocks, Split, flow maps and the shared-resource kinds. Hidden fields keep their values: the editor writes single keys and never rebuilds a document from the fields it shows. Documents of those kinds that arrive in Simple (an import, a dropped file, a bundle, the server) are counted in one message with **Switch to Advanced**, which opens them. Workspaces made before the switch existed start in Advanced.
- **Help.** Field help sits behind an (i) button next to each label, generated from the plugin's JSON schemas and hand-written where a schema is silent. The guided tour, the shortcut list and Settings live in the Help menu.

## Previews

Every stage sits in one frame that scales its content to fit and grows with it; a preview never shows an inner scrollbar. Game screens (sidebar, tab list, server list, chat, boss bar, action bar, title, container windows) are drawn at the client's GUI scale. Menus, holograms, drops and world surfaces render in a WebGL2 block world built from the 26.2 client's models and textures (`tool/extract_minecraft_world_assets.dart`), with a textured player rig and the common mob rigs. A browser without WebGL2 shows a still image of the world instead.

The menu preview simulates the open pose, `followPlayer`, icon billboards, click planes, hover push and the nearest-click rule, and logs every action a click would run. The drop stage runs a port of the plugin's `RealDropModel`, including the `physics` and `script` blocks. Each stage names what it approximates in its readout: client text wrapping, lighting, occlusion and packet interpolation still need checking in game, and PlaceholderAPI tokens stay literal.

## Pairing a server

The editor opens on a start page. It leads with **Continue** once this browser keeps a paired server or a browser workspace with work in it: each server with its state (Online, Checking or Not reachable, checked again every 10 seconds while not reachable), its document and image counts, **Open**, and a `...` menu with **Forget server**; then the browser workspace. Below it are the **Pairing link** field with **Pair**, then **Import**, **Templates** and **New document** for the browser workspace. In the editor, **Pair a server** (the Server button while the workspace has no server, the File menu and the command palette) opens a dialog with the same field and the command to run.

1. On the server, in game or from the console, run `/gloss editor pair [label=] [scopes=a,b] [folders=a,b] [expires=7d]`. The link works once, for 10 minutes.
2. The link opens one card: the server's name, "Link works until HH:MM, once.", what the editor may do, **Browser name** and **Remember this browser** (on: every tab of this browser stays paired; off: the pairing ends with the tab). Advanced shows the fingerprint, the direct and relay addresses and the link name. The editor removes the code from the address bar once it read it.
3. **Pair** exchanges the link for a token and opens the server's own workspace in this browser. Every document is listed at once by name; bodies fill in the background ("Loading n of m" in the save bar) and a document opened before its turn is read first.
4. Changes made on the server (console, in game, files) reach an open document within about a second. Publish sends only what changed here; a document changed on both sides offers **Keep mine** and **Use server copy** in Review, and the save bar says it needs a choice. Review lists deletions under their own **Deleted** heading, and a publish that deletes many documents (more than 25, and more than a fifth of what the pairing reaches) asks first, naming the count.

A server's workspace in this browser is a copy of the server plus the edits made here. A browser that lost that copy, or kept it empty, reads the server again on the next open instead of counting the missing documents as deletions.

The editor reaches the server through the plugin's own listener (`http://127.0.0.1:9799/gloss/v1` by default) or through the relay the server dials out to, whichever answers. When one path drops it moves to the other without the save bar changing, and moves back to the direct path by itself. A server that stops answering reads Not reachable until it is back, in every tab of the browser, without alternating words while the editor retries. A card whose relay does not answer says so and pairs over the direct address; `/gloss editor status` names an unreachable relay the same way. Over the relay the server proves its key before the editor sends a secret or a token. A pairing lasts as long as it is used (`tokenDays`, 7 by default); within three days of its end the start page and the Server sheet say "Pairing ends in n days unless used." A revoked or expired pairing shows "Pairing ended." with a **New link** field. Only one tab of a browser edits a workspace: another tab shows "Open in another tab." with **Take over** and takes over by itself when the first one closes. Each paired server has its own workspace, so pairing never replaces the browser workspace.

## What leaves the browser

Authoring, autosave (IndexedDB), import, export and the workspace stay in the browser. The editor makes outbound requests in two cases only:

- Fetching a player head by username sends that name to `https://minotar.net/skin/<name>.png`, or `https://mc-heads.net/skin/<name>` when minotar does not answer.
- A paired server's workspace talks to that server's listener or its relay. The pairing token stays in `localStorage` (Remember this browser) or the tab's `sessionStorage`, never in documents or exported bundles.

The preview player's skin and cape are baked into `web/assets/mc/` when the asset pack is built; the site makes no request for them.

## Development

Run editor commands from `gloss-web/` inside a Gloss checkout. Use Dart 3.13.5 and the `arcane_jaspr` revision pinned in [the Hosting workflow](../.github/workflows/gloss-web-firebase-hosting.yml), checked out (or symlinked) at `gloss-web/.deps/arcane_jaspr`. That checkout supplies the core and the shadcn package.

```bash
cd gloss-web
git clone https://github.com/ArcaneArts/arcane_jaspr .deps/arcane_jaspr
ARCANE_JASPR_REF="$(sed -n 's/^  ARCANE_JASPR_REF: //p' ../.github/workflows/gloss-web-firebase-hosting.yml)"
git -C .deps/arcane_jaspr checkout "$ARCANE_JASPR_REF"
dart pub get --enforce-lockfile
dart analyze
dart test                                   # editor logic and Gloss contracts
node --test test/channel_filters_engine_test.cjs
dart run tool/update_localizations.dart     # after changing UI strings; --check validates
dart run jaspr_cli:jaspr serve              # dev server on :8080
dart run jaspr_cli:jaspr build              # static output in build/jaspr/
```

A new UI string needs a translation in every `l10n/<locale>.json`; the update tool copies the catalogs to `web/languages`. Contract tests and catalog generators read the parent Gloss repository (`..`); set `GLOSS_REPOSITORY` to test against another checkout. The relay is a separate Dart package with its own `dart pub get --enforce-lockfile`, `dart analyze` and `dart test` in `sync-relay/`.

`tool/fake_server` is an in-memory Gloss server for development and tests: the editor API at `/gloss/v1`, the browser side of the relay at `/app` and test controls at `/__fake/`. `dart run tool/fake_server/main.dart --port 9806 --editor-url http://127.0.0.1:8808 [--data plugins/Gloss]` prints a pairing link; `dart test` runs the editor against it in-process.

### Browser checks

The browser scripts need Playwright with its Chromium (set `PLAYWRIGHT_MODULE` when it is not importable as `playwright`), a served build and, for the pairing screens, the fake Gloss server:

```bash
python3 -m http.server 8804 --bind 127.0.0.1 --directory build/jaspr &
dart run tool/fake_server/main.dart --port 9804 --editor-url http://127.0.0.1:8804 &
export GLOSS_WEB_URL=http://127.0.0.1:8804 GLOSS_FAKE_SERVER=http://127.0.0.1:9804
node test/button_uniformity_browser.cjs   # one button height per part, rows that never wrap
node test/preview_fit_browser.cjs        # previews fit their frame with no inner scrollbar
node test/nested_cards_browser.cjs       # no bordered box inside another
```

Each of the three walks every screen at 1600, 768 and 390 px in Advanced mode: dialogs, menus, drawers, every kind in each of its views, the start page, the Pair a server dialog and, with the fake server, the pairing card, a server's workspace loading, connected, changed, in conflict, open in another tab, offline and ended, with its Server sheet (the names are `PAIRING_SCREENS` in `test/support/screens.cjs`). Each prints a count per screen and exits 0 only at 0. `GLOSS_THEME=light` and `GLOSS_MODE=simple` run the walk in the light theme and in Simple. `GLOSS_<BUTTONS|FIT|CARDS>_WIDTHS`, `_SCREENS` (a regular expression) and `_LIST=1` narrow and expand a run; `GLOSS_SCREENSHOTS=<dir>` saves a picture of each screen.

`test/css_scan_test.dart` (part of `dart test`) keeps the stylesheets in `web/styles` honest: no rule selects an arcane_jaspr class or attribute, colour literals appear only in the files that draw Minecraft itself, and the total stays under its line ceiling.

The feature scripts (`behavior`, `behavior_authoring`, `channel_filters`, `dialog_inventory`, `export_paths`, `glyph`, `motd`, `nameplate_relationship`, `number_field`, `scoreboard`, `surface_subscriptions`, `surfaces_presets`, `tablist_layout`, `transient_policy`, each `test/<name>_browser.cjs`) need only `GLOSS_WEB_URL`. `test/simple_import_browser.cjs` also pairs with the fake server when `GLOSS_FAKE_SERVER` is set, and with a second fake that holds only shared resources when `GLOSS_FAKE_ADVANCED_ONLY` is set. `test/sync_scale_browser.cjs` pairs with the fake server holding a server-size data folder (`--data`) and reports pairing, idle traffic, opening, a server edit, typing, publish and restart timings over both paths; `GLOSS_PAIR_LINK` points it at a live server's link instead, read only. The [demo scripts](test/demo/README.md) record walkthroughs.

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

The old local directory can be deleted after verification. This workflow does
not deploy the relay. Its source lives in [`sync-relay/`](sync-relay/README.md),
which also says [how the default relay is deployed](sync-relay/README.md#cloud-run).
A plugin and its relay speak editor API 1. No editor release artifact is
required: `/gloss editor pair` links open the hosted editor.


## Architecture

```
lib/model/        Gloss menu JSON data model + codec (runtime semantics, unknown-key preserving)
lib/doctype/      per-kind DocumentTypeAdapter registry (codec, views, capabilities, templates, inspector)
lib/logic/        validation rules, Minecraft text parser, viewport math, hitbox geometry
lib/mc/           Minecraft asset pack index and model resolver, block/item meshers, entity rigs, world scene and the shared camera
lib/services/     browser storage, image library, catalogs, file transfer, server connection, pairing and sync controller
lib/state/        EditorStore, IndexedDB persistence, multi-doc workspace and menu flow maps
lib/config/       stylesheet, kind groups (labels, tips, Advanced flags), field docs, catalogs
lib/ui/           the kit: arcane_jaspr components wrapped for the editor's sizes and action order
lib/components/   shell (top bar, library, status bar, palette, tour), stages, inspectors, dialogs, Server sheet
web/styles/       app CSS on shadcn tokens: kit layout, frame, and the game-drawn surfaces
web/assets/       fonts (Geist, lucide, Minecraft), item/sound catalogs, brand
web/assets/mc/    extracted Minecraft asset pack: block and item atlases, models, entity, player and HUD textures
tool/             asset extraction, localization and catalog tools
sync-relay/       the relay servers dial out to (separate Dart package)
tool/fake_server/ an in-memory Gloss server for tests and local development
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
