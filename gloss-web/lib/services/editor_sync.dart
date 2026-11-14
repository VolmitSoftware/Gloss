/// The editor's half of editor API 1 (sync-relay/README.md, editor API
/// sections 9 to 12): the wire types, the save bar status and the hashes.
///
/// The server lists every document and image in a paged catalog with
/// summaries, serves bodies in pages or by key and images by content hash,
/// pushes catalog changes as events, and takes a changeset that names only
/// what changed, each entry with the hash it started from. Kinds this build
/// cannot edit are never named, so they survive untouched.
library;

import 'dart:convert';

import 'package:crypto/crypto.dart' as crypto;

import '../config/defaults.dart' show isCanonicalMenuId;
import '../l10n/hui_localizations.dart';
import '../logic/gloss_show.dart';
import '../logic/sync_problems.dart';
import '../logic/validation.dart';
import 'image_library.dart' show validateImagePath;
import 'server/transport.dart' show relayBodyBytes;

export 'editor_sync_binding.dart';

/// Contract limits (section 14).
const int huiEditorSyncMaxDocuments = 20000;
const int huiEditorSyncMaxImages = 4096;
const int huiEditorSyncMaxDocumentBytes = 2 * 1024 * 1024;
const int huiEditorSyncMaxImageBytes = 512 * 1024;
const int huiEditorSyncMaxDocumentIdChars = 256;

/// Document entries one publish may carry.
const int huiEditorSyncMaxPublishDocuments = 2000;

/// Image entries one publish may carry.
const int huiEditorSyncMaxPublishImages = 4096;

/// A publish deleting more than this many documents and images, and more
/// than [huiEditorSyncConfirmDeletesPercent] percent of what the pairing
/// reaches, is sent only after the owner agrees, with `confirmDeletes`.
const int huiEditorSyncConfirmDeletesAbove = 25;
const int huiEditorSyncConfirmDeletesPercent = 20;

/// Whether deleting [deletes] of the [total] documents and images a pairing
/// reaches needs the owner's yes (the server refuses it otherwise).
bool editorSyncDeletesNeedConfirm(int deletes, int total) =>
    deletes > huiEditorSyncConfirmDeletesAbove &&
    deletes * 100 > total * huiEditorSyncConfirmDeletesPercent;

/// Refs one batch read may carry.
const int huiEditorSyncMaxReadRefs = 200;

/// Hashes one image read may carry.
const int huiEditorSyncMaxReadImages = 64;

/// The body page the editor asks for on the direct path.
const int huiEditorSyncDirectPageBytes = 4 * 1024 * 1024;

/// The body page the editor asks for over the relay (a frame less 16 KiB).
const int huiEditorSyncRelayPageBytes = relayBodyBytes;
const int huiEditorSyncMaxSafeInteger = 9007199254740991;

/// What the save bar, the Server button and the Server sheet say about the
/// server.
enum EditorSyncStatus {
  /// Opening the connection before anything was read.
  connecting,

  /// Reading the catalog or filling documents in the background.
  loading,
  connected,

  /// A publish is on its way.
  publishing,

  /// A publish waits for the owner's answer to a question (many deletions).
  asking,

  /// The last publish landed.
  applied,

  /// A document changed here and on the server, or a publish conflicted:
  /// pick a copy.
  conflict,

  /// The server refused the last publish.
  rejected,

  /// The connection dropped; it reconnects by itself. Changes are kept.
  reconnecting,

  /// Not connected and not trying (Disconnect).
  offline,

  /// The pairing was revoked or expired: pair again.
  ended,

  /// A different server answered, or one that speaks another protocol.
  refused,

  /// Another tab of this browser holds this server's workspace.
  elsewhere,
}

/// Whether Publish is offered in [status].
bool canPublishEditorSyncStatus(EditorSyncStatus status) => switch (status) {
  EditorSyncStatus.connected ||
  EditorSyncStatus.loading ||
  EditorSyncStatus.applied ||
  EditorSyncStatus.conflict ||
  EditorSyncStatus.rejected => true,
  _ => false,
};

