import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:gloss_editor/services/editor_sync.dart' show editorSyncBytesHash, editorSyncCanonicalJson, editorSyncDocumentHash;

import 'kinds.dart';
import 'protocol.dart';

/// `kind NUL id`: sorts by kind, then id, in code-unit order.
String documentKey(String kind, String id) => '$kind\u0000$id';

/// The parsed body of [text] as one JSON object, or why it is not one.
({Map<String, Object?>? json, String? problem}) parseBody(GlossKind kind, String text) {
  final Object? value;
  try {
    value = jsonDecode(text);
  } on FormatException catch (failure) {
    return (json: null, problem: 'not JSON: ${failure.message}');
  }
  if (value is! Map<String, Object?>) return (json: null, problem: 'not a JSON object');
  if (kind.versioned) {
    final Object? revision = value['revision'];
    if (revision is! int || revision < 1) return (json: null, problem: 'revision must be a positive whole number');
  }
  return (json: value, problem: null);
}

final RegExp _colorCodes = RegExp('[&§][0-9a-fk-orA-FK-OR]');

/// Contract 10.1 `summary.name`: the top-level name, title or displayName
/// without colour codes, else the last id segment, at most 80 characters.
String summaryName(String id, Map<String, Object?> json) {
  for (final String field in const <String>['name', 'title', 'displayName']) {
    final Object? value = json[field];
    if (value is String) {
      final String plain = value.replaceAll(_colorCodes, '').trim();
      if (plain.isNotEmpty) return plain.length > 80 ? plain.substring(0, 80) : plain;
    }
  }
  final String last = id.substring(id.lastIndexOf('/') + 1);
  return last.length > 80 ? last.substring(0, 80) : last;
}

/// True when [value] holds an action `{"type":"command","source":"server"}` anywhere.
bool runsCommands(Object? value) {
  if (value is Map<String, Object?>) {
    if (value['type'] == 'command' && value['source'] == 'server') return true;
    return value.values.any(runsCommands);
  }
  if (value is List<Object?>) return value.any(runsCommands);
  return false;
}

/// One document: its exact body text and what the catalog says about it.
class StoredDocument {
  StoredDocument._(this.kind, this.id, this.json, this.parsed)
      : hash = editorSyncDocumentHash(json),
        size = utf8.encode(json).length;

  /// A document from body [text], or null with [problem] set when the text
  /// is not a body of [kind]. Panels are kept in canonical form (10.2).
  static StoredDocument? of(GlossKind kind, String id, String text, [void Function(String problem)? problem]) {
    final ({Map<String, Object?>? json, String? problem}) parsed = parseBody(kind, text);
    if (parsed.json == null) {
      problem?.call(parsed.problem!);
      return null;
    }
    return StoredDocument._(kind, id, kind.wire == 'panel' ? editorSyncCanonicalJson(parsed.json) : text, parsed.json!);
  }

  final GlossKind kind;
  final String id;
  final String json;
  final Map<String, Object?> parsed;
  final String hash;
  final int size;

  String get key => documentKey(kind.wire, id);

  String get path => kind.path(id);

  int? get revision => kind.versioned ? parsed['revision']! as int : null;

  late final bool commands = runsCommands(parsed);

  late final Map<String, Object?> summary = <String, Object?>{
    'name': summaryName(id, parsed),
    'folder': ?folderOf(id),
    if (commands) 'commands': true,
  };

  /// The catalog entry (10.1).
  late final Map<String, Object?> entry = <String, Object?>{
    'kind': kind.wire,
    'id': id,
    'path': path,
    'hash': hash,
    'size': size,
    'revision': ?revision,
    'summary': summary,
  };

  /// A body page entry (10.2).
  late final Map<String, Object?> body = <String, Object?>{
    'kind': kind.wire,
    'id': id,
    'hash': hash,
    'revision': ?revision,
    'json': json,
  };

  /// A batch or single read entry (10.3).
  late final Map<String, Object?> read = <String, Object?>{...body, 'summary': summary};

  late final int entryBytes = jsonBytes(entry);
  late final int bodyBytes = jsonBytes(body);
  late final int readBytes = jsonBytes(read);
}

/// The media type and size an image's bytes declare.
class ImageInfo {
  const ImageInfo(this.mediaType, this.width, this.height);

  final String mediaType;
  final int width;
  final int height;
}

const Set<String> imageMediaTypes = <String>{'image/png', 'image/jpeg', 'image/gif', 'image/webp', 'image/bmp'};

