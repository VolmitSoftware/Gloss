/// Per-kind inspector bodies.
///
/// This is the web-only half of the document-type registry: the inspector
/// widgets pull in DOM bridges, so they cannot live on the DOM-free adapters
/// in `document_type.dart` without dragging `package:web` into the store's VM
/// test suite. Only the inspector pane imports this file. A new document kind
/// registers its body here and its adapter in `document_type_registry.dart` —
/// both inside `lib/doctype/`.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart' show ValueKey, Widget;

import '../components/inspector/animation_inspector.dart';
import '../components/inspector/bubble_inspector.dart';
import '../components/inspector/component_inspector.dart';
import '../components/inspector/damage_indicator_inspector.dart';
import '../components/inspector/emoji_inspector.dart';
import '../components/inspector/entity_overlay_inspector.dart';
import '../components/inspector/hologram_inspector.dart';
import '../components/inspector/inspector_session.dart';
import '../components/inspector/menu_inspector.dart';
import '../components/inspector/document_preset_picker.dart';
import '../components/inspector/connections_inspector.dart';
import '../components/inspector/channel_inspector.dart';
import '../components/inspector/motd_inspector.dart';
import '../components/inspector/panel_inspector.dart';
import '../components/inspector/preview_element_editor.dart';
import '../components/inspector/scoreboard_inspector.dart';
import '../components/inspector/surface_inspector.dart';
import '../components/inspector/tablist_inspector.dart';
import '../components/inspector/preview_match_editor.dart';
import '../components/inspector/real_drop_inspector.dart';
import '../components/inspector/lane_inspectors.dart';
import '../components/panels/preview_sim_panel.dart';
import '../ui/mode.dart';
import '../model/model.dart';
import '../services/catalogs.dart';
import '../services/image_library.dart';
import '../state/editor_store.dart';
import '../state/workspace.dart';
import 'document_type_registry.dart';

/// What the inspector pane hands the active kind's body builder.
final class DocumentInspectorScope {
  const DocumentInspectorScope({
    required this.store,
    required this.images,
    required this.session,
    this.bootCatalogs,
  });

  final EditorStore store;
  final ImageLibrary images;

  /// Survives selection changes and type switches for the whole session.
  final InspectorSession session;

  /// Boot snapshot from the shell. `store.catalogs` wins whenever it holds
  /// more; null means the fetch has not settled yet — the only honest signal
  /// for a skeleton.
  final HuiCatalogs? bootCatalogs;
}

typedef DocumentInspectorBuilder =
    List<Widget> Function(DocumentInspectorScope scope);

final Map<WorkspaceDocKind, DocumentInspectorBuilder> _builders =
    <WorkspaceDocKind, DocumentInspectorBuilder>{
      DocumentTypes.menu.kind: _menuBody,
      DocumentTypes.containerPreview.kind: _previewBody,
      DocumentTypes.panel.kind: _panelBody,
      DocumentTypes.hologram.kind: _hologramBody,
      DocumentTypes.animation.kind: _animationBody,
      DocumentTypes.scoreboard.kind: _scoreboardBody,
      DocumentTypes.surface.kind: _surfaceBody,
      DocumentTypes.motd.kind: _motdBody,
      DocumentTypes.channel.kind: (DocumentInspectorScope scope) => <Widget>[ChannelInspector(store: scope.store)],
      DocumentTypes.connections.kind: _connectionsBody,
      DocumentTypes.emoji.kind: _emojiBody,
      DocumentTypes.bubbleStyle.kind: _bubbleBody,
      DocumentTypes.damageIndicators.kind: _damageIndicatorsBody,
      DocumentTypes.entityOverlays.kind: _entityOverlaysBody,
      DocumentTypes.tablist.kind: _tablistBody,
      DocumentTypes.realDrops.kind: _realDropBody,
      DocumentTypes.inventory.kind: _inventoryBody,
      DocumentTypes.nameplate.kind: _nameplateBody,
      DocumentTypes.nametag.kind: _nametagBody,
      DocumentTypes.marker.kind: _markerBody,
    };

/// The inspector for the active document kind: its body (which opens with
/// its own `PaneHeader`), then the preset picker in Advanced.
///
/// Catalog kinds (names, strings, waypoints, glyphs, behaviors, presets)
/// register no body: their editable form is the centre view, and the shell
/// mounts no inspector pane for them (`kindHasInspector`).
List<Widget> buildDocumentInspector(DocumentInspectorScope scope) {
  final DocumentInspectorBuilder? body = _builders[scope.store.docKind];
  if (body == null) return const <Widget>[];
  return <Widget>[
    ...body(scope),
    AdvancedOnly(child: DocumentPresetPicker(store: scope.store)),
  ];
}

