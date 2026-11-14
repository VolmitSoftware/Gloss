// Kit pieces that keep rows uniform: affixes beside an input instead of
// inside it, one selectable list row, section headings on the same edge as
// group headings, and field rows on the library's Field family.
import 'dart:io';

import 'package:test/test.dart';

String read(String path) => File(path).readAsStringSync();

void main() {
  test('number fields draw units and axis letters beside the input', () {
    final String number = read('lib/ui/number_field.dart');
    final String input = number.substring(
      number.indexOf('TextInput('),
      number.indexOf('return Affixed('),
    );
    // The library puts affixes in a bordered box around a 2 px shorter
    // input; beside it, the input keeps the row's height.
    expect(input, isNot(contains('prefix:')));
    expect(input, isNot(contains('suffix:')));
    expect(number, contains('prefix: field.prefix'));
    expect(number, contains('suffix: field.suffix'));
    final String affixed = read('lib/ui/affixed.dart');
    expect(affixed, contains("'aria-hidden': 'true'"));
    expect(affixed, contains("classes: 'ui-affixed-input'"));
  });

  test('no inspector passes an affix to a raw text input', () {
    final RegExp affix = RegExp(r'(prefix|suffix): Text\(');
    for (final FileSystemEntity entity in Directory(
      'lib/components',
    ).listSync(recursive: true)) {
      if (entity is! File || !entity.path.endsWith('.dart')) continue;
      expect(
        affix.hasMatch(entity.readAsStringSync()),
        isFalse,
        reason: '${entity.path}: use Affixed around the input',
      );
    }
  });

  test('list rows are the kit row, selected by aria-current', () {
    final String row = read('lib/ui/list_row.dart');
    expect(row, contains("if (selected) 'aria-current': 'true'"));
    expect(row, contains('buttonSize(DensityScope.of(context))'));
    expect(row, contains('ButtonVariant.ghost'));
    for (final String path in <String>[
      'lib/components/panels/components_rail.dart',
      'lib/components/inspector/entity_overlay_inspector.dart',
    ]) {
      final String source = read(path);
      expect(source, contains('ListRow('), reason: path);
      expect(source, isNot(contains("'aria-pressed'")), reason: path);
    }
    final String kit = read('web/styles/00-kit.css');
    expect(kit, contains('.ui-list-row.is-selected {'));
    expect(read('web/styles/10-gloss.css'), isNot(contains('line-select')));
  });

  test('section headings start on the edge of their fields', () {
    // The library's minimal disclosure summary has no inline padding
    // (arcane_jaspr 3ecb5d6), so the heading takes no offset of its own.
    expect(
      read('lib/ui/section.dart'),
      contains('variant: DisclosureVariant.minimal'),
    );
    final String kit = read('web/styles/00-kit.css');
    expect(kit, isNot(contains('is-summary')));
    expect(kit, isNot(contains('margin-inline-start: -1rem')));
  });

  test(
    'field rows are the library Field family, with no field CSS of their own',
    () {
      final String field = read('lib/ui/field.dart');
      expect(field, contains('ArcaneFormField('));
      expect(field, contains('orientation: FieldOrientation.responsive'));
      expect(field, contains('ArcaneFieldLabel.child('));
      expect(field, contains('ArcaneFieldError.list(errors)'));
      final String toggle = read('lib/ui/switch_field.dart');
      expect(toggle, contains('orientation: FieldOrientation.horizontal'));
      expect(read('lib/ui/field_group.dart'), contains('ArcaneFieldGroup('));
      expect(read('lib/ui/section.dart'), contains('onOpenChange: _set'));
      final String kit = read('web/styles/00-kit.css');
      for (final String gone in <String>[
        '.ui-form-box',
        '.ui-field {',
        '.ui-field,',
        '.ui-field-control',
        '.ui-switch-field',
        '.ui-switch-line',
        '.ui-field-label',
        '.ui-field-name',
        '.ui-field-hint',
        '.ui-field-problem {',
        '.ui-field-problem.is-error',
        '@container (min-width: 36rem)',
      ]) {
        expect(kit, isNot(contains(gone)), reason: gone);
      }
      // Screens draw help and problem lines with the library or issueNote.
      for (final FileSystemEntity entity in Directory(
        'lib',
      ).listSync(recursive: true)) {
        if (entity is! File || !entity.path.endsWith('.dart')) continue;
        final String source = entity.readAsStringSync();
        for (final String gone in <String>[
          "'ui-field-hint'",
          "'ui-form-box",
          "'ui-field-control'",
          'ui-field-problem is-',
        ]) {
          expect(
            source,
            isNot(contains(gone)),
            reason: '${entity.path}: $gone',
          );
        }
      }
    },
  );
}
