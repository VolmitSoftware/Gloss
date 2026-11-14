/// What the stage controller tells its listeners, and what it keeps quiet
/// about. Views write the whole scene from inside `build`, so an unchanged
/// scene must not notify.
library;

import 'package:gloss_editor/components/mc/mc_stage_controller.dart';
import 'package:gloss_editor/mc/scene/mc_camera.dart';
import 'package:gloss_editor/mc/scene/mc_math.dart';
import 'package:gloss_editor/mc/scene/mc_scene.dart';
import 'package:test/test.dart';

McStageController _controller() =>
    McStageController(kind: McStageKind.drops, homePivot: McVec3.zero);

McScene _sceneAt(double x, {bool grid = false, bool world = true}) => McScene(
  <McSceneNode>[
    McRigNode(
      key: 'rig',
      rigId: 'player',
      textureId: 'player',
      transform: McMat4.translation(x, 0, 0),
      poseTimeMs: 0,
      hurt: 0,
    ),
  ],
  gridVisible: grid,
  worldVisible: world,
);

void main() {
  test('an equal scene does not notify', () {
    final McStageController controller = _controller();
    int notifications = 0;
    controller.addListener(() => notifications++);
    controller.scene = _sceneAt(0);
    expect(notifications, 1);
    controller.scene = _sceneAt(0);
    expect(notifications, 1);
    expect(controller.scene.byKey['rig'], isNotNull);
  });

  test('a changed node notifies', () {
    final McStageController controller = _controller();
    int notifications = 0;
    controller.scene = _sceneAt(0);
    controller.addListener(() => notifications++);
    controller.scene = _sceneAt(1);
    expect(notifications, 1);
  });

  test('an added or removed node notifies', () {
    final McStageController controller = _controller();
    int notifications = 0;
    controller.scene = _sceneAt(0);
    controller.addListener(() => notifications++);
    controller.scene = McScene(const <McSceneNode>[]);
    expect(notifications, 1);
    controller.scene = _sceneAt(0);
    expect(notifications, 2);
  });

  test('the grid and world toggles notify on their own', () {
    final McStageController controller = _controller();
    int notifications = 0;
    controller.scene = _sceneAt(0);
    controller.addListener(() => notifications++);
    controller.scene = _sceneAt(0, grid: true);
    expect(notifications, 1);
    controller.scene = _sceneAt(0, grid: true, world: false);
    expect(notifications, 2);
    controller.scene = _sceneAt(0, grid: true, world: false);
    expect(notifications, 2);
  });

  test('the webgl verdict notifies once', () {
    final McStageController controller = _controller();
    int notifications = 0;
    controller.addListener(() => notifications++);
    expect(controller.webGlAvailable, isTrue);
    controller.webGlAvailable = false;
    expect(notifications, 1);
    controller.webGlAvailable = false;
    expect(notifications, 1);
    expect(controller.webGlAvailable, isFalse);
  });

  group('showDocument', () {
    test('each document starts at home until its camera is moved', () {
      final McStageController controller = _controller();
      controller.showDocument('a');
      expect(controller.isHome, isTrue);
      controller.camera = controller.camera.copyWith(distance: 2);
      controller.showDocument('b');
      expect(controller.isHome, isTrue);
    });

    test('a moved camera comes back with its document', () {
      final McStageController controller = _controller();
      controller.showDocument('a');
      final McCamera moved = controller.camera.copyWith(distance: 2);
      controller.camera = moved;
      controller.showDocument('b');
      controller.showDocument('a');
      expect(controller.camera, moved);
    });

    test('a camera put back home is forgotten', () {
      final Map<String, McCamera> cameras = <String, McCamera>{};
      final McStageController controller = McStageController(
        kind: McStageKind.drops,
        homePivot: McVec3.zero,
        cameras: cameras,
      );
      controller.showDocument('a');
      controller.camera = controller.camera.copyWith(distance: 2);
      controller.resetCamera();
      controller.showDocument('b');
      expect(cameras, isEmpty);
    });

    test('cameras outlive the controller when the map does', () {
      final Map<String, McCamera> cameras = <String, McCamera>{};
      final McStageController first = McStageController(
        kind: McStageKind.drops,
        homePivot: McVec3.zero,
        cameras: cameras,
      );
      first.showDocument('a');
      final McCamera moved = first.camera.copyWith(distance: 2);
      first.camera = moved;
      first.showDocument(null);
      final McStageController second = McStageController(
        kind: McStageKind.drops,
        homePivot: McVec3.zero,
        cameras: cameras,
      );
      second.showDocument('a');
      expect(second.camera, moved);
    });
  });
}