/// Whether [status] needs the owner to act before anything moves again.
bool isTerminalEditorSyncStatus(EditorSyncStatus status) => switch (status) {
  EditorSyncStatus.ended ||
  EditorSyncStatus.refused ||
  EditorSyncStatus.elsewhere => true,
  _ => false,
};

/// `kind\u0000id`, the key of one server document.
String editorSyncKey(String kind, String id) => '$kind\u0000$id';

/// The kind half of [editorSyncKey].
String editorSyncKeyKind(String key) => key.substring(0, key.indexOf('\u0000'));

/// The id half of [editorSyncKey].
String editorSyncKeyId(String key) => key.substring(key.indexOf('\u0000') + 1);

/// The document hash: SHA-256 over the canonical JSON of the body, the same
/// value the plugin computes over its file.
String editorSyncDocumentHash(String json) =>
    'sha256:${crypto.sha256.convert(utf8.encode(_canonicalJson(jsonDecode(json))))}';

/// The image hash: SHA-256 over the raw bytes.
String editorSyncBytesHash(List<int> bytes) =>
    'sha256:${crypto.sha256.convert(bytes)}';

/// The 64 hex characters of a `sha256:` hash, as image URLs use them.
String editorSyncHashHex(String hash) =>
    hash.startsWith('sha256:') ? hash.substring(7) : hash;

/// Canonical JSON text for [value] under the frozen canonicalization
/// (lexicographic keys, array order, ECMAScript number spelling). Panel
/// documents are required to be in this form on the wire.
String editorSyncCanonicalJson(Object? value) => _canonicalJson(value);

String? editorSyncPanelDefinitionProblem(
  Map<String, dynamic> board,
  Iterable<String> menuIds,
) {
  try {
    _validatePanelDefinition(board, menuIds.toSet());
    return null;
  } on FormatException catch (error) {
    return huiText(error.message.toString());
  }
}

/// A catalog entry's summary (section 10.1).
final class SyncSummary {
  const SyncSummary({required this.name, this.folder, this.commands = false});

  final String name;
  final String? folder;

  /// The body runs a console command: publishing it needs `publish:commands`.
  final bool commands;
}

/// One catalog entry of a document.
final class SyncDocumentRow {
  const SyncDocumentRow({
    required this.kind,
    required this.id,
    required this.hash,
    required this.size,
    required this.summary,
    this.path,
    this.revision,
    this.source,
  });

  final String kind;
  final String id;
  final String hash;

  /// UTF-8 bytes of the body.
  final int size;
  final SyncSummary summary;

  /// The file under the plugin's folder.
  final String? path;

  /// The document's own revision, for versioned kinds.
  final int? revision;

  /// Who changed it, on an event entry: `editor`, `server` or `file`.
  final String? source;

  String get key => editorSyncKey(kind, id);

  String get name => summary.name;

  static SyncDocumentRow decode(Object? raw) {
    if (raw is! Map) throw const SyncMalformed();
    final Object? kind = raw['kind'];
    final Object? id = raw['id'];
    final Object? hash = raw['hash'];
    final Object? size = raw['size'];
    final Object? revision = raw['revision'];
    final Object? summary = raw['summary'];
    if (kind is! String ||
        !editorSyncKindPattern.hasMatch(kind) ||
        id is! String ||
        id.isEmpty ||
        id.length > huiEditorSyncMaxDocumentIdChars ||
        hash is! String ||
        !editorSyncRevisionPattern.hasMatch(hash) ||
        (revision != null && revision is! int)) {
      throw const SyncMalformed();
    }
    final Object? name = summary is Map ? summary['name'] : null;
    final Object? folder = summary is Map ? summary['folder'] : null;
    return SyncDocumentRow(
      kind: kind,
      id: id,
      hash: hash,
      size: size is int ? size : 0,
      revision: revision as int?,
      path: raw['path'] is String ? raw['path'] as String : null,
      source: raw['source'] is String ? raw['source'] as String : null,
      summary: SyncSummary(
        name: name is String && name.isNotEmpty ? name : id.split('/').last,
        folder: folder is String && folder.isNotEmpty ? folder : null,
        commands: summary is Map && summary['commands'] == true,
      ),
    );
  }
}

