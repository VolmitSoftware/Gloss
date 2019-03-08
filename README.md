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
| Holograms Work `/holo new a`              | PASS |        |        |        |       |       |       |
| Holograms Move `/holo move`               |        |        |        |        |       |       |       |
| Holograms Editable `/holo edit`           |        |        |        |        |       |       |       |
| Holograms Line Edit `/holo line`          |        |        |        |        |       |       |       |
| Holograms Listed `/holo list` (delete/tp) |        |        |        |        |       |       |       |

## Boards
Tests boards (1.8-1.13.2)

| Expected Functionality                    | 1.13.2 | 1.12.2 | 1.11.2 | 1.10.2 | 1.9.4 | 1.9.2 | 1.8.8 |
|-------------------------------------------|--------|--------|--------|--------|-------|-------|-------|
| Boards Work `/board new a`                |        |        |        |        |       |       |       |
| Boards Hide/Show `/board [hide/show <a>]` |        |        |        |        |       |       |       |
| Boards Default `/board default a` (relog) |        |        |        |        |       |       |       |
| Boards Edited, Hotload                    |        |        |        |        |       |       |       |

## Emoji
Tests Emoji (1.8-1.13.2)

| Expected Functionality                   | 1.13.2 | 1.12.2 | 1.11.2 | 1.10.2 | 1.9.4 | 1.9.2 | 1.8.8 |
|------------------------------------------|--------|--------|--------|--------|-------|-------|-------|
| Type `:air` and press <TAB> (completion) |        |        |        |        |       |       |       |
| Use /gloss emoji                         |        |        |        |        |       |       |       |
| Edit the heart emoji (json) Hotloads.    |        |        |        |        |       |       |       |
| Create new emoji, and `/gloss reload`.   |        |        |        |        |       |       |       |
  
## Groups & Tablist
Tests Groups and tablist names (1.8-1.13.2)

| Expected Functionality                               | 1.13.2 | 1.12.2 | 1.11.2 | 1.10.2 | 1.9.4 | 1.9.2 | 1.8.8 |
|------------------------------------------------------|--------|--------|--------|--------|-------|-------|-------|
| Edit the _op.yml file `/gloss reload` check tablist. |        |        |        |        |       |       |       |
| Create `derp.yml` and create permission group "derp" |        |        |        |        |       |       |       |
| Make a board and set derp.yml to default it.         |        |        |        |        |       |       |       |
