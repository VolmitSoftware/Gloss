/// What a paired server's workspace keeps in browser storage across a
/// reload: the binding never claims more than the workspace store holds.
library;

import 'dart:async';

import 'package:gloss_editor/services/editor_sync_controller.dart';
import 'package:gloss_editor/services/server/server_hub.dart';
import 'package:gloss_editor/services/server/server_profiles.dart';
import 'package:gloss_editor/state/workspace.dart';
import 'package:gloss_editor/state/workspace_repository.dart';
import 'package:test/test.dart';

import '../tool/fake_server/server.dart';
import 'support/io_transport.dart';
import 'support/sync_tab.dart';

/// A store over a map that answers later, as IndexedDB does, and can refuse
/// every write, as a full one does.
final class _LaterStore implements WorkspaceRepository {
  _LaterStore(this.values);

  final Map<String, String> values;
  bool refuse = false;

  @override
  Future<String?> read(String key) async => values[key];

  @override
  Future<bool> write(String key, String value) async {
    await Future<void>.delayed(Duration.zero);
    if (refuse) return false;
    values[key] = value;
    return true;
  }
}

void main() {
  late SyncHarness harness;

  setUp(() async {
    harness = SyncHarness(
      await FakeGlossServer.start(),
      ServerHub(
        profiles: ServerProfileStore(
          local: MemoryKeyValueStore(),
          session: MemoryKeyValueStore(),
        ),
        open: ioTransports(),
        handshakeTimeout: const Duration(seconds: 3),
      ),
    );
  });

  tearDown(() => harness.close());

  test(
    'should keep the binding behind a workspace write that failed',
    () async {
      for (int index = 0; index < 60; index++) {
        await control(harness.server, 'edit', <String, Object?>{
          'kind': 'hologram',
          'id': 'seed-h$index',
          'json': hologramJson('Line $index'),
        });
      }
      final String id = await harness.pair();
      await control(harness.server, 'latency', <String, Object?>{
        'millis': 250,
      });
      final Map<String, String> stored = <String, String>{};
      final _LaterStore store = _LaterStore(stored);
      final Tab first = await harness.tab(id, repository: store);
      await waitFor(
        () =>
            first.doc('hologram', 'seed-h59') != null &&
            !first.workspace.hasUnsavedChanges &&
            !first.workspace.hasPendingWrites,
        reason: 'the documents listed by name and stored',
      );
      expect(first.sync.binding.unloaded, greaterThan(0));
      store.refuse = true;
      await waitFor(
        () => first.sync.binding.unloaded == 0,
        reason: 'the bodies landed in this tab',
      );
      await first.workspace.writesSettled;
      expect(first.workspace.hasUnsavedChanges, isTrue);
      await harness.closeTab(first);

      await control(harness.server, 'latency', <String, Object?>{'millis': 0});
      store.refuse = false;
      final Tab reload = await harness.tab(
        id,
        reloadOf: first,
        repository: _LaterStore(stored),
      );
      await reload.settled();
      expect(
        reload.sync.changes.where(
          (SyncChange change) => change.state == SyncDocState.changedHere,
        ),
        isEmpty,
        reason: 'a blank the store kept is not an edit',
      );
      expect(reload.sync.pendingChangeCount, 0);
      final WorkspaceDoc filled = reload.doc('hologram', 'seed-h42')!;
      expect(filled.json, contains('Line 42'));
    },
  );
}
