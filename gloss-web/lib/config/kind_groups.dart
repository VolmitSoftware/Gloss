/// The six groups every document kind belongs to, in the order the library,
/// its kind filter, the New dialog and the command palette list them. One
/// entry per kind: its group, its plural and singular labels, a one-line tip
/// and whether it is an Advanced kind (Simple leaves it out of New, the
/// filter and the library).
library;

import '../doctype/doctype.dart';
import '../l10n/hui_localizations.dart';
import '../state/workspace.dart';

enum KindGroup {
  screens,
  hud,
  chat,
  world,
  players,
  shared;

  String get label => switch (this) {
    KindGroup.screens => huiText('Menus and screens'),
    KindGroup.hud => huiText('HUD and server list'),
    KindGroup.chat => huiText('Chat'),
    KindGroup.world => huiText('World'),
    KindGroup.players => huiText('Players'),
    KindGroup.shared => huiText('Shared resources'),
  };

  /// Every kind of this group is Advanced.
  bool get advanced => this == KindGroup.shared;
}

/// What the shell knows about one document kind.
class KindInfo {
  const KindInfo(
    this.kind,
    this.group, {
    required this.labelOf,
    required this.singularOf,
    required this.tipOf,
    this.advanced = false,
  });

  final WorkspaceDocKind kind;
  final KindGroup group;

  /// Shown in New and the kind filter only in Advanced.
  final bool advanced;
  final String Function() labelOf;
  final String Function() singularOf;
  final String Function() tipOf;

  /// The plural name: the filter and the group lists.
  String get label => labelOf();

  /// The kind label on a library row.
  String get singular => singularOf();

  /// One line on what the kind puts in front of players.
  String get tip => tipOf();
}

