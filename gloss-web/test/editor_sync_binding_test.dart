/// How a paired server's binding is kept in browser storage: a small header
/// and rows in buckets, each save rewriting only the buckets that changed.
library;

import 'dart:convert';

import 'package:gloss_editor/services/editor_sync.dart';
import 'package:gloss_editor/state/workspace.dart';
import 'package:test/test.dart';

const String _serverId = 'srv';

final class _Storage {
  final Map<String, String> values = <String, String>{};
  final List<String> writes = <String>[];

  EditorSyncBindingStore get store => EditorSyncBindingStore(
    read: (String name) => values[name],
    write: (String name, String value) {
      writes.add(name);
      values[name] = value;
      return true;
    },
    remove: values.remove,
  );
}

String _hash(int index) => 'sha256:${index.toRadixString(16).padLeft(64, '0')}';

SyncBaseDocument _row(int index, {int hash = 0, bool loaded = true}) =>
    SyncBaseDocument(
      kind: 'hologram',
      id: 'doc-$index',
      hash: _hash(hash == 0 ? index + 1 : hash),
      name: 'doc-$index',
      revision: 1,
      docId: '00000000-0000-4000-8000-${index.toString().padLeft(12, '0')}',
      size: 100,
      loaded: loaded,
    );

Future<Workspace> _workspace({int saves = 0}) async {
  final Map<String, String> values = <String, String>{};
  final Workspace workspace = Workspace(
    key: Workspace.serverStorageKey(_serverId),
    read: (String key) => values[key],
    write: (String key, String value) {
      values[key] = value;
      return true;
    },
  );
  await workspace.ready;
  for (int index = 0; index < saves; index++) {
    workspace.create(title: 'd$index', runtimeId: 'd$index', json: '{}');
  }
  return workspace;
}

EditorSyncBinding _binding(Workspace workspace, int rows) => EditorSyncBinding(
  serverId: _serverId,
  workspaceId: workspace.id,
  documents: <String, SyncBaseDocument>{
    for (int index = 0; index < rows; index++) _row(index).key: _row(index),
  },
);

void main() {
  test('should write only the buckets whose rows changed', () async {
    final Workspace workspace = await _workspace(saves: 1);
    final _Storage storage = _Storage();
    final EditorSyncBinding binding = _binding(workspace, 1600);
    expect(storage.store.save(binding), isTrue);
    final int first = storage.writes.length;
    expect(first, greaterThan(2));
    storage.writes.clear();

    binding.documents[_row(700).key] = _row(700, hash: 9999);
    expect(storage.store.save(binding), isTrue);

    expect(
      storage.writes,
      hasLength(1),
      reason: 'the bucket of the changed row; the header did not change',
    );
    expect(storage.writes.single, isNot(_serverId));
    final int bytes = storage.values.entries
        .where((MapEntry<String, String> entry) => entry.key != _serverId)
        .fold(
          0,
          (int sum, MapEntry<String, String> entry) => sum + entry.value.length,
        );
    expect(storage.values[storage.writes.first]!.length, lessThan(bytes ~/ 20));
  });

  test('should read back every row it wrote', () async {
    final Workspace workspace = await _workspace(saves: 1);
    final _Storage storage = _Storage();
    final EditorSyncBinding binding = _binding(workspace, 300)
      ..metaHash = 'meta'
      ..conflicts['hologram\u0000doc-3'] = 'changed'
      ..images['shop/coin.png'] = SyncBaseImage(
        path: 'shop/coin.png',
        hash: _hash(77),
        size: 10,
        mediaType: 'image/png',
        width: 2,
        height: 2,
        local: true,
      );
    storage.store.save(binding);

    final EditorSyncBinding read = storage.store.load(_serverId, workspace);

    expect(read.documents.keys, unorderedEquals(binding.documents.keys));
    expect(read.documents[_row(42).key]!.hash, _row(42).hash);
    expect(read.images['shop/coin.png']!.local, isTrue);
    expect(read.conflicts, binding.conflicts);
    expect(read.metaHash, 'meta');
  });

  test('should read a single-record binding and save it in buckets', () async {
    final Workspace workspace = await _workspace(saves: 1);
    final _Storage storage = _Storage();
    storage.values[_serverId] = jsonEncode(<String, Object?>{
      'version': 6,
      'serverId': _serverId,
      'workspaceId': workspace.id,
      'metaHash': null,
      'notesHash': null,
      'documents': <Object?>[
        for (int index = 0; index < 50; index++)
          <Object?>[
            'hologram',
            'doc-$index',
            _hash(index + 1),
            'doc-$index',
            1,
            _row(index).docId,
            100,
            true,
          ],
      ],
      'images': <Object?>[],
      'conflicts': <String, String>{},
    });

    final EditorSyncBinding read = storage.store.load(_serverId, workspace);
    expect(read.documents, hasLength(50));
    storage.store.save(read);

    expect(
      (jsonDecode(storage.values[_serverId]!)
          as Map<String, Object?>)['version'],
      isNot(6),
    );
    final EditorSyncBinding again = storage.store.load(_serverId, workspace);
    expect(again.documents.keys, unorderedEquals(read.documents.keys));
  });

  test('should remove the header and every bucket on clear', () async {
    final Workspace workspace = await _workspace(saves: 1);
    final _Storage storage = _Storage();
    storage.store.save(_binding(workspace, 400));
    expect(storage.values.length, greaterThan(1));

    storage.store.clear(_serverId);

    expect(storage.values, isEmpty);
  });

  test(
    'should start fresh when the binding is newer than the stored workspace',
    () async {
      final Workspace workspace = await _workspace(saves: 2);
      final _Storage storage = _Storage();
      final EditorSyncBinding ahead = _binding(workspace, 10)
        ..workspaceRevision = workspace.storedRevision + 1;
      storage.store.save(ahead);
      expect(storage.store.load(_serverId, workspace).documents, isEmpty);

      final EditorSyncBinding level = _binding(workspace, 10)
        ..workspaceRevision = workspace.storedRevision;
      storage.store.save(level);
      expect(storage.store.load(_serverId, workspace).documents, hasLength(10));
    },
  );

  test('should store a pending publish without the rows', () async {
    final Workspace workspace = await _workspace(saves: 1);
    final _Storage storage = _Storage();
    final EditorSyncBinding binding = _binding(workspace, 100);
    storage.store.save(binding);
    storage.writes.clear();
    binding.pending = const SyncPendingPublish('pub_abc', <String, Object?>{
      'idempotencyKey': 'pub_abc',
    });
    binding.documents[_row(5).key] = _row(5, hash: 4242);

    expect(storage.store.saveHeader(binding), isTrue);

    expect(storage.writes, <String>[_serverId]);
    final EditorSyncBinding read = storage.store.load(_serverId, workspace);
    expect(read.pending?.idempotencyKey, 'pub_abc');
    expect(read.documents[_row(5).key]!.hash, _row(5).hash);
  });
}
