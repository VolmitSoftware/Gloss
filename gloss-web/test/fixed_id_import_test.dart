/// A fixed-id kind (presets, names, glyphs and the like) imported with
/// Replace document right after another in-place import, before autosave
/// has written the workspace entry, must still swap the document kind with
/// its content.
library;

import 'dart:io';

import 'package:gloss_editor/doctype/doctype.dart';
import 'package:gloss_editor/state/editor_store.dart';
import 'package:gloss_editor/state/workspace.dart';
import 'package:test/test.dart';

void main() {
  test('a fixed-id import inside the autosave delay keeps kind and content together', () {
    final Map<String, String> storage = <String, String>{};
    final EditorStore store = EditorStore(
      workspace: Workspace(
        read: (String key) => storage[key],
        write: (String key, String value) {
          storage[key] = value;
          return true;
        },
      ),
      autosaveDelay: const Duration(milliseconds: 500),
    );
    addTearDown(store.dispose);
    final String presets = File('test/demo/fixtures/presets.json')
        .readAsStringSync();
    final String behavior = File('test/demo/fixtures/behavior.json')
        .readAsStringSync();

    store.importJson('presets.json', presets);
    store.flushAutosave();
    store.importJson('behavior.json', behavior);
    store.importJson('presets.json', presets);

    expect(store.docKind, DocumentTypes.presets.kind);
    expect(store.exportJson, returnsNormally);
    store.flushAutosave();
    expect(store.workspace.active!.kind, DocumentTypes.presets.kind);
  });
}
