/// What a stage view owns and the stage widget reads: the scene, the camera,
/// whether frames should keep coming, the home framing, and the sprite a
/// model falls back to when the browser has no WebGL2.
library;

import 'package:jaspr/jaspr.dart' show ChangeNotifier;

import '../../mc/scene/mc_camera.dart';
import '../../mc/scene/mc_math.dart';
import '../../mc/scene/mc_scene.dart';
import '../../services/catalogs.dart';

/// The stage views' [McStageController.spriteFor]: item and block models from
/// the material catalog, rigs from the entity catalog under their
/// `minecraft:` key (the player only when the catalog carries
/// `minecraft:player`, which it does not today), billboards as they are.
String? mcCatalogSpriteFor(HuiCatalogs catalogs, McSceneNode node) => switch (node) {
  McItemModelNode(:final String itemId) => catalogs.textureFor(itemId),
  McBlockModelNode(:final String blockId) => catalogs.textureFor(blockId),
  McRigNode(:final String rigId) => catalogs.entityTextureFor('minecraft:$rigId'),
  McBillboardNode(:final String textureUrl) => textureUrl,
  McShadowNode() || McWaterNode() || McParticleNode() => null,
};

final class McStageController extends ChangeNotifier {
  McStageController({
    required this.kind,
    required McVec3 homePivot,
    this.freeCamera = true,
    Map<String, McCamera>? cameras,
  })  : _homePivot = homePivot,
        _camera = mcCameraHome(kind, homePivot),
        _cameras = cameras ?? <String, McCamera>{};

  final McStageKind kind;

  /// False inside the game frame: the client does not get to fly.
  final bool freeCamera;

  /// The catalog sprite for a model node, drawn as a DOM billboard where the
  /// model would stand once [webGlAvailable] is false. Null, or a null
  /// answer, draws nothing for that node.
  String? Function(McSceneNode node)? spriteFor;

  McVec3 _homePivot;
  McCamera? _home;
  McCamera _camera;

  /// Cameras moved away from home, per document id (see [showDocument]).
  final Map<String, McCamera> _cameras;
  String? _documentId;
  McScene _scene = McScene.empty;
  bool _animating = false;

  /// Frames rendered so far; the browser verification reads it off
  /// `data-frames` to prove idle costs nothing.
  int frames = 0;

  /// The stage's measured size in CSS pixels, written by the stage widget
  /// through [resize].
  double widthPx = 0;
  double heightPx = 0;

  /// The stage widget measured a new size; views that frame their subject
  /// for the size hear it.
  void resize(double width, double height) {
    if (width == widthPx && height == heightPx) return;
    widthPx = width;
    heightPx = height;
    notifyListeners();
  }

  bool _webGlAvailable = true;

  /// False once `McGl.create` returned null or the pack failed to load; the
  /// stage shows the still and the owning view can drop its 3D-only chrome.
  bool get webGlAvailable => _webGlAvailable;
  set webGlAvailable(bool value) {
    if (value == _webGlAvailable) return;
    _webGlAvailable = value;
    notifyListeners();
  }

  McCamera get camera => _camera;
  set camera(McCamera value) {
    if (value == _camera) return;
    _camera = value;
    notifyListeners();
  }

  /// Stages rebuild the whole scene every frame, so the setter takes the
  /// structural diff and stays quiet when the frame drew the same thing: a
  /// write from inside `build` then never re-enters `setState`.
  McScene get scene => _scene;
  set scene(McScene value) {
    final McScene previous = _scene;
    _scene = value;
    if (value.gridVisible == previous.gridVisible &&
        value.worldVisible == previous.worldVisible &&
        value.diff(previous).isEmpty) {
      return;
    }
    notifyListeners();
  }

  /// True while a stage clock is running, so the canvas keeps drawing.
  bool get animating => _animating;
  set animating(bool value) {
    if (value == _animating) return;
    _animating = value;
    notifyListeners();
  }

  McVec3 get homePivot => _homePivot;
  set homePivot(McVec3 value) {
    if (value == _homePivot) return;
    final bool wasHome = isHome;
    _homePivot = value;
    if (wasHome) _camera = home;
    notifyListeners();
  }

  /// Where the camera rests: [mcCameraHome] for [kind], unless the view frames
  /// its subject itself. A camera resting at home follows a new home; one the
  /// user moved stays where it is.
  McCamera get home => _home ?? mcCameraHome(kind, _homePivot);
  set home(McCamera value) {
    if (value == home) return;
    final bool wasHome = isHome;
    _home = value;
    if (wasHome) camera = value;
  }

  bool get isHome => _camera == home;

  void resetCamera() => camera = home;

  /// The stage now shows document [id]. Each document keeps its own camera:
  /// the one it left behind if it was moved away from home, else home, so a
  /// zoom made on one document never carries over to the next.
  void showDocument(String? id) {
    if (id == _documentId) return;
    final String? leaving = _documentId;
    if (leaving != null) {
      if (isHome) {
        _cameras.remove(leaving);
      } else {
        _cameras[leaving] = _camera;
      }
    }
    _documentId = id;
    camera = (id == null ? null : _cameras[id]) ?? home;
  }

  /// Wakes the stage after an out-of-band change (a pack image prefetched).
  void notify() => notifyListeners();
}
