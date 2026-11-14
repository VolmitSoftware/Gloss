import 'dart:io';

import 'package:arcane_jaspr/arcane_jaspr.dart' show TextAlign;
import 'package:gloss_editor/ui/technical_input.dart';
import 'package:test/test.dart';

void main() {
  test('technical inputs are left-to-right bidi islands', () {
    expect(technicalInputAttributes['dir'], 'ltr');
    expect(technicalInputAttributes['autocomplete'], 'off');
    expect(technicalInputAttributes['spellcheck'], 'false');
    expect(technicalInputStyles.textAlign, TextAlign.left);
    final String shellStyles = File(
      'web/styles/02-shell.css',
    ).readAsStringSync();
    expect(shellStyles, contains('input[dir="ltr"]'));
    expect(shellStyles, contains('textarea[dir="ltr"]'));
    expect(shellStyles, contains('unicode-bidi: isolate'));
  });

  test('technical identifiers and player names use the shared bidi island', () {
    const Map<String, int> expectedUses = <String, int>{
      'lib/components/dialogs/export_dialog.dart': 1,
      'lib/components/dialogs/menu_handoff_dialog.dart': 1,
      'lib/components/dialogs/image_manager_dialog.dart': 2,
      'lib/components/panels/library_dialogs.dart': 1,
      'lib/components/inspector/extras_editor.dart': 1,
      'lib/components/inspector/preview_match_editor.dart': 2,
      'lib/components/inspector/real_drop_inspector.dart': 1,
      'lib/components/inspector/component_inspector.dart': 1,
      'lib/components/inspector/scoreboard_inspector.dart': 2,
      'lib/components/inspector/player_head_picker.dart': 1,
      'lib/components/inspector/tablist_inspector.dart': 1,
      'lib/components/inspector/real_drop_animation_inspector.dart': 1,
      'lib/components/inspector/bubble_inspector.dart': 1,
    };

    for (final MapEntry<String, int> entry in expectedUses.entries) {
      final String source = File(entry.key).readAsStringSync();
      expect(
        RegExp(
          r'styles: technicalInputStyles',
        ).allMatches(source).length,
        greaterThanOrEqualTo(entry.value),
        reason: entry.key,
      );
      expect(
        RegExp(r'technicalInputAttributes').allMatches(source).length,
        greaterThanOrEqualTo(entry.value),
        reason: entry.key,
      );
    }
  });

  test('pasted JSON uses the shared left-to-right styles', () {
    final String source = File(
      'lib/components/dialogs/import_dialog.dart',
    ).readAsStringSync();

    expect(source, contains('styles: technicalInputStyles'));
    expect(source, contains('attributes: technicalInputAttributes'));
  });
}
