/// One browser tab on a paired server's workspace against the in-process
/// fake server, and the helpers the sync tests share.
library;

import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:gloss_editor/logic/advanced_notice.dart';
import 'package:gloss_editor/services/editor_sync.dart';
import 'package:gloss_editor/services/editor_sync_controller.dart';
import 'package:gloss_editor/services/image_library.dart';
import 'package:gloss_editor/services/server/server_hub.dart';
import 'package:gloss_editor/state/editor_store.dart';
import 'package:gloss_editor/state/workspace.dart';
import 'package:gloss_editor/state/workspace_repository.dart';
import 'package:test/test.dart';

import '../../tool/fake_server/server.dart';

/// Polls [condition] every 10 ms until it holds or [timeout] passes.
Future<void> waitFor(
  bool Function() condition, {
  Duration timeout = const Duration(seconds: 8),
  String? reason,
}) async {
  final Stopwatch clock = Stopwatch()..start();
  while (!condition()) {
    if (clock.elapsed > timeout) {
      fail('timed out waiting${reason == null ? '' : ' for $reason'}');
    }
    await Future<void>.delayed(const Duration(milliseconds: 10));
  }
}

String hologramJson(String line, {int revision = 1}) =>
    jsonEncode(<String, Object?>{
      'schemaVersion': 1,
      'revision': revision,
      'lines': <Object?>[line],
      'anchor': <String, Object?>{'world': 'world', 'x': 0, 'y': 64, 'z': 0},
    });

/// One call to the fake server's test controls.
Future<Map<String, Object?>> control(
  FakeGlossServer server,
  String path, [
  Map<String, Object?> body = const <String, Object?>{},
]) async {
  final HttpClient client = HttpClient();
  try {
    final HttpClientRequest request = await client.postUrl(
      Uri.parse('${server.directUrl}/__fake/$path'),
    );
    request.headers.contentType = ContentType.json;
    request.add(utf8.encode(jsonEncode(body)));
    final HttpClientResponse response = await request.close();
    final Object? decoded = jsonDecode(await utf8.decodeStream(response));
    return decoded is Map<String, Object?> ? decoded : <String, Object?>{};
  } finally {
    client.close(force: true);
  }
}

/// One browser tab on one paired server's workspace. A second tab built on
/// the same maps is a reload.
final class Tab {
  Tab(
    this.hub,
    this.serverId, {
    Map<String, String>? storage,
    Map<String, String>? bindings,
    this.answer = true,
    this.repository,
  }) : storage = storage ?? <String, String>{},
       bindingStorage = bindings ?? <String, String>{};

  /// The workspace store, when not [storage] written at once.
  final WorkspaceRepository? repository;

  /// Answers the controller's next confirm, when set: the question stays
  /// open until it completes.
  Completer<bool>? holdAnswer;

  /// What the tab answers when the controller asks to confirm.
  bool answer;

  /// Every confirm the controller asked: title, then confirm label.
  final List<(String, String)> asked = <(String, String)>[];

  final ServerHub hub;
  final String serverId;
  final Map<String, String> storage;
  final Map<String, String> bindingStorage;
  final List<String> toasts = <String>[];

  /// Each batch of new documents the server wrote into the workspace.
  final List<List<ArrivedDocument>> arrivals = <List<ArrivedDocument>>[];

  late final Workspace workspace;
  late final ImageLibrary images;
  late final EditorStore store;
  late final EditorSyncController sync;

  Future<Tab> open({bool start = true}) async {
    workspace = Workspace(
      key: Workspace.serverStorageKey(serverId),
      repository: repository,
      read: (String key) => storage[key],
      write: (String key, String value) {
        storage[key] = value;
        return true;
      },
    );
    await workspace.ready;
    images = ImageLibrary();
    store = EditorStore(
      workspace: workspace,
      images: images,
      autosaveDelay: const Duration(days: 1),
      seedDocument: false,
    );
    sync = EditorSyncController(
      store: store,
      images: images,
      hub: hub,
      serverId: serverId,
      bindings: EditorSyncBindingStore(
        read: (String id) => bindingStorage[id],
        write: (String id, String value) {
          bindingStorage[id] = value;
          return true;
        },
        remove: bindingStorage.remove,
      ),
      toast: (String message, {bool error = false}) => toasts.add(message),
      onArrived: arrivals.add,
      confirm:
          ({
            required String title,
            required String confirmLabel,
            String? message,
            bool destructive = false,
          }) async {
            asked.add((title, confirmLabel));
            final Completer<bool>? held = holdAnswer;
            if (held != null) return held.future;
            return answer;
          },
      landEvery: Duration.zero,
    );
    if (start) sync.start();
    return this;
  }

  WorkspaceDoc? doc(String kind, String id) {
    for (final WorkspaceDoc doc in workspace.docs) {
      if (editorSyncKeyOf(doc) == editorSyncKey(kind, id)) return doc;
    }
    return null;
  }

  Future<void> settled() => waitFor(
    () =>
        (sync.status == EditorSyncStatus.connected ||
            sync.status == EditorSyncStatus.applied) &&
        sync.binding.unloaded == 0 &&
        !sync.publishing,
    reason: 'connected and filled, was ${sync.status} ${sync.statusDetail}',
  );

  /// Edits [doc]'s JSON the way the inspector's autosave lands.
  void edit(WorkspaceDoc doc, String json) {
    workspace.updateDocuments(<WorkspaceDocumentUpdate>[
      WorkspaceDocumentUpdate(documentId: doc.id, json: json),
    ]);
  }

  Future<void> close() async {
    sync.dispose();
    store.dispose();
  }
}

/// A fake server, a hub and the tabs one test opened, closed together.
final class SyncHarness {
  SyncHarness(this.server, this.hub);

  final FakeGlossServer server;
  final ServerHub hub;
  final List<Tab> tabs = <Tab>[];

  Future<String> pair({
    PairPaths paths = PairPaths.both,
    Duration lifetime = const Duration(days: 7),
    List<String>? scopes,
  }) {
    final PairLink link = server.pair(
      paths: paths,
      lifetime: lifetime,
      scopes: scopes,
    );
    return hub.pair(link.code, remember: true, client: 'Test on VM');
  }

  /// A tab on [serverId]; with [reloadOf], the same browser storage again.
  Future<Tab> tab(
    String serverId, {
    Tab? reloadOf,
    bool start = true,
    WorkspaceRepository? repository,
  }) {
    final Tab made = Tab(
      hub,
      serverId,
      storage: reloadOf?.storage,
      bindings: reloadOf?.bindingStorage,
      repository: repository ?? reloadOf?.repository,
    );
    tabs.add(made);
    return made.open(start: start);
  }

  /// Closes [tab] and releases the server, as a closed page does.
  Future<void> closeTab(Tab tab) async {
    await tab.close();
    tabs.remove(tab);
    await hub.release(tab.serverId);
  }

  Future<void> close() async {
    for (final Tab open in tabs) {
      await open.close();
    }
    tabs.clear();
    hub.dispose();
    await server.close();
  }
}
