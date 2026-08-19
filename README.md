<div align="center">

<img src="images/icon/gloss-256.png" alt="Gloss" width="128"/>

# Gloss

Server polish and display suite: holograms, scoreboards, tablist, emoji chat, chat bubbles and damage indicators — in one plugin.

</div>

## Features

- **Holograms** — TextDisplay-based floating text with per-player placeholder rendering, hotloadable JSON files, text-to-block-art rendering and full command management.
- **Scoreboards** — JSON-defined sidebars with primary/world defaults, permission-gated boards and per-group defaults.
- **Tablist** — configurable header/footer and per-group player list names.
- **Emoji** — `:emoji:` and trigger replacement in chat with tab completion and per-emoji permissions.
- **Chat bubbles** — messages float above the speaker's head, stack, and fly away as they expire.
- **Damage indicators** — floating damage and heal numbers with ballistic motion, measured from actual applied health deltas.
- **Animations** — frame-based text animations usable in any hologram, board or tablist line via `|animation.<id>|`.
- **MOTD** — randomized, color-filtered server list MOTD.

Rendered text supports bounded `{{ ... }}` expressions with native `player.*` and `server.*` values. PlaceholderAPI remains optional: `papi(...)` and `papiNumber(...)` use it when installed, while the standard player and server keys resolve from Gloss itself when it is absent.

## Requirements

- A 26.1.2 – 26.2 server: Paper, Purpur, Leaf, Folia, Canvas or Spigot.
- Java 25.
- Optional: PlaceholderAPI (placeholders in any rendered line), Vault (permission-group tablist names and default boards).

## Commands

`/gloss` (aliases `gl`, `glo`, `gg`) is the root; `/hologram` (`holo`, `h`) and `/board` (`sb`, `bd`) jump straight into their subtrees. Run `/gloss help` in game for the full paged menu.

## Building

```
./gradlew build       # full gate: tests + spigot-compatibility compile + shaded jar
./gradlew shadowJar   # just the plugin jar
```

Java 25 is required. The local `VolmLib` sibling checkout is resolved automatically as a composite build; pass `-PuseLocalVolmLib=false` to resolve it remotely instead.

## Data layout

```
plugins/Gloss/
├── config.toml
├── holograms/<id>.json
├── boards/<id>.json
├── emoji/<id>.json
├── animations/<id>.json
└── groups/<name>.yml
```

All data files hotload — edit them on disk and the change applies in game without a reload.
