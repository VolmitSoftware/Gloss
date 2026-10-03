import argparse
import datetime
import re
import sys
import tempfile
from dataclasses import dataclass
from html import escape
from pathlib import Path


ROOT: Path = Path(__file__).resolve().parents[3]
FRONTMATTER: re.Pattern[str] = re.compile(r'\A---\n.*?\n---\n', re.DOTALL)
DATE: re.Pattern[str] = re.compile(r'^date: .*$', re.MULTILINE)


@dataclass(frozen=True)
class Demo:
    identifier: str
    page: str
    section: str
    title: str
    capture: str = 'pov'


DEMOS: tuple[Demo, ...] = (
    Demo('holograms', '04-holograms.md', 'Mixed lines and pages', 'Mixed hologram lines and pages'),
    Demo('native-object-bounds', '04-holograms.md', 'Mixed lines and pages', 'Heads and scaled entity lines'),
    Demo('particle-layers', '25-particle-layers.md', 'Geometry', 'Particle layer geometry'),
    Demo('personal-menus', '09-menus.md', 'The session model', 'Personal hologram menus'),
    Demo('inventory-menus', '09b-inventory-menus.md', 'Slot icons and actions', 'Inventory menu interaction'),
    Demo('world-panels', '16-panels.md', 'Placement, rotation and scale', 'World panel placement'),
    Demo('entity-overlays', '20-entity-overlays.md', 'Default behavior', 'Entity overlays'),
    Demo('markers-waypoints', '04-holograms.md', 'World markers', 'World markers and waypoints'),
    Demo('chat-bubbles', '08-chat-bubbles.md', 'Motion', 'Chat bubbles'),
    Demo('damage-healing', '08b-damage-indicators.md', 'Runtime behavior', 'Damage and healing indicators'),
    Demo('real-drops', '08c-drop-labels.md', 'Real drops', 'Dropped item models and labels'),
    Demo('chat-features', '08-chat-bubbles.md', 'Chat channels and mentions', 'Chat channels and rich messages'),
    Demo('boards-tablist', '06-tablist.md', 'Header and footer', 'Scoreboard and tablist'),
    Demo('screen-surfaces', '06c-screen-surfaces.md', 'The surface document', 'Action bars, boss bars, and titles'),
    Demo('text-hotload', '07-emoji-text-animations.md', 'Hot reload', 'Text and automatic reload'),
    Demo('container-previews', '15-container-previews.md', 'What triggers a preview', 'Container previews'),
    Demo('connection-messages', '26-connection-messages.md', 'The document', 'Connection messages'),
    Demo('server-list', '06b-server-list-motd.md', 'Ping fields', 'Server list presentation'),
    Demo('server-list-motd', '06b-server-list-motd.md', 'Ping fields', 'Server list and player sample'),
    Demo('server-links', '06b-server-list-motd.md', 'Server links', 'Native pause-menu server links'),
    Demo('player-identities', '20-entity-overlays.md', 'Permission-selected nameplates', 'Nametag and nameplate live updates'),
    Demo('chat-recipient', '08-chat-bubbles.md', 'Chat channels and mentions', 'Recipient mentions and item hover'),
    Demo('prompt-sign', '12-actions.md', '`prompt`', 'Sign input and continuation'),
    Demo('prompt-anvil', '12-actions.md', '`prompt`', 'Anvil input and continuation'),
    Demo('prompt-chat', '12-actions.md', '`prompt`', 'Chat input and continuation'),
    Demo('viewer-glow', '21-api-getting-started.md', 'Entity glow', 'Per-viewer entity glow'),
    Demo('standalone-beam', '21-api-getting-started.md', 'Beams and trails', 'Standalone block-display beam'),
    Demo('book-reading', '12-actions.md', 'Session and client interfaces', 'Written book action'),
    Demo('sky-transition', '12-actions.md', 'Sky and glow', 'Per-viewer sky transition'),
    Demo('menu-catalog', '11-icons.md', 'Icon types', 'Menu icon catalog'),
    Demo('editor-live-sync', '18-web-editor.md', 'Publish', 'Published hologram in Minecraft'),
    Demo('menu', '18-web-editor.md', 'Editing', 'Menu authoring', 'editor'),
    Demo('menu-shop', '18-web-editor.md', 'Editing', 'Shop menu authoring', 'editor'),
    Demo('menu-toggle', '10-components-hitboxes.md', 'Toggle', 'Toggle component authoring', 'editor'),
    Demo('menu-forms', '18-web-editor.md', 'Editing', 'Form component authoring', 'editor'),
    Demo('menu-list', '10-components-hitboxes.md', 'Lists and tabs', 'Scoped list component authoring', 'editor'),
    Demo('menu-slider', '10-components-hitboxes.md', 'Slider and field', 'Slider component authoring', 'editor'),
    Demo('menu-field', '10-components-hitboxes.md', 'Slider and field', 'Field component authoring', 'editor'),
    Demo('menu-tabs', '10-components-hitboxes.md', 'Lists and tabs', 'Tabs component authoring', 'editor'),
    Demo('world-panel', '16-panels.md', 'Browser authoring', 'World panel authoring', 'editor'),
    Demo('container-preview', '15-container-previews.md', 'The preview document', 'Container preview authoring', 'editor'),
    Demo('hologram-mixed', '04-holograms.md', 'Mixed lines and pages', 'Mixed hologram authoring', 'editor'),
    Demo('hologram', '04-holograms.md', 'Mixed lines and pages', 'Hologram text and pages authoring', 'editor'),
    Demo('animation', '07-emoji-text-animations.md', 'Animations', 'Text animation authoring', 'editor'),
    Demo('scoreboard', '05-scoreboards-groups.md', 'The board document', 'Scoreboard authoring', 'editor'),
    Demo('tablist', '06-tablist.md', 'Header and footer', 'Tablist authoring', 'editor'),
    Demo('surface', '06c-screen-surfaces.md', 'The surface document', 'Screen surface authoring', 'editor'),
    Demo('motd', '06b-server-list-motd.md', 'Ping fields', 'MOTD authoring', 'editor'),
    Demo('connections', '26-connection-messages.md', 'The document', 'Connection message authoring', 'editor'),
    Demo('channel', '08-chat-bubbles.md', 'Chat channels and mentions', 'Chat channel authoring', 'editor'),
    Demo('emoji', '07-emoji-text-animations.md', 'Emoji', 'Emoji authoring', 'editor'),
    Demo('bubble-style', '08-chat-bubbles.md', 'Style documents', 'Bubble style authoring', 'editor'),
    Demo('damage-indicators', '08b-damage-indicators.md', 'Web renderer', 'Damage indicator authoring', 'editor'),
    Demo('entity-overlays', '20-entity-overlays.md', 'Web editor', 'Entity overlay authoring', 'editor'),
    Demo('real-drops', '08c-drop-labels.md', 'Real drops', 'Dropped item authoring', 'editor'),
    Demo('inventory', '09b-inventory-menus.md', 'The inventory document', 'Inventory menu authoring', 'editor'),
    Demo('nameplate', '20-entity-overlays.md', 'Permission-selected nameplates', 'Nameplate authoring', 'editor'),
    Demo('nametag', '20-entity-overlays.md', 'Permission-selected nametags', 'Nametag authoring', 'editor'),
    Demo('marker', '04-holograms.md', 'World markers', 'Marker authoring', 'editor'),
    Demo('waypoint', '04-holograms.md', 'Waypoints', 'Waypoint authoring', 'editor'),
    Demo('names', '13-expressions-placeholders.md', 'Game-object names', 'Names catalog authoring', 'editor'),
    Demo('strings', '19-localization.md', 'Authored content strings', 'Content string authoring', 'editor'),
    Demo('project-workflow', '18-web-editor-tutorial.md', 'Four editor modes', 'Browser project workflow', 'editor'),
    Demo('image-library', '18-web-editor-tutorial.md', 'Images', 'Image library and import', 'editor'),
    Demo('seeded-randomizer', '18-web-editor.md', 'Seeded randomizer', 'Seeded document generation', 'editor'),
    Demo('live-sync', '18-web-editor.md', 'Publish', 'Connected editor publication', 'editor'),
)


