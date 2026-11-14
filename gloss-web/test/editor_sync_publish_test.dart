/// Publishes the server refuses or the path cannot carry, what the bar says
/// meanwhile and after, and choices on documents deleted here.
library;

import 'dart:async';
import 'dart:convert';

import 'package:gloss_editor/services/editor_sync.dart';
import 'package:gloss_editor/services/editor_sync_controller.dart';
import 'package:gloss_editor/services/server/server_hub.dart';
import 'package:gloss_editor/services/server/server_profiles.dart';
import 'package:gloss_editor/services/server/transport.dart';
import 'package:gloss_editor/state/workspace.dart';
import 'package:test/test.dart';

import '../tool/fake_server/kinds.dart';
import '../tool/fake_server/server.dart';
import '../tool/fake_server/store.dart' show StoredDocument;
import 'support/io_transport.dart';
import 'support/sync_tab.dart';

StoredDocument? serverHologram(FakeGlossServer server, String id) =>
    server.api.store.document(kindNamed('hologram')!, id);

int requestCount(FakeGlossServer server, String route) =>
    ((server.state()['requests']! as Map<String, Object?>)[route] as int?) ??
    0;

/// A relay path that counts the answers it gives with [status].
final class WatchedRelay implements ServerTransport, RelayLink {
  WatchedRelay(this.inner);

  final ServerTransport inner;
  static int refused = 0;

  @override
  ConnectionPath get path => inner.path;

  @override
  Future<ApiResponse> send(ApiRequest request) async {
    final ApiResponse response = await inner.send(request);
    if (response.status == 413) refused++;
    return response;
  }

  @override
  Future<EventSubscription> subscribe(String token) => inner.subscribe(token);

  @override
  Future<void> close() => inner.close();

  @override
  bool get serverOnline => (inner as RelayLink).serverOnline;

  @override
  bool get isClosed => (inner as RelayLink).isClosed;

  @override
  Future<void> get serverBack => (inner as RelayLink).serverBack;
}

/// Answers the [at]th publish with [answer] instead of sending it.
final class PartRefusal {
  PartRefusal(this.at, this.answer);

  final int at;
  final ApiResponse answer;
  int publishes = 0;
}

/// A relay path that lets [refusal] answer one publish.
final class RefusingRelay implements ServerTransport, RelayLink {
  RefusingRelay(this.inner, this.refusal);

  final ServerTransport inner;
  final PartRefusal refusal;

  @override
  ConnectionPath get path => inner.path;

  @override
  Future<ApiResponse> send(ApiRequest request) async {
    if (request.method == 'POST' &&
        request.path == '/v1/changes' &&
        ++refusal.publishes == refusal.at) {
      return refusal.answer;
    }
    return inner.send(request);
  }

  @override
  Future<EventSubscription> subscribe(String token) => inner.subscribe(token);

  @override
  Future<void> close() => inner.close();

  @override
  bool get serverOnline => (inner as RelayLink).serverOnline;

  @override
  bool get isClosed => (inner as RelayLink).isClosed;

  @override
  Future<void> get serverBack => (inner as RelayLink).serverBack;
}

