# Gloss Tests
Test the following versions
* 1.8.8
* 1.9.2
* 1.9.4
* 1.10.2
* 1.11.2
* 1.12.2
* 1.13.2

## Holograms
Tests holograms (1.8-1.13.2)

| Expected Functionality                    | 1.13.2 | 1.12.2 | 1.11.2 | 1.10.2 | 1.9.4 | 1.9.2 | 1.8.8 |
|-------------------------------------------|--------|--------|--------|--------|-------|-------|-------|
| Holograms Work `/holo new a`              |        | PASS | PASS |        |       |       |       |
| Hologram Glow Particles are visible       |        | PASS | PASS |        |       |       |       |
| Modify Holograms via File (hotloads)      |        | PASS | PASS |        |       |       |       |
| Holograms Move `/holo move`               |        | PASS | PASS |        |       |       |       |
| Holograms Editable `/holo edit`           |        | PASS | PASS |        |       |       |       |
| Holograms Line Edit `/holo line`          |        | PASS | PASS |        |       |       |       |
| Holograms Listed `/holo list` (delete/tp) |        | PASS | PASS |        |       |       |       |

## Boards
Tests boards (1.8-1.13.2)

| Expected Functionality                    | 1.13.2 | 1.12.2 | 1.11.2 | 1.10.2 | 1.9.4 | 1.9.2 | 1.8.8 |
|-------------------------------------------|--------|--------|--------|--------|-------|-------|-------|
| Boards Work `/board new a`                |        | PASS | PASS |        |       |       |       |
| Boards Hide/Show `/board [hide/show <a>]` |        | PASS | PASS |        |       |       |       |
| Boards Default `/board default a` (relog) |        | PASS | PASS |        |       |       |       |
| Boards Edited, Hotload                    |        | PASS | PASS |        |       |       |       |

## Emoji
Tests Emoji (1.8-1.13.2)

| Expected Functionality                   | 1.13.2 | 1.12.2 | 1.11.2 | 1.10.2 | 1.9.4 | 1.9.2 | 1.8.8 |
|------------------------------------------|--------|--------|--------|--------|-------|-------|-------|
| Type `:air` and press <TAB> (completion) |        | PASS | PASS |        |       |       |       |
| Use /gloss emoji                         |        | PASS | PASS |        |       |       |       |
| Edit the heart emoji (json) Hotloads.    |        | PASS | PASS |        |       |       |       |
| Create new emoji, and `/gloss reload`.   |        | PASS | PASS |        |       |       |       |
  
## Groups & Tablist
Tests Groups and tablist names (1.8-1.13.2)

| Expected Functionality                               | 1.13.2 | 1.12.2 | 1.11.2 | 1.10.2 | 1.9.4 | 1.9.2 | 1.8.8 |
|------------------------------------------------------|--------|--------|--------|--------|-------|-------|-------|
| Edit the `op.yml`file `/gloss reload` check tablist. |        | PASS | PASS |        |       |       |       |
| Create `derp.yml` and create permission group "derp" |        |      |        |        |       |       |       |
| Make a board and set derp.yml to default it.         |        |      |        |        |       |       |       |