List<Widget> _panelBody(DocumentInspectorScope scope) => <Widget>[
  PanelInspector(store: scope.store),
];

List<Widget> _hologramBody(DocumentInspectorScope scope) => <Widget>[
  HologramInspector(
    store: scope.store,
    catalogs: huiFreshestCatalogs(scope.store.catalogs, scope.bootCatalogs),
  ),
];

List<Widget> _animationBody(DocumentInspectorScope scope) => <Widget>[
  AnimationInspector(store: scope.store),
];

List<Widget> _scoreboardBody(DocumentInspectorScope scope) => <Widget>[
  ScoreboardInspector(
    store: scope.store,
    catalogs: huiFreshestCatalogs(scope.store.catalogs, scope.bootCatalogs),
  ),
];

List<Widget> _surfaceBody(DocumentInspectorScope scope) => <Widget>[
  SurfaceInspector(store: scope.store),
];

List<Widget> _motdBody(DocumentInspectorScope scope) => <Widget>[
  MotdInspector(store: scope.store),
];

List<Widget> _connectionsBody(DocumentInspectorScope scope) => <Widget>[
  ConnectionsInspector(store: scope.store),
];

List<Widget> _emojiBody(DocumentInspectorScope scope) => <Widget>[
  EmojiInspector(store: scope.store),
];

List<Widget> _bubbleBody(DocumentInspectorScope scope) => <Widget>[
  BubbleInspector(store: scope.store),
];

List<Widget> _damageIndicatorsBody(DocumentInspectorScope scope) => <Widget>[
  DamageIndicatorInspector(store: scope.store),
];

List<Widget> _entityOverlaysBody(DocumentInspectorScope scope) => <Widget>[
  EntityOverlayInspector(store: scope.store),
];

List<Widget> _tablistBody(DocumentInspectorScope scope) => <Widget>[
  TablistInspector(store: scope.store),
];

List<Widget> _realDropBody(DocumentInspectorScope scope) => <Widget>[
  RealDropInspector(store: scope.store),
];

List<Widget> _inventoryBody(DocumentInspectorScope scope) => <Widget>[
  InventoryInspector(store: scope.store),
];

List<Widget> _nameplateBody(DocumentInspectorScope scope) => <Widget>[
  NameplateInspector(store: scope.store),
];

List<Widget> _nametagBody(DocumentInspectorScope scope) => <Widget>[
  NametagInspector(store: scope.store),
];

List<Widget> _markerBody(DocumentInspectorScope scope) => <Widget>[
  MarkerInspector(store: scope.store),
];

List<Widget> _previewBody(DocumentInspectorScope scope) {
  final EditorStore store = scope.store;
  final int? index = store.previewSelectedIndex;
  final HuiPreviewElement? element = store.previewSelectedElement;
  return <Widget>[
    if (index == null || element == null)
      PreviewMatchEditor(store: store)
    else
      // Keyed by index so switching selection rebuilds local draft state
      // instead of carrying the previous element's text across.
      PreviewElementEditor(
        key: ValueKey<int>(index),
        store: store,
        index: index,
        element: element,
      ),
    // The simulation section follows the editor, whose PaneHeader opens the
    // pane. It drives the card on screen, so the Code view has none.
    if (store.view != EditorView.code)
      PreviewSimPanel(store: store, catalogs: scope.bootCatalogs),
  ];
}

List<Widget> _menuBody(DocumentInspectorScope scope) {
  final EditorStore store = scope.store;
  final HuiCatalogs catalogs = huiFreshestCatalogs(
    store.catalogs,
    scope.bootCatalogs,
  );
  final HuiComponent? selected = store.selected;
  if (selected == null) return <Widget>[MenuInspector(store: store)];
  return <Widget>[
    ComponentInspector(
      // Keyed by id so switching selection rebuilds the id draft state
      // instead of carrying the previous component's text across.
      key: ValueKey<String>('inspector-${selected.id}'),
      store: store,
      images: scope.images,
      catalogs: catalogs,
      catalogsLoading: scope.bootCatalogs == null && !catalogs.loaded,
      session: scope.session,
      target: selected,
    ),
  ];
}
