/// The Gloss document kinds as EditorSyncDocumentKind declares them, with the
/// `publish:` scope of each (contract section 6.1, the groups of
/// lib/config/kind_groups.dart), and the scope and folder rules.
library;

/// Every scope, in the order the contract shows them.
const List<String> allScopes = <String>[
  'read',
  'history',
  'publish:screens',
  'publish:hud',
  'publish:chat',
  'publish:world',
  'publish:players',
  'publish:shared',
  'publish:images',
  'publish:commands',
];

/// What a pairing grants without `scopes`: everything but publish:commands.
final List<String> defaultScopes = <String>[
  for (final String scope in allScopes)
    if (scope != 'publish:commands') scope,
];

/// [named] in contract order; empty gives the defaults.
List<String> orderedScopes(Iterable<String> named) {
  final Set<String> wanted = named.toSet();
  if (wanted.isEmpty) return defaultScopes;
  return <String>[
    for (final String scope in allScopes)
      if (wanted.contains(scope)) scope,
  ];
}

enum KindLayout {
  folder('folder'),
  tree('tree'),
  single('single'),
  realDrops('real-drops');

  const KindLayout(this.wire);

  final String wire;
}

final RegExp _flatSegment = RegExp(r'^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$');
final RegExp _panelSegment = RegExp(r'^[a-z0-9][a-z0-9._-]{0,63}$');

class GlossKind {
  const GlossKind(this.wire, this.storage, this.layout, this.versioned, this.singletonId, this.scope);

  final String wire;

  /// The folder, or the file for a single kind, under the data folder.
  final String storage;
  final KindLayout layout;
  final bool versioned;
  final String? singletonId;
  final String scope;

  /// The history collection: the storage name without `.json`.
  String get collection => storage.endsWith('.json') ? storage.substring(0, storage.length - 5) : storage;

  /// The data-folder path of document [id], relative to the data folder.
  String path(String id) => layout == KindLayout.single ? storage : '$storage/$id.json';

  /// Why [id] is not a canonical id of this kind, or null when it is.
  String? idProblem(String id) {
    if (singletonId != null) return id == singletonId ? null : '$wire id must be $singletonId';
    final List<String> segments = id.split('/');
    if (layout != KindLayout.tree && segments.length > 1) return '$wire id must not contain /';
    if (id.length > 255) return '$wire id must be at most 255 characters';
    final RegExp segment = wire == 'panel' ? _panelSegment : _flatSegment;
    for (final String part in segments) {
      if (part == '.' || part == '..' || !segment.hasMatch(part)) return '$wire id segment must match ${segment.pattern}: $part';
    }
    return null;
  }

  Map<String, Object?> get meta => <String, Object?>{
        'kind': wire,
        'storage': storage,
        'layout': layout.wire,
        'versioned': versioned,
        'singletonId': singletonId,
        'scope': scope,
      };
}

/// Every kind, by wire name (catalog order).
const List<GlossKind> glossKinds = <GlossKind>[
  GlossKind('animation', 'animations', KindLayout.folder, true, null, 'publish:shared'),
  GlossKind('behavior', 'behaviors', KindLayout.folder, true, null, 'publish:shared'),
  GlossKind('bubble-style', 'bubbles', KindLayout.folder, true, null, 'publish:chat'),
  GlossKind('channel', 'channels', KindLayout.folder, true, null, 'publish:chat'),
  GlossKind('connections', 'connections.json', KindLayout.single, true, 'connections', 'publish:chat'),
  GlossKind('container-preview', 'previews', KindLayout.folder, false, null, 'publish:screens'),
  GlossKind('damage-indicators', 'damage-indicators', KindLayout.folder, true, 'default', 'publish:world'),
  GlossKind('emoji', 'emoji', KindLayout.folder, true, null, 'publish:chat'),
  GlossKind('entity-overlays', 'entity-overlays', KindLayout.folder, true, 'default', 'publish:world'),
  GlossKind('glyph', 'glyphs', KindLayout.folder, true, null, 'publish:shared'),
  GlossKind('hologram', 'holograms', KindLayout.folder, true, null, 'publish:world'),
  GlossKind('inventory', 'inventories', KindLayout.folder, true, null, 'publish:screens'),
  GlossKind('leaderboard', 'leaderboards', KindLayout.folder, true, null, 'publish:hud'),
  GlossKind('marker', 'markers', KindLayout.folder, true, null, 'publish:world'),
  GlossKind('menu', 'menus', KindLayout.tree, false, null, 'publish:screens'),
  GlossKind('motd', 'motd.json', KindLayout.single, true, 'motd', 'publish:hud'),
  GlossKind('nameplate', 'nameplates', KindLayout.folder, true, null, 'publish:players'),
  GlossKind('names', 'names.json', KindLayout.single, true, 'names', 'publish:shared'),
  GlossKind('nametag', 'nametags', KindLayout.folder, true, null, 'publish:players'),
  GlossKind('panel', 'panels', KindLayout.tree, true, null, 'publish:screens'),
  GlossKind('presets', 'presets.json', KindLayout.single, true, 'presets', 'publish:shared'),
  GlossKind('real-drops', 'real-drops', KindLayout.realDrops, true, 'default', 'publish:world'),
  GlossKind('scoreboard', 'boards', KindLayout.folder, true, null, 'publish:hud'),
  GlossKind('strings', 'strings', KindLayout.folder, true, null, 'publish:shared'),
  GlossKind('surface', 'surfaces', KindLayout.folder, true, null, 'publish:hud'),
  GlossKind('tablist', 'tablist.json', KindLayout.single, true, 'tablist', 'publish:hud'),
  GlossKind('waypoint', 'waypoints', KindLayout.folder, true, null, 'publish:world'),
];

final Map<String, GlossKind> _byWire = <String, GlossKind>{for (final GlossKind kind in glossKinds) kind.wire: kind};
final Map<String, GlossKind> _byCollection = <String, GlossKind>{for (final GlossKind kind in glossKinds) kind.collection: kind};

GlossKind? kindNamed(String wire) => _byWire[wire];

GlossKind? kindOfCollection(String collection) => _byCollection[collection];

/// A `folders=` name: 1 to 8 segments of `[a-z0-9_-]{1,64}`.
final RegExp folderPattern = RegExp(r'^[a-z0-9_-]{1,64}(?:/[a-z0-9_-]{1,64}){0,7}$');

/// The folder of a document id or image path: everything before its last
/// slash, or null for a flat id.
String? folderOf(String idOrPath) {
  final int slash = idOrPath.lastIndexOf('/');
  return slash < 0 ? null : idOrPath.substring(0, slash);
}

/// Whether a token limited to [folders] (empty for all) reaches [idOrPath].
bool reaches(List<String> folders, String idOrPath) {
  if (folders.isEmpty) return true;
  final String? folder = folderOf(idOrPath);
  if (folder == null) return false;
  return folders.any((String allowed) => folder == allowed || folder.startsWith('$allowed/'));
}

/// Why [path] is not a usable image path, or null (as image_library.dart).
String? imagePathProblem(String path) {
  if (path.trim().isEmpty || path.length > 256) return 'image path must be 1 to 256 characters';
  if (path.startsWith('/') || path.endsWith('/') || path.contains(r'\') || path.contains(':')) {
    return 'image path must be relative with forward slashes: $path';
  }
  for (final String segment in path.split('/')) {
    if (segment == '..' || segment == '.' || segment.trim().isEmpty) return 'image path has an invalid segment: $path';
  }
  return null;
}