/// Every kind, grouped, in display order. Read through the doctype layer's
/// adapters, so the kinds stay named in one place.
final List<KindInfo> kindInfos = <KindInfo>[
  KindInfo(
    DocumentTypes.menu.kind,
    KindGroup.screens,
    labelOf: () => huiText('Menus'),
    singularOf: () => huiText('Menu'),
    tipOf: () =>
        huiText('A floating 3D menu of icons and buttons in the world.'),
  ),
  KindInfo(
    DocumentTypes.containerPreview.kind,
    KindGroup.screens,
    labelOf: () => huiText('Container previews'),
    singularOf: () => huiText('Container preview'),
    tipOf: () =>
        huiText('A card shown when a player looks at a chest or other block.'),
  ),
  KindInfo(
    DocumentTypes.inventory.kind,
    KindGroup.screens,
    labelOf: () => huiText('Inventories'),
    singularOf: () => huiText('Inventory'),
    tipOf: () =>
        huiText('A normal container window with items and click actions.'),
  ),
  KindInfo(
    DocumentTypes.panel.kind,
    KindGroup.screens,
    advanced: true,
    labelOf: () => huiText('Flow maps'),
    singularOf: () => huiText('Flow map'),
    tipOf: () => huiText('An editor-only map of how your menus link together.'),
  ),
  KindInfo(
    DocumentTypes.scoreboard.kind,
    KindGroup.hud,
    labelOf: () => huiText('Scoreboards'),
    singularOf: () => huiText('Scoreboard'),
    tipOf: () => huiText('The sidebar on the right of the screen.'),
  ),
  KindInfo(
    DocumentTypes.tablist.kind,
    KindGroup.hud,
    labelOf: () => huiText('Tab lists'),
    singularOf: () => huiText('Tab list'),
    tipOf: () => huiText('The player list shown while Tab is held.'),
  ),
  KindInfo(
    DocumentTypes.surface.kind,
    KindGroup.hud,
    labelOf: () => huiText('Surfaces'),
    singularOf: () => huiText('Surface'),
    tipOf: () => huiText('An action bar, boss bar or title.'),
  ),
  KindInfo(
    DocumentTypes.motd.kind,
    KindGroup.hud,
    labelOf: () => huiText('MOTDs'),
    singularOf: () => huiText('MOTD'),
    tipOf: () => huiText('Your server in the multiplayer server list.'),
  ),
  KindInfo(
    DocumentTypes.leaderboard.kind,
    KindGroup.hud,
    labelOf: () => huiText('Leaderboards'),
    singularOf: () => huiText('Leaderboard'),
    tipOf: () => huiText('Top players by a statistic, placeholder or metric.'),
  ),
  KindInfo(
    DocumentTypes.channel.kind,
    KindGroup.chat,
    labelOf: () => huiText('Chat channels'),
    singularOf: () => huiText('Chat channel'),
    tipOf: () => huiText('A chat channel with its own format and filters.'),
  ),
  KindInfo(
    DocumentTypes.connections.kind,
    KindGroup.chat,
    labelOf: () => huiText('Connection messages'),
    singularOf: () => huiText('Connection messages'),
    tipOf: () => huiText('Join, quit and first-join messages in chat.'),
  ),
  KindInfo(
    DocumentTypes.emoji.kind,
    KindGroup.chat,
    labelOf: () => huiText('Emoji'),
    singularOf: () => huiText('Emoji'),
    tipOf: () => huiText('Shortcodes that turn into images in chat.'),
  ),
  KindInfo(
    DocumentTypes.bubbleStyle.kind,
    KindGroup.chat,
    labelOf: () => huiText('Bubble styles'),
    singularOf: () => huiText('Bubble style'),
    tipOf: () => huiText('Chat bubbles that rise above the speaker.'),
  ),
  KindInfo(
    DocumentTypes.hologram.kind,
    KindGroup.world,
    labelOf: () => huiText('Holograms'),
    singularOf: () => huiText('Hologram'),
    tipOf: () =>
        huiText('Floating lines of text and items at a place in the world.'),
  ),
  KindInfo(
    DocumentTypes.marker.kind,
    KindGroup.world,
    labelOf: () => huiText('Markers'),
    singularOf: () => huiText('Marker'),
    tipOf: () => huiText('A pin that marks a place in the world.'),
  ),
  KindInfo(
    DocumentTypes.waypoint.kind,
    KindGroup.world,
    labelOf: () => huiText('Waypoints'),
    singularOf: () => huiText('Waypoint'),
    tipOf: () => huiText('Locator bar waypoints that point players to places.'),
  ),
  KindInfo(
    DocumentTypes.damageIndicators.kind,
    KindGroup.world,
    labelOf: () => huiText('Damage indicators'),
    singularOf: () => huiText('Damage indicators'),
    tipOf: () => huiText('Numbers that pop out when an entity takes damage.'),
  ),
  KindInfo(
    DocumentTypes.realDrops.kind,
    KindGroup.world,
    labelOf: () => huiText('Real drops'),
    singularOf: () => huiText('Real drops'),
    tipOf: () => huiText('Labels and models for dropped items.'),
  ),
  KindInfo(
    DocumentTypes.entityOverlays.kind,
    KindGroup.world,
    labelOf: () => huiText('Entity overlays'),
    singularOf: () => huiText('Entity overlays'),
    tipOf: () => huiText('Health bars and names above nearby mobs.'),
  ),
  KindInfo(
    DocumentTypes.nameplate.kind,
    KindGroup.players,
    labelOf: () => huiText('Nameplates'),
    singularOf: () => huiText('Nameplate'),
    tipOf: () => huiText('The text and images above a player.'),
  ),
  KindInfo(
    DocumentTypes.nametag.kind,
    KindGroup.players,
    labelOf: () => huiText('Nametags'),
    singularOf: () => huiText('Nametag'),
    tipOf: () =>
        huiText('Prefix, name colour and suffix chosen by permission.'),
  ),
  KindInfo(
    DocumentTypes.animation.kind,
    KindGroup.shared,
    advanced: true,
    labelOf: () => huiText('Animations'),
    singularOf: () => huiText('Animation'),
    tipOf: () => huiText('Reusable text animations other documents play.'),
  ),
  KindInfo(
    DocumentTypes.strings.kind,
    KindGroup.shared,
    advanced: true,
    labelOf: () => huiText('Strings'),
    singularOf: () => huiText('Strings'),
    tipOf: () => huiText('Translated text other documents use by key.'),
  ),
  KindInfo(
    DocumentTypes.names.kind,
    KindGroup.shared,
    advanced: true,
    labelOf: () => huiText('Names'),
    singularOf: () => huiText('Names'),
    tipOf: () =>
        huiText('Display names for game objects, used by placeholders.'),
  ),
  KindInfo(
    DocumentTypes.glyph.kind,
    KindGroup.shared,
    advanced: true,
    labelOf: () => huiText('Glyphs'),
    singularOf: () => huiText('Glyphs'),
    tipOf: () => huiText(
      'Font bitmaps, spacing and waypoint styles for the resource pack.',
    ),
  ),
  KindInfo(
    DocumentTypes.behavior.kind,
    KindGroup.shared,
    advanced: true,
    labelOf: () => huiText('Behaviors'),
    singularOf: () => huiText('Behaviors'),
    tipOf: () => huiText('Shared states and events other documents react to.'),
  ),
  KindInfo(
    DocumentTypes.presets.kind,
    KindGroup.shared,
    advanced: true,
    labelOf: () => huiText('Presets'),
    singularOf: () => huiText('Presets'),
    tipOf: () =>
        huiText('Shared defaults and named presets other documents inherit.'),
  ),
];