/// One catalog entry of an image.
final class SyncImageRow {
  const SyncImageRow({
    required this.path,
    required this.hash,
    required this.size,
    required this.mediaType,
    required this.width,
    required this.height,
  });

  final String path;
  final String hash;
  final int size;
  final String mediaType;
  final int width;
  final int height;

  static SyncImageRow decode(Object? raw) {
    if (raw is! Map) throw const SyncMalformed();
    final Object? path = raw['path'];
    final Object? hash = raw['hash'];
    final Object? size = raw['size'];
    final Object? mediaType = raw['mediaType'];
    final Object? width = raw['width'];
    final Object? height = raw['height'];
    if (path is! String ||
        !_validSyncImagePath(path) ||
        hash is! String ||
        !editorSyncRevisionPattern.hasMatch(hash) ||
        mediaType is! String ||
        !mediaType.startsWith('image/')) {
      throw const SyncMalformed();
    }
    return SyncImageRow(
      path: path,
      hash: hash,
      size: size is int ? size : 0,
      mediaType: mediaType,
      width: width is int ? width : 0,
      height: height is int ? height : 0,
    );
  }
}

/// A catalog page, or a catalog event: new versions and removals.
final class SyncCatalog {
  const SyncCatalog({
    this.documents = const <SyncDocumentRow>[],
    this.deleted = const <String>[],
    this.images = const <SyncImageRow>[],
    this.removedImages = const <String>[],
    this.metaHash,
    this.notesHash,
    this.next,
  });

  final List<SyncDocumentRow> documents;

  /// Keys ([editorSyncKey]) removed.
  final List<String> deleted;
  final List<SyncImageRow> images;
  final List<String> removedImages;
  final String? metaHash;
  final String? notesHash;

  /// The cursor of the next page; null on the last.
  final String? next;

  bool get isEmpty =>
      documents.isEmpty &&
      deleted.isEmpty &&
      images.isEmpty &&
      removedImages.isEmpty;

  /// A catalog page (`GET /v1/catalog`) or event (`type: catalog`), or the
  /// delta of a publish answer.
  static SyncCatalog decode(Object? raw) {
    if (raw is! Map) throw const SyncMalformed();
    List<Object?> list(String name) =>
        raw[name] is List ? raw[name] as List<Object?> : const <Object?>[];
    return SyncCatalog(
      documents: List<SyncDocumentRow>.unmodifiable(<SyncDocumentRow>[
        for (final Object? entry in list('documents'))
          SyncDocumentRow.decode(entry),
      ]),
      deleted: List<String>.unmodifiable(<String>[
        for (final Object? entry in list('deleted'))
          if (entry is Map && entry['kind'] is String && entry['id'] is String)
            editorSyncKey(entry['kind'] as String, entry['id'] as String),
      ]),
      images: List<SyncImageRow>.unmodifiable(<SyncImageRow>[
        for (final Object? entry in list('images')) SyncImageRow.decode(entry),
      ]),
      removedImages: List<String>.unmodifiable(<String>[
        for (final Object? entry in list('removedImages'))
          if (entry is Map && entry['path'] is String)
            entry['path'] as String
          else if (entry is String)
            entry,
      ]),
      metaHash: raw['metaHash'] is String ? raw['metaHash'] as String : null,
      notesHash: raw['notesHash'] is String ? raw['notesHash'] as String : null,
      next: raw['next'] is String ? raw['next'] as String : null,
    );
  }
}

final class SyncKindInfo {
  const SyncKindInfo({
    required this.kind,
    required this.layout,
    required this.versioned,
    this.singletonId,
    this.scope,
  });

  final String kind;
  final String layout;
  final bool versioned;
  final String? singletonId;

  /// The `publish:` scope a change of this kind needs.
  final String? scope;
}

/// `GET /v1/meta`: kinds, schemas and defaults.
final class SyncMeta {
  const SyncMeta({
    required this.hash,
    required this.kinds,
    this.schemas = const <String, Object?>{},
    this.defaults = const <String, Object?>{},
  });

  final String? hash;
  final List<SyncKindInfo> kinds;
  final Map<String, Object?> schemas;
  final Map<String, Object?> defaults;

  SyncKindInfo? kind(String kind) {
    for (final SyncKindInfo info in kinds) {
      if (info.kind == kind) return info;
    }
    return null;
  }

