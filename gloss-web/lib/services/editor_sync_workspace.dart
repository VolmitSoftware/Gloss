/// How server documents become workspace documents and back, and the hash
/// memo that tells an edited document from an untouched one without hashing
/// the whole workspace on every keystroke.
library;

import 'dart:convert';

import '../config/defaults.dart';
import '../doctype/doctype.dart';
import '../model/model.dart';
import '../state/workspace.dart';
import '../state/workspace_panel.dart';
import 'editor_sync.dart';
import 'image_library.dart';

final class _DocHash {
  const _DocHash(this.json, this.kind, this.runtimeId, this.key, this.hash);

  final String json;
  final WorkspaceDocKind kind;
  final String? runtimeId;
  final String? key;
  final String? hash;

  bool matches(WorkspaceDoc doc) =>
      (identical(json, doc.json) || json == doc.json) &&
      kind == doc.kind &&
      runtimeId == doc.runtimeId;
}

/// The key and hash of one workspace document as it would publish.
typedef SyncDocIdentity = ({String? key, String? hash});

/// Hashes keyed by workspace document id (and image path), recomputed only
/// when the document's text, kind or runtime id changed.
final class SyncHashMemo {
  final Map<String, _DocHash> _docs = <String, _DocHash>{};
  final Map<String, (String, String)> _images = <String, (String, String)>{};

  /// The identity of [doc], computing it on a miss.
  SyncDocIdentity of(WorkspaceDoc doc) {
    final _DocHash? cached = _docs[doc.id];
    if (cached != null && cached.matches(doc)) {
      return (key: cached.key, hash: cached.hash);
    }
    final _DocHash computed = _compute(doc);
    _docs[doc.id] = computed;
    return (key: computed.key, hash: computed.hash);
  }

  /// The identity of [doc] when it is already known, else null.
  SyncDocIdentity? peek(WorkspaceDoc doc) {
    final _DocHash? cached = _docs[doc.id];
    if (cached == null || !cached.matches(doc)) return null;
    return (key: cached.key, hash: cached.hash);
  }

  /// Records what an import wrote, so a fresh pairing hashes nothing.
  void seed(WorkspaceDoc doc, String key, String hash) {
    _docs[doc.id] = _DocHash(doc.json, doc.kind, doc.runtimeId, key, hash);
  }

  void seedJson({
    required String docId,
    required String json,
    required WorkspaceDocKind kind,
    required String? runtimeId,
    required String key,
    required String hash,
  }) {
    _docs[docId] = _DocHash(json, kind, runtimeId, key, hash);
  }

  /// The image hash when it is already known, else null.
  String? peekImageHash(StoredImage image) {
    final (String, String)? cached = _images[image.path];
    if (cached == null ||
        !(identical(cached.$1, image.dataUri) || cached.$1 == image.dataUri)) {
      return null;
    }
    return cached.$2;
  }

  /// The SHA-256 of an image's bytes.
  String imageHash(StoredImage image) {
    final (String, String)? cached = _images[image.path];
    if (cached != null &&
        (identical(cached.$1, image.dataUri) || cached.$1 == image.dataUri)) {
      return cached.$2;
    }
    final String hash = editorSyncBytesHash(
      decodeDataUriBytes(image.dataUri) ?? const <int>[],
    );
    _images[image.path] = (image.dataUri, hash);
    return hash;
  }

  void clear() {
    _docs.clear();
    _images.clear();
  }

  static _DocHash _compute(WorkspaceDoc doc) {
    final String? kind = editorSyncWireKindOf(doc);
    if (kind == null) {
      return _DocHash(doc.json, doc.kind, doc.runtimeId, null, null);
    }
    String? id;
    String? wire;
    if (editorSyncWireKindOf(doc) == 'panel') {
      final WorkspacePanelData data = decodeWorkspacePanel(doc.json).data;
      id = data.runtimeBoardId;
      wire = data.runtimeBoard == null
          ? null
          : editorSyncCanonicalJson(data.runtimeBoard);
    } else {
      id = doc.runtimeId;
      wire = doc.json;
    }
    String? hash;
    if (wire != null) {
      try {
        hash = editorSyncDocumentHash(wire);
      } catch (_) {
        hash = 'invalid';
      }
    }
    return _DocHash(
      doc.json,
      doc.kind,
      doc.runtimeId,
      id == null ? null : editorSyncKey(kind, id),
      hash,
    );
  }
}

/// Whether the workspace holds only what a fresh workspace starts with:
/// nothing, or the untouched sample menu. A server's first read replaces it.
bool editorSyncIsSampleWorkspace(Workspace workspace, ImageLibrary images) {
  if (images.images.isNotEmpty || workspace.folders.isNotEmpty) return false;
  final List<WorkspaceDoc> docs = workspace.docs;
  if (docs.isEmpty) return true;
  if (docs.length != 1) return false;
  final WorkspaceDoc doc = docs.single;
  return editorSyncWireKindOf(doc) == 'menu' &&
      doc.runtimeId == huiDefaultMenuId &&
      doc.json == encodeHuiMenu(createDefaultMenu());
}

/// The workspace JSON for a server body: the body itself, or for a world
/// panel the editor's panel wrapper around the board (keeping [existing]'s
/// layout when the panel is already here).
String editorSyncWorkspaceJson({
  required String kind,
  required String id,
  required String json,
  String? existing,
  String? folderId,
  List<String> menuIds = const <String>[],
  List<String> imagePaths = const <String>[],
}) {
  if (kind != 'panel') return json;
  final WorkspacePanelData base = existing == null
      ? WorkspacePanelData(scopeFolderId: folderId)
      : decodeWorkspacePanel(existing).data;
  return encodeWorkspacePanel(
    base.copyWith(
      runtimeBoardId: id,
      runtimeBoard: Map<String, dynamic>.from(jsonDecode(json) as Map),
      syncMenuIds: menuIds,
      syncImagePaths: imagePaths,
    ),
  );
}

