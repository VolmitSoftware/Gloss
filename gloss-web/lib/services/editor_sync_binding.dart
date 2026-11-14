/// What this browser remembers about a paired server's workspace: the base
/// it started from (the catalog hash of every server document and image it
/// holds), which documents are filled in, the conflicts still open and a
/// publish whose answer never arrived. Kept in localStorage per server, so
/// every tab and every reload knows which documents changed here: a small
/// header and the rows spread over [editorSyncBindingBuckets] records, so a
/// save rewrites only the buckets that changed. It names the workspace store
/// it describes and the revision of that store its rows match: a binding is
/// only compared with that store, never with one that was lost, made again
/// or left behind the rows.
library;

import 'dart:convert';

import '../doctype/doctype.dart';
import '../state/workspace.dart';
import '../state/workspace_panel.dart';
import 'editor_sync.dart';
import 'editor_sync_binding_storage.dart';

const int _bindingVersion = 7;

/// The single-record layout, still read once and then rewritten.
const int _singleRecordVersion = 6;

/// How many records the rows are spread over.
const int editorSyncBindingBuckets = 64;

/// The storage name of one bucket of [serverId]'s rows.
String _bucketName(String serverId, int bucket) => '$serverId.$bucket';

/// A row's bucket: FNV-1a over the key, stable across runs.
int _bucketOf(String key) {
  int hash = 0x811c9dc5;
  for (int index = 0; index < key.length; index++) {
    hash = ((hash ^ key.codeUnitAt(index)) * 0x01000193) & 0xffffffff;
  }
  return hash % editorSyncBindingBuckets;
}

/// One server document this tab holds, with the hash it started from.
final class SyncBaseDocument {
  const SyncBaseDocument({
    required this.kind,
    required this.id,
    required this.hash,
    required this.name,
    this.revision,
    this.docId,
    this.size = 0,
    this.loaded = true,
  });

  final String kind;
  final String id;

  /// The index hash this tab's copy started from; a publication sends it as
  /// `baseHash`.
  final String hash;
  final String name;

  /// The server's revision of a versioned kind.
  final int? revision;

  /// The workspace document holding it, or null for a kind this build cannot
  /// edit: such rows are carried by reference and never published.
  final String? docId;
  final int size;

  /// Whether the workspace holds the server's body. A document listed from
  /// the catalog holds a blank of its kind until its body is read; it is
  /// never published and never counts as changed.
  final bool loaded;

  String get key => editorSyncKey(kind, id);

  bool get editable => docId != null;

  SyncBaseDocument copyWith({
    String? hash,
    int? revision,
    String? name,
    String? docId,
    int? size,
    bool? loaded,
  }) => SyncBaseDocument(
    kind: kind,
    id: id,
    hash: hash ?? this.hash,
    name: name ?? this.name,
    revision: revision ?? this.revision,
    docId: docId ?? this.docId,
    size: size ?? this.size,
    loaded: loaded ?? this.loaded,
  );

  List<Object?> toJson() => <Object?>[
    kind,
    id,
    hash,
    name,
    revision,
    docId,
    size,
    loaded,
  ];

  static SyncBaseDocument? decode(Object? raw) {
    if (raw is! List || raw.length != 8) return null;
    final Object? kind = raw[0];
    final Object? id = raw[1];
    final Object? hash = raw[2];
    final Object? name = raw[3];
    final Object? revision = raw[4];
    final Object? docId = raw[5];
    final Object? size = raw[6];
    final Object? loaded = raw[7];
    if (kind is! String ||
        id is! String ||
        hash is! String ||
        !editorSyncRevisionPattern.hasMatch(hash) ||
        name is! String ||
        (revision != null && revision is! int) ||
        (docId != null && docId is! String) ||
        size is! int ||
        loaded is! bool) {
      return null;
    }
    return SyncBaseDocument(
      kind: kind,
      id: id,
      hash: hash,
      name: name,
      revision: revision as int?,
      docId: docId as String?,
      size: size,
      loaded: loaded,
    );
  }
}

/// One server image this tab knows of.
final class SyncBaseImage {
  const SyncBaseImage({
    required this.path,
    required this.hash,
    required this.size,
    required this.mediaType,
    required this.width,
    required this.height,
    this.local = false,
  });

  final String path;
  final String hash;
  final int size;
  final String mediaType;
  final int width;
  final int height;

  /// Whether the bytes are in this browser's image library. Images arrive in
  /// the background after the documents; a missing local copy of an image
  /// never fetched is not a deletion.
  final bool local;

