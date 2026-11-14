/// The canvas frame's header: the toggles and actions of the menu canvas and
/// its `...` menu, as [StageFrame] takes them. Every control writes straight
/// to the store; the frame rebuilds through a selector on [CanvasToolbarState]
/// so a drag, which notifies on every pointer move, does not rebuild it.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';

import '../../config/editing.dart';
import '../../logic/multi_select.dart';
import '../../logic/ui_scale_choices.dart';
import '../../model/model.dart';
import '../../state/editor_store.dart';
import '../../ui/action_row.dart';
import '../../ui/menu.dart';
import 'package:gloss_editor/l10n/hui_localizations.dart';

/// Order of the backdrop choices.
const List<HuiBackdropMode> huiBackdropCycle = <HuiBackdropMode>[
  HuiBackdropMode.image,
  HuiBackdropMode.dark,
  HuiBackdropMode.light,
  HuiBackdropMode.none,
];

String huiBackdropLabel(HuiBackdropMode mode) => switch (mode) {
  HuiBackdropMode.image => huiText('Scene'),
  HuiBackdropMode.dark => huiText('Dark'),
  HuiBackdropMode.light => huiText('Light'),
  HuiBackdropMode.none => huiText('None'),
};

/// Undo labels for the restack controls. Shared with the viewport so the
/// history entry and the control say the same thing.
const Map<HuiZOrder, String> huiZOrderLabels = <HuiZOrder, String>{
  HuiZOrder.forward: 'bring forward',
  HuiZOrder.backward: 'send back',
  HuiZOrder.toFront: 'bring to front',
  HuiZOrder.toBack: 'send to back',
};

/// The preview uiScale as radio entries of a `...` menu; the 3D preview's
/// menu offers the same.
List<AppMenuEntry> uiScaleMenuEntries(EditorStore store) {
  final double current = store.previewUiScale;
  return <AppMenuEntry>[
    AppMenuEntry.heading(huiText('Preview uiScale')),
    for (final double value in uiScaleChoices(current))
      AppMenuEntry.radio(
        uiScaleLabel(value),
        group: 'ui-scale',
        value: uiScaleLabel(value),
        checked: value == current,
        onPicked: (String picked) =>
            store.previewUiScale = parseUiScaleLabel(picked) ?? current,
      ),
  ];
}

/// Everything the canvas header shows. A record, so a selector compares it
/// by value.
typedef CanvasToolbarState = ({
  bool grid,
  bool snap,
  bool hitboxes,
  bool anchors,
  bool trueRender,
  HuiBackdropMode backdrop,
  double uiScale,
  bool selection,
  String? toggleId,
  bool showsTrue,
  bool playing,
});

CanvasToolbarState canvasToolbarState(EditorStore store) {
  final HuiComponent? selected = store.selected;
  final String? toggleId = selected != null && selected.data is HuiToggleData
      ? selected.id
      : null;
  return (
    grid: store.showGrid,
    snap: store.snapToGrid,
    hitboxes: store.showHitboxes,
    anchors: store.showAnchors,
    trueRender: store.trueRender,
    backdrop: store.backdrop,
    uiScale: store.previewUiScale,
    selection: store.selectionIds.isNotEmpty,
    toggleId: toggleId,
    showsTrue: toggleId != null && store.togglePreviewFor(toggleId),
    playing: store.animationsPlaying,
  );
}

