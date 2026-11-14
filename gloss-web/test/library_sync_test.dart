/// Renaming a server document in the library renames it on the server: the
/// title becomes the last part of its id, its folder stays.
library;

import 'package:gloss_editor/components/panels/library_sync.dart';
import 'package:gloss_editor/doctype/doctype.dart';
import 'package:gloss_editor/services/editor_sync.dart';
import 'package:gloss_editor/state/editor_store.dart';
import 'package:gloss_editor/state/workspace.dart';
import 'package:test/test.dart';

final class _MemoryStorage {
  final Map<String, String> values = <String, String>{};

  String? read(String key) => values[key];

  bool write(String key, String value) {
    values[key] = value;
    return true;
  }
}

Workspace _workspace() {
  final _MemoryStorage storage = _MemoryStorage();
  return EditorStore(
    workspace: Workspace(read: storage.read, write: storage.write),
    autosaveDelay: Duration.zero,
  ).workspace;
}

EditorSyncBinding _binding(String kind, String runtimeId, String docId) =>
    EditorSyncBinding(
      serverId: '0123456789abcdef',
      workspaceId: '00000000-0000-4000-8000-000000000000',
      documents: <String, SyncBaseDocument>{
        editorSyncKey(kind, runtimeId): SyncBaseDocument(
          kind: kind,
          id: runtimeId,
          hash: 'sha256:${'0' * 64}',
          name: runtimeId,
          docId: docId,
        ),
      },
    );

WorkspaceDoc _menu(Workspace workspace, String runtimeId) => workspace.create(
  title: runtimeId,
  runtimeId: runtimeId,
  json: '{}',
  kind: DocumentTypes.menu.kind,
);

void main() {
  test('should keep a rename local in the browser workspace', () {
    final Workspace workspace = _workspace();
    final WorkspaceDoc doc = _menu(workspace, 'shop/main');
    final LibrarySyncRules rules = LibrarySyncRules(null, workspace);
    expect(rules.renamedRuntimeId(doc, 'Shop two'), isNull);
    expect(rules.scopeHint, isNull);
  });

  test('should rename a server document inside its folder', () {
    final Workspace workspace = _workspace();
    final WorkspaceDoc doc = _menu(workspace, 'shop/main');
    final LibrarySyncRules rules = LibrarySyncRules(
      _binding('menu', 'shop/main', doc.id),
      workspace,
    );
    expect(rules.renamedRuntimeId(doc, 'Shop two'), 'shop/shop-two');
    expect(rules.scopeHint, contains('Renaming a document renames its file'));
  });

  test('should keep the id when the title names it already', () {
    final Workspace workspace = _workspace();
    final WorkspaceDoc doc = _menu(workspace, 'lobby');
    final LibrarySyncRules rules = LibrarySyncRules(
      _binding('menu', 'lobby', doc.id),
      workspace,
    );
    expect(rules.renamedRuntimeId(doc, 'lobby'), isNull);
    expect(rules.renamedRuntimeId(doc, '  '), isNull);
    expect(rules.renamedRuntimeId(doc, 'Main hall'), 'main-hall');
  });

  test('should not rename a local document that only shares a key', () {
    final Workspace workspace = _workspace();
    final WorkspaceDoc doc = _menu(workspace, 'shop/main');
    final LibrarySyncRules rules = LibrarySyncRules(
      _binding('menu', 'shop/main', 'another-document'),
      workspace,
    );
    expect(rules.renamedRuntimeId(doc, 'Shop two'), isNull);
  });

  test('should say Publish deletes a server document on the server', () {
    final Workspace workspace = _workspace();
    final WorkspaceDoc served = _menu(workspace, 'shop/main');
    final WorkspaceDoc fresh = _menu(workspace, 'shop/new');
    final LibrarySyncRules server = LibrarySyncRules(
      _binding('menu', 'shop/main', served.id),
      workspace,
    );
    expect(server.deleteMessage(served), isNot(contains('from this browser')));
    expect(server.deleteMessage(served), contains('Publish deletes it on the server'));
    expect(server.deleteMessage(fresh), contains('from this browser'));
    expect(
      LibrarySyncRules(null, workspace).deleteMessage(served),
      contains('from this browser'),
    );
  });
}