  SyncBaseImage copyWith({String? hash, int? size, bool? local}) =>
      SyncBaseImage(
        path: path,
        hash: hash ?? this.hash,
        size: size ?? this.size,
        mediaType: mediaType,
        width: width,
        height: height,
        local: local ?? this.local,
      );

  static SyncBaseImage fromRow(SyncImageRow row, {bool local = false}) =>
      SyncBaseImage(
        path: row.path,
        hash: row.hash,
        size: row.size,
        mediaType: row.mediaType,
        width: row.width,
        height: row.height,
        local: local,
      );

  List<Object?> toJson() => <Object?>[
    path,
    hash,
    size,
    mediaType,
    width,
    height,
    local,
  ];

  static SyncBaseImage? decode(Object? raw) {
    if (raw is! List || raw.length != 7) return null;
    final Object? path = raw[0];
    final Object? hash = raw[1];
    final Object? size = raw[2];
    final Object? mediaType = raw[3];
    final Object? width = raw[4];
    final Object? height = raw[5];
    final Object? local = raw[6];
    if (path is! String ||
        hash is! String ||
        !editorSyncRevisionPattern.hasMatch(hash) ||
        size is! int ||
        mediaType is! String ||
        width is! int ||
        height is! int ||
        local is! bool) {
      return null;
    }
    return SyncBaseImage(
      path: path,
      hash: hash,
      size: size,
      mediaType: mediaType,
      width: width,
      height: height,
      local: local,
    );
  }
}

/// The key the image half of [EditorSyncBinding.conflicts] uses.
String editorSyncImageKey(String path) => editorSyncKey('image', path);

/// A publish whose answer never arrived: sent again, with the same
/// idempotency key, before anything else once the server answers again.
final class SyncPendingPublish {
  const SyncPendingPublish(this.idempotencyKey, this.body);

  final String idempotencyKey;

  /// The request body as sent.
  final Map<String, Object?> body;
}

class EditorSyncBinding {
  EditorSyncBinding({
    required this.serverId,
    required this.workspaceId,
    this.metaHash,
    this.notesHash,
    this.pending,
    this.workspaceRevision = 0,
    Map<String, SyncBaseDocument>? documents,
    Map<String, SyncBaseImage>? images,
    Map<String, String>? conflicts,
  }) : documents = documents ?? <String, SyncBaseDocument>{},
       images = images ?? <String, SyncBaseImage>{},
       conflicts = conflicts ?? <String, String>{};

  /// The paired server ([ServerProfile.id]).
  final String serverId;

  /// The workspace store ([Workspace.id]) these rows were read into.
  final String workspaceId;

  /// The meta and notes this browser last read.
  String? metaHash;
  String? notesHash;

  /// A publish sent whose answer is still missing.
  SyncPendingPublish? pending;

  /// The workspace store's revision ([Workspace.storedRevision]) the rows
  /// were saved at: they describe that store as it was then.
  int workspaceRevision;

  /// The rows of each bucket as storage holds them, by identity; null for a
  /// bucket not known to be stored.
  List<List<Object>?> _stored = List<List<Object>?>.filled(
    editorSyncBindingBuckets,
    null,
  );

  /// The header text storage holds.
  String? _storedHeader;

  /// Base rows by [editorSyncKey].
  final Map<String, SyncBaseDocument> documents;

  /// Base images by path.
  final Map<String, SyncBaseImage> images;

  /// Keys the last publish did not apply, to its reason.
  final Map<String, String> conflicts;

  /// The workspace document bound to `(kind, id)`.
  String? documentId(String kind, String id) =>
      documents[editorSyncKey(kind, id)]?.docId;

  /// Whether [doc] is the local copy of a server document.
  bool isDocumentBound(WorkspaceDoc doc) {
    final String? key = editorSyncKeyOf(doc);
    return key != null && documents[key]?.docId == doc.id;
  }

  /// Documents this build edits: every one the workspace lists.
  int get editable {
    int count = 0;
    for (final SyncBaseDocument row in documents.values) {
      if (row.editable) count++;
    }
    return count;
  }

  /// Documents whose body is not filled in yet.
  int get unloaded {
    int count = 0;
    for (final SyncBaseDocument row in documents.values) {
      if (row.editable && !row.loaded) count++;
    }
    return count;
  }

  /// The rows by bucket, documents before images.
  List<List<Object>> _buckets() {
    final List<List<Object>> buckets = <List<Object>>[
      for (int bucket = 0; bucket < editorSyncBindingBuckets; bucket++)
        <Object>[],
    ];
    for (final SyncBaseDocument row in documents.values) {
      buckets[_bucketOf(row.key)].add(row);
    }
    for (final SyncBaseImage row in images.values) {
      buckets[_bucketOf(editorSyncImageKey(row.path))].add(row);
    }
    return buckets;
  }

