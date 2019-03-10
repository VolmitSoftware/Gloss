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
| Holograms Work `/holo new a`              |   PASS     | PASS | PASS | PASS |       |       |       |
| Hologram Glow Particles are visible       |   FAIL (#37)    | PASS | PASS | PASS |       |       |       |
| Modify Holograms via File (hotloads)      |   PASS   | PASS | PASS | PASS |       |       |       |
| Holograms Move `/holo move`               |   PASS     | PASS | PASS | PASS |       |       |       |
| Holograms Editable `/holo edit`           |   PASS     | PASS | PASS | PASS |       |       |       |
| Holograms Line Edit `/holo line`          |   PASS     | PASS | PASS | PASS |       |       |       |
| Holograms Listed `/holo list` (delete/tp) |   PASS     | PASS | PASS | PASS |       |       |       |

## Boards
Tests boards (1.8-1.13.2)

| Expected Functionality                    | 1.13.2 | 1.12.2 | 1.11.2 | 1.10.2 | 1.9.4 | 1.9.2 | 1.8.8 |
|-------------------------------------------|--------|--------|--------|--------|-------|-------|-------|
| Boards Work `/board new a`                |   PASS     | PASS | PASS | PASS |       |       |       |
| Boards Hide/Show `/board [hide/show <a>]` |   PASS     | PASS | PASS | PASS |       |       |       |
| Boards Default `/board default a` (relog) |   PASS     | PASS | PASS | PASS |       |       |       |
| Boards Edited, Hotload                    |   PASS     | PASS | PASS | PASS |       |       |       |

## Emoji
Tests Emoji (1.8-1.13.2)

| Expected Functionality                   | 1.13.2 | 1.12.2 | 1.11.2 | 1.10.2 | 1.9.4 | 1.9.2 | 1.8.8 |
|------------------------------------------|--------|--------|--------|--------|-------|-------|-------|
| Type `:air` and press <TAB> (completion) |  FAIL (#46)     | PASS | PASS | PASS |       |       |       |
| Use /gloss emoji                         |  PASS      | PASS | PASS | PASS |       |       |       |
| Edit the heart emoji (json) Hotloads.    |  PASS      | PASS | PASS | PASS |       |       |       |
| Create new emoji, and `/gloss reload`.   |  PASS      | PASS | PASS | PASS |       |       |       |
  
## Groups & Tablist
Tests Groups and tablist names (1.8-1.13.2)

| Expected Functionality                               | 1.13.2 | 1.12.2 | 1.11.2 | 1.10.2 | 1.9.4 | 1.9.2 | 1.8.8 |
|------------------------------------------------------|--------|--------|--------|--------|-------|-------|-------|
| Edit the `_op.yml`file `/gloss reload` check tablist. |  PASS      | PASS | PASS | PASS |       |       |       |
| Create `derp.yml` and create permission group "derp" |   PASS     |      |        |        |       |       |       |
| Make a board and set derp.yml to default it.         |   PASS     |      |        |        |       |       |       |
