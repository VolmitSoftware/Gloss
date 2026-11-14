/// What the 3D preview's frame header shows, as a value: the selector compares
/// it, so a store change the header does not show (an edit, a selection, a
/// readout tick) rebuilds nothing.
library;

import '../../preview/preview_types.dart';
import '../../state/editor_store.dart';
import 'preview_pose.dart';

import 'package:gloss_editor/l10n/hui_localizations.dart';

typedef PreviewHeaderState = ({
  PreviewCameraMode camera,
  bool playing,
  bool logOpen,
  bool logEmpty,

  /// Bit `i` set when [previewOverlays] entry `i` is on.
  int overlays,
  double uiScale,
});

/// One overlay switch of the header's menu: the store flag it flips and the
/// stage key (1 to 6) that flips it too.
class PreviewOverlay {
  const PreviewOverlay(this.label, this.key, this.read, this.write);

  /// Localized on every build, so a locale switch relabels the menu.
  final String Function() label;
  final String key;
  final bool Function(EditorStore store) read;
  final void Function(EditorStore store, bool value) write;
}

/// In stage key order (`PreviewStage.onInputToggle`).
final List<PreviewOverlay> previewOverlays = <PreviewOverlay>[
  PreviewOverlay(
    () => huiText('Collision planes'),
    '1',
    (EditorStore store) => store.previewShowPlanes,
    (EditorStore store, bool value) => store.previewShowPlanes = value,
  ),
  PreviewOverlay(
    () => huiText('Plane normals'),
    '2',
    (EditorStore store) => store.previewShowNormals,
    (EditorStore store, bool value) => store.previewShowNormals = value,
  ),
  PreviewOverlay(
    () => huiText('Anchors'),
    '3',
    (EditorStore store) => store.previewShowAnchors,
    (EditorStore store, bool value) => store.previewShowAnchors = value,
  ),
  PreviewOverlay(
    () => huiText('Menu centre'),
    '4',
    (EditorStore store) => store.previewShowCenter,
    (EditorStore store, bool value) => store.previewShowCenter = value,
  ),
  PreviewOverlay(
    () => huiText('maxDistance sphere'),
    '5',
    (EditorStore store) => store.previewShowDistanceSphere,
    (EditorStore store, bool value) => store.previewShowDistanceSphere = value,
  ),
  PreviewOverlay(
    () => huiText('Ground grid'),
    '6',
    (EditorStore store) => store.previewShowGroundGrid,
    (EditorStore store, bool value) => store.previewShowGroundGrid = value,
  ),
];

PreviewHeaderState previewHeaderState(EditorStore store, PreviewPose pose) {
  int overlays = 0;
  for (int index = 0; index < previewOverlays.length; index++) {
    if (previewOverlays[index].read(store)) overlays |= 1 << index;
  }
  return (
    camera: store.previewCameraMode,
    playing: !pose.paused,
    logOpen: store.previewLogOpen,
    logEmpty: pose.log.isEmpty,
    overlays: overlays,
    uiScale: store.previewUiScale,
  );
}