/// [json] with its top-level `revision` set to [revision]; for a panel
/// wrapper, the board's revision. Unchanged text when it already matches.
String editorSyncWithRevision(String kind, String json, int revision) {
  final Object? decoded = jsonDecode(json);
  if (decoded is! Map) return json;
  if (kind == 'panel' && decoded.containsKey('runtimeBoard')) {
    final WorkspacePanelData data = decodeWorkspacePanel(json).data;
    final Map<String, dynamic>? board = data.runtimeBoard;
    if (board == null || board['revision'] == revision) return json;
    return encodeWorkspacePanel(
      data.copyWith(
        runtimeBoard: <String, dynamic>{...board, 'revision': revision},
      ),
    );
  }
  if (decoded['revision'] == revision) return json;
  final Map<String, Object?> patched = <String, Object?>{
    ...decoded.cast<String, Object?>(),
    'revision': revision,
  };
  if (kind == 'panel') return editorSyncCanonicalJson(patched);
  return json.contains('\n')
      ? const JsonEncoder.withIndent('  ').convert(patched)
      : jsonEncode(patched);
}

/// One server document to write into the workspace.
final class SyncDocWrite {
  const SyncDocWrite({
    required this.key,
    required this.json,
    required this.title,
    this.docId,
  });

  final String key;

  /// The wire body.
  final String json;
  final String title;

  /// The workspace document it replaces, or null for a new one.
  final String? docId;
}

/// What writing [writes] does to [workspace], for one
/// [Workspace.applyBatch]: new folders, new documents (each in its kind's
/// top-level folder, made when missing), updates of documents already there,
/// and the workspace id each written key lands in. Documents in [removed]
/// are not written to.
({
  List<WorkspaceFolder> folders,
  List<WorkspaceDoc> documents,
  List<WorkspaceDocumentUpdate> updates,
  Map<String, String> docIds,
})
editorSyncBatch({
  required Workspace workspace,
  required List<SyncDocWrite> writes,
  Set<String> removed = const <String>{},
  List<String> menuIds = const <String>[],
  List<String> imagePaths = const <String>[],
  String Function() newId = newWorkspaceUuid,
}) {
  final Set<String> usedIds = <String>{
    for (final WorkspaceDoc doc in workspace.docs) doc.id,
    for (final WorkspaceFolder folder in workspace.folders) folder.id,
  };
  String freshId() {
    String id = newId();
    while (!usedIds.add(id)) {
      id = newId();
    }
    return id;
  }

  final List<WorkspaceFolder> folders = <WorkspaceFolder>[];
  final Map<String, String> folderByTitle = <String, String>{
    for (final WorkspaceFolder folder in workspace.folders)
      if (folder.parentId == null) folder.title: folder.id,
  };
  String folderFor(DocumentTypeAdapter adapter) =>
      folderByTitle.putIfAbsent(adapter.pluralLabel, () {
        final WorkspaceFolder folder = WorkspaceFolder(
          id: freshId(),
          title: adapter.pluralLabel,
          updatedAt: 0,
        );
        folders.add(folder);
        return folder.id;
      });

  final List<WorkspaceDoc> documents = <WorkspaceDoc>[];
  final List<WorkspaceDocumentUpdate> updates = <WorkspaceDocumentUpdate>[];
  final Map<String, String> docIds = <String, String>{};
  for (final SyncDocWrite write in writes) {
    final String kind = editorSyncKeyKind(write.key);
    final String id = editorSyncKeyId(write.key);
    final DocumentTypeAdapter? adapter = DocumentTypeRegistry.byWireKind(kind);
    if (adapter == null) continue;
    final WorkspaceDoc? existing =
        write.docId == null || removed.contains(write.docId)
        ? null
        : workspace.byId(write.docId);
    if (existing != null) {
      if (docIds.containsValue(existing.id)) continue;
      updates.add(
        WorkspaceDocumentUpdate(
          documentId: existing.id,
          json: editorSyncWorkspaceJson(
            kind: kind,
            id: id,
            json: write.json,
            existing: existing.json,
            menuIds: menuIds,
            imagePaths: imagePaths,
          ),
        ),
      );
      docIds[write.key] = existing.id;
      continue;
    }
    final String folderId = folderFor(adapter);
    final WorkspaceDoc doc = WorkspaceDoc(
      id: freshId(),
      title: write.title,
      runtimeId: kind == 'panel' ? null : id,
      json: editorSyncWorkspaceJson(
        kind: kind,
        id: id,
        json: write.json,
        folderId: folderId,
        menuIds: menuIds,
        imagePaths: imagePaths,
      ),
      updatedAt: 0,
      kind: adapter.kind,
      folderId: folderId,
    );
    documents.add(doc);
    docIds[write.key] = doc.id;
  }
  return (
    folders: folders,
    documents: documents,
    updates: updates,
    docIds: docIds,
  );
}

/// A blank document of [wireKind]: what a server document listed from the
/// catalog holds until its body is read. Null for a kind that has none
/// (world panels), whose bodies are read at once instead.
String? editorSyncBlankJson(String wireKind) {
  final DocumentTypeAdapter? adapter = DocumentTypeRegistry.byWireKind(
    wireKind,
  );
  if (adapter is GlossDocumentTypeAdapter) {
    return adapter.encodeDoc(adapter.newBlank());
  }
  return switch (wireKind) {
    'menu' => encodeHuiMenu(createDefaultMenu()),
    'container-preview' => encodeHuiPreviewDoc(HuiPreviewDoc()),
    _ => null,
  };
}
