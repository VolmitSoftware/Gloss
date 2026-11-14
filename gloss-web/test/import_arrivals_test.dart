/// Every import keeps its data and says where it landed: Import JSON with
/// no document open adds one instead of replacing nothing, and each import
/// reports the documents it wrote and their kinds, so Simple can say when
/// only Advanced lists them.
library;

import 'dart:io';

import 'package:gloss_editor/doctype/doctype.dart';
import 'package:gloss_editor/logic/advanced_notice.dart';
import 'package:gloss_editor/state/editor_store.dart';
import 'package:gloss_editor/state/workspace.dart';
import 'package:test/test.dart';

String _fixture(String name) =>
    File('test/demo/fixtures/$name.json').readAsStringSync();

void main() {
  late EditorStore store;
  late List<(List<ArrivedDocument>, String?)> reports;

  EditorStore open({bool seed = false}) {
    final EditorStore made = EditorStore(
      workspace: Workspace(autoLoad: false),
      seedDocument: seed,
    );
    reports = <(List<ArrivedDocument>, String?)>[];
    made.onImported = (List<ArrivedDocument> documents, String? message) =>
        reports.add((documents, message));
    addTearDown(made.dispose);
    return made;
  }

  WorkspaceDocKind kindOf(String id) => store.workspace.byId(id)!.kind;

  test('Import JSON with no document open adds a new document', () {
    store = open();
    store.newGlossDocument(DocumentTypes.names);
    final String names = store.workspace.activeId!;
    store.flushAutosave();
    store.closeDocument();
    expect(store.hasActiveDocument, isFalse);

    store.importJson('strings.json', _fixture('strings'));
    store.flushAutosave();

    expect(store.workspace.docs, hasLength(2));
    expect(kindOf(names), DocumentTypes.names.kind);
    final WorkspaceDoc strings = store.workspace.docs.firstWhere(
      (WorkspaceDoc doc) => doc.id != names,
    );
    expect(strings.kind, DocumentTypes.strings.kind);
    expect(strings.json, contains('"entries"'));
    expect(reports, hasLength(1));
    expect(reports.single.$1.single.id, strings.id);
    expect(reports.single.$1.single.kind, DocumentTypes.strings.kind);
  });

  test('Replace document reports the open document with its new kind', () {
    store = open(seed: true);
    final String menu = store.workspace.activeId!;

    store.importJson('names.json', _fixture('names'));

    expect(reports, hasLength(1));
    final (List<ArrivedDocument> documents, String? message) = reports.single;
    expect(documents.single.id, menu);
    expect(documents.single.kind, DocumentTypes.names.kind);
    expect(message, isNull, reason: 'the replaced document is on screen');
  });

  test('a refused import reports nothing', () {
    store = open(seed: true);
    store.importJson('broken.json', '{"schemaVersion": ');
    expect(reports, isEmpty);
  });

  test('a dropped file reports the new document and its message', () {
    store = open(seed: true);

    expect(
      store.importJsonAsNewDocument('names.json', _fixture('names')),
      isTrue,
    );
    expect(
      store.importJsonAsNewDocument('menu.json', _fixture('menu')),
      isTrue,
    );

    expect(reports, hasLength(2));
    expect(reports[0].$1.single.kind, DocumentTypes.names.kind);
    expect(kindOf(reports[0].$1.single.id), DocumentTypes.names.kind);
    expect(reports[0].$2, 'Imported names as a new Names.');
    expect(reports[1].$1.single.kind, DocumentTypes.menu.kind);
    expect(reports[1].$2, startsWith('Imported '));
  });

  test('without an import sink the message goes to onInfo as before', () {
    store = EditorStore(workspace: Workspace(autoLoad: false));
    addTearDown(store.dispose);
    final List<String> info = <String>[];
    store.onInfo = info.add;

    store.importJsonAsNewDocument('names.json', _fixture('names'));

    expect(info, <String>['Imported names as a new Names.']);
  });
}
