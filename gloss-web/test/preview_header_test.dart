import 'package:gloss_editor/components/preview/preview_header.dart';
import 'package:gloss_editor/components/preview/preview_pose.dart';
import 'package:gloss_editor/preview/action_log.dart';
import 'package:gloss_editor/preview/preview_types.dart';
import 'package:gloss_editor/state/editor_store.dart';
import 'package:gloss_editor/state/workspace.dart';
import 'package:test/test.dart';

class _FakeStorage {
  final Map<String, String> values = <String, String>{};

  String? read(String key) => values[key];

  bool write(String key, String value) {
    values[key] = value;
    return true;
  }
}

EditorStore _store() {
  final _FakeStorage storage = _FakeStorage();
  return EditorStore(
    workspace: Workspace(
      read: storage.read,
      write: storage.write,
      autoLoad: true,
    ),
    autosaveDelay: Duration.zero,
  );
}

void main() {
  group('preview header state', () {
    test('ignores store changes the header does not show', () {
      final EditorStore store = _store();
      final PreviewPose pose = PreviewPose();
      final PreviewHeaderState before = previewHeaderState(store, pose);
      store.addComponent('decoration', id: 'extra');
      pose.live.update(
        hoveredId: 'extra',
        hoveredIds: 1,
        hoverPushBlocks: 0.2,
        distanceToCenter: 3,
        isOpen: true,
        closeReason: null,
        movementLocked: false,
      );
      expect(previewHeaderState(store, pose), before);
    });

    test('follows the camera, clock, log and uiScale', () {
      final EditorStore store = _store();
      final PreviewPose pose = PreviewPose();
      final PreviewHeaderState before = previewHeaderState(store, pose);
      expect(before.camera, PreviewCameraMode.orbit);
      expect(before.playing, isTrue);
      expect(before.logOpen, isTrue);
      expect(before.logEmpty, isTrue);

      store.previewCameraMode = PreviewCameraMode.player;
      pose.paused = true;
      store.previewLogOpen = false;
      pose.log.addAll(<ActionLogEntry>[
        const ActionLogEntry(
          tick: 1,
          componentId: 'title',
          trigger: ActionLogTrigger.button,
          clickTrigger: 'left_click',
          actions: <LoggedAction>[],
        ),
      ]);
      store.previewUiScale = 1.5;

      final PreviewHeaderState after = previewHeaderState(store, pose);
      expect(after.camera, PreviewCameraMode.player);
      expect(after.playing, isFalse);
      expect(after.logOpen, isFalse);
      expect(after.logEmpty, isFalse);
      expect(after.uiScale, 1.5);
    });

    test('overlay switches carry the stage keys 1 to 6 in order', () {
      expect(
        <String>[
          for (final PreviewOverlay overlay in previewOverlays) overlay.key,
        ],
        <String>['1', '2', '3', '4', '5', '6'],
      );
    });

    test('each overlay switch flips its own store flag and bit', () {
      final List<bool Function(EditorStore)> flags =
          <bool Function(EditorStore)>[
            (EditorStore s) => s.previewShowPlanes,
            (EditorStore s) => s.previewShowNormals,
            (EditorStore s) => s.previewShowAnchors,
            (EditorStore s) => s.previewShowCenter,
            (EditorStore s) => s.previewShowDistanceSphere,
            (EditorStore s) => s.previewShowGroundGrid,
          ];
      for (int index = 0; index < previewOverlays.length; index++) {
        final EditorStore store = _store();
        final PreviewPose pose = PreviewPose();
        final PreviewOverlay overlay = previewOverlays[index];
        final bool was = flags[index](store);
        overlay.write(store, !was);
        expect(flags[index](store), !was, reason: overlay.key);
        expect(overlay.read(store), !was, reason: overlay.key);
        expect(
          previewHeaderState(store, pose).overlays & (1 << index) != 0,
          !was,
          reason: overlay.key,
        );
      }
    });
  });
}