/// The header buttons: the view switches (Snap, Block grid, the toggle's
/// true icon, Play) the frame joins into one control after the title, then
/// the zoom buttons. Hitboxes, Anchors and restacking are in `...`.
List<RowAction> canvasActions({
  required EditorStore store,
  required CanvasToolbarState state,
  required bool animated,
  required void Function() onZoomIn,
  required void Function() onZoomOut,
}) => <RowAction>[
  RowAction(
    huiText('Snap'),
    () => store.snapToGrid = !state.snap,
    icon: ArcaneIcon.magnet(),
    iconOnly: true,
    pressed: state.snap,
  ),
  RowAction(
    huiText('Block grid'),
    () => store.showGrid = !state.grid,
    icon: ArcaneIcon.grid3x3(),
    iconOnly: true,
    pressed: state.grid,
  ),
  if (state.toggleId != null)
    RowAction(
      huiText('Preview true icon'),
      () => store.setTogglePreview(state.toggleId!, !state.showsTrue),
      icon: ArcaneIcon.toggleRight(),
      iconOnly: true,
      pressed: state.showsTrue,
    ),
  if (animated)
    RowAction(
      huiText('Play animations'),
      () => store.animationsPlaying = !state.playing,
      icon: ArcaneIcon.play(),
      iconOnly: true,
      pressed: state.playing,
    ),
  RowAction(
    huiText('Zoom out'),
    onZoomOut,
    icon: ArcaneIcon.zoomOut(),
    iconOnly: true,
  ),
  RowAction(
    huiText('Zoom in'),
    onZoomIn,
    icon: ArcaneIcon.zoomIn(),
    iconOnly: true,
  ),
];

/// The header's `...` menu: the rarer view settings and the restack moves.
List<AppMenuEntry> canvasMoreEntries({
  required EditorStore store,
  required CanvasToolbarState state,
  required void Function(HuiZOrder op) onZOrder,
}) => <AppMenuEntry>[
  AppMenuEntry.checkbox(
    huiText('Hitboxes'),
    checked: state.hitboxes,
    onChanged: (bool on) => store.showHitboxes = on,
    icon: ArcaneIcon.frame(),
  ),
  AppMenuEntry.checkbox(
    huiText('Anchors'),
    checked: state.anchors,
    onChanged: (bool on) => store.showAnchors = on,
    icon: ArcaneIcon.locateFixed(),
  ),
  AppMenuEntry.checkbox(
    huiText('True render'),
    checked: state.trueRender,
    onChanged: (bool on) => store.trueRender = on,
  ),
  const AppMenuEntry.separator(),
  AppMenuEntry.heading(huiText('Backdrop')),
  for (final HuiBackdropMode mode in huiBackdropCycle)
    AppMenuEntry.radio(
      huiBackdropLabel(mode),
      group: 'canvas-backdrop',
      value: mode.name,
      checked: state.backdrop == mode,
      onPicked: (String _) => store.backdrop = mode,
    ),
  const AppMenuEntry.separator(),
  ...uiScaleMenuEntries(store),
  if (state.selection) ...<AppMenuEntry>[
    const AppMenuEntry.separator(),
    AppMenuEntry(
      huiText('Bring forward'),
      () => onZOrder(HuiZOrder.forward),
      icon: ArcaneIcon.chevronUp(),
    ),
    AppMenuEntry(
      huiText('Send back'),
      () => onZOrder(HuiZOrder.backward),
      icon: ArcaneIcon.chevronDown(),
    ),
    AppMenuEntry(
      huiText('Bring to front'),
      () => onZOrder(HuiZOrder.toFront),
      icon: ArcaneIcon.bringToFront(),
    ),
    AppMenuEntry(
      huiText('Send to back'),
      () => onZOrder(HuiZOrder.toBack),
      icon: ArcaneIcon.sendToBack(),
    ),
    AppMenuEntry(
      huiText('Move nearer (-z)'),
      () => _nudgeDepth(store, -huiDepthStep),
      icon: ArcaneIcon.moveLeft(),
    ),
    AppMenuEntry(
      huiText('Move deeper (+z)'),
      () => _nudgeDepth(store, huiDepthStep),
      icon: ArcaneIcon.moveRight(),
    ),
  ],
];

void _nudgeDepth(EditorStore store, double delta) {
  final Map<String, Vec3> offsets = <String, Vec3>{
    for (final HuiComponent selected in store.selectedComponents)
      selected.id: selected.offset.copy(),
  };
  if (offsets.isEmpty) return;
  final Map<String, Vec3> next = shiftOffsets(offsets: offsets, dz: delta);
  store.setOffsets(
    next.length == 1
        ? 'depth ${next.keys.first}'
        : 'depth ${next.length} components',
    next,
  );
}