  bool isVersioned(String kind) =>
      this.kind(kind)?.versioned ??
      (kind != 'menu' && kind != 'container-preview');

  /// The scope publishing [kind] needs, by the server's word.
  String? scopeOf(String kind) => this.kind(kind)?.scope;

  static SyncMeta decode(Object? raw, {String? hash}) {
    if (raw is! Map) throw const SyncMalformed();
    final List<SyncKindInfo> kinds = <SyncKindInfo>[];
    final Object? rawKinds = raw['kinds'];
    if (rawKinds is List) {
      for (final Object? entry in rawKinds) {
        if (entry is! Map || entry['kind'] is! String) continue;
        kinds.add(
          SyncKindInfo(
            kind: entry['kind'] as String,
            layout: entry['layout'] is String
                ? entry['layout'] as String
                : 'folder',
            versioned: entry['versioned'] == true,
            singletonId: entry['singletonId'] is String
                ? entry['singletonId'] as String
                : null,
            scope: entry['scope'] is String ? entry['scope'] as String : null,
          ),
        );
      }
    }
    final Object? schemas = raw['schemas'];
    final Object? defaults = raw['defaults'];
    return SyncMeta(
      hash: hash,
      kinds: List<SyncKindInfo>.unmodifiable(kinds),
      schemas: schemas is Map
          ? Map<String, Object?>.unmodifiable(schemas.cast<String, Object?>())
          : const <String, Object?>{},
      defaults: defaults is Map
          ? Map<String, Object?>.unmodifiable(defaults.cast<String, Object?>())
          : const <String, Object?>{},
    );
  }
}

/// `GET /v1/notes`: the plugin's findings and the stored versions.
final class SyncNotes {
  const SyncNotes({
    required this.hash,
    this.warnings = const <String>[],
    this.history = const <Object?>[],
  });

  static const SyncNotes empty = SyncNotes(hash: null);

  final String? hash;
  final List<String> warnings;
  final List<Object?> history;

  static SyncNotes decode(Object? raw, {String? hash}) {
    if (raw is! Map) return SyncNotes(hash: hash);
    final Object? warnings = raw['warnings'];
    final Object? history = raw['history'];
    return SyncNotes(
      hash: hash,
      warnings: List<String>.unmodifiable(<String>[
        if (warnings is List)
          for (final Object? warning in warnings)
            if (warning is String) warning,
      ]),
      history: history is List
          ? List<Object?>.unmodifiable(history)
          : const <Object?>[],
    );
  }
}

/// One document body.
final class SyncDocumentBody {
  const SyncDocumentBody({
    required this.kind,
    required this.id,
    required this.hash,
    required this.json,
    this.revision,
  });

  final String kind;
  final String id;
  final String hash;

  /// The exact text the hash is computed from.
  final String json;
  final int? revision;

  String get key => editorSyncKey(kind, id);

  static SyncDocumentBody decode(Object? raw) {
    if (raw is! Map ||
        raw['kind'] is! String ||
        raw['id'] is! String ||
        raw['hash'] is! String ||
        raw['json'] is! String ||
        (raw['json'] as String).isEmpty) {
      throw const SyncMalformed();
    }
    final Object? revision = raw['revision'];
    return SyncDocumentBody(
      kind: raw['kind'] as String,
      id: raw['id'] as String,
      hash: raw['hash'] as String,
      json: raw['json'] as String,
      revision: revision is int ? revision : null,
    );
  }
}

/// One entry of a changeset.
final class SyncDocumentChange {
  const SyncDocumentChange.put({
    required this.kind,
    required this.id,
    required this.baseHash,
    required String this.json,
  }) : delete = false;

  const SyncDocumentChange.delete({
    required this.kind,
    required this.id,
    required String this.baseHash,
  }) : delete = true,
       json = null;

  final bool delete;
  final String kind;
  final String id;
  final String? baseHash;
  final String? json;

  String get key => editorSyncKey(kind, id);

  Map<String, Object?> toJson() => <String, Object?>{
    'op': delete ? 'delete' : 'put',
    'kind': kind,
    'id': id,
    'baseHash': baseHash,
    if (!delete) 'json': json,
  };
}

