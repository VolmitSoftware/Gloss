/// An edit made just before the page goes away is not lost: the
/// browser may drop an IndexedDB write still on its way when the page
/// unloads, so the open document is also kept in localStorage at exit and
/// the next load takes it back.
library;

import 'dart:async';

import 'package:gloss_editor/state/workspace.dart';
import 'package:gloss_editor/state/workspace_repository_contract.dart';
import 'package:test/test.dart';

/// A record store whose writes can be held, like an IndexedDB transaction
/// the unloading page never finishes.
final class _SlowRecords implements WorkspaceRepository {
  final Map<String, String> records = <String, String>{};
  bool hold = false;
  final List<Completer<bool>> held = <Completer<bool>>[];

  @override
  FutureOr<String?> read(String key) => records[key];

  @override
  FutureOr<bool> write(String key, String value) {
    if (!hold) {
      records[key] = value;
      return true;
    }
    final Completer<bool> completer = Completer<bool>();
    held.add(completer);
    return completer.future;
  }
}

void main() {
  test('should take back an edit whose write the unload dropped', () async {
    final _SlowRecords records = _SlowRecords();
    final Map<String, String> local = <String, String>{};
    String? read(String key) => local[key];
    bool write(String key, String value) {
      local[key] = value;
      return true;
    }

    final Workspace first = await Workspace.open(
      repository: records,
      read: read,
      write: write,
    );
    final WorkspaceDoc doc = first.create(
      title: 'Lobby',
      runtimeId: 'lobby',
      json: '{"a":1}',
    );
    await first.writesSettled;
    records.hold = true;
    first.updateDocuments(<WorkspaceDocumentUpdate>[
      WorkspaceDocumentUpdate(documentId: doc.id, json: '{"a":2}'),
    ]);
    first.rescueOnExit();

    final Workspace reloaded = await Workspace.open(
      repository: records,
      read: read,
      write: write,
    );
    expect(reloaded.byId(doc.id)!.json, '{"a":2}');
    expect(local['${Workspace.storageKey}.unsaved'], isEmpty);
  });

  test('should change nothing when the write finished', () async {
    final _SlowRecords records = _SlowRecords();
    final Map<String, String> local = <String, String>{};
    final Workspace first = await Workspace.open(
      repository: records,
      read: (String key) => local[key],
      write: (String key, String value) {
        local[key] = value;
        return true;
      },
    );
    final WorkspaceDoc doc = first.create(
      title: 'Lobby',
      runtimeId: 'lobby',
      json: '{"a":1}',
    );
    await first.writesSettled;
    first.rescueOnExit();
    expect(local['${Workspace.storageKey}.unsaved'], isNull);
    final Workspace reloaded = await Workspace.open(
      repository: records,
      read: (String key) => local[key],
      write: (String key, String value) {
        local[key] = value;
        return true;
      },
    );
    expect(reloaded.byId(doc.id)!.json, '{"a":1}');
  });

  test('should keep a paused workspace from writing at all', () async {
    final _SlowRecords records = _SlowRecords();
    final Workspace workspace = await Workspace.open(repository: records);
    workspace.pauseWrites();
    expect(workspace.canWrite, isFalse);
    expect(workspace.save(), isFalse);
    workspace.rescueOnExit();
    expect(records.records, isEmpty);
  });
}
