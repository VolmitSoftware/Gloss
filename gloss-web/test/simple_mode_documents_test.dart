import 'package:gloss_editor/doctype/doctype.dart';
import 'package:gloss_editor/state/editor_store.dart';
import 'package:gloss_editor/state/workspace.dart';
import 'package:test/test.dart';

void main() {
  test('closing the open document leaves the workspace with none open', () {
    final EditorStore store = EditorStore(
      workspace: Workspace(autoLoad: false),
      seedDocument: false,
    );
    addTearDown(store.dispose);
    store.newGlossDocument(DocumentTypes.glyph);
    final String glyphs = store.workspace.activeId!;
    store.closeDocument();
    expect(store.workspace.activeId, isNull);
    expect(store.workspace.byId(glyphs), isNotNull);
  });
}