final class SyncImageChange {
  const SyncImageChange.put({
    required this.path,
    required this.baseHash,
    required String this.hash,
    required String this.mediaType,
    required List<int> this.bytes,
  }) : delete = false;

  const SyncImageChange.delete({
    required this.path,
    required String this.baseHash,
  }) : delete = true,
       hash = null,
       mediaType = null,
       bytes = null;

  final bool delete;
  final String path;
  final String? baseHash;
  final String? hash;
  final String? mediaType;

  /// The raw bytes, uploaded with `POST /v1/images` before the changeset.
  final List<int>? bytes;

  Map<String, Object?> toJson() => <String, Object?>{
    'op': delete ? 'delete' : 'put',
    'path': path,
    'baseHash': baseHash,
    if (!delete) 'hash': hash,
    if (!delete) 'mediaType': mediaType,
  };
}

final class SyncChangeset {
  const SyncChangeset({
    this.documents = const <SyncDocumentChange>[],
    this.images = const <SyncImageChange>[],
  });

  final List<SyncDocumentChange> documents;
  final List<SyncImageChange> images;

  bool get isEmpty => documents.isEmpty && images.isEmpty;

  int get length => documents.length + images.length;

  /// Documents and images this changeset deletes.
  int get deletes =>
      documents.where((SyncDocumentChange change) => change.delete).length +
      images.where((SyncImageChange change) => change.delete).length;

  /// The body of `POST /v1/changes`; [confirmDeletes] is the deletion count
  /// the owner agreed to.
  Map<String, Object?> toJson(String idempotencyKey, {int? confirmDeletes}) =>
      <String, Object?>{
        'idempotencyKey': idempotencyKey,
        'confirmDeletes': ?confirmDeletes,
        'documents': <Map<String, Object?>>[
          for (final SyncDocumentChange change in documents) change.toJson(),
        ],
        'images': <Map<String, Object?>>[
          for (final SyncImageChange change in images) change.toJson(),
        ],
      };
}

/// The answer to `POST /v1/changes` (section 12).
final class SyncPublishResult {
  const SyncPublishResult({
    required this.status,
    required this.message,
    required this.conflicts,
    required this.delta,
    this.changeId,
  });

  /// `applied`, `conflict` or `rejected`.
  final String status;
  final String message;
  final List<SyncConflict> conflicts;

  /// The catalog delta of the apply.
  final SyncCatalog delta;
  final String? changeId;

  bool get applied => status == 'applied';

  static SyncPublishResult decode(Object? raw) {
    if (raw is! Map || raw['status'] is! String) {
      throw const SyncMalformed();
    }
    return SyncPublishResult(
      status: raw['status'] as String,
      message: raw['message'] is String ? raw['message'] as String : '',
      conflicts: SyncConflict.of(raw['conflicts']),
      delta: SyncCatalog.decode(raw),
      changeId: raw['changeId'] is String ? raw['changeId'] as String : null,
    );
  }
}

/// An answer that does not have the contract's shape.
final class SyncMalformed implements Exception {
  const SyncMalformed();

  @override
  String toString() => 'malformed server answer';
}

/// A request the server refused or the path could not carry, in plain words.
final class EditorSyncFailure implements Exception {
  const EditorSyncFailure(
    this.message, {
    this.code,
    this.status = 0,
    this.error,
  });

  /// One sentence, then the next step.
  final String message;

  /// The server's error code or the transport failure code.
  final String? code;
  final int status;

  /// The server's whole `error` object, for the fields a code carries
  /// (`deletes` and `total` on `confirm-deletes`).
  final Map<String, Object?>? error;

  /// A whole-number field of [error], or null.
  int? errorCount(String field) {
    final Object? value = error?[field];
    return value is int ? value : null;
  }

  /// The pairing is gone: the token was revoked or expired.
  bool get ended =>
      code == 'unauthorized' ||
      code == 'token-expired' ||
      code == 'token-revoked';

  @override
  String toString() => message;
}

/// The revision grammar shared with the relay and the plugin: a lowercase
/// SHA-256 with its algorithm prefix.
final RegExp editorSyncRevisionPattern = RegExp(r'^sha256:[0-9a-f]{64}$');