def key(demo: Demo) -> str:
    return demo.identifier + '-' + demo.capture


def block(demo: Demo, perspectives: tuple[str, ...]) -> str:
    lines: list[str] = ['<div class="gloss-demo" data-demo="' + key(demo) + '">']
    label: str = 'Browser editor' if demo.capture == 'editor' else 'Minecraft client'
    note: str = (' Browser editing and previews; game rendering is shown in the Minecraft client clips.'
                 if demo.capture == 'editor' else '')
    lines.append('<p><strong>' + escape(demo.title) + '</strong> ' + label + '. Silent capture.' + note + '</p>')
    for perspective in perspectives:
        source: str = '/gloss-assets/demos/' + demo.identifier + '-' + perspective + '.webm'
        view: str = {'pov': 'First person', 'observer': 'Third person', 'editor': 'Browser editor'}[perspective]
        lines.append('<video src="' + source + '" aria-label="' + escape(demo.title + ', ' + view.lower())
                     + '" autoplay muted loop playsinline controls preload="metadata"></video>')
    lines.append('</div>')
    return '\n'.join(lines)


def render_page(text: str, demos: tuple[Demo, ...], views: dict[str, tuple[str, ...]], now: str) -> tuple[str, int]:
    front: re.Match[str] | None = FRONTMATTER.match(text)
    if front is None or DATE.search(front.group(0)) is None:
        raise ValueError('Page needs Wiki.js frontmatter and a date')
    changed: int = 0
    for demo in reversed(demos):
        heading: re.Match[str] | None = re.search(r'^## ' + re.escape(demo.section) + r'[ \t]*$', text, re.MULTILINE)
        if heading is None:
            raise ValueError('Missing section ' + demo.section + ' in ' + demo.page)
        next_heading: re.Match[str] | None = re.search(r'^## ', text[heading.end():], re.MULTILINE)
        section_end: int = len(text) if next_heading is None else heading.end() + next_heading.start()
        existing: re.Match[str] | None = re.compile(
            r'\n\n<div class="gloss-demo" data-demo="' + re.escape(key(demo))
            + r'">\n.*?\n</div>(?=\n|\Z)', re.DOTALL).search(text, heading.end(), section_end)
        fresh: str = '\n\n' + block(demo, views[key(demo)])
        if existing is not None and existing.group(0) == fresh:
            continue
        start: int = heading.end() if existing is None else existing.start()
        end: int = start if existing is None else existing.end()
        text = text[:start] + fresh + text[end:]
        changed += 1
    if changed:
        text = DATE.sub('date: ' + now, text[:front.end()], count=1) + text[front.end():]
    return text, changed