  static String _encodeBucket(List<Object> rows) =>
      jsonEncode(<String, Object?>{
        'documents': <Object?>[
          for (final Object row in rows)
            if (row is SyncBaseDocument) row.toJson(),
        ],
        'images': <Object?>[
          for (final Object row in rows)
            if (row is SyncBaseImage) row.toJson(),
        ],
      });

  String _encodeHeader() => jsonEncode(<String, Object?>{
    'version': _bindingVersion,
    'serverId': serverId,
    'workspaceId': workspaceId,
    'workspaceRevision': workspaceRevision,
    'metaHash': metaHash,
    'notesHash': notesHash,
    if (pending != null)
      'pending': <String, Object?>{
        'idempotencyKey': pending!.idempotencyKey,
        'body': pending!.body,
      },
    'conflicts': conflicts,
  });

  /// The header fields of [decoded] with [documents] and [images]; null
  /// when they do not have the binding's shape.
  static EditorSyncBinding? _of(
    Map<Object?, Object?> decoded, {
    required Map<String, SyncBaseDocument> documents,
    required Map<String, SyncBaseImage> images,
  }) {
    final Object? serverId = decoded['serverId'];
    final Object? workspaceId = decoded['workspaceId'];
    final Object? revision = decoded['workspaceRevision'];
    final Object? metaHash = decoded['metaHash'];
    final Object? notesHash = decoded['notesHash'];
    if (serverId is! String ||
        workspaceId is! String ||
        (metaHash != null && metaHash is! String) ||
        (notesHash != null && notesHash is! String)) {
      return null;
    }
    final Object? pending = decoded['pending'];
    return EditorSyncBinding(
      serverId: serverId,
      workspaceId: workspaceId,
      workspaceRevision: revision is int ? revision : 0,
      metaHash: metaHash as String?,
      notesHash: notesHash as String?,
      pending:
          pending is Map &&
              pending['idempotencyKey'] is String &&
              pending['body'] is Map
          ? SyncPendingPublish(
              pending['idempotencyKey'] as String,
              (pending['body'] as Map).cast<String, Object?>(),
            )
          : null,
      documents: documents,
      images: images,
      conflicts: <String, String>{
        if (decoded['conflicts'] is Map)
          for (final MapEntry<Object?, Object?> entry
              in (decoded['conflicts'] as Map).entries)
            if (entry.key is String && entry.value is String)
              entry.key! as String: entry.value! as String,
      },
    );
  }

  /// Adds the rows of [rawDocuments] and [rawImages]; false when one is
  /// damaged.
  static bool _readRows(
    Object? rawDocuments,
    Object? rawImages,
    Map<String, SyncBaseDocument> documents,
    Map<String, SyncBaseImage> images,
  ) {
    if (rawDocuments is! List || rawImages is! List) return false;
    for (final Object? entry in rawDocuments) {
      final SyncBaseDocument? row = SyncBaseDocument.decode(entry);
      if (row == null) return false;
      documents[row.key] = row;
    }
    for (final Object? entry in rawImages) {
      final SyncBaseImage? row = SyncBaseImage.decode(entry);
      if (row == null) return false;
      images[row.path] = row;
    }
    return true;
  }
}

/// The sync wire kind of a workspace document, or null for editor-only kinds.
String? editorSyncWireKindOf(WorkspaceDoc doc) =>
    DocumentTypeRegistry.of(doc.kind).syncWireKind;

/// The server id of a workspace document: its runtime id, or a world panel's
/// board id.
String? editorSyncRuntimeIdOf(WorkspaceDoc doc) {
  if (editorSyncWireKindOf(doc) == 'panel') {
    return decodeWorkspacePanel(doc.json).data.runtimeBoardId;
  }
  return doc.runtimeId;
}

/// The [editorSyncKey] a workspace document publishes under, or null.
String? editorSyncKeyOf(WorkspaceDoc doc) {
  final String? kind = editorSyncWireKindOf(doc);
  final String? id = editorSyncRuntimeIdOf(doc);
  return kind == null || id == null ? null : editorSyncKey(kind, id);
}

/// The body a workspace document publishes: the JSON itself, or a world
/// panel's board as canonical JSON. Null for a panel with no board.
String? editorSyncWireJsonOf(WorkspaceDoc doc) {
  if (editorSyncWireKindOf(doc) == 'panel') {
    final Map<String, dynamic>? board = decodeWorkspacePanel(doc.json)
        .data
        .runtimeBoard;
    return board == null ? null : editorSyncCanonicalJson(board);
  }
  return doc.json;
}