final RegExp editorSyncKindPattern = RegExp(r'^[a-z][a-z0-9-]{0,31}$');

final RegExp _uuidPattern = RegExp(
  r'^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$',
);
final RegExp _worldKeyPattern = RegExp(r'^[a-z0-9._-]+:[a-z0-9/._-]+$');
final RegExp _permissionPattern = RegExp(r'^[a-z0-9][a-z0-9._-]*$');

Map<String, dynamic> _copyStringMap(Map raw) => <String, dynamic>{
  for (final MapEntry<Object?, Object?> entry in raw.entries)
    if (entry.key is String) entry.key! as String: entry.value,
};

String _canonicalJson(Object? value) {
  if (value is num) {
    return _canonicalNumber(value);
  }
  if (value == null || value is bool || value is String) {
    return jsonEncode(value);
  }
  if (value is List) {
    return '[${value.map(_canonicalJson).join(',')}]';
  }
  if (value is Map) {
    final List<String> keys = <String>[
      for (final Object? key in value.keys)
        if (key is String) key,
    ]..sort();
    if (keys.length != value.length) {
      throw const FormatException(
        'Canonical JSON object keys must be strings.',
      );
    }
    return '{${keys.map((String key) => '${jsonEncode(key)}:${_canonicalJson(value[key])}').join(',')}}';
  }
  throw const FormatException('Canonical JSON contains an unsupported value.');
}

String _canonicalNumber(num value) {
  final double numeric = value.toDouble();
  if (!numeric.isFinite) {
    throw const FormatException('Canonical JSON numbers must be finite.');
  }
  if (numeric == 0) return '0';
  final String encoded = numeric.toString();
  if (encoded.endsWith('.0')) {
    return encoded.substring(0, encoded.length - 2);
  }
  return encoded.replaceFirstMapped(
    RegExp(r'e([+-]?)0+(\d+)'),
    (Match match) => 'e${match.group(1)}${match.group(2)}',
  );
}

void _validatePanelDefinition(Map<String, dynamic> board, Set<String> menuIds) {
  _requireExactKeys(
    <String, dynamic>{'show': true, ...board},
    const <String>{
      'schemaVersion',
      'id',
      'uuid',
      'revision',
      'rootMenuId',
      'transform',
      'follow',
      'visibility',
      'show',
    },
  );
  final Object? show = board['show'];
  if (show != null &&
      show is! bool &&
      (show is! String || show.trim().isEmpty)) {
    throw const FormatException('show must be a boolean or expression.');
  }
  final List<HuiIssue> showIssues = validateGlossShow(show);
  if (showIssues.isNotEmpty) {
    throw FormatException(showIssues.first.message);
  }
  final Object? schemaVersion = board['schemaVersion'];
  final Object? id = board['id'];
  final Object? uuid = board['uuid'];
  final Object? revision = board['revision'];
  final Object? rootMenuId = board['rootMenuId'];
  if (schemaVersion != 1 ||
      id is! String ||
      !isCanonicalMenuId(id) ||
      id != id.toLowerCase() ||
      uuid is! String ||
      !_uuidPattern.hasMatch(uuid) ||
      revision is! num ||
      !revision.isFinite ||
      revision.toInt() != revision ||
      revision.toInt() < 1 ||
      revision.toInt() > huiEditorSyncMaxSafeInteger ||
      rootMenuId is! String ||
      !menuIds.contains(rootMenuId)) {
    throw const FormatException('The world panel identity is invalid.');
  }
  _validateBoardTransform(board['transform']);
  _validateBoardFollow(board['follow']);
  _validateBoardVisibility(board['visibility']);
}

