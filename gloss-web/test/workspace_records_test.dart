/// A workspace kept as records: a save writes what changed, a reload reads
/// the same workspace back, and a workspace stored whole moves into records.
library;

import 'dart:convert';

import 'package:gloss_editor/state/workspace.dart';
import 'package:gloss_editor/state/workspace_records.dart';
import 'package:test/test.dart';

import 'support/layered_store.dart';

const String _key = 'gloss.workspace.server.test';

String _body(int index) => jsonEncode(<String, Object?>{
  'schemaVersion': 1,
  'revision': 1,
  'lines': <String>[
    for (int line = 0; line < 20; line++) 'Line $line of $index',
  ],
});

Future<Workspace> _open(LayeredMemoryStore store) =>
    Workspace.open(repository: store, key: _key);

Future<Workspace> _seeded(LayeredMemoryStore store, int count) async {
  final Workspace workspace = await _open(store);
  final WorkspaceFolder folder = workspace.createFolder(title: 'Holograms');
  for (int index = 0; index < count; index++) {
    workspace.create(
      title: 'doc-$index',
      runtimeId: 'doc-$index',
      json: _body(index),
      kind: WorkspaceDocKind.hologram,
      folderId: folder.id,
    );
  }
  await workspace.writesSettled;
  return workspace;
}