/// Loads, saves and clears the binding of one server. Tests pass maps. The
/// header is stored under the server id, each bucket of rows under the
/// server id, a dot and its number.
final class EditorSyncBindingStore {
  const EditorSyncBindingStore({
    this.read = readEditorSyncBinding,
    this.write = writeEditorSyncBinding,
    this.remove = removeEditorSyncBinding,
  });

  final String? Function(String name) read;
  final bool Function(String name, String value) write;
  final void Function(String name) remove;

  /// The binding of [serverId] when it describes [workspace]: the store it
  /// was read into, still holding documents, at or after the revision its
  /// rows were saved at. A store that was lost and made again, came back
  /// empty, or is older than the rows gets a fresh binding: the workspace
  /// is then read from the server again, never compared with rows it does
  /// not hold.
  EditorSyncBinding load(String serverId, Workspace workspace) {
    final EditorSyncBinding? binding = _read(serverId);
    if (binding != null &&
        binding.serverId == serverId &&
        binding.workspaceId == workspace.id &&
        binding.workspaceRevision <= workspace.storedRevision &&
        (!workspace.isEmpty ||
            !binding.documents.values.any(
              (SyncBaseDocument row) => row.editable,
            ))) {
      return binding;
    }
    return EditorSyncBinding(serverId: serverId, workspaceId: workspace.id);
  }

  EditorSyncBinding? _read(String serverId) {
    try {
      final String? raw = read(serverId);
      if (raw == null) return null;
      final Object? decoded = jsonDecode(raw);
      if (decoded is! Map) return null;
      final Map<String, SyncBaseDocument> documents =
          <String, SyncBaseDocument>{};
      final Map<String, SyncBaseImage> images = <String, SyncBaseImage>{};
      if (decoded['version'] == _singleRecordVersion) {
        if (!EditorSyncBinding._readRows(
          decoded['documents'],
          decoded['images'],
          documents,
          images,
        )) {
          return null;
        }
        return EditorSyncBinding._of(
          decoded,
          documents: documents,
          images: images,
        );
      }
      if (decoded['version'] != _bindingVersion) return null;
      for (int bucket = 0; bucket < editorSyncBindingBuckets; bucket++) {
        final String? text = read(_bucketName(serverId, bucket));
        if (text == null) return null;
        final Object? rows = jsonDecode(text);
        if (rows is! Map ||
            !EditorSyncBinding._readRows(
              rows['documents'],
              rows['images'],
              documents,
              images,
            )) {
          return null;
        }
      }
      final EditorSyncBinding? binding = EditorSyncBinding._of(
        decoded,
        documents: documents,
        images: images,
      );
      if (binding == null) return null;
      binding._stored = binding._buckets();
      binding._storedHeader = raw;
      return binding;
    } catch (_) {
      return null;
    }
  }

  /// Writes the header and every bucket whose rows changed since the last
  /// save. False when the browser refused one of them.
  bool save(EditorSyncBinding binding) {
    final List<List<Object>> buckets = binding._buckets();
    bool kept = true;
    for (int bucket = 0; bucket < editorSyncBindingBuckets; bucket++) {
      final List<Object> rows = buckets[bucket];
      final List<Object>? stored = binding._stored[bucket];
      if (stored != null && _same(rows, stored)) continue;
      if (write(
        _bucketName(binding.serverId, bucket),
        EditorSyncBinding._encodeBucket(rows),
      )) {
        binding._stored[bucket] = rows;
      } else {
        binding._stored[bucket] = null;
        kept = false;
      }
    }
    return saveHeader(binding) && kept;
  }

  /// Writes the header alone: the pending publish, the conflicts and the
  /// revision the stored rows match.
  bool saveHeader(EditorSyncBinding binding) {
    final String header = binding._encodeHeader();
    if (header == binding._storedHeader) return true;
    if (!write(binding.serverId, header)) {
      binding._storedHeader = null;
      return false;
    }
    binding._storedHeader = header;
    return true;
  }

  void clear(String serverId) {
    remove(serverId);
    for (int bucket = 0; bucket < editorSyncBindingBuckets; bucket++) {
      remove(_bucketName(serverId, bucket));
    }
  }

  static bool _same(List<Object> a, List<Object> b) {
    if (a.length != b.length) return false;
    for (int index = 0; index < a.length; index++) {
      if (!identical(a[index], b[index])) return false;
    }
    return true;
  }
}
