/// The chat and world kinds (connections, channel, emoji, bubble styles,
/// damage indicators, entity overlays, real drops) are built on the kit:
/// none of their inspectors or views reaches for the old shared widgets,
/// and their expert-only inputs sit behind Advanced.
library;

import 'dart:io';

import 'package:test/test.dart';

const List<String> _files = <String>[
  'lib/components/connections/connections_view.dart',
  'lib/components/channel/channel_view.dart',
  'lib/components/emoji/emoji_view.dart',
  'lib/components/bubble/bubble_view.dart',
  'lib/components/damage_indicators/damage_indicator_view.dart',
  'lib/components/entity_overlays/entity_overlay_view.dart',
  'lib/components/real_drops/real_drops_view.dart',
  'lib/components/inspector/connections_inspector.dart',
  'lib/components/inspector/channel_inspector.dart',
  'lib/components/inspector/channel_fields_editor.dart',
  'lib/components/inspector/emoji_inspector.dart',
  'lib/components/inspector/bubble_inspector.dart',
  'lib/components/inspector/damage_indicator_inspector.dart',
  'lib/components/inspector/entity_overlay_inspector.dart',
  'lib/components/inspector/real_drop_inspector.dart',
  'lib/components/inspector/real_drop_animation_inspector.dart',
  'lib/components/inspector/real_drop_expr_field.dart',
  'lib/components/inspector/real_drop_label_names_editor.dart',
  'lib/components/inspector/text_icon_editor.dart',
  'lib/components/inspector/mc_text_preview.dart',
];

/// Widgets the kit replaced; delete each one once nothing uses it.
const List<String> _oldWidgets = <String>[
  'InspectorSection(',
  'HuiMore(',
  'HuiNote(',
  'HuiDetailRow(',
  'HuiRevisionRow(',
  'HuiInlineIssues(',
  'HuiArmedButton(',
  'HuiSegmented(',
  'HuiSwitchRow(',
  'HuiSliderField(',
  'HuiIconButton(',
  'HuiSkeleton',
  'HuiEmptyState(',
  'HuiHelpCluster(',
  'HuiRowTools(',
  'HuiFieldHelp(',
  'HuiHelpPopover(',
  'HuiReorderList(',
  'HuiField(',
  'HuiNumberField(',
  'HuiColorField(',
  'HuiDurationField(',
  'HuiVec3Field(',
  'HuiPanel(',
  'HuiEyebrow(',
  'HuiActionMenu(',
  'RegistryPicker(',
  'GlossPreviewZoom(',
  'dom.button(',
];

void main() {
  test('chat and world kinds use no replaced widget', () {
    for (final String path in _files) {
      final String source = File(path).readAsStringSync();
      for (final String widget in _oldWidgets) {
        expect(source, isNot(contains(widget)), reason: '$path uses $widget');
      }
      expect(
        source,
        isNot(contains("'inspector_widgets.dart'")),
        reason: '$path imports the old inspector widgets',
      );
      expect(
        source,
        isNot(contains("'field_help.dart'")),
        reason: '$path imports the old field help',
      );
      expect(
        source,
        isNot(contains("'../common/")),
        reason: '$path imports the old common widgets',
      );
    }
  });

  test('buttons take their size from the row', () {
    for (final String path in _files) {
      final String source = File(path).readAsStringSync();
      expect(source, isNot(contains('ButtonSize.')), reason: path);
    }
  });

  test('show conditions are Advanced only on chat kinds', () {
    for (final String path in <String>[
      'lib/components/inspector/connections_inspector.dart',
      'lib/components/inspector/channel_inspector.dart',
      'lib/components/inspector/emoji_inspector.dart',
    ]) {
      final String source = File(path).readAsStringSync();
      final int editors = 'GlossVisibilityEditor('.allMatches(source).length;
      final int wrapped = RegExp(
        r'AdvancedOnly\(\s*child:\s*GlossVisibilityEditor\(',
      ).allMatches(source).length;
      expect(editors, greaterThan(0), reason: path);
      expect(wrapped, editors, reason: path);
    }
  });

  test('channel filters and their policy are Advanced only', () {
    final String source = File(
      'lib/components/inspector/channel_fields_editor.dart',
    ).readAsStringSync();
    expect(source, contains('AdvancedOnly(child: _policy())'));
    expect(source, contains('AdvancedOnly(child: _filters(filters))'));
  });

  test('a channel variant nests no section inside its blocks', () {
    final String source = File(
      'lib/components/inspector/channel_fields_editor.dart',
    ).readAsStringSync();
    // Inside a variant ([inherited]) a block is an override switch and its
    // fields; only the document level draws a section per block.
    expect('AppSection('.allMatches(source).length, 1);
    expect(source, contains('if (!inherited) {\n      return AppSection('));
  });
}
