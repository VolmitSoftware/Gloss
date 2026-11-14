/// Server writes that land while the editor is busy: an in-game save inside
/// a publish, catalog changes during an autosave or a body read, and the
/// background fill across a reconnect.
library;

import 'dart:async';
import 'dart:convert';

import 'package:gloss_editor/services/editor_sync.dart';
import 'package:gloss_editor/services/editor_sync_controller.dart';
import 'package:gloss_editor/services/editor_sync_workspace.dart';
import 'package:gloss_editor/services/server/server_hub.dart';
import 'package:gloss_editor/services/server/server_profiles.dart';
import 'package:gloss_editor/services/server/transport.dart';
import 'package:gloss_editor/state/workspace.dart';
import 'package:gloss_editor/state/workspace_repository.dart';
import 'package:test/test.dart';

import '../tool/fake_server/kinds.dart';
import '../tool/fake_server/server.dart';
import '../tool/fake_server/store.dart' show StoredDocument;
import 'support/io_transport.dart';
import 'support/sync_tab.dart';

/// A workspace store whose writes take [delay], like a large IndexedDB put.
final class SlowRepository implements WorkspaceRepository {
  SlowRepository({this.delay = const Duration(milliseconds: 80)});

  final Duration delay;
  final Map<String, String> values = <String, String>{};

  /// Payloads written and not stored yet.
  final List<String> inFlight = <String>[];

  @override
  String? read(String key) => values[key];

  @override
  Future<bool> write(String key, String value) async {
    inFlight.add(value);
    await Future<void>.delayed(delay);
    inFlight.remove(value);
    values[key] = value;
    return true;
  }

  /// The ids of the documents the stored record of [key] holds.
  Set<String> storedIds(String key) => <String>{
    for (final Object? entry
        in (jsonDecode(values[key]!) as Map<String, Object?>)['documents']!
            as List<Object?>)
      (entry! as Map<String, Object?>)['id']! as String,
  };
}

/// A direct path whose answers to matching requests wait for [release].
final class HeldTransport implements ServerTransport {
  HeldTransport(this.inner, this.holds);

  final ServerTransport inner;
  final bool Function(ApiRequest request) holds;

  /// Completed to let the held answers through.
  static Completer<void> release = Completer<void>();
  static int held = 0;

  @override
  ConnectionPath get path => inner.path;

  @override
  Future<ApiResponse> send(ApiRequest request) async {
    final ApiResponse response = await inner.send(request);
    if (holds(request) && !release.isCompleted) {
      held++;
      await release.future;
    }
    return response;
  }

  @override
  Future<EventSubscription> subscribe(String token) => inner.subscribe(token);

  @override
  Future<void> close() => inner.close();
}