/// Reads the media type from the magic bytes and the size from the header;
/// null when the bytes are none of png, jpeg, gif, webp or bmp.
ImageInfo? imageInfo(Uint8List b) {
  int be16(int at) => (b[at] << 8) | b[at + 1];
  int le16(int at) => b[at] | (b[at + 1] << 8);
  int be32(int at) => (be16(at) << 16) | be16(at + 2);
  int le32(int at) => le16(at) | (le16(at + 2) << 16);
  bool text(int at, String value) => b.length >= at + value.length && String.fromCharCodes(b.sublist(at, at + value.length)) == value;
  if (b.length >= 24 && b[0] == 0x89 && text(1, 'PNG\r\n\u001a\n')) return ImageInfo('image/png', be32(16), be32(20));
  if (b.length >= 10 && (text(0, 'GIF87a') || text(0, 'GIF89a'))) return ImageInfo('image/gif', le16(6), le16(8));
  if (b.length >= 26 && text(0, 'BM')) {
    final int height = le32(22);
    return ImageInfo('image/bmp', le32(18), height >= 0x80000000 ? 0x100000000 - height : height);
  }
  if (b.length >= 30 && text(0, 'RIFF') && text(8, 'WEBP')) {
    if (text(12, 'VP8 ')) return ImageInfo('image/webp', le16(26) & 0x3fff, le16(28) & 0x3fff);
    if (text(12, 'VP8L')) {
      return ImageInfo('image/webp', 1 + (((b[22] & 0x3f) << 8) | b[21]), 1 + (((b[24] & 0x0f) << 10) | (b[23] << 2) | ((b[22] & 0xc0) >> 6)));
    }
    if (text(12, 'VP8X')) return ImageInfo('image/webp', 1 + (le16(24) | (b[26] << 16)), 1 + (le16(27) | (b[29] << 16)));
    return null;
  }
  if (b.length >= 4 && b[0] == 0xff && b[1] == 0xd8) {
    int at = 2;
    while (at + 9 < b.length) {
      if (b[at] != 0xff) return null;
      final int marker = b[at + 1];
      if (marker >= 0xc0 && marker <= 0xcf && marker != 0xc4 && marker != 0xc8 && marker != 0xcc) {
        return ImageInfo('image/jpeg', be16(at + 7), be16(at + 5));
      }
      at += 2 + be16(at + 2);
    }
  }
  return null;
}

class StoredImage {
  StoredImage(this.path, this.bytes, this.info) : hash = editorSyncBytesHash(bytes);

  final String path;
  final Uint8List bytes;
  final ImageInfo info;
  final String hash;

  /// The catalog entry (10.1).
  late final Map<String, Object?> entry = <String, Object?>{
    'path': path,
    'hash': hash,
    'size': bytes.length,
    'mediaType': info.mediaType,
    'width': info.width,
    'height': info.height,
  };

  late final int entryBytes = jsonBytes(entry);
  late final String base64 = base64Encode(bytes);
}

/// One stored version of a document, as `notes.history` lists it.
class HistoryRow {
  HistoryRow(this.collection, this.id, this.version, this.source, this.json);

  final String collection;
  final String id;
  final int version;
  final String source;
  final String json;

  Map<String, Object?> get row => <String, Object?>{
        'kind': collection,
        'id': id,
        'version': version,
        'source': source,
        'bytes': utf8.encode(json).length,
      };
}

/// Documents, images and history in memory. Loading reads a Gloss data folder
/// once (or builds the built-in set); nothing is ever written to disk.
class FakeStore {
  FakeStore(this.dataDirectory);

  final String? dataDirectory;
  final Map<String, StoredDocument> _documents = <String, StoredDocument>{};
  final Map<String, StoredImage> _images = <String, StoredImage>{};
  List<StoredDocument>? _sortedDocuments;
  List<StoredImage>? _sortedImages;

  /// `notes.warnings`: `unreadable|kind|id||why` for files left out at load.
  final List<String> warnings = <String>[];
  final List<HistoryRow> history = <HistoryRow>[];

  List<StoredDocument> get documents => _sortedDocuments ??= _documents.values.toList()
    ..sort((StoredDocument a, StoredDocument b) => a.key.compareTo(b.key));

  List<StoredImage> get images => _sortedImages ??= _images.values.toList()
    ..sort((StoredImage a, StoredImage b) => a.path.compareTo(b.path));

  StoredDocument? document(GlossKind kind, String id) => _documents[documentKey(kind.wire, id)];

  StoredImage? image(String path) => _images[path];

