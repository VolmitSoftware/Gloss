/// Camera rules for the pan-and-zoom stages (the menu canvas and the
/// container card). Pure, so the rules are unit tested.
///
/// A stage fits its content when a document opens, is imported or is switched
/// to, and whenever its box changes size, until the user moves the camera by
/// hand. That hand-set camera is kept per document and comes back with the
/// document; Reset view forgets it and fits again.
library;

/// The longest a stage holds its paint for the game font, the catalogs and
/// the sprites it draws; after that it paints whatever has arrived.
const Duration stageAssetHold = Duration(milliseconds: 1500);

/// What a stage does with its camera before it draws.
enum StageCamera { keep, fit, restore }

class StageFitMemory<V> {
  /// [cameras] holds the hand-set cameras by document id. Pass a map that
  /// outlives the stage (the store's) so a remounted stage restores them.
  StageFitMemory({Map<String, V>? cameras}) : _cameras = cameras ?? <String, V>{};

  final Map<String, V> _cameras;
  String? _docId;
  int? _fitRequest;

  /// The document the stage framed last.
  String? get docId => _docId;

  /// The shown document's camera was set by hand.
  bool get handSet => _docId != null && _cameras.containsKey(_docId);

  /// The shown document's hand-set camera, if any.
  V? get handView => _docId == null ? null : _cameras[_docId];

  /// Decides the camera for [docId] before a frame. [fitRequest] is the
  /// document's fit request count (the store bumps it on an import or a
  /// template); [resized] says the stage's box changed size since the last
  /// call.
  StageCamera next({
    required String? docId,
    required int fitRequest,
    bool resized = false,
  }) {
    final bool switched = docId != _docId;
    final bool requested =
        !switched && _fitRequest != null && fitRequest != _fitRequest;
    _docId = docId;
    _fitRequest = fitRequest;
    if (docId == null) return StageCamera.keep;
    if (requested) {
      _cameras.remove(docId);
      return StageCamera.fit;
    }
    if (switched) {
      return _cameras.containsKey(docId) ? StageCamera.restore : StageCamera.fit;
    }
    if (resized && !_cameras.containsKey(docId)) return StageCamera.fit;
    return StageCamera.keep;
  }

  /// The user zoomed or panned the shown document to [view].
  void moved(V view) {
    final String? id = _docId;
    if (id != null) _cameras[id] = view;
  }

  /// Reset view: the shown document fits again.
  void reset() {
    final String? id = _docId;
    if (id != null) _cameras.remove(id);
  }
}

/// The `data-content-box` hook of a stage surface: the content's left, top,
/// right and bottom edges as fractions of the surface's width and height
/// (below 0 or above 1 past an edge), so a browser check can place the
/// content on screen whatever CSS zoom or transform scales the surface. Null
/// while the surface has no area.
String? stageContentBox({
  required double left,
  required double top,
  required double right,
  required double bottom,
  required double width,
  required double height,
}) {
  if (width <= 0 || height <= 0) return null;
  String fraction(double value, double size) =>
      (value / size).toStringAsFixed(4);
  return '${fraction(left, width)} ${fraction(top, height)} '
      '${fraction(right, width)} ${fraction(bottom, height)}';
}