def sync(docs: Path, selected: tuple[str, ...], now: str) -> int:
    known: dict[str, Demo] = {key(demo): demo for demo in DEMOS}
    unknown: set[str] = set(selected) - known.keys()
    if unknown:
        raise ValueError('Unknown demonstrations: ' + ', '.join(sorted(unknown)))
    directory: Path = docs / 'gloss-assets/demos'
    demos: tuple[Demo, ...] = (tuple(demo for demo in DEMOS if key(demo) in selected) if selected else
                               tuple(demo for demo in DEMOS if (directory / (key(demo) + '.webm')).is_file()))
    if not demos:
        raise ValueError('No accepted demonstration clips found: ' + str(directory))
    views: dict[str, tuple[str, ...]] = {}
    for demo in demos:
        clip: Path = directory / (key(demo) + '.webm')
        if not clip.is_file() or clip.stat().st_size == 0:
            raise ValueError('Missing or empty accepted clip: ' + str(clip))
        perspectives: tuple[str, ...] = (demo.capture,)
        observer: Path = directory / (demo.identifier + '-observer.webm')
        if demo.capture == 'pov' and observer.is_file() and observer.stat().st_size > 0:
            perspectives += ('observer',)
        views[key(demo)] = perspectives
    pages: dict[str, tuple[Demo, ...]] = {}
    for demo in demos:
        pages[demo.page] = pages.get(demo.page, ()) + (demo,)
    updates: list[tuple[Path, str, int]] = []
    for name, page_demos in pages.items():
        page: Path = docs / 'gloss' / name
        text, changed = render_page(page.read_text(encoding='utf-8'), page_demos, views, now)
        updates.append((page, text, changed))
    for page, text, changed in updates:
        if changed:
            temporary: Path | None = None
            try:
                with tempfile.NamedTemporaryFile(mode='w', encoding='utf-8', dir=page.parent, delete=False) as output:
                    temporary = Path(output.name)
                    output.write(text)
                temporary.chmod(page.stat().st_mode)
                temporary.replace(page)
            finally:
                if temporary is not None:
                    temporary.unlink(missing_ok=True)
    return sum(changed for _, _, changed in updates)


def main(argv: list[str] | None = None) -> int:
    parser: argparse.ArgumentParser = argparse.ArgumentParser(description='Embed accepted Gloss demonstration clips into their usage references.')
    parser.add_argument('--docs', type=Path, default=ROOT.parent / 'docs')
    parser.add_argument('--only', action='append', choices=sorted(key(demo) for demo in DEMOS),
                        help='Synchronize this accepted clip only; repeat to select several.')
    args: argparse.Namespace = parser.parse_args(argv)
    now: str = datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%S.000Z')
    try:
        changed: int = sync(args.docs, tuple(args.only or ()), now)
    except (ValueError, OSError) as error:
        print(str(error), file=sys.stderr)
        return 2
    print('Updated ' + str(changed) + ' demonstration block(s).')
    return 0


if __name__ == '__main__':
    sys.exit(main())