void main() {
  test(
    'should write only the edited document when the store takes changes',
    () async {
      final LayeredMemoryStore store = LayeredMemoryStore();
      final Workspace workspace = await _seeded(store, 200);
      final WorkspaceDoc doc = workspace.docs[120];
      final int whole = store.records.values.fold(
        0,
        (int sum, String value) => sum + value.length,
      );
      store.commits.clear();

      workspace.updateDocuments(<WorkspaceDocumentUpdate>[
        WorkspaceDocumentUpdate(documentId: doc.id, json: '{"edited":true}'),
      ]);
      await workspace.writesSettled;

      expect(store.commits, hasLength(1));
      expect(store.commits.single.keys, <String>{
        workspaceDocumentKey(_key, doc.id),
        _key,
      });
      expect(store.written, lessThan(whole ~/ 50));
      expect(workspace.hasUnsavedChanges, isFalse);
    },
  );

  test(
    'should write the layout only when folders or the order change',
    () async {
      final LayeredMemoryStore store = LayeredMemoryStore();
      final Workspace workspace = await _seeded(store, 5);
      store.commits.clear();

      workspace.switchTo(workspace.docs[2].id);
      await workspace.writesSettled;
      expect(store.commits.single.keys, <String>{_key});

      workspace.createFolder(title: 'Later');
      await workspace.writesSettled;
      expect(store.commits.last.keys, contains(workspaceLayoutKey(_key)));

      final String gone = workspace.docs[1].id;
      workspace.delete(gone);
      await workspace.writesSettled;
      expect(
        store.commits.last,
        containsPair(workspaceDocumentKey(_key, gone), isNull),
      );
      expect(store.commits.last.keys, contains(workspaceLayoutKey(_key)));
    },
  );

  test(
    'should read back edits, new documents and deletions after a reload',
    () async {
      final LayeredMemoryStore store = LayeredMemoryStore();
      final Workspace first = await _seeded(store, 6);
      first.updateDocuments(<WorkspaceDocumentUpdate>[
        WorkspaceDocumentUpdate(documentId: first.docs[0].id, json: '{"a":1}'),
      ]);
      first.delete(first.docs[3].id);
      first.create(
        title: 'late',
        runtimeId: 'late',
        json: '{"late":true}',
        kind: WorkspaceDocKind.hologram,
      );
      await first.writesSettled;
      final Map<String, dynamic> before = first.exportPortableState();

      final Workspace reload = await _open(LayeredMemoryStore(store.records));

      expect(reload.exportPortableState(), before);
      expect(reload.lastError, isNull);
      expect(reload.storedRevision, first.storedRevision);
    },
  );

  test(
    'should move a workspace stored whole into records on its first read',
    () async {
      final Map<String, String> whole = <String, String>{};
      final Workspace old = Workspace(
        key: _key,
        read: (String key) => whole[key],
        write: (String key, String value) {
          whole[key] = value;
          return true;
        },
      );
      await old.ready;
      for (int index = 0; index < 4; index++) {
        old.create(
          title: 'doc-$index',
          runtimeId: 'doc-$index',
          json: _body(index),
          kind: WorkspaceDocKind.hologram,
        );
      }
      final Map<String, dynamic> before = old.exportPortableState();
      final LayeredMemoryStore store = LayeredMemoryStore(<String, String>{
        _key: whole[_key]!,
      });

      final Workspace migrated = await _open(store);

      expect(migrated.exportPortableState(), before);
      expect(isWorkspaceHeader(store.records[_key]!), isTrue);
      expect(store.records[workspaceLayoutKey(_key)], isNotNull);
      for (final WorkspaceDoc doc in migrated.docs) {
        expect(store.records[workspaceDocumentKey(_key, doc.id)], isNotNull);
      }
      store.commits.clear();
      migrated.updateDocuments(<WorkspaceDocumentUpdate>[
        WorkspaceDocumentUpdate(documentId: migrated.docs[1].id, json: '{}'),
      ]);
      await migrated.writesSettled;
      expect(store.commits.single.keys, hasLength(2));
    },
  );

  test(
    'should write the whole workspace when the store refuses the changes',
    () async {
      final LayeredMemoryStore store = LayeredMemoryStore();
      final Workspace workspace = await _seeded(store, 3);
      store.refuseChanges = true;
      store.commits.clear();

      workspace.updateDocuments(<WorkspaceDocumentUpdate>[
        WorkspaceDocumentUpdate(
          documentId: workspace.docs[0].id,
          json: '{"x":2}',
        ),
      ]);
      await workspace.writesSettled;

      expect(workspace.hasUnsavedChanges, isFalse);
      expect(workspace.lastError, isNull);
      expect(
        store.commits.single.keys,
        containsAll(<String>[
          _key,
          workspaceLayoutKey(_key),
          for (final WorkspaceDoc doc in workspace.docs)
            workspaceDocumentKey(_key, doc.id),
        ]),
      );
      final Workspace reload = await _open(LayeredMemoryStore(store.records));
      expect(reload.exportPortableState(), workspace.exportPortableState());
    },
  );

  test('should rewrite what a failed write left behind', () async {
    final LayeredMemoryStore store = LayeredMemoryStore();
    final Workspace workspace = await _seeded(store, 3);
    final String id = workspace.docs[2].id;
    store.refuse = true;
    workspace.updateDocuments(<WorkspaceDocumentUpdate>[
      WorkspaceDocumentUpdate(documentId: id, json: '{"lost":false}'),
    ]);
    await workspace.writesSettled;
    expect(workspace.hasUnsavedChanges, isTrue);

    store.refuse = false;
    expect(await workspace.retrySave(), isTrue);

    final Workspace reload = await _open(LayeredMemoryStore(store.records));
    expect(reload.byId(id)!.json, '{"lost":false}');
  });

  test('should keep the revision storage holds across a reload', () async {
    final Map<String, String> values = <String, String>{};
    Workspace open() => Workspace(
      key: _key,
      read: (String key) => values[key],
      write: (String key, String value) {
        values[key] = value;
        return true;
      },
    );
    final Workspace first = open();
    await first.ready;
    expect(first.storedRevision, 0);
    first.create(title: 'a', runtimeId: 'a', json: '{}');
    first.create(title: 'b', runtimeId: 'b', json: '{}');
    expect(first.storedRevision, 2);
    expect((jsonDecode(values[_key]!) as Map<String, Object?>)['revision'], 2);

    final Workspace reload = open();
    await reload.ready;
    expect(reload.storedRevision, 2);
    reload.create(title: 'c', runtimeId: 'c', json: '{}');
    expect(reload.storedRevision, 3);
  });

  test('should assemble the workspace it split', () {
    final Map<String, Object?> whole = <String, Object?>{
      'schemaVersion': wholeWorkspaceSchema,
      'workspaceId': '00000000-0000-4000-8000-000000000001',
      'folders': <Object?>[
        <String, Object?>{
          'id': '00000000-0000-4000-8000-000000000002',
          'title': 'Menus',
          'parentId': null,
          'updatedAt': 1,
        },
      ],
      'documents': <Object?>[
        for (int index = 3; index < 6; index++)
          <String, Object?>{
            'id': '00000000-0000-4000-8000-00000000000$index',
            'title': 'Doc "$index" é中',
            'runtimeId': 'doc-$index',
            'json': '{"line":"\\\\n $index"}',
            'updatedAt': index,
            'kind': 'menu',
            'folderId': null,
          },
      ],
      'activeDocumentId': '00000000-0000-4000-8000-000000000004',
      'revision': 7,
    };
    final WorkspaceRecordSet records = splitWorkspaceRecord(whole);
    expect(jsonDecode(records.header), <String, Object?>{
      'schemaVersion': layeredWorkspaceSchema,
      'workspaceId': '00000000-0000-4000-8000-000000000001',
      'revision': 7,
      'activeDocumentId': '00000000-0000-4000-8000-000000000004',
    });
    expect(
      jsonDecode(
        assembleWorkspaceRecord(
          header: records.header,
          layout: records.layout,
          documents: records.documents,
        ),
      ),
      whole,
    );
  });
}
