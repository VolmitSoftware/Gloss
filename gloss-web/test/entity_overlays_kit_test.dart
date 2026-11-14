/// The entity overlays inspector, its view and the shared text editor are
/// built on the kit: raw JSON and show conditions sit behind Advanced, the
/// line tools fold instead of overflowing the pane, the game screen shows
/// once with no stage of its own, and the text preview is one game-drawn
/// surface, not a card.
library;

import 'dart:io';

import 'package:test/test.dart';

String _read(String path) => File(path).readAsStringSync();

void main() {
  final String inspector = _read(
    'lib/components/inspector/entity_overlay_inspector.dart',
  );
  final String view = _read(
    'lib/components/entity_overlays/entity_overlay_view.dart',
  );
  final String editor = _read('lib/components/inspector/text_icon_editor.dart');
  final String preview = _read('lib/components/inspector/mc_text_preview.dart');

  test('raw JSON blocks are Advanced only', () {
    final int blocks = 'ExtrasEditor('.allMatches(inspector).length;
    final int wrapped = RegExp(r'AdvancedOnly\(\s*child:\s*ExtrasEditor\(')
        .allMatches(inspector)
        .length;
    expect(blocks, 2);
    expect(wrapped, blocks);
  });

  test('show conditions are Advanced only', () {
    final int fields = 'PreviewExprField('.allMatches(inspector).length;
    final int wrapped = RegExp(r'AdvancedOnly\(\s*child:\s*PreviewExprField\(')
        .allMatches(inspector)
        .length;
    expect(fields, 2);
    expect(wrapped, fields);
  });

  test('line tools fold into a menu and lines reorder through the kit', () {
    expect(inspector, contains('ActionRow('));
    for (final String label in <String>[
      'Add text',
      'Add Insight',
      'Add spacer',
    ]) {
      expect(
        RegExp("RowAction\\(\\s*huiText\\('$label'\\)").hasMatch(inspector),
        isTrue,
        reason: label,
      );
    }
    expect(inspector, contains('ReorderList('));
    expect(inspector, contains('PaneHeader('));
    expect(inspector, isNot(contains('hui-inspector-header')));
  });

  test('the game screen shows once with no stage wrapper or error list', () {
    expect('GlossGameScreen('.allMatches(view).length, 1);
    expect(view, contains('actions: <RowAction>['));
    expect(view, isNot(contains('hui-entity-overlay-stage')));
    expect(view, isNot(contains('hui-entity-overlay-sky')));
    expect(view, isNot(contains('McStage(')));
    expect(view, isNot(contains('preview.errors')));
    expect(view, isNot(contains('gameContext')));
  });

  test('the text editor keeps its constructor and drops hand-made buttons', () {
    expect(
      editor,
      contains(
        'const TextIconEditor({\n'
        '    required this.fieldId,\n'
        '    required this.text,\n'
        '    required this.onChanged,\n'
        '    this.emoji = const GlossNoEmoji(),\n'
        '    this.issues = const <HuiIssue>[],\n'
        '    this.label,\n'
        '    super.key,\n'
        '  });',
      ),
    );
    expect(editor, contains('ActionRow('));
    expect(editor, isNot(contains('dom.button(')));
  });

  test('the text preview is one game-drawn surface', () {
    expect('hui-mc-preview-surface'.allMatches(preview).length, 1);
    expect(preview, contains("'data-game-drawn': ''"));
    expect(preview, isNot(contains('hui-mc-preview-metrics')));
    expect(preview, isNot(contains('hui-mc-preview-warnings')));
  });
}
