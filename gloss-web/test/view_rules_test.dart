import 'package:gloss_editor/components/shell/view_rules.dart';
import 'package:gloss_editor/doctype/doctype.dart';
import 'package:gloss_editor/state/editor_store.dart';
import 'package:gloss_editor/state/workspace.dart';
import 'package:test/test.dart';

void main() {
  test('Edit and Preview are offered only where the preview differs', () {
    for (final DocumentTypeAdapter type in DocumentTypeRegistry.all) {
      final ViewChoices choices = viewChoices(
        type,
        advanced: true,
        compact: false,
      );
      expect(
        choices.editPreview,
        type.kind == WorkspaceDocKind.menu ||
            type.kind == WorkspaceDocKind.containerPreview,
        reason: type.kind.name,
      );
    }
  });

  test('the View switch lists Edit, Preview, Code and Split in order', () {
    expect(
      viewChoices(DocumentTypes.menu, advanced: true, compact: false).options,
      <EditorView>[
        EditorView.visual,
        EditorView.preview,
        EditorView.code,
        EditorView.split,
      ],
    );
    expect(
      viewChoices(
        DocumentTypes.hologram,
        advanced: true,
        compact: false,
      ).options,
      <EditorView>[EditorView.visual, EditorView.code, EditorView.split],
    );
    expect(
      viewChoices(DocumentTypes.menu, advanced: true, compact: true).options,
      <EditorView>[EditorView.visual, EditorView.preview, EditorView.code],
    );
    expect(
      viewChoices(
        DocumentTypes.hologram,
        advanced: false,
        compact: true,
      ).options,
      <EditorView>[EditorView.visual, EditorView.code],
    );
  });

  test('a kind with one view gets no switch', () {
    final ViewChoices panel = viewChoices(
      DocumentTypes.panel,
      advanced: true,
      compact: false,
    );
    expect(panel.options, <EditorView>[EditorView.visual]);
    expect(panel.any, isFalse);
  });

  test('every width has one bar layout, whatever the kind', () {
    expect(barWidth(compact: false, phone: false), BarWidth.wide);
    expect(barWidth(compact: true, phone: false), BarWidth.compact);
    expect(barWidth(compact: true, phone: true), BarWidth.phone);

    final BarLayout wide = barLayout(BarWidth.wide);
    expect(wide.viewLabels, isTrue);
    expect(wide.actionLabels, isTrue);
    expect(wide.history && wide.search && wide.server, isTrue);
    expect(wide.mode, isTrue);

    final BarLayout compact = barLayout(BarWidth.compact);
    expect(compact.viewLabels, isFalse);
    expect(compact.actionLabels, isFalse);
    expect(compact.history && compact.search && compact.server, isTrue);
    expect(compact.mode, isFalse);

    final BarLayout phone = barLayout(BarWidth.phone);
    expect(phone.viewLabels || phone.actionLabels, isFalse);
    expect(phone.history || phone.search || phone.server, isFalse);
    expect(phone.mode, isFalse);
  });

  test('a measured fold moves the mode switch first, then the View labels', () {
    final BarLayout once = barLayout(BarWidth.wide, fold: 1);
    expect(once.mode, isFalse);
    expect(once.viewLabels, isTrue);
    final BarLayout twice = barLayout(BarWidth.wide, fold: 2);
    expect(twice.mode, isFalse);
    expect(twice.viewLabels, isFalse);
    expect(twice.history && twice.search && twice.server, isTrue);
    expect(
      barLayout(BarWidth.wide, fold: barFoldSteps + 3).viewLabels,
      isFalse,
    );
    expect(barLayout(BarWidth.phone, fold: 2).history, isFalse);
  });

  test('Code follows runtime JSON; flow maps offer nothing', () {
    final ViewChoices panel = viewChoices(
      DocumentTypes.panel,
      advanced: true,
      compact: false,
    );
    expect(panel.any, isFalse);
    expect(
      viewChoices(DocumentTypes.hologram, advanced: false, compact: true).code,
      isTrue,
    );
  });

  test('Split is Advanced and desktop only', () {
    expect(
      viewChoices(DocumentTypes.menu, advanced: true, compact: false).split,
      isTrue,
    );
    expect(
      viewChoices(DocumentTypes.menu, advanced: false, compact: false).split,
      isFalse,
    );
    expect(
      viewChoices(DocumentTypes.menu, advanced: true, compact: true).split,
      isFalse,
    );
  });

  test('a preview that draws the same stage shows the stage', () {
    expect(
      shownView(
        EditorView.preview,
        WorkspaceDocKind.hologram,
        advanced: true,
        compact: false,
      ),
      EditorView.visual,
    );
    expect(
      shownView(
        EditorView.preview,
        WorkspaceDocKind.menu,
        advanced: false,
        compact: true,
      ),
      EditorView.preview,
    );
  });

  test('a split the bar does not offer shows the stage', () {
    expect(
      shownView(
        EditorView.split,
        WorkspaceDocKind.menu,
        advanced: false,
        compact: false,
      ),
      EditorView.visual,
    );
    expect(
      shownView(
        EditorView.split,
        WorkspaceDocKind.menu,
        advanced: true,
        compact: true,
      ),
      EditorView.visual,
    );
    expect(
      shownView(
        EditorView.split,
        WorkspaceDocKind.menu,
        advanced: true,
        compact: false,
      ),
      EditorView.split,
    );
  });

  test('the Code and Split toggles go in and back out', () {
    expect(
      afterCodeToggle(EditorView.preview, EditorView.preview),
      EditorView.code,
    );
    expect(
      afterCodeToggle(EditorView.code, EditorView.preview),
      EditorView.preview,
    );
    expect(afterSplitToggle(EditorView.visual), EditorView.split);
    expect(afterSplitToggle(EditorView.split), EditorView.visual);
  });
}
