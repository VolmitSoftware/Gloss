import 'package:gloss_editor/components/dialogs/new_document_choice.dart';
import 'package:gloss_editor/config/kind_groups.dart';
import 'package:gloss_editor/doctype/doctype.dart';
import 'package:gloss_editor/services/showcase_randomizer.dart';
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

EditorStore _store() {
  final _MemoryStorage storage = _MemoryStorage();
  return EditorStore(
    workspace: Workspace(read: storage.read, write: storage.write),
    autosaveDelay: Duration.zero,
  );
}

List<WorkspaceDocKind> _kinds(List<NewDocumentGroup> groups) =>
    <WorkspaceDocKind>[
      for (final NewDocumentGroup group in groups)
        for (final DocumentTypeAdapter type in group.types) type.kind,
    ];

void main() {
  group('newDocumentGroups', () {
    test('blank in Advanced offers every kind in group order', () {
      final List<NewDocumentGroup> groups = newDocumentGroups(
        NewDocumentStart.blank,
        advanced: true,
      );
      expect(<KindGroup>[
        for (final NewDocumentGroup group in groups) group.group,
      ], KindGroup.values);
      expect(_kinds(groups), <WorkspaceDocKind>[
        for (final KindInfo info in kindInfos) info.kind,
      ]);
    });

    test('Simple leaves out Advanced kinds and Shared resources', () {
      final List<NewDocumentGroup> groups = newDocumentGroups(
        NewDocumentStart.blank,
        advanced: false,
      );
      expect(
        groups.map((NewDocumentGroup group) => group.group),
        isNot(contains(KindGroup.shared)),
      );
      expect(_kinds(groups), isNot(contains(WorkspaceDocKind.panel)));
      expect(
        _kinds(groups).every((WorkspaceDocKind kind) => !kindIsAdvanced(kind)),
        isTrue,
      );
    });

    test('template lists only kinds that ship templates', () {
      final List<WorkspaceDocKind> kinds = _kinds(
        newDocumentGroups(NewDocumentStart.template, advanced: true),
      );
      expect(kinds, isNot(contains(WorkspaceDocKind.panel)));
      expect(kinds, isNot(contains(WorkspaceDocKind.presets)));
      expect(kinds, contains(WorkspaceDocKind.menu));
      for (final WorkspaceDocKind kind in kinds) {
        expect(
          templatesOf(DocumentTypeRegistry.of(kind)),
          isNotEmpty,
          reason: kind.name,
        );
      }
    });

    test('random lists only kinds the randomizer can fill', () {
      final List<WorkspaceDocKind> kinds = _kinds(
        newDocumentGroups(NewDocumentStart.random, advanced: true),
      );
      expect(kinds, isNot(contains(WorkspaceDocKind.panel)));
      expect(kinds, contains(WorkspaceDocKind.scoreboard));
      for (final WorkspaceDocKind kind in kinds) {
        expect(
          canRandomizeShowcase(DocumentTypeRegistry.of(kind)),
          isTrue,
          reason: kind.name,
        );
      }
    });
  });

  group('createNewDocument', () {
    test('blank creates the kind in the folder and opens it', () {
      final EditorStore store = _store();
      final WorkspaceFolder folder = store.workspace.createFolder(
        title: 'Lobby',
      );
      final int before = store.workspace.docs.length;
      createNewDocument(
        store,
        const NewDocumentChoice(DocumentTypes.scoreboard),
        folderId: folder.id,
      );
      final WorkspaceDoc active = store.workspace.active!;
      expect(store.workspace.docs, hasLength(before + 1));
      expect(active.kind, WorkspaceDocKind.scoreboard);
      expect(active.folderId, folder.id);
    });

    test('blank takes the runtime id override', () {
      final EditorStore store = _store();
      createNewDocument(
        store,
        const NewDocumentChoice(DocumentTypes.menu),
        runtimeId: 'sync-menu',
      );
      expect(store.workspace.active!.runtimeId, 'sync-menu');
    });

    test('a template creates a new document and moves it to the folder', () {
      final EditorStore store = _store();
      final WorkspaceFolder folder = store.workspace.createFolder(
        title: 'World',
      );
      final int before = store.workspace.docs.length;
      final DocumentTemplate template = templatesOf(DocumentTypes.hologram)
          .first;
      createNewDocument(
        store,
        NewDocumentChoice(DocumentTypes.hologram, template: template),
        folderId: folder.id,
        runtimeId: 'sync-holo',
      );
      final WorkspaceDoc active = store.workspace.active!;
      expect(store.workspace.docs, hasLength(before + 1));
      expect(active.kind, WorkspaceDocKind.hologram);
      expect(active.folderId, folder.id);
      expect(active.runtimeId, 'sync-holo');
    });

    test('random fills a new document from the seed, the same every time', () {
      String created(int seed) {
        final EditorStore store = _store();
        final WorkspaceFolder folder = store.workspace.createFolder(
          title: 'Board',
        );
        createNewDocument(
          store,
          NewDocumentChoice(DocumentTypes.scoreboard, seed: seed),
          folderId: folder.id,
        );
        store.flushAutosave();
        final WorkspaceDoc active = store.workspace.active!;
        expect(active.kind, WorkspaceDocKind.scoreboard);
        expect(active.folderId, folder.id);
        return active.json;
      }

      final EditorStore blank = _store();
      createNewDocument(
        blank,
        const NewDocumentChoice(DocumentTypes.scoreboard),
      );
      blank.flushAutosave();
      expect(created(42), created(42));
      expect(created(42), isNot(blank.workspace.active!.json));
    });
  });
}
