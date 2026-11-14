/// The box drawn around a display's text: on or off, then its padding,
/// border width and two colours. Problems show at their field; one at a
/// path no shown field owns is listed at the end.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';

import '../../l10n/hui_localizations.dart';
import '../../logic/validation.dart';
import '../../model/gloss_hologram_box.dart';
import '../../ui/color_field.dart';
import '../../ui/field.dart';
import '../../ui/number_field.dart';
import '../../ui/section.dart';
import '../../ui/switch_field.dart';

int _nextBoxId = 0;

class HologramBoxEditor extends StatefulWidget {
  const HologramBoxEditor({
    required this.box,
    required this.mutate,
    this.issues = const <HuiIssue>[],
    this.sectionKey,
    super.key,
  });
  final GlossHologramBox box;
  final void Function(String, void Function(GlossHologramBox)) mutate;
  final List<HuiIssue> issues;
  final String? sectionKey;

  @override
  State<HologramBoxEditor> createState() => _HologramBoxEditorState();
}

class _HologramBoxEditorState extends State<HologramBoxEditor> {
  final String _id = 'box-${_nextBoxId++}';

  /// The last segment of an issue path: the box key it is about.
  static String _key(HuiIssue issue) =>
      issue.path.substring(issue.path.lastIndexOf('.') + 1);

  List<HuiIssue> _at(String key) =>
      component.issues.where((HuiIssue issue) => _key(issue) == key).toList();

  @override
  Widget build(BuildContext context) {
    final GlossHologramBox box = component.box;
    final Set<String> shown = box.enabled
        ? const <String>{
            'enabled',
            'padding',
            'borderWidth',
            'backgroundArgb',
            'borderArgb',
          }
        : const <String>{'enabled'};
    final List<HuiIssue> rest = component.issues
        .where((HuiIssue issue) => !shown.contains(_key(issue)))
        .toList();
    return AppSection(
      title: huiText('Box decoration'),
      memoryKey: component.sectionKey,
      children: <Widget>[
        SwitchField(
          id: '$_id-on',
          label: huiText('Show box'),
          value: box.enabled,
          problems: _at('enabled'),
          onChanged: (bool value) => component.mutate(
            'show box',
            (GlossHologramBox edited) => edited.enabled = value,
          ),
        ),
        if (box.enabled) ...<Widget>[
          Field(
            label: huiText('Padding'),
            forId: '$_id-padding',
            tip: huiText('Space around the text in font pixels. 0..64.'),
            problems: _at('padding'),
            child: NumberField(
              id: '$_id-padding',
              value: box.padding.toDouble(),
              min: 0,
              max: 64,
              step: 1,
              decimals: 0,
              onChanged: (double value) => component.mutate(
                'box padding',
                (GlossHologramBox edited) => edited.padding = value.round(),
              ),
            ),
          ),
          Field(
            label: huiText('Border width'),
            forId: '$_id-border-width',
            tip: huiText('Uniform border width in font pixels. 0..16.'),
            problems: _at('borderWidth'),
            child: NumberField(
              id: '$_id-border-width',
              value: box.borderWidth.toDouble(),
              min: 0,
              max: 16,
              step: 1,
              decimals: 0,
              onChanged: (double value) => component.mutate(
                'box border width',
                (GlossHologramBox edited) => edited.borderWidth = value.round(),
              ),
            ),
          ),
          Field(
            label: huiText('Background'),
            forId: '$_id-background',
            problems: _at('backgroundArgb'),
            child: ColorField(
              id: '$_id-background',
              value: box.backgroundArgb,
              label: huiText('box background'),
              onChanged: (String value) => component.mutate(
                'box background',
                (GlossHologramBox edited) => edited.backgroundArgb = value,
              ),
            ),
          ),
          Field(
            label: huiText('Border color'),
            forId: '$_id-border',
            problems: _at('borderArgb'),
            child: ColorField(
              id: '$_id-border',
              value: box.borderArgb,
              label: huiText('box border'),
              onChanged: (String value) => component.mutate(
                'box border',
                (GlossHologramBox edited) => edited.borderArgb = value,
              ),
            ),
          ),
        ],
        for (final HuiIssue issue in distinctIssues(rest))
          issueNote(issue),
      ],
    );
  }
}