void _validateBoardTransform(Object? raw) {
  final Map<String, dynamic> transform = _strictStringMap(
    raw,
    'world panel transform',
  );
  _requireExactKeys(transform, const <String>{
    'worldKey',
    'worldUuid',
    'x',
    'y',
    'z',
    'yaw',
    'pitch',
    'roll',
    'scale',
  });
  final Object? worldKey = transform['worldKey'];
  final Object? worldUuid = transform['worldUuid'];
  if (worldKey is! String ||
      worldKey.length > 255 ||
      !_worldKeyPattern.hasMatch(worldKey) ||
      worldUuid is! String ||
      !_uuidPattern.hasMatch(worldUuid)) {
    throw const FormatException('The world panel transform is invalid.');
  }
  for (final String field in <String>[
    'x',
    'y',
    'z',
    'yaw',
    'pitch',
    'roll',
    'scale',
  ]) {
    final Object? value = transform[field];
    if (value is! num || !value.isFinite) {
      throw const FormatException('The world panel transform is invalid.');
    }
  }
  final num scale = transform['scale']! as num;
  if (scale < 0.05 || scale > 16) {
    throw const FormatException('The world panel scale is invalid.');
  }
}

void _validateBoardFollow(Object? raw) {
  final Map<String, dynamic> follow = _strictStringMap(
    raw,
    'world panel follow settings',
  );
  _requireExactKeys(follow, const <String>{
    'mode',
    'targetPlayerUuid',
    'rotation',
  });
  final Object? mode = follow['mode'];
  final Object? target = follow['targetPlayerUuid'];
  final Object? rotation = follow['rotation'];
  if (mode == 'none') {
    if (target != null || rotation != 'fixed') {
      throw const FormatException(
        'The world panel follow settings are invalid.',
      );
    }
    return;
  }
  if (mode != 'player' ||
      target is! String ||
      !_uuidPattern.hasMatch(target) ||
      !const <String>{'fixed', 'yaw', 'full'}.contains(rotation)) {
    throw const FormatException('The world panel follow settings are invalid.');
  }
}

void _validateBoardVisibility(Object? raw) {
  final Map<String, dynamic> visibility = _strictStringMap(
    raw,
    'world panel visibility',
  );
  _requireExactKeys(visibility, const <String>{
    'mode',
    'viewPermission',
    'interactPermission',
    'viewRange',
    'interactionRange',
  });
  final Object? mode = visibility['mode'];
  final Object? viewPermission = visibility['viewPermission'];
  final Object? interactPermission = visibility['interactPermission'];
  final Object? viewRange = visibility['viewRange'];
  final Object? interactionRange = visibility['interactionRange'];
  if (!const <String>{'public', 'permission', 'hidden'}.contains(mode) ||
      !_validPermission(viewPermission) ||
      !_validPermission(interactPermission) ||
      viewRange is! num ||
      !viewRange.isFinite ||
      viewRange <= 0 ||
      viewRange > 256 ||
      interactionRange is! num ||
      !interactionRange.isFinite ||
      interactionRange <= 0 ||
      interactionRange > 32 ||
      interactionRange > viewRange ||
      (mode == 'permission' && viewPermission == null) ||
      (mode != 'permission' && viewPermission != null) ||
      (mode == 'hidden' && interactPermission != null)) {
    throw const FormatException('The world panel visibility is invalid.');
  }
}

Map<String, dynamic> _strictStringMap(Object? raw, String label) {
  if (raw is! Map) throw FormatException(_invalidMapMessage(label));
  final Map<String, dynamic> result = _copyStringMap(raw);
  if (result.length != raw.length) {
    throw FormatException(_invalidMapMessage(label));
  }
  return result;
}

String _invalidMapMessage(String label) => switch (label) {
  'world panel transform' => huiText('Invalid world panel transform.'),
  'world panel follow settings' => huiText(
    'Invalid world panel follow settings.',
  ),
  'world panel visibility' => huiText('Invalid world panel visibility.'),
  _ => throw ArgumentError.value(label, 'label'),
};

void _requireExactKeys(Map<String, dynamic> value, Set<String> keys) {
  if (value.length != keys.length || !value.keys.toSet().containsAll(keys)) {
    throw const FormatException(
      'World panel definitions cannot contain unsupported fields.',
    );
  }
}

bool _validPermission(Object? value) =>
    value == null || (value is String && _permissionPattern.hasMatch(value));

bool _validSyncImagePath(String path) {
  if (validateImagePath(path) != null || path != path.trim()) return false;
  for (final String segment in path.split('/')) {
    if (segment == '.' || segment == '..' || segment.startsWith('.')) {
      return false;
    }
  }
  return true;
}