/// The server's own copy of a hologram.
StoredDocument? serverHologram(FakeGlossServer server, String id) =>
    server.api.store.document(kindNamed('hologram')!, id);

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

  Future<void> seed(String id, String line) => control(
    harness.server,
    'edit',
    <String, Object?>{'kind': 'hologram', 'id': id, 'json': hologramJson(line)},
  );

  group('an in-game save inside a publish', () {
    for (final bool older in <bool>[true, false]) {
      test(
        'should keep the in-game copy when the answer ${older ? 'carries' : 'leaves out'} the other change',
        () async {
          await seed('x', 'Base X');
          await seed('y', 'Trade supplies here');
          await seed('z', 'Base Z');
          final String id = await harness.pair();
          final Tab browser = await harness.tab(id);
          await browser.settled();
          await control(harness.server, 'older-answers', <String, Object?>{
            'on': older,
          });
          browser.edit(browser.doc('hologram', 'x')!, hologramJson('Mine X'));
          await control(harness.server, 'edit', <String, Object?>{
            'kind': 'hologram',
            'id': 'y',
            'json': hologramJson('Ingame Y'),
            'quiet': true,
          });
          await browser.sync.publish();
          expect(browser.sync.status, EditorSyncStatus.applied);
          await waitFor(
            () => browser.doc('hologram', 'y')!.json.contains('Ingame Y'),
            reason: 'the in-game copy of y to land',
          );
          final WorkspaceDoc y = browser.doc('hologram', 'y')!;
          expect(browser.sync.stateOf(y.id), isNull);
          expect(browser.sync.pendingChangeCount, 0);

          browser.edit(browser.doc('hologram', 'z')!, hologramJson('Mine Z'));
          await waitFor(() => browser.sync.pendingChangeCount == 1);
          await browser.sync.publish();
          final Map<String, Object?> request =
              (harness.server.state()['lastPublish']!
                      as Map<String, Object?>)['request']!
                  as Map<String, Object?>;
          expect(
            <Object?>[
              for (final Object? entry in request['documents']! as List<Object?>)
                (entry! as Map<String, Object?>)['id'],
            ],
            <Object?>['z'],
          );
          expect(serverHologram(harness.server, 'y')!.json, contains('Ingame Y'));
        },
      );
    }

    test(
      'should ask for a choice when the other change was edited here during the publish',
      () async {
        await seed('x', 'Base X');
        await seed('y', 'Base Y');
        final String id = await harness.pair();
        final Tab browser = await harness.tab(id);
        await browser.settled();
        await control(harness.server, 'older-answers', <String, Object?>{
          'on': true,
        });
        browser.edit(browser.doc('hologram', 'x')!, hologramJson('Mine X'));
        await control(harness.server, 'edit', <String, Object?>{
          'kind': 'hologram',
          'id': 'y',
          'json': hologramJson('Ingame Y'),
          'quiet': true,
        });
        await control(harness.server, 'latency', <String, Object?>{
          'answerMillis': 300,
        });
        final Future<void> publishing = browser.sync.publish();
        await waitFor(
          () =>
              (harness.server.state()['lastPublish']!
                  as Map<String, Object?>)['request'] !=
              null,
          reason: 'the publish to reach the server',
        );
        final WorkspaceDoc y = browser.doc('hologram', 'y')!;
        browser.edit(y, hologramJson('Mine Y'));
        await publishing;
        await control(harness.server, 'latency', <String, Object?>{
          'answerMillis': 0,
        });
        await waitFor(
          () => browser.sync.stateOf(y.id) == SyncDocState.changedOnServer,
          reason: 'y to need a choice, was ${browser.sync.stateOf(y.id)}',
        );
        expect(browser.doc('hologram', 'y')!.json, contains('Mine Y'));
        expect(browser.sync.buildChangeset().documents, isEmpty);
        expect(serverHologram(harness.server, 'y')!.json, contains('Ingame Y'));
      },
    );
  });

  group('a catalog change during an autosave', () {
    for (final bool created in <bool>[true, false]) {
      test(
        'should keep an edit saved while a server ${created ? 'creation' : 'deletion'} is stored',
        () async {
          await seed('typed', 'Before');
          await seed('other', 'Other');
          final String id = await harness.pair();
          final SlowRepository repository = SlowRepository();
          final Tab browser = await harness.tab(id, repository: repository);
          await browser.settled();
          await browser.workspace.writesSettled;
          final String key = Workspace.serverStorageKey(id);
          await control(
            harness.server,
            'edit',
            created
                ? <String, Object?>{
                    'kind': 'hologram',
                    'id': 'fresh-one',
                    'json': hologramJson('Fresh'),
                  }
                : <String, Object?>{
                    'kind': 'hologram',
                    'id': 'other',
                    'delete': true,
                  },
          );
          final String otherId = browser.doc('hologram', 'other')?.id ?? '';
          await waitFor(
            () => repository.inFlight.any(
              (String payload) => created
                  ? payload.contains('fresh-one')
                  : !payload.contains(otherId),
            ),
            reason: 'the server change to be stored',
          );
          final WorkspaceDoc typed = browser.doc('hologram', 'typed')!;
          browser.edit(typed, hologramJson('Typed during the write'));
          await waitFor(
            () =>
                (browser.doc('hologram', 'fresh-one') != null) == created &&
                (browser.doc('hologram', 'other') == null) == !created,
          );
          await waitFor(() => !browser.workspace.hasPendingWrites);
          await browser.workspace.writesSettled;
          expect(
            browser.doc('hologram', 'typed')!.json,
            contains('Typed during the write'),
          );
          expect(browser.workspace.hasUnsavedChanges, isFalse);
          await waitFor(() => browser.sync.pendingChangeCount == 1);
          expect(browser.sync.stateOf(typed.id), SyncDocState.changedHere);
          expect(repository.values[key], contains('Typed during the write'));
          expect(
            repository.storedIds(key),
            containsAll(<String>[
              for (final SyncBaseDocument row
                  in browser.sync.binding.documents.values)
                if (row.docId != null) row.docId!,
            ]),
          );
        },
      );
    }
  });

  group('a server change during a body read', () {
    test('should keep what was typed while a server change is read', () async {
      await seed('typing', 'Base');
      final String id = await harness.pair();
      final Tab browser = await harness.tab(id);
      await browser.settled();
      final WorkspaceDoc doc = browser.doc('hologram', 'typing')!;
      browser.store.openDocument(doc.id);
      int reads() =>
          ((harness.server.state()['requests']!
                  as Map<String, Object?>)['POST /v1/documents/read']
              as int?) ??
          0;
      final int before = reads();
      await control(harness.server, 'latency', <String, Object?>{
        'answerMillis': 300,
      });
      await seed('typing', 'Server change');
      await waitFor(() => reads() > before, reason: 'the body read');
      expect(
        browser.store.applyCode(
          editorSyncBlankJson('hologram')!.replaceFirst(
            '&dNew hologram',
            'Typed here',
          ),
        ),
        isTrue,
        reason: browser.store.codeError,
      );
      await waitFor(
        () => browser.sync.stateOf(doc.id) == SyncDocState.changedOnServer,
        reason: 'a choice, was ${browser.sync.stateOf(doc.id)}',
      );
      await control(harness.server, 'latency', <String, Object?>{
        'answerMillis': 0,
      });
      browser.store.flushAutosave(notify: false);
      expect(browser.doc('hologram', 'typing')!.json, contains('Typed here'));
    });

    test(
      'should read a listed document again when the server moved it before its body landed',
      () async {
        for (int index = 0; index < 60; index++) {
          await seed('s-$index', 'Line $index');
        }
        final String id = await harness.pair();
        await control(harness.server, 'latency', <String, Object?>{
          'answerMillis': 400,
        });
        await control(harness.server, 'edit', <String, Object?>{
          'kind': 'hologram',
          'id': 's-59',
          'json': hologramJson('Edited on server'),
          'afterRead': true,
        });
        final Tab browser = await harness.tab(id);
        await waitFor(
          () =>
              browser.sync.binding.unloaded == 0 &&
              browser.sync.status == EditorSyncStatus.connected,
          reason: 'the fill, was ${browser.sync.statusDetail}',
        );
        await control(harness.server, 'latency', <String, Object?>{
          'answerMillis': 0,
        });
        final WorkspaceDoc doc = browser.doc('hologram', 's-59')!;
        await waitFor(
          () => doc.json.contains('Edited on server'),
          reason: 'the server copy, was ${doc.json}',
        );
        expect(browser.sync.stateOf(doc.id), isNull);
      },
    );
  });

  group('a body the server no longer has', () {
    test(
      'should drop a listed document the server no longer has instead of asking again',
      () async {
        for (int index = 0; index < 60; index++) {
          await seed('s-$index', 'Line $index');
        }
        await control(harness.server, 'edit', <String, Object?>{
          'kind': 'hologram',
          'id': 's-59',
          'delete': true,
          'quiet': true,
          'beforeRead': true,
        });
        final String id = await harness.pair();
        final Tab browser = await harness.tab(id);
        await browser.settled();
        expect(browser.doc('hologram', 's-59'), isNull);
        expect(browser.sync.pendingChangeCount, 0);
      },
    );
  });

  group('a reconnect', () {
    test('should finish the background fill after a reconnect', () async {
      await harness.close();
      HeldTransport.release = Completer<void>();
      HeldTransport.held = 0;
      harness = SyncHarness(
        await FakeGlossServer.start(),
        ServerHub(
          profiles: ServerProfileStore(
            local: MemoryKeyValueStore(),
            session: MemoryKeyValueStore(),
          ),
          open: (ConnectionPath path, String url, String fingerprint) async =>
              HeldTransport(
                await ioTransports()(path, url, fingerprint),
                (ApiRequest request) =>
                    request.method == 'GET' &&
                    request.path.startsWith('/v1/documents'),
              ),
          handshakeTimeout: const Duration(seconds: 3),
        ),
      );
      for (int index = 0; index < 220; index++) {
        await seed('f-$index', 'Fill $index');
      }
      final String id = await harness.pair(paths: PairPaths.direct);
      final Tab browser = await harness.tab(id);
      await waitFor(() => HeldTransport.held > 0, reason: 'a held page');
      int catalogs() =>
          ((harness.server.state()['requests']!
                  as Map<String, Object?>)['GET /v1/catalog']
              as int?) ??
          0;
      final int before = catalogs();
      harness.server.restart(Duration.zero);
      await waitFor(
        () => catalogs() > before && browser.sync.connectionStatus.online,
        reason: 'the reconnect to read the catalog',
      );
      await Future<void>.delayed(const Duration(milliseconds: 200));
      HeldTransport.release.complete();
      await browser.settled();
      expect(browser.sync.binding.unloaded, 0);
    });

    test(
      'should read the catalog after a reconnect during an open question',
      () async {
        for (int index = 0; index < 40; index++) {
          await seed('bulk-$index', 'Bulk $index');
        }
        final String id = await harness.pair(paths: PairPaths.direct);
        final Tab first = await harness.tab(id);
        await first.settled();
        for (int index = 0; index < 40; index++) {
          first.workspace.delete(first.doc('hologram', 'bulk-$index')!.id);
        }
        final SyncChangeset changeset = first.sync.buildChangeset();
        first.sync.binding.pending = SyncPendingPublish(
          'pub_heldquestion000000',
          changeset.toJson('pub_heldquestion000000'),
        );
        first.sync.bindings.save(first.sync.binding);
        first.store.flushAutosave(notify: false);
        await first.workspace.writesSettled;
        await harness.closeTab(first);
        final Tab reload = Tab(
          harness.hub,
          id,
          storage: first.storage,
          bindings: first.bindingStorage,
        )..holdAnswer = Completer<bool>();
        harness.tabs.add(reload);
        await reload.open();
        await waitFor(() => reload.asked.isNotEmpty, reason: 'the question');
        int streams() =>
            ((harness.server.state()['requests']!
                    as Map<String, Object?>)['GET /v1/events']
                as int?) ??
            0;
        final int opened = streams();
        harness.server.restart(Duration.zero);
        await waitFor(
          () => streams() > opened && reload.sync.connectionStatus.online,
          reason: 'the reconnect',
        );
        await Future<void>.delayed(const Duration(milliseconds: 100));
        reload.holdAnswer!.complete(false);
        await waitFor(
          () => reload.sync.status == EditorSyncStatus.connected,
          reason: 'the catalog, was ${reload.sync.status}',
        );
        expect(reload.sync.pendingChangeCount, 40);
      },
    );
  });
}
