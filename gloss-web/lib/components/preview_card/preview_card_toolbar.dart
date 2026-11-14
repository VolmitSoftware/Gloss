/// The card frame's header: the pixel grid, the simulation clock and the zoom,
/// as [StageFrame] takes them. Same shape as the component canvas header
/// (`canvas_toolbar.dart`). Picking the simulated target and pinning values
/// is the simulation section of the inspector; these are the controls a
/// layout author needs without leaving the card.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';

import '../../state/editor_store.dart';
import '../../ui/action_row.dart';
import '../../ui/menu.dart';
import 'package:gloss_editor/l10n/hui_localizations.dart';

/// What the card header shows; compared by value so a drag does not rebuild
/// the frame.
typedef PreviewCardToolbarState = ({bool grid, bool playing});

PreviewCardToolbarState previewCardToolbarState(EditorStore store) =>
    (grid: store.showGrid, playing: store.animationsPlaying);

/// The header buttons, left to right; the leftmost fold into `...` first.
List<RowAction> previewCardActions({
  required EditorStore store,
  required PreviewCardToolbarState state,
  required void Function() onZoomIn,
  required void Function() onZoomOut,
  required void Function() onStepSim,
}) => <RowAction>[
  RowAction(
    huiText('Pixel grid'),
    () => store.showGrid = !state.grid,
    icon: ArcaneIcon.grid3x3(),
    iconOnly: true,
    pressed: state.grid,
  ),
  RowAction(
    huiText('Step one second of simulation'),
    onStepSim,
    icon: ArcaneIcon.stepForward(),
    iconOnly: true,
  ),
  RowAction(
    huiText('Play'),
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

/// The header's `...` menu.
List<AppMenuEntry> previewCardMoreEntries({
  required void Function() onResetSim,
}) => <AppMenuEntry>[
  AppMenuEntry(
    huiText('Reset the simulated state'),
    onResetSim,
    icon: ArcaneIcon.rotateCcw(),
  ),
];
