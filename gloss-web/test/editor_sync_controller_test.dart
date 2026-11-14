/// The editor's half of a paired server against the in-process fake server
/// (`tool/fake_server`) over real sockets: pairing, the catalog listed at
/// once, the background fill and documents read on open, server events,
/// publish and conflicts, an ended pairing, reconnect after a restart, both
/// paths, a second tab, and edits made while images arrive.
library;

import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:gloss_editor/doctype/doctype.dart';
import 'package:gloss_editor/logic/advanced_notice.dart';
import 'package:gloss_editor/services/editor_sync.dart';
import 'package:gloss_editor/services/editor_sync_controller.dart';
import 'package:gloss_editor/services/image_library.dart';
import 'package:gloss_editor/services/server/server_hub.dart';
import 'package:gloss_editor/services/server/server_profiles.dart';
import 'package:gloss_editor/services/server/transport.dart';
import 'package:gloss_editor/state/workspace.dart';
import 'package:test/test.dart';

import '../tool/fake_server/server.dart';
import 'support/io_transport.dart';
import 'support/sync_tab.dart';

void main() {
  late FakeGlossServer server;
  late ServerHub hub;
  final List<Tab> tabs = <Tab>[];

  Future<String> pair({
    PairPaths paths = PairPaths.both,
    Duration lifetime = const Duration(days: 7),
    List<String>? scopes,
  }) async {
    final PairLink link = server.pair(
      paths: paths,
      lifetime: lifetime,
      scopes: scopes,
    );
    return hub.pair(link.code, remember: true, client: 'Test on VM');
  }

  Future<Tab> tab(String serverId, {Tab? reloadOf, bool start = true}) async {
    final Tab made = Tab(
      hub,
      serverId,
      storage: reloadOf?.storage,
      bindings: reloadOf?.bindingStorage,
    );
    tabs.add(made);
    return made.open(start: start);
  }

  setUp(() async {
    server = await FakeGlossServer.start();
    hub = ServerHub(
      profiles: ServerProfileStore(
        local: MemoryKeyValueStore(),
        session: MemoryKeyValueStore(),
      ),
      open: ioTransports(),
      handshakeTimeout: const Duration(seconds: 3),
    );
  });

  tearDown(() async {
    for (final Tab open in tabs) {
      await open.close();
    }
    tabs.clear();
    hub.dispose();
    await server.close();
  });

  group('pairing and the first read', () {
    test('should list every document with no click after Pair', () async {
      final String id = await pair();
      final Tab browser = await tab(id);
      await browser.settled();
      final Map<String, Object?> state = server.state();
      expect(
        browser.sync.documentCount,
        state['documents'],
        reason: 'the catalog lists everything the server holds',
      );
      expect(browser.workspace.docs, isNotEmpty);
      expect(
        browser.workspace.docs.every(
          (WorkspaceDoc doc) => !browser.sync.isLoading(doc.id),
        ),
        isTrue,
      );
      expect(browser.sync.pendingChangeCount, 0);
      expect(hub.server(id)!.documents, browser.sync.documentCount);
    });

    test('should list a large catalog by name first and fill it', () async {
      for (int index = 0; index < 60; index++) {
        await control(server, 'edit', <String, Object?>{
          'kind': 'hologram',
          'id': 'seed-h$index',
          'json': hologramJson('Line $index'),
        });
      }
      final String id = await pair();
      final Tab browser = await tab(id);
      final List<String> details = <String>[];
      browser.sync.addListener(() => details.add(browser.sync.statusDetail));
      await waitFor(() => browser.doc('hologram', 'seed-h59') != null);
      await browser.settled();
      expect(
        details.any((String line) => line.startsWith('Loading')),
        isTrue,
        reason: 'the save bar says Loading n of m while the fill runs',
      );
      final WorkspaceDoc filled = browser.doc('hologram', 'seed-h42')!;
      expect(filled.json, contains('Line 42'));
      expect(browser.sync.stateOf(filled.id), isNull);
    });

    test(
      'should offer nothing to publish before the server list arrives',
      () async {
        final String id = await pair();
        await control(server, 'latency', <String, Object?>{'millis': 400});
        final Tab browser = await tab(id);
        browser.workspace.create(
          title: 'my-menu',
          runtimeId: 'my-menu',
          json: '{}',
          kind: WorkspaceDocKind.menu,
        );
        await waitFor(
          () => browser.sync.status == EditorSyncStatus.loading,
          reason: 'reading the catalog',
        );
        expect(browser.sync.pendingChangeCount, 0);
        expect(browser.sync.saveBarState.primary, isNull);
        await browser.sync.publish();
        final Map<String, Object?> requests =
            server.state()['requests']! as Map<String, Object?>;
        expect(requests['POST /v1/changes'], isNull);
        await control(server, 'latency', <String, Object?>{'millis': 0});
        await browser.settled();
      },
    );

    test('should seed nothing and say Loading the server until its first list arrives', () async {
      final String id = await pair();
      await control(server, 'latency', <String, Object?>{'millis': 300});
      final Tab browser = await tab(id);
      expect(browser.workspace.docs, isEmpty, reason: 'no starter menu in a server workspace');
      expect(browser.sync.firstListing, isTrue);
      expect(browser.sync.activeLoading, browser.sync.serverName);
      await control(server, 'latency', <String, Object?>{'millis': 0});
      await browser.settled();
      expect(browser.sync.firstListing, isFalse);
      expect(browser.sync.activeLoading, isNull);
      expect(browser.workspace.docs, isNotEmpty);
    });

    test('should read a document opened before the fill reached it', () async {
      for (int index = 0; index < 220; index++) {
        await control(server, 'edit', <String, Object?>{
          'kind': 'hologram',
          'id': 'early-$index',
          'json': hologramJson('Early $index'),
        });
      }
      final String id = await pair();
      await control(server, 'latency', <String, Object?>{'millis': 150});
      final Tab browser = await tab(id);
      await waitFor(() => browser.doc('hologram', 'early-219') != null);
      final WorkspaceDoc stub = browser.doc('hologram', 'early-219')!;
      expect(browser.sync.isLoading(stub.id), isTrue);
      expect(browser.sync.status, EditorSyncStatus.loading);
      browser.store.openDocument(stub.id);
      expect(browser.sync.activeLoading, stub.title);
      await waitFor(() => !browser.sync.isLoading(stub.id));
      expect(browser.sync.activeLoading, isNull);
      expect(browser.doc('hologram', 'early-219')!.json, contains('Early 219'));
      final Map<String, Object?> requests =
          server.state()['requests']! as Map<String, Object?>;
      expect(requests['POST /v1/documents/read'], greaterThanOrEqualTo(1));
      await control(server, 'latency', <String, Object?>{'millis': 0});
      await browser.settled();
    });

    test(
      'should strip nothing it cannot edit and keep it on the server',
      () async {
        final String id = await pair();
        final Tab browser = await tab(id);
        await browser.settled();
        for (final WorkspaceDoc doc in browser.workspace.docs) {
          expect(
            DocumentTypeRegistry.of(doc.kind).syncWireKind,
            isNotNull,
            reason: doc.title,
          );
        }
      },
    );
  });

  group('server changes', () {
    test('should refresh an untouched open document within a second', () async {
      await control(server, 'edit', <String, Object?>{
        'kind': 'hologram',
        'id': 'lobby-welcome',
        'json': hologramJson('Before'),
      });
      final String id = await pair();
      final Tab browser = await tab(id);
      await browser.settled();
      final Stopwatch clock = Stopwatch()..start();
      await control(server, 'edit', <String, Object?>{
        'kind': 'hologram',
        'id': 'lobby-welcome',
        'json': hologramJson('After'),
      });
      await waitFor(
        () => browser.doc('hologram', 'lobby-welcome')!.json.contains('After'),
        timeout: const Duration(seconds: 2),
      );
      expect(clock.elapsedMilliseconds, lessThan(1000));
      expect(
        browser.sync.stateOf(browser.doc('hologram', 'lobby-welcome')!.id),
        isNull,
      );
    });

    test(
      'should report the documents the listing and a server change bring',
      () async {
        final String id = await pair();
        final Tab browser = await tab(id);
        await browser.settled();
        expect(
          <String>{
            for (final List<ArrivedDocument> batch in browser.arrivals)
              for (final ArrivedDocument document in batch) document.id,
          },
          <String>{for (final WorkspaceDoc doc in browser.workspace.docs) doc.id},
          reason: 'the listing writes every server document once',
        );
        browser.arrivals.clear();
        await control(server, 'edit', <String, Object?>{
          'kind': 'animation',
          'id': 'arrived-pulse',
          'json': File('test/demo/fixtures/animation.json').readAsStringSync(),
        });
        await waitFor(() => browser.doc('animation', 'arrived-pulse') != null);
        await browser.settled();
        final List<ArrivedDocument> arrived = <ArrivedDocument>[
          for (final List<ArrivedDocument> batch in browser.arrivals) ...batch,
        ];
        expect(arrived, <ArrivedDocument>[
          (
            id: browser.doc('animation', 'arrived-pulse')!.id,
            kind: DocumentTypes.animation.kind,
          ),
        ]);
        await control(server, 'edit', <String, Object?>{
          'kind': 'animation',
          'id': 'arrived-pulse',
          'json': File('test/demo/fixtures/animation.json')
              .readAsStringSync()
              .replaceFirst('Market', 'Bazaar'),
        });
        await waitFor(
          () => browser.doc('animation', 'arrived-pulse')!.json.contains('Bazaar'),
        );
        expect(browser.arrivals, hasLength(1), reason: 'an update is no arrival');
      },
    );

    test(
      'should list a document created on the server and drop a deleted one',
      () async {
        final String id = await pair();
        final Tab browser = await tab(id);
        await browser.settled();
        await control(server, 'edit', <String, Object?>{
          'kind': 'hologram',
          'id': 'new-one',
          'json': hologramJson('Fresh'),
        });
        await waitFor(() => browser.doc('hologram', 'new-one') != null);
        await control(server, 'edit', <String, Object?>{
          'kind': 'hologram',
          'id': 'new-one',
          'delete': true,
        });
        await waitFor(() => browser.doc('hologram', 'new-one') == null);
      },
    );

    test(
      'should mark a document edited on both sides and say so in the bar',
      () async {
        await control(server, 'edit', <String, Object?>{
          'kind': 'hologram',
          'id': 'both',
          'json': hologramJson('Base'),
        });
        final String id = await pair();
        final Tab browser = await tab(id);
        await browser.settled();
        final WorkspaceDoc doc = browser.doc('hologram', 'both')!;
        browser.edit(doc, hologramJson('Mine'));
        await control(server, 'edit', <String, Object?>{
          'kind': 'hologram',
          'id': 'both',
          'json': hologramJson('Theirs'),
        });
        await waitFor(
          () => browser.sync.stateOf(doc.id) == SyncDocState.changedOnServer,
        );
        expect(browser.doc('hologram', 'both')!.json, contains('Mine'));
        expect(browser.sync.status, EditorSyncStatus.conflict);
        expect(browser.sync.statusDetail, '1 document');
        expect(
          browser.sync.saveBarState.primary.toString(),
          contains('review'),
        );
      },
    );
  });

  group('publish', () {
    test('should publish an edit and read it back unchanged', () async {
      await control(server, 'edit', <String, Object?>{
        'kind': 'hologram',
        'id': 'pub',
        'json': hologramJson('One'),
      });
      final String id = await pair();
      final Tab browser = await tab(id);
      await browser.settled();
      final WorkspaceDoc doc = browser.doc('hologram', 'pub')!;
      browser.edit(doc, hologramJson('Two'));
      await waitFor(() => browser.sync.pendingChangeCount == 1);
      await browser.sync.publish();
      expect(browser.sync.status, EditorSyncStatus.applied);
      expect(browser.sync.pendingChangeCount, 0);
      final Map<String, Object?> answer =
          (server.state()['lastPublish']! as Map<String, Object?>)['answer']!
              as Map<String, Object?>;
      expect(answer['status'], 'applied');
      final Map<String, Object?> json = jsonDecode(
        browser.doc('hologram', 'pub')!.json,
      ) as Map<String, Object?>;
      expect(json['revision'], 2, reason: 'the local copy takes the bump');
      await Future<void>.delayed(const Duration(milliseconds: 300));
      expect(browser.sync.stateOf(doc.id), isNull);
    });

    test(
      'should keep my copy or take the server copy after a conflict',
      () async {
        for (final String name in <String>['keep', 'take']) {
          await control(server, 'edit', <String, Object?>{
            'kind': 'hologram',
            'id': name,
            'json': hologramJson('Base $name'),
          });
        }
        final String id = await pair();
        final Tab browser = await tab(id);
        await browser.settled();
        for (final String name in <String>['keep', 'take']) {
          browser.edit(
            browser.doc('hologram', name)!,
            hologramJson('Mine $name'),
          );
          await control(server, 'edit', <String, Object?>{
            'kind': 'hologram',
            'id': name,
            'json': hologramJson('Server $name'),
          });
        }
        await waitFor(() => browser.sync.choiceCount == 2);
        await browser.sync.keepMine(editorSyncKey('hologram', 'keep'));
        await browser.sync.useServer(editorSyncKey('hologram', 'take'));
        expect(browser.doc('hologram', 'take')!.json, contains('Server take'));
        expect(browser.sync.choiceCount, 0);
        await browser.sync.publish();
        final Map<String, Object?> request =
            (server.state()['lastPublish']! as Map<String, Object?>)['request']!
                as Map<String, Object?>;
        final List<Object?> sent = request['documents']! as List<Object?>;
        expect(sent, hasLength(1));
        expect((sent.single! as Map<String, Object?>)['id'], 'keep');
      },
    );

    test(
      'should send a rename as the new id and the removal of the old',
      () async {
        await control(server, 'edit', <String, Object?>{
          'kind': 'hologram',
          'id': 'old-name',
          'json': hologramJson('Rename me'),
        });
        final String id = await pair();
        final Tab browser = await tab(id);
        await browser.settled();
        final WorkspaceDoc doc = browser.doc('hologram', 'old-name')!;
        expect(
          browser.store.renameDocumentRuntimeId(doc.id, 'new-name'),
          isTrue,
        );
        final SyncChangeset changeset = browser.sync.buildChangeset();
        expect(
          changeset.documents.map(
            (SyncDocumentChange change) =>
                '${change.delete ? 'delete' : 'put'} ${change.id}',
          ),
          unorderedEquals(<String>['put new-name', 'delete old-name']),
        );
        await browser.sync.publish();
        await waitFor(() => browser.sync.status == EditorSyncStatus.applied);
        expect(browser.sync.pendingChangeCount, 0);
        expect(browser.doc('hologram', 'new-name'), isNotNull);
      },
    );

    test(
      'should send a publish whose answer was lost again with its key',
      () async {
        await control(server, 'edit', <String, Object?>{
          'kind': 'hologram',
          'id': 'lost',
          'json': hologramJson('Base'),
        });
        final String id = await pair();
        final Tab first = await tab(id);
        await first.settled();
        first.edit(first.doc('hologram', 'lost')!, hologramJson('Edited'));
        final SyncChangeset changeset = first.sync.buildChangeset();
        first.sync.binding.pending = SyncPendingPublish(
          'pub_lostanswer00000000',
          changeset.toJson('pub_lostanswer00000000'),
        );
        first.sync.bindings.save(first.sync.binding);
        first.store.flushAutosave(notify: false);
        await first.workspace.writesSettled;
        await first.close();
        tabs.remove(first);
        await hub.release(id);
        final Tab reload = await tab(id, reloadOf: first);
        await reload.settled();
        expect(reload.sync.binding.pending, isNull);
        expect(reload.sync.pendingChangeCount, 0);
        final Map<String, Object?> state = server.state();
        final Map<String, Object?> requests =
            state['requests']! as Map<String, Object?>;
        expect(requests['POST /v1/changes'], 1, reason: 'sent once, answered');
      },
    );
  });

  group('the pairing ending', () {
    test(
      'should say Pairing ended. and what to do when revoked',
      () async {
        final String id = await pair();
        final Tab browser = await tab(id);
        await browser.settled();
        final String tokenId = hub.token(id)!.tokenId;
        await control(server, 'revoke', <String, Object?>{'tokenId': tokenId});
        await waitFor(() => browser.sync.status == EditorSyncStatus.ended);
        expect(browser.sync.saveBarState.message, 'Pairing ended');
        expect(browser.sync.statusDetail, contains('/gloss editor pair'));
        expect(hub.hasToken(id), isFalse);
      },
    );

    test(
      'should say Pairing ended. on a fresh open of a revoked pairing',
      () async {
        final String id = await pair();
        final String tokenId = hub.token(id)!.tokenId;
        await control(server, 'revoke', <String, Object?>{'tokenId': tokenId});
        final Tab browser = await tab(id);
        await waitFor(() => browser.sync.status == EditorSyncStatus.ended);
        expect(
          browser.sync.saveBarState.primary.toString(),
          contains('newLink'),
        );
      },
    );

    test('should warn three days before the pairing ends', () async {
      final String id = await pair(lifetime: const Duration(days: 2));
      final Tab browser = await tab(id);
      await browser.settled();
      expect(browser.sync.expiryLine, 'Pairing ends in 2 days unless used.');
      expect(browser.sync.statusDetail, contains('Pairing ends in 2 days'));
    });

    test('should say nothing about the end a week away', () async {
      final String id = await pair();
      final Tab browser = await tab(id);
      await browser.settled();
      expect(browser.sync.expiryLine, isNull);
    });
  });

  group('connections', () {
    test('should be back well under a second after a restart', () async {
      final String id = await pair(paths: PairPaths.direct);
      final Tab browser = await tab(id);
      await browser.settled();
      server.restart(const Duration(milliseconds: 600));
      await waitFor(
        () => browser.sync.status == EditorSyncStatus.reconnecting,
        timeout: const Duration(seconds: 2),
      );
      final Stopwatch clock = Stopwatch()..start();
      await waitFor(
        () => browser.sync.status == EditorSyncStatus.connected,
        timeout: const Duration(seconds: 4),
      );
      expect(
        clock.elapsedMilliseconds,
        lessThan(600 + 900),
        reason: 'up after 600 ms, online within the 250 ms retry',
      );
    });

    test('should move to the relay without the bar noticing when the direct path drops', () async {
      final String id = await pair();
      final Tab browser = await tab(id);
      await browser.settled();
      expect(browser.sync.connectionStatus.path, ConnectionPath.direct);
      final Set<EditorSyncStatus> seen = <EditorSyncStatus>{};
      void note() => seen.add(browser.sync.status);
      browser.sync.addListener(note);
      server.directDown = true;
      await waitFor(
        () =>
            browser.sync.connectionStatus.path == ConnectionPath.relay &&
            browser.sync.connectionStatus.online,
        timeout: const Duration(seconds: 4),
      );
      await browser.settled();
      note();
      browser.sync.removeListener(note);
      expect(seen, <EditorSyncStatus>{EditorSyncStatus.connected});
    });

    test('should work over the relay only', () async {
      final String id = await pair(paths: PairPaths.relay);
      final Tab browser = await tab(id);
      await browser.settled();
      expect(browser.sync.connectionStatus.path, ConnectionPath.relay);
      await control(server, 'edit', <String, Object?>{
        'kind': 'hologram',
        'id': 'relay-one',
        'json': hologramJson('Over the relay'),
      });
      await waitFor(() => browser.doc('hologram', 'relay-one') != null);
    });

    test(
      'should reconnect over the relay the moment the server is back on it',
      () async {
        final String id = await pair(paths: PairPaths.relay);
        final Tab browser = await tab(id);
        await browser.settled();
        server.restart(const Duration(milliseconds: 500));
        await waitFor(
          () => browser.sync.status == EditorSyncStatus.reconnecting,
        );
        final Stopwatch clock = Stopwatch()..start();
        await waitFor(
          () => browser.sync.status == EditorSyncStatus.connected,
          timeout: const Duration(seconds: 4),
        );
        expect(clock.elapsedMilliseconds, lessThan(500 + 700));
      },
    );
  });

  group('two tabs', () {
    test('should not connect or accept edits in a tab that waits', () async {
      final String id = await pair();
      final Tab waiting = await tab(id, start: false);
      waiting.sync.owned = false;
      waiting.workspace.pauseWrites();
      expect(waiting.sync.status, EditorSyncStatus.elsewhere);
      expect(waiting.sync.saveBarState.message, 'Open in another tab');
      expect(waiting.workspace.canWrite, isFalse);
      final WorkspaceDoc doc = waiting.workspace.create(
        title: 'typed',
        runtimeId: 'typed',
        json: hologramJson('x'),
        kind: WorkspaceDocKind.hologram,
      );
      expect(waiting.workspace.save(), isFalse);
      expect(
        waiting.storage.values.any((String v) => v.contains(doc.id)),
        isFalse,
      );
      expect(server.state()['streams'], <String, Object?>{
        'direct': 0,
        'relay': 0,
      });
    });
  });

  group('images', () {
    test('should still count edits made while images keep arriving', () async {
      final String id = await pair();
      final Tab browser = await tab(id);
      await browser.settled();
      final WorkspaceDoc doc = browser.workspace.docs.firstWhere(
        (WorkspaceDoc candidate) => candidate.kind == WorkspaceDocKind.hologram,
      );
      for (int index = 0; index < 30; index++) {
        browser.images.upsertAll(<StoredImage>[
          StoredImage(
            path: 'late/$index.png',
            dataUri:
                'data:image/png;base64,${base64Encode(solidPng(2, 2, <int>[index, 0, 0]))}',
            width: 2,
            height: 2,
          ),
        ]);
        browser.sync.stateOf(doc.id);
        await Future<void>.delayed(Duration.zero);
      }
      browser.edit(doc, hologramJson('Edited while images arrive'));
      await waitFor(() => browser.sync.pendingChangeCount >= 1);
      expect(browser.sync.saveBarState.primary.toString(), contains('publish'));
    });
  });

  group('lost stores, deletes and counts', () {
    test(
      'should rebuild a workspace whose store was lost, never list deletions',
      () async {
        final String id = await pair();
        final Tab first = await tab(id);
        await first.settled();
        final int listed = first.workspace.docs.length;
        expect(listed, greaterThan(0));
        await first.close();
        tabs.remove(first);
        await hub.release(id);
        // localStorage (the binding) kept, IndexedDB (the workspace) lost.
        final Tab lost = Tab(hub, id, bindings: first.bindingStorage);
        tabs.add(lost);
        await lost.open();
        await lost.settled();
        expect(
          lost.sync.changes.where(
            (SyncChange change) => change.state == SyncDocState.deleted,
          ),
          isEmpty,
        );
        expect(lost.sync.pendingChangeCount, 0);
        expect(lost.workspace.docs, hasLength(listed));
      },
    );

    test('should rebuild a workspace whose store came back empty', () async {
      final String id = await pair();
      final Tab first = await tab(id);
      await first.settled();
      final int listed = first.workspace.docs.length;
      first.store.flushAutosave(notify: false);
      await first.workspace.writesSettled;
      await first.close();
      tabs.remove(first);
      await hub.release(id);
      final String key = Workspace.serverStorageKey(id);
      final Map<String, Object?> record =
          jsonDecode(first.storage[key]!) as Map<String, Object?>;
      record['documents'] = <Object?>[];
      record['activeDocumentId'] = null;
      final Map<String, String> emptied = <String, String>{
        ...first.storage,
        key: jsonEncode(record),
      };
      final Tab reload = Tab(
        hub,
        id,
        storage: emptied,
        bindings: first.bindingStorage,
      );
      tabs.add(reload);
      await reload.open();
      await reload.settled();
      expect(reload.sync.pendingChangeCount, 0);
      expect(reload.workspace.docs, hasLength(listed));
    });

    test(
      'should ask before a publish that deletes many documents and send the count',
      () async {
        for (int index = 0; index < 40; index++) {
          await control(server, 'edit', <String, Object?>{
            'kind': 'hologram',
            'id': 'bulk-$index',
            'json': hologramJson('Bulk $index'),
          });
        }
        final String id = await pair();
        final Tab browser = await tab(id);
        await browser.settled();
        for (int index = 0; index < 40; index++) {
          browser.workspace.delete(browser.doc('hologram', 'bulk-$index')!.id);
        }
        await waitFor(() => browser.sync.pendingChangeCount == 40);
        browser.answer = false;
        await browser.sync.publish();
        expect(browser.asked, hasLength(1));
        expect(browser.asked.single.$2, 'Delete 40');
        Map<String, Object?> requests =
            server.state()['requests']! as Map<String, Object?>;
        expect(requests['POST /v1/changes'], isNull, reason: 'Cancel sends nothing');
        expect(browser.sync.pendingChangeCount, 40);
        browser.answer = true;
        await browser.sync.publish();
        expect(browser.asked, hasLength(2));
        final Map<String, Object?> request =
            (server.state()['lastPublish']! as Map<String, Object?>)['request']!
                as Map<String, Object?>;
        expect(request['confirmDeletes'], 40);
        expect(browser.sync.status, EditorSyncStatus.applied);
        expect(browser.sync.pendingChangeCount, 0);
        requests = server.state()['requests']! as Map<String, Object?>;
        expect(requests['POST /v1/changes'], 1);
      },
    );

    test(
      'should ask with the server count when the server refuses with confirm-deletes',
      () async {
        for (int index = 0; index < 40; index++) {
          await control(server, 'edit', <String, Object?>{
            'kind': 'hologram',
            'id': 'bulk-$index',
            'json': hologramJson('Bulk $index'),
          });
        }
        final String id = await pair();
        final Tab first = await tab(id);
        await first.settled();
        // A publish whose answer was lost, sent by a build that did not ask:
        // its resend meets the server's refusal, which asks before the same
        // key goes again with the count.
        for (int index = 0; index < 40; index++) {
          first.workspace.delete(first.doc('hologram', 'bulk-$index')!.id);
        }
        final SyncChangeset changeset = first.sync.buildChangeset();
        expect(changeset.deletes, 40);
        first.sync.binding.pending = SyncPendingPublish(
          'pub_lostdeletes0000000',
          changeset.toJson('pub_lostdeletes0000000'),
        );
        first.sync.bindings.save(first.sync.binding);
        first.store.flushAutosave(notify: false);
        await first.workspace.writesSettled;
        await first.close();
        tabs.remove(first);
        await hub.release(id);
        final Tab reload = await tab(id, reloadOf: first);
        await reload.settled();
        expect(reload.asked, hasLength(1));
        expect(reload.asked.single.$2, 'Delete 40');
        final Map<String, Object?> publish =
            server.state()['lastPublish']! as Map<String, Object?>;
        expect(
          (publish['request']! as Map<String, Object?>)['confirmDeletes'],
          40,
        );
        expect(
          (publish['answer']! as Map<String, Object?>)['status'],
          'applied',
        );
        expect(reload.sync.binding.pending, isNull);
        expect(reload.sync.pendingChangeCount, 0);
      },
    );

    test('should count the fill up to every listed document', () async {
      for (int index = 0; index < 240; index++) {
        await control(server, 'edit', <String, Object?>{
          'kind': 'hologram',
          'id': 'fill-$index',
          'json': hologramJson('Fill $index ${'x' * 400}'),
        });
      }
      final String id = await pair();
      final Tab browser = await tab(id);
      final List<({int loaded, int total})> seen = <({int loaded, int total})>[];
      void note() {
        final ({int loaded, int total})? progress = browser.sync.progress;
        if (progress != null && (seen.isEmpty || seen.last != progress)) {
          seen.add(progress);
        }
      }

      browser.sync.addListener(note);
      browser.sync.progressChanges.addListener(note);
      await browser.settled();
      final int listed = browser.workspace.docs.length;
      expect(
        seen.map((({int loaded, int total}) step) => step.total).toSet(),
        <int>{listed},
        reason: 'the count names every listed document',
      );
      expect(
        seen.map((({int loaded, int total}) step) => step.loaded).toSet().length,
        greaterThanOrEqualTo(3),
        reason: 'the count moves while bodies arrive: $seen',
      );
      for (int index = 1; index < seen.length; index++) {
        expect(seen[index].loaded, greaterThanOrEqualTo(seen[index - 1].loaded));
      }
    });

    test(
      'should enable Publish and count the change right after Keep mine',
      () async {
        await control(server, 'edit', <String, Object?>{
          'kind': 'hologram',
          'id': 'keep',
          'json': hologramJson('Base'),
        });
        final String id = await pair();
        final Tab browser = await tab(id);
        await browser.settled();
        browser.edit(browser.doc('hologram', 'keep')!, hologramJson('Mine'));
        await control(server, 'edit', <String, Object?>{
          'kind': 'hologram',
          'id': 'keep',
          'json': hologramJson('Server', revision: 2),
        });
        await waitFor(() => browser.sync.choiceCount == 1);
        int notified = 0;
        browser.sync.addListener(() => notified++);
        await browser.sync.keepMine(editorSyncKey('hologram', 'keep'));
        // One rebuild reads the bar first, then the sheet's Review list.
        await Future<void>.delayed(Duration.zero);
        browser.sync.pendingChangeCount;
        browser.sync.changes;
        final int before = notified;
        await waitFor(
          () => notified > before && browser.sync.pendingChangeCount == 1,
          timeout: const Duration(seconds: 1),
          reason: 'a rebuild that shows the change',
        );
        expect(browser.sync.saveBarState.primary.toString(), contains('publish'));
        expect(browser.sync.statusDetail, '1 change to publish');
      },
    );
  });

  group('the bar after a choice', () {
    test(
      'should tell the bar the count the open sheet read after Keep mine',
      () async {
        await control(server, 'edit', <String, Object?>{
          'kind': 'hologram',
          'id': 'keep',
          'json': hologramJson('Base'),
        });
        final String id = await pair();
        final Tab browser = await tab(id);
        await browser.settled();
        browser.edit(browser.doc('hologram', 'keep')!, hologramJson('Mine'));
        await control(server, 'edit', <String, Object?>{
          'kind': 'hologram',
          'id': 'keep',
          'json': hologramJson('Server', revision: 2),
        });
        await waitFor(() => browser.sync.choiceCount == 1);
        int notified = 0;
        browser.sync.addListener(() => notified++);
        await browser.sync.keepMine(editorSyncKey('hologram', 'keep'));
        // One rebuild: the bar reads its line, then the open sheet builds
        // Review and its footer counts what Review listed.
        await Future<void>.delayed(Duration.zero);
        final String bar = browser.sync.statusDetail;
        browser.sync.changes;
        final int sheet = browser.sync.pendingChangeCount;
        expect(sheet, 1);
        final int before = notified;
        if (bar != '1 change to publish') {
          await waitFor(
            () => notified > before,
            timeout: const Duration(seconds: 1),
            reason: 'the bar to hear of the count the sheet shows',
          );
        }
        expect(browser.sync.statusDetail, '1 change to publish');
      },
    );

    test('should drop the last publish line once a choice is made', () async {
      for (final String name in <String>['first', 'take']) {
        await control(server, 'edit', <String, Object?>{
          'kind': 'hologram',
          'id': name,
          'json': hologramJson('Base $name'),
        });
      }
      final String id = await pair();
      final Tab browser = await tab(id);
      await browser.settled();
      browser.edit(browser.doc('hologram', 'first')!, hologramJson('Two'));
      await waitFor(() => browser.sync.pendingChangeCount == 1);
      await browser.sync.publish();
      expect(browser.sync.statusDetail, 'Published 1 change.');
      browser.edit(browser.doc('hologram', 'take')!, hologramJson('Mine'));
      await control(server, 'edit', <String, Object?>{
        'kind': 'hologram',
        'id': 'take',
        'json': hologramJson('Server take', revision: 2),
      });
      await waitFor(() => browser.sync.choiceCount == 1);
      await browser.sync.useServer(editorSyncKey('hologram', 'take'));
      expect(browser.sync.choiceCount, 0);
      expect(browser.sync.status, EditorSyncStatus.connected);
      expect(browser.sync.statusDetail, 'No changes to publish');
    });
  });
}
