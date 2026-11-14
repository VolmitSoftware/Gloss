import 'package:gloss_editor/logic/gloss_hologram_scene.dart';
import 'package:gloss_editor/mc/scene/mc_camera.dart';
import 'package:gloss_editor/mc/scene/mc_math.dart';
import 'package:gloss_editor/mc/scene/mc_projection_bridge.dart';
import 'package:gloss_editor/model/model.dart';
import 'package:gloss_editor/preview/preview_types.dart';
import 'package:gloss_editor/preview/projection.dart';
import 'package:test/test.dart';

const McVec3 _anchor = McVec3(0.5, 0, 0.5);

GlossHologramDoc _doc(List<String> lines) => GlossHologramDoc(
  anchor: GlossHologramAnchor(world: 'world', positionRaw: <num>[0, 64, 0]),
  lines: lines,
);

PVec3 _p(McVec3 v) => PVec3(v.x, v.y, v.z);

/// Where the stack's four corners land on screen. The default billboard faces
/// the camera, so the stack grows along the camera's up from the anchor and
/// spreads along its right.
List<ProjectedPoint?> _corners(
  McCamera camera,
  HologramStackSize stack,
  double width,
  double height,
) => <ProjectedPoint?>[
  for (final double up in <double>[stack.bottom, stack.bottom + stack.height])
    for (final double side in <double>[-stack.width / 2, stack.width / 2])
      projectToScreen(
        basis: mcCameraBasis(camera),
        point: _p(_anchor + camera.up * up + camera.right * side),
        viewportWidth: width,
        viewportHeight: height,
        perspectivePx: huiPreviewPerspectivePx,
      ),
];

void main() {
  final Map<String, GlossHologramDoc> docs = <String, GlossHologramDoc>{
    'two-line sign': _doc(<String>['&6&lMarket', '&7Trade supplies here']),
    'ten lines': _doc(<String>[for (int i = 0; i < 10; i++) '&fLine $i']),
    'one wide line': _doc(<String>[
      '&bWelcome to the server, read the rules at spawn first',
    ]),
  };
  const List<(double, double)> frames = <(double, double)>[
    (1100, 620),
    (720, 540),
    (358, 520),
    (358, 300),
    (300, 240),
  ];

  for (final MapEntry<String, GlossHologramDoc> entry in docs.entries) {
    for (final (double width, double height) in frames) {
      test(
        '${entry.key} sits inside a ${width}x$height frame above the HUD',
        () {
          final HologramStackSize stack = hologramStackSize(entry.value);
          final McCamera camera = hologramFrameCamera(
            anchor: _anchor,
            stack: stack,
            viewportWidth: width,
            viewportHeight: height,
          );
          final double hud = glossHudHeightGuiPx * glossGuiScale(width, height);
          for (final ProjectedPoint? corner in _corners(
            camera,
            stack,
            width,
            height,
          )) {
            expect(corner, isNotNull);
            expect(
              corner!.x,
              greaterThanOrEqualTo(hologramFrameMarginPx - 0.5),
            );
            expect(
              corner.x,
              lessThanOrEqualTo(width - hologramFrameMarginPx + 0.5),
            );
            expect(corner.y, greaterThanOrEqualTo(hologramFrameMarginPx - 0.5));
            expect(
              corner.y,
              lessThanOrEqualTo(height - hud - hologramFrameMarginPx + 0.5),
            );
          }
        },
      );
    }
  }

  test('a short hologram is seen from the client eye, no nearer than the '
      'frame camera', () {
    final HologramStackSize stack = hologramStackSize(_doc(<String>['&aHi']));
    final McCamera camera = hologramFrameCamera(
      anchor: _anchor,
      stack: stack,
      viewportWidth: 1100,
      viewportHeight: 620,
    );
    expect(camera.distance, mcCameraHome(McStageKind.frame, _anchor).distance);
    expect(camera.eye.y, closeTo(mcCameraEyeHeight, 1e-6));
    expect(camera.yawDeg, 180);
  });

  test('the stack size counts rows, the widest row and the box padding', () {
    final GlossHologramDoc doc = _doc(<String>['&fAB', '&fABCD']);
    final HologramStackSize plain = hologramStackSize(doc);
    expect(plain.height, closeTo(2 * glossHologramLineHeightBlocks, 1e-9));
    // "ABCD" is four 6 px glyphs plus the background pixel.
    expect(plain.width, closeTo(25 * glossTextBlocksPerPixel, 1e-9));
    expect(plain.bottom, 0);
    doc.box.enabled = true;
    final HologramStackSize boxed = hologramStackSize(doc);
    final double inset = hologramBoxInsetBlocks(doc);
    expect(inset, greaterThan(0));
    expect(boxed.height, closeTo(plain.height + 2 * inset, 1e-9));
    expect(boxed.bottom, -inset);
  });
}