/// A hologram whose one line is [line].
String bigHologram(String line) => const JsonEncoder.withIndent('  ').convert(
  <String, Object?>{
    'schemaVersion': 1,
    'revision': 1,
    'lines': <Object?>[line],
    'anchor': <String, Object?>{'world': 'world', 'x': 0, 'y': 64, 'z': 0},
  },
);

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

  /// Reloads [first] with [pending] as a publish whose answer was lost.
  Future<Tab> reloadWithPending(
    Tab first,
    String id,
    Map<String, Object?> pending,
  ) async {
    first.sync.binding.pending = SyncPendingPublish(
      pending['idempotencyKey']! as String,
      pending,
    );
    first.sync.bindings.save(first.sync.binding);
    first.store.flushAutosave(notify: false);
    await first.workspace.writesSettled;
    await harness.closeTab(first);
    return harness.tab(id, reloadOf: first);
  }

  group('a publish the server refuses whole', () {
    test(
      'should drop a lost publish the server refuses and read the catalog',
      () async {
        final String id = await harness.pair();
        final Tab first = await harness.tab(id);
        await first.settled();
        final Tab reload = await reloadWithPending(first, id, <String, Object?>{
          'idempotencyKey': 'pub_uploadgone00000000',
          'documents': <Object?>[],
          'images': <Object?>[
            <String, Object?>{
              'op': 'put',
              'path': 'probe/lost.png',
              'baseHash': null,
              'hash': 'sha256:${'a' * 64}',
              'mediaType': 'image/png',
            },
          ],
        });
        await waitFor(
          () => reload.sync.status == EditorSyncStatus.rejected,
          reason: 'the refusal, was ${reload.sync.status}',
        );
        expect(reload.sync.binding.pending, isNull);
        expect(reload.sync.documentCount, greaterThan(0));
        expect(reload.sync.saveBarState.message, 'Rejected');
        await reload.sync.sync();
        expect(reload.sync.status, EditorSyncStatus.connected);
        expect(requestCount(harness.server, 'POST /v1/changes'), 1);
      },
    );

    test(
      'should keep the edit when a publish is refused and read the catalog after',
      () async {
        await seed('cmd', 'Base');
        final String id = await harness.pair();
        final Tab browser = await harness.tab(id);
        await browser.settled();
        final Map<String, Object?> body =
            jsonDecode(hologramJson('Runs a command'))
                as Map<String, Object?>;
        body['click'] = <String, Object?>{
          'type': 'command',
          'source': 'server',
          'command': 'say hi',
        };
        browser.edit(browser.doc('hologram', 'cmd')!, jsonEncode(body));
        await waitFor(() => browser.sync.pendingChangeCount == 1);
        await browser.sync.publish();
        expect(browser.sync.status, EditorSyncStatus.rejected);
        expect(browser.sync.binding.pending, isNull);
        expect(browser.sync.pendingChangeCount, 1);
        await browser.sync.sync();
        expect(browser.sync.status, EditorSyncStatus.connected);
        expect(browser.sync.statusDetail, '1 change to publish');
        expect(requestCount(harness.server, 'POST /v1/changes'), 1);
      },
    );
  });

  group('the relay frame', () {
    test(
      'should cut a publish of wide text into parts that fit the relay',
      () async {
        final List<String> lines = <String>[
          for (int index = 0; index < 8; index++)
            'Привет, мир $index ' * 2600,
          for (int index = 0; index < 4; index++) '你好世界$index ' * 4600,
          for (int index = 0; index < 4; index++)
            'a"b\\c\\"d$index ' * 6000,
        ];
        for (int index = 0; index < lines.length; index++) {
          await seed('wide-$index', 'Base $index');
        }
        harness.hub.dispose();
        WatchedRelay.refused = 0;
        harness = SyncHarness(
          harness.server,
          ServerHub(
            profiles: ServerProfileStore(
              local: MemoryKeyValueStore(),
              session: MemoryKeyValueStore(),
            ),
            open: (ConnectionPath path, String url, String fingerprint) async =>
                WatchedRelay(await ioTransports()(path, url, fingerprint)),
            handshakeTimeout: const Duration(seconds: 3),
          ),
        );
        final String id = await harness.pair(paths: PairPaths.relay);
        final Tab browser = await harness.tab(id);
        await browser.settled();
        for (int index = 0; index < lines.length; index++) {
          browser.edit(
            browser.doc('hologram', 'wide-$index')!,
            bigHologram(lines[index]),
          );
        }
        await waitFor(
          () => browser.sync.pendingChangeCount == lines.length,
        );
        final int relays = harness.server.state()['relayConnections']! as int;
        await browser.sync.publish();
        expect(
          browser.sync.status,
          EditorSyncStatus.applied,
          reason: browser.sync.statusDetail,
        );
        expect(browser.sync.binding.pending, isNull);
        expect(browser.sync.pendingChangeCount, 0);
        expect(
          requestCount(harness.server, 'POST /v1/changes'),
          greaterThanOrEqualTo(2),
        );
        expect(harness.server.state()['relayConnections'], relays);
        expect(WatchedRelay.refused, 0, reason: 'every part fits as cut');
        expect(browser.sync.statusDetail, 'Published 16 changes.');
        for (int index = 0; index < lines.length; index++) {
          expect(
            (jsonDecode(serverHologram(harness.server, 'wide-$index')!.json)
                as Map<String, Object?>)['lines'],
            <Object?>[lines[index]],
          );
        }
      },
    );

    test('should split a part the relay answers too large', () async {
      for (int index = 0; index < 6; index++) {
        await seed('part-$index', 'Base $index');
      }
      final String id = await harness.pair(paths: PairPaths.relay);
      final Tab browser = await harness.tab(id);
      await browser.settled();
      for (int index = 0; index < 6; index++) {
        browser.edit(
          browser.doc('hologram', 'part-$index')!,
          bigHologram('Line $index ${'x' * 60000}'),
        );
      }
      await waitFor(() => browser.sync.pendingChangeCount == 6);
      await control(harness.server, 'relay-limit', <String, Object?>{
        'bytes': 150 * 1024,
      });
      await browser.sync.publish();
      expect(
        browser.sync.status,
        EditorSyncStatus.applied,
        reason: browser.sync.statusDetail,
      );
      expect(browser.sync.binding.pending, isNull);
      expect(browser.sync.pendingChangeCount, 0);
      expect(browser.sync.statusDetail, 'Published 6 changes.');
      for (int index = 0; index < 6; index++) {
        expect(
          serverHologram(harness.server, 'part-$index')!.json,
          contains('Line $index'),
        );
      }
    });

    /// Four edits of about 400 KB each, published over a relay where
    /// [refusal] answers one part: two parts of two.
    Future<Tab> publishFourWide(PartRefusal refusal) async {
      for (int index = 0; index < 4; index++) {
        await seed('half-$index', 'Base $index');
      }
      harness.hub.dispose();
      harness = SyncHarness(
        harness.server,
        ServerHub(
          profiles: ServerProfileStore(
            local: MemoryKeyValueStore(),
            session: MemoryKeyValueStore(),
          ),
          open: (ConnectionPath path, String url, String fingerprint) async =>
              RefusingRelay(await ioTransports()(path, url, fingerprint), refusal),
          handshakeTimeout: const Duration(seconds: 3),
        ),
      );
      final String id = await harness.pair(paths: PairPaths.relay);
      final Tab browser = await harness.tab(id);
      await browser.settled();
      for (int index = 0; index < 4; index++) {
        browser.edit(
          browser.doc('hologram', 'half-$index')!,
          bigHologram('Line $index ${'x' * 400000}'),
        );
      }
      await waitFor(() => browser.sync.pendingChangeCount == 4);
      await browser.sync.publish();
      expect(refusal.publishes, 2, reason: 'two parts');
      return browser;
    }

    test(
      'should report one total and one line when the server rejects a later part',
      () async {
        final Tab browser = await publishFourWide(
          PartRefusal(
            2,
            const ApiResponse(200, <String, Object?>{
              'status': 'rejected',
              'message': 'Nothing was published: the disk is full.',
              'conflicts': <Object?>[],
            }, path: ConnectionPath.relay),
          ),
        );
        expect(browser.sync.status, EditorSyncStatus.rejected);
        expect(
          browser.sync.statusDetail,
          'Published 2 changes. The other 2 changes did not publish. The disk is full.',
        );
        expect(browser.sync.pendingChangeCount, 2);
      },
    );

    test(
      'should keep the total of earlier parts when a later part is refused',
      () async {
        final Tab browser = await publishFourWide(
          PartRefusal(
            2,
            const ApiResponse(400, <String, Object?>{
              'error': <String, Object?>{
                'code': 'invalid-request',
                'message': 'Bad body.',
              },
            }, path: ConnectionPath.relay),
          ),
        );
        expect(browser.sync.status, EditorSyncStatus.rejected);
        expect(
          browser.sync.statusDetail,
          'Published 2 changes. The other 2 changes did not publish. '
          'The server refused the request: Bad body. Reload the editor.',
        );
        expect(browser.sync.binding.pending, isNull);
        expect(browser.sync.pendingChangeCount, 2);
      },
    );

    test('should refuse a relay frame over the limit by its UTF-8 size', () {
      expect(relayRefusal('a' * (relayBodyBytes - 10)), isNull);
      final ApiResponse? refused = relayRefusal('ж' * (relayBodyBytes ~/ 2 + 1));
      expect(refused?.status, 413);
      expect(refused?.errorCode, 'too-large');
      expect(utf8Length('aж你😀'), utf8.encode('aж你😀').length);
    });
  });

  group('the bar', () {
    test(
      'should say it waits for an answer while the large-delete question is open',
      () async {
        for (int index = 0; index < 40; index++) {
          await seed('bulk-$index', 'Bulk $index');
        }
        final String id = await harness.pair();
        final Tab browser = await harness.tab(id);
        await browser.settled();
        for (int index = 0; index < 40; index++) {
          browser.workspace.delete(browser.doc('hologram', 'bulk-$index')!.id);
        }
        await waitFor(() => browser.sync.pendingChangeCount == 40);
        browser.holdAnswer = Completer<bool>();
        final Future<void> publishing = browser.sync.publish();
        await waitFor(() => browser.asked.isNotEmpty);
        expect(browser.sync.status, EditorSyncStatus.asking);
        expect(browser.sync.saveBarState.message, 'Needs an answer');
        expect(browser.sync.statusDetail, browser.asked.single.$1);
        expect(browser.sync.saveBarState.busy, isNull);
        browser.holdAnswer!.complete(false);
        await publishing;
        expect(browser.sync.status, EditorSyncStatus.connected);
      },
    );

    test('should say Connected once the catalog is read after a restart', () async {
      await seed('pub', 'One');
      final String id = await harness.pair(paths: PairPaths.direct);
      final Tab browser = await harness.tab(id);
      await browser.settled();
      browser.edit(browser.doc('hologram', 'pub')!, hologramJson('Two'));
      await waitFor(() => browser.sync.pendingChangeCount == 1);
      await browser.sync.publish();
      expect(browser.sync.status, EditorSyncStatus.applied);
      final int catalogs = requestCount(harness.server, 'GET /v1/catalog');
      harness.server.restart(const Duration(milliseconds: 200));
      await waitFor(
        () =>
            requestCount(harness.server, 'GET /v1/catalog') > catalogs &&
            browser.sync.status == EditorSyncStatus.connected,
        reason: 'Connected, was ${browser.sync.status}',
      );
      expect(browser.sync.saveBarState.message, 'Connected');
    });

    test(
      'should need a choice, not say Published, when nothing landed',
      () async {
        await seed('both', 'Base');
        final String id = await harness.pair();
        final Tab browser = await harness.tab(id);
        await browser.settled();
        final WorkspaceDoc doc = browser.doc('hologram', 'both')!;
        browser.edit(doc, hologramJson('Mine'));
        await control(harness.server, 'edit', <String, Object?>{
          'kind': 'hologram',
          'id': 'both',
          'json': hologramJson('Theirs'),
          'quiet': true,
        });
        await waitFor(() => browser.sync.pendingChangeCount == 1);
        await browser.sync.publish();
        expect(
          (harness.server.state()['lastPublish']!
              as Map<String, Object?>)['answer'],
          containsPair('status', 'conflict'),
        );
        expect(browser.sync.status, EditorSyncStatus.conflict);
        expect(browser.sync.stateOf(doc.id), SyncDocState.conflict);
        expect(browser.sync.buildChangeset().isEmpty, isTrue);
      },
    );

    test(
      'should read the catalog again once the editor moves to the direct path',
      () async {
        harness.server.directDown = true;
        final String id = await harness.pair();
        final ServerHub hub = harness.hub;
        final Tab browser = await harness.tab(id);
        await browser.settled();
        expect(browser.sync.connectionStatus.path, ConnectionPath.relay);
        final int catalogs = requestCount(harness.server, 'GET /v1/catalog');
        harness.server.directDown = false;
        hub.wake();
        await waitFor(
          () =>
              browser.sync.connectionStatus.path == ConnectionPath.direct &&
              requestCount(harness.server, 'GET /v1/catalog') > catalogs,
          reason: 'a catalog read on the direct path',
        );
        await browser.settled();
      },
    );
  });

  group('a choice on a document deleted here', () {
    test(
      'should publish the deletion after Keep mine on a document the server changed',
      () async {
        await seed('gone', 'Base');
        await seed('kept', 'Kept');
        final String id = await harness.pair();
        final Tab browser = await harness.tab(id);
        await browser.settled();
        browser.workspace.delete(browser.doc('hologram', 'gone')!.id);
        await seed('gone', 'Server change');
        await waitFor(() => browser.sync.choiceCount == 1);
        await browser.sync.keepMine(editorSyncKey('hologram', 'gone'));
        expect(browser.sync.choiceCount, 0);
        expect(browser.sync.pendingChangeCount, 1);
        expect(
          browser.sync.changes.single.state,
          SyncDocState.deleted,
        );
        await browser.sync.publish();
        expect(browser.sync.status, EditorSyncStatus.applied);
        expect(serverHologram(harness.server, 'gone'), isNull);
      },
    );
  });
}