  /// An image with [hash] that [visible] lets the caller see.
  StoredImage? imageWithHash(String hash, bool Function(String path) visible) {
    for (final StoredImage image in _images.values) {
      if (image.hash == hash && visible(image.path)) return image;
    }
    return null;
  }

  void load() {
    _documents.clear();
    _images.clear();
    warnings.clear();
    history.clear();
    _changed();
    final String? root = dataDirectory;
    if (root == null) {
      _builtIn();
    } else {
      _readFolder(Directory(root));
    }
  }

  /// Stores [document], keeping the version it replaces in the history.
  StoredDocument putDocument(StoredDocument document, String source) {
    final StoredDocument? before = _documents[document.key];
    if (before != null) _remember(before, source);
    _documents[document.key] = document;
    _changed();
    return document;
  }

  StoredDocument? deleteDocument(GlossKind kind, String id, String source) {
    final StoredDocument? removed = _documents.remove(documentKey(kind.wire, id));
    if (removed != null) {
      _remember(removed, source);
      _changed();
    }
    return removed;
  }

  StoredImage putImage(StoredImage image) {
    _images[image.path] = image;
    _changed();
    return image;
  }

  StoredImage? deleteImage(String path) {
    final StoredImage? removed = _images.remove(path);
    if (removed != null) _changed();
    return removed;
  }

  /// A stored version, or null when it is no longer kept.
  HistoryRow? version(String collection, String id, int version) {
    for (final HistoryRow row in history) {
      if (row.collection == collection && row.id == id && row.version == version) return row;
    }
    return null;
  }

  void _changed() {
    _sortedDocuments = null;
    _sortedImages = null;
  }

  /// At most 20 versions per document and 2,000 in all, oldest dropped.
  void _remember(StoredDocument document, String source) {
    final String collection = document.kind.collection;
    final List<HistoryRow> own = history.where((HistoryRow row) => row.collection == collection && row.id == document.id).toList();
    final int last = own.isEmpty ? 0 : own.last.version;
    final int stamp = now().millisecondsSinceEpoch;
    history.add(HistoryRow(collection, document.id, stamp > last ? stamp : last + 1, source, document.json));
    if (own.length >= 20) history.remove(own.first);
    if (history.length > 2000) history.removeAt(0);
  }

  void _add(GlossKind kind, String id, String text) {
    final StoredDocument? document = StoredDocument.of(kind, id, text, (String problem) => warnings.add('unreadable|${kind.wire}|$id||$problem'));
    if (document != null) _documents[document.key] = document;
  }