final Map<WorkspaceDocKind, KindInfo> _byKind = <WorkspaceDocKind, KindInfo>{
  for (final KindInfo info in kindInfos) info.kind: info,
};

KindInfo kindInfo(WorkspaceDocKind kind) => _byKind[kind]!;

KindGroup groupOf(WorkspaceDocKind kind) => kindInfo(kind).group;

bool kindIsAdvanced(WorkspaceDocKind kind) => kindInfo(kind).advanced;

/// Whether the library and the palette list [doc] in this mode.
bool listedInMode(WorkspaceDoc doc, {required bool advanced}) =>
    advanced || !kindIsAdvanced(doc.kind);

/// The document Simple opens in place of [activeId]: [activeId] itself when
/// Simple lists its kind, else the newest document Simple lists, else null.
String? simpleLanding(List<WorkspaceDoc> docs, String? activeId) {
  WorkspaceDoc? newest;
  for (final WorkspaceDoc doc in docs) {
    if (!listedInMode(doc, advanced: false)) continue;
    if (doc.id == activeId) return activeId;
    if (newest == null || doc.updatedAt > newest.updatedAt) newest = doc;
  }
  return newest?.id;
}

/// The kinds of [group] offered in New and the filter; in Simple the
/// Advanced kinds are left out.
List<KindInfo> kindsIn(KindGroup group, {required bool advanced}) => <KindInfo>[
  for (final KindInfo info in kindInfos)
    if (info.group == group && (advanced || !info.advanced)) info,
];

/// The groups that offer at least one kind in this mode.
List<KindGroup> groupsFor({required bool advanced}) => <KindGroup>[
  for (final KindGroup group in KindGroup.values)
    if (kindsIn(group, advanced: advanced).isNotEmpty) group,
];

/// Only the menu and the container preview draw something different in
/// Preview; every other kind shows its stage once.
bool hasDistinctPreview(WorkspaceDocKind kind) =>
    kind == DocumentTypes.menu.kind ||
    kind == DocumentTypes.containerPreview.kind;

/// Catalog kinds edit in the centre and have no inspector pane.
bool kindHasInspector(WorkspaceDocKind kind) => !_catalogKinds.contains(kind);

final Set<WorkspaceDocKind> _catalogKinds = <WorkspaceDocKind>{
  DocumentTypes.names.kind,
  DocumentTypes.strings.kind,
  DocumentTypes.waypoint.kind,
  DocumentTypes.glyph.kind,
  DocumentTypes.behavior.kind,
  DocumentTypes.presets.kind,
  DocumentTypes.leaderboard.kind,
};

String kindLabel(WorkspaceDocKind kind) => kindInfo(kind).label;

String kindSingular(WorkspaceDocKind kind) => kindInfo(kind).singular;

String kindTip(WorkspaceDocKind kind) => kindInfo(kind).tip;
