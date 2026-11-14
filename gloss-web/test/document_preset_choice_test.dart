import 'dart:convert';

import 'package:gloss_editor/doctype/doctype.dart';
import 'package:gloss_editor/logic/document_preset_choice.dart';
import 'package:gloss_editor/state/editor_store.dart';
import 'package:gloss_editor/state/workspace.dart';
import 'package:gloss_editor/state/workspace_panel.dart';
import 'package:test/test.dart';

const String _catalog = '''{
  "schemaVersion":1,"revision":1,
  "defaults":{"boards":{"select":{"priority":0,"when":"true"},"variants":[],"presentation":{"title":"Global","lines":["Global"],"hideNumbers":true}}},
  "presets":{"boards":{"wide":{"values":{"presentation":{"title":"Wide"}}},"base":{"values":{}}}}
}''';

EditorStore _store(Workspace workspace) {
  final EditorStore store = EditorStore(workspace: workspace);
  addTearDown(store.dispose);
  return store;
}

void main() {
  test('flow maps offer no preset instead of throwing on export', () {
    final Workspace workspace = Workspace(autoLoad: false);
    workspace.create(
      title: 'flow',
      json: encodeWorkspacePanel(
        const WorkspacePanelData(
          runtimeBoardId: 'flow',
          runtimeBoard: <String, Object?>{
            'schemaVersion': 1,
            'revision': 1,
            'id': 'flow',
          },
        ),
      ),
      kind: DocumentTypes.panel.kind,
      presetContext: _catalog,
    );
    final EditorStore store = _store(workspace);
    expect(() => store.exportJson(), throwsStateError);
    expect(documentPresetChoice(store), isNull);
  });

  test('lists the collection presets sorted with the selected one', () {
    final Workspace workspace = Workspace(autoLoad: false);
    workspace.create(
      title: 'board',
      runtimeId: 'board',
      json: '{"schemaVersion":2,"revision":3,"preset":"wide"}',
      kind: DocumentTypes.scoreboard.kind,
      presetContext: _catalog,
    );
    final DocumentPresetChoice choice = documentPresetChoice(
      _store(workspace),
    )!;
    expect(choice.error, isNull);
    expect(choice.selected, 'wide');
    expect(choice.names, <String>['base', 'wide']);
  });

  test('picking writes only the preset key and clearing removes it', () {
    final Workspace workspace = Workspace(autoLoad: false);
    workspace.create(
      title: 'board',
      runtimeId: 'board',
      json: '{"schemaVersion":2,"revision":3,"preset":"wide","extra":{"a":1}}',
      kind: DocumentTypes.scoreboard.kind,
      presetContext: _catalog,
    );
    final DocumentPresetChoice choice = documentPresetChoice(
      _store(workspace),
    )!;
    final Map<String, Object?> picked =
        jsonDecode(choice.withPreset('base')) as Map<String, Object?>;
    expect(picked['preset'], 'base');
    expect(picked['extra'], <String, Object?>{'a': 1});
    final Map<String, Object?> cleared =
        jsonDecode(choice.withPreset('')) as Map<String, Object?>;
    expect(cleared.containsKey('preset'), isFalse);
    expect(cleared['revision'], 3);
  });

  test('no catalog and no preset shows nothing', () {
    final Workspace workspace = Workspace(autoLoad: false);
    workspace.create(
      title: 'board',
      runtimeId: 'board',
      json: '{"schemaVersion":2,"revision":3}',
      kind: DocumentTypes.scoreboard.kind,
    );
    expect(documentPresetChoice(_store(workspace)), isNull);
  });

  test('an unreadable catalog reports its error', () {
    final Workspace workspace = Workspace(autoLoad: false);
    workspace.create(
      title: 'board',
      runtimeId: 'board',
      json: '{"schemaVersion":2,"revision":3}',
      kind: DocumentTypes.scoreboard.kind,
      presetContext: '{"presets":',
    );
    final DocumentPresetChoice? choice = documentPresetChoice(
      _store(workspace),
    );
    expect(choice, isNotNull);
    expect(choice!.error, isNotNull);
  });
}