  void _readFolder(Directory root) {
    if (!root.existsSync()) throw ArgumentError('No data folder at ${root.path}');
    final String base = root.absolute.path;
    String relative(FileSystemEntity entity, String under) => entity.absolute.path.substring(under.length + 1).replaceAll(r'\', '/');
    bool hidden(String path) => path.split('/').any((String segment) => segment.startsWith('.'));
    String? text(File file) {
      try {
        return utf8.decode(file.readAsBytesSync());
      } on FormatException {
        return null;
      }
    }

    for (final GlossKind kind in glossKinds) {
      if (kind.layout == KindLayout.single) {
        final File file = File('$base/${kind.storage}');
        final String? content = file.existsSync() ? text(file) : null;
        if (content != null) _add(kind, kind.singletonId!, content);
        continue;
      }
      final Directory folder = Directory('$base/${kind.storage}');
      if (!folder.existsSync()) continue;
      final String under = folder.absolute.path;
      for (final File file in folder.listSync(recursive: kind.layout == KindLayout.tree).whereType<File>()) {
        final String path = relative(file, under);
        if (!path.endsWith('.json') || hidden(path)) continue;
        final String id = path.substring(0, path.length - 5);
        if (kind.idProblem(id) != null) continue;
        final String? content = text(file);
        if (content == null) {
          warnings.add('unreadable|${kind.wire}|$id||not UTF-8');
        } else {
          _add(kind, id, content);
        }
      }
    }
    final Directory images = Directory('$base/images');
    if (!images.existsSync()) return;
    final String under = images.absolute.path;
    for (final File file in images.listSync(recursive: true).whereType<File>()) {
      final String path = relative(file, under);
      if (hidden(path) || imagePathProblem(path) != null) continue;
      final Uint8List bytes = file.readAsBytesSync();
      final ImageInfo? info = imageInfo(bytes);
      if (info == null) {
        warnings.add('unreadable|image|$path||not a png, jpeg, gif, webp or bmp image');
      } else {
        _images[path] = StoredImage(path, bytes, info);
      }
    }
  }

  /// A few documents of each common shape and three images: enough for unit
  /// tests (folders, a command menu, a panel, a singleton, a flat menu).
  void _builtIn() {
    String pretty(Object? value) => '${const JsonEncoder.withIndent('  ').convert(value)}\n';
    Map<String, Object?> hologram(String name, List<String> lines, int revision) => <String, Object?>{
          'schemaVersion': 3,
          'revision': revision,
          'name': name,
          'anchor': <String, Object?>{'world': 'world', 'position': <double>[0, 64, 0]},
          'lines': lines,
        };
    Map<String, Object?> menu(String title, {bool command = false}) => <String, Object?>{
          'title': title,
          'components': <Object?>[
            <String, Object?>{
              'id': 'title',
              'offset': <double>[0, 0, 0],
              'data': <String, Object?>{
                'type': command ? 'button' : 'decoration',
                'icon': <String, Object?>{'type': 'text', 'text': title},
                if (command)
                  'actions': <Object?>[
                    <String, Object?>{'type': 'command', 'source': 'server', 'command': 'give {player} diamond'},
                  ],
              },
            },
          ],
        };
    final Map<String, Map<String, Object?>> documents = <String, Map<String, Object?>>{
      documentKey('hologram', 'welcome'): hologram('&6Welcome', <String>['&6Welcome to Twin Peaks'], 1),
      documentKey('hologram', 'diner'): hologram('&cDouble R Diner', <String>['&cDouble R Diner', '&7Cherry pie'], 2),
      documentKey('hologram', 'sawmill'): hologram('', <String>['&7Packard Sawmill'], 1),
      documentKey('menu', 'shop/main'): menu('&aShop', command: true),
      documentKey('menu', 'shop/sub/hats'): menu('&eHats'),
      documentKey('menu', 'lobby/hub'): menu('&bLobby'),
      documentKey('menu', 'welcome'): menu('&fWelcome'),
      documentKey('panel', 'shop/flow'): <String, Object?>{'id': 'shop/flow', 'revision': 1, 'rootMenuId': 'shop/main', 'schemaVersion': 1},
      documentKey('motd', 'motd'): <String, Object?>{'schemaVersion': 1, 'revision': 1, 'lines': <String>['&bTwin Peaks', '&7The owls are not what they seem']},
      documentKey('animation', 'marquee'): <String, Object?>{'schemaVersion': 1, 'revision': 1, 'name': 'Marquee', 'frameIntervalMs': 80},
    };
    documents.forEach((String key, Map<String, Object?> json) {
      final int split = key.indexOf('\u0000');
      _add(kindNamed(key.substring(0, split))!, key.substring(split + 1), pretty(json));
    });
    for (final (String path, int size, List<int> rgb) in <(String, int, List<int>)>[
      ('shop/coin.png', 16, <int>[230, 190, 40]),
      ('lobby/banner.png', 32, <int>[60, 120, 220]),
      ('logo.png', 8, <int>[200, 40, 60]),
    ]) {
      final Uint8List bytes = solidPng(size, size, rgb);
      _images[path] = StoredImage(path, bytes, imageInfo(bytes)!);
    }
  }
}

/// A [width] by [height] PNG of one opaque colour.
Uint8List solidPng(int width, int height, List<int> rgb) {
  final BytesBuilder rows = BytesBuilder();
  for (int y = 0; y < height; y++) {
    rows.addByte(0);
    for (int x = 0; x < width; x++) {
      rows.add(<int>[rgb[0], rgb[1], rgb[2], 255]);
    }
  }
  List<int> be32(int value) => <int>[(value >> 24) & 0xff, (value >> 16) & 0xff, (value >> 8) & 0xff, value & 0xff];
  List<int> chunk(String type, List<int> data) {
    final List<int> body = <int>[...type.codeUnits, ...data];
    return <int>[...be32(data.length), ...body, ...be32(_crc32(body))];
  }

  return Uint8List.fromList(<int>[
    0x89, ...'PNG\r\n\u001a\n'.codeUnits,
    ...chunk('IHDR', <int>[...be32(width), ...be32(height), 8, 6, 0, 0, 0]),
    ...chunk('IDAT', ZLibCodec().encode(rows.takeBytes())),
    ...chunk('IEND', const <int>[]),
  ]);
}

int _crc32(List<int> bytes) {
  int crc = 0xffffffff;
  for (final int byte in bytes) {
    crc ^= byte;
    for (int bit = 0; bit < 8; bit++) {
      crc = (crc & 1) != 0 ? (crc >> 1) ^ 0xedb88320 : crc >> 1;
    }
  }
  return crc ^ 0xffffffff;
}
