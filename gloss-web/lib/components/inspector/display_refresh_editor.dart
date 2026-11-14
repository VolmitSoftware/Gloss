/// How often a display refreshes its content, visibility and motion, in
/// ticks. Tick maths is Advanced only: Simple mode draws nothing and the
/// keys stay as they are.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';

import '../../l10n/hui_localizations.dart';
import '../../model/gloss_display_refresh.dart';
import '../../ui/field.dart';
import '../../ui/mode.dart';
import '../../ui/number_field.dart';
import '../../ui/section.dart';
import '../../ui/switch_field.dart';

int _nextRefreshId = 0;

class DisplayRefreshEditor extends StatefulWidget {
  const DisplayRefreshEditor({
    required this.refresh,
    required this.onChanged,
    this.sectionKey,
    super.key,
  });
  final GlossDisplayRefresh refresh;
  final void Function(String, GlossDisplayRefresh) onChanged;

  /// Keeps the section's open state across documents.
  final String? sectionKey;

  @override
  State<DisplayRefreshEditor> createState() => _DisplayRefreshEditorState();
}

class _DisplayRefreshEditorState extends State<DisplayRefreshEditor> {
  final String _id = 'refresh-${_nextRefreshId++}';

  void _edit(String label, void Function(GlossDisplayRefresh) edit) {
    final GlossDisplayRefresh next = component.refresh.copy();
    edit(next);
    component.onChanged(label, next);
  }

  List<Widget> _interval(
    String label,
    int? value,
    void Function(GlossDisplayRefresh, int?) edit,
  ) {
    final String id = '$_id-${label.toLowerCase()}';
    return <Widget>[
      SwitchField(
        id: '$id-on',
        label: huiText('Override {label} interval', <String, Object?>{
          'label': huiText(label),
        }),
        value: value != null,
        onChanged: (bool enabled) => _edit(
          'refresh $label',
          (GlossDisplayRefresh next) => edit(next, enabled ? 2 : null),
        ),
      ),
      if (value != null)
        Field(
          label: huiText('{label} ticks', <String, Object?>{
            'label': huiText(label),
          }),
          forId: id,
          child: NumberField(
            id: id,
            value: value.toDouble(),
            integer: true,
            step: 1,
            decimals: 0,
            onChanged: (double value) => _edit(
              'refresh $label',
              (GlossDisplayRefresh next) => edit(next, value.toInt()),
            ),
          ),
        ),
    ];
  }

  @override
  Widget build(BuildContext context) => AdvancedOnly(
    child: AppSection(
      title: huiText('Display refresh'),
      memoryKey: component.sectionKey,
      tip: huiText(
        'Omitted intervals use the existing surface cadence. Animation and particle clocks remain independent.',
      ),
      children: <Widget>[
        ..._interval(
          'Content',
          component.refresh.contentTicks,
          (GlossDisplayRefresh next, int? value) => next.contentTicks = value,
        ),
        ..._interval(
          'Visibility',
          component.refresh.visibilityTicks,
          (GlossDisplayRefresh next, int? value) =>
              next.visibilityTicks = value,
        ),
        ..._interval(
          'Motion',
          component.refresh.motionTicks,
          (GlossDisplayRefresh next, int? value) => next.motionTicks = value,
        ),
      ],
    ),
  );
}
