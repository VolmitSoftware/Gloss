// Repeated controls say which item they belong to, line inputs have a name
// of their own (not the placeholder), and each MOTD entry remembers its own
// "More" disclosure.
import 'dart:io';

import 'package:test/test.dart';

String read(String path) => File(path).readAsStringSync();

const String inspectors = 'lib/components/inspector';

void main() {
  test('a field inside a named item adds the item to its name', () {
    final String field = read('lib/ui/field.dart');
    expect(field, contains('class FieldOwner extends InheritedComponent'));
    expect(field, contains("huiText('{field} ({item})'"));
    // The label on screen stays short; the owner part is visually hidden.
    expect(field, contains("dom.span(classes: 'ui-sr-only'"));
    expect(field, contains('named.substring(field.label.length)'));
    expect(
      read('lib/ui/switch_field.dart'),
      contains('ariaLabel: FieldOwner.accessibleName(context, label)'),
    );
  });

  test('every repeated item names its fields', () {
    final Map<String, String> owners = <String, String>{
      'scoreboard_advanced_editor.dart': 'Widget _slot(',
      'surface_subscriptions_editor.dart': 'Widget _subscription(',
      'motd_inspector.dart': 'Widget _link(',
      'motd_policy_editor.dart': 'Widget _link(',
      'tablist_layout_editor.dart': 'Widget _cell(',
    };
    for (final MapEntry<String, String> owner in owners.entries) {
      final String source = read('$inspectors/${owner.key}');
      final int start = source.indexOf(owner.value);
      expect(start, isNonNegative, reason: owner.key);
      // The method ends at the first closing brace on its own indentation.
      final int next = source.indexOf('\n  }\n', start);
      final String body = source.substring(
        start,
        next < 0 ? source.length : next,
      );
      expect(body, contains('return FieldOwner('), reason: owner.key);
      expect(body, isNot(contains('return AppSection(')), reason: owner.key);
    }
  });

  test('line inputs are named by their position, not their placeholder', () {
    for (final String file in <String>[
      'animation_inspector.dart',
      'hologram_inspector.dart',
      'scoreboard_inspector.dart',
      'motd_inspector.dart',
    ]) {
      final String source = read('$inspectors/$file');
      final int rows = 'LineRow('.allMatches(source).length;
      expect(rows, greaterThan(0), reason: file);
      final RegExp named = RegExp(r"LineRow\([^;]*?\blabel: huiText\(");
      expect(named.allMatches(source).length, rows, reason: file);
    }
    final String row = read('$inspectors/line_list_section.dart');
    expect(row, contains("'aria-label': ?label"));
  });

  test('each MOTD entry keeps its own More state', () {
    final String motd = read('$inspectors/motd_inspector.dart');
    expect(motd, isNot(contains("memoryKey: 'motd.more'")));
    expect(motd, contains(r"memoryKey: 'motd.entry.$index.more'"));
  });
}
