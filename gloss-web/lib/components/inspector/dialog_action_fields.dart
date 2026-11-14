/// The fields of a `dialog` action: a native Minecraft dialog with its body,
/// inputs and buttons.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/jaspr.dart' show Component;

import '../../l10n/hui_localizations.dart';
import '../../model/hui_actions.dart';
import '../../ui/density.dart';
import '../../ui/field.dart';
import '../../ui/field_group.dart';
import '../../ui/icon_button.dart';
import '../../ui/number_field.dart';
import '../../ui/reorder_list.dart';
import '../../ui/switch_field.dart';
import 'extras_editor.dart';

class DialogActionFields extends StatelessWidget {
  const DialogActionFields({
    required this.action,
    required this.path,
    required this.onChanged,
    super.key,
  });

  final HuiRuntimeAction action;

  /// The action's path in its list, the stem of the field ids.
  final String path;
  final void Function(HuiRuntimeAction action) onChanged;

  String _id(String key) => 'hui-action-$path/$key';

  void _set(String key, Object value) {
    final HuiRuntimeAction next = action.copy();
    next.extras[key] = value;
    onChanged(next);
  }

  List<Map<String, Object?>> _entries(String key) => <Map<String, Object?>>[
    if (action.extras[key] case final List<Object?> entries)
      for (final Object? entry in entries)
        if (entry is Map<String, Object?>) Map<String, Object?>.from(entry),
  ];

  double _integer(String key, int fallback) {
    final Object? value = action.extras[key];
    return value is num ? value.toDouble() : fallback.toDouble();
  }

  Widget _whole(String key, String label, int fallback, double min) => Field(
    label: label,
    forId: _id(key),
    child: NumberField(
      id: _id(key),
      value: _integer(key, fallback),
      min: min,
      step: 1,
      decimals: 0,
      integer: true,
      onChanged: (double value) => _set(key, value.round()),
    ),
  );

  /// One list of the dialog (body lines, inputs, buttons): each entry is a
  /// key and value editor, in an order the arrows change.
  Widget _list(
    String label,
    String key,
    Map<String, Object?> Function(int index) create,
  ) {
    final List<Map<String, Object?>> entries = _entries(key);
    return FieldGroup(
      title: label,
      count: entries.length,
      actions: <Widget>[
        AppIconButton(
          icon: ArcaneIcon.plus(),
          label: huiText('Add {item}', <String, Object?>{
            'item': label.toLowerCase(),
          }),
          onPressed: () =>
              _set(key, <Object?>[...entries, create(entries.length)]),
        ),
      ],
      children: <Widget>[
        if (entries.isNotEmpty)
          ReorderList(
            label: label,
            itemCount: entries.length,
            onReorder: (int from, int to) {
              final Map<String, Object?> moved = entries.removeAt(from);
              entries.insert(to, moved);
              _set(key, entries);
            },
            onRemove: (int index) {
              entries.removeAt(index);
              _set(key, entries);
            },
            itemBuilder: (int index) => ExtrasEditor(
              title: '$label ${index + 1}',
              extensionKeys: false,
              extras: entries[index],
              onChanged: (String _, Map<String, Object?> values) {
                entries[index] = values;
                _set(key, entries);
              },
            ),
          ),
      ],
    );
  }

  @override
  Widget build(BuildContext context) {
    final ComponentSize size = controlSize(DensityScope.of(context));
    final String kindId = _id('kind');
    return Component.fragment(<Widget>[
      Field(
        label: huiText('Dialog title'),
        forId: _id('title'),
        child: TextInput(
          id: _id('title'),
          value: '${action.extras['title'] ?? ''}',
          size: size,
          fullWidth: true,
          onChanged: (String value) => _set('title', value),
        ),
      ),
      Field(
        label: huiText('Dialog kind'),
        forId: '$kindId-trigger',
        tip: huiText(
          'Native Minecraft dialog for Java 1.21.6 or newer. The unsupported action list handles older clients. The browser does not simulate the game screen.',
        ),
        child: ArcaneSelect(
          id: kindId,
          value: '${action.extras['kind'] ?? 'notice'}',
          size: size,
          fullWidth: true,
          options: <ArcaneSelectOption>[
            for (final String kind in <String>[
              'notice',
              'confirmation',
              'multi_action',
            ])
              ArcaneSelectOption(value: kind, label: kind),
          ],
          onChanged: (String value) => _set('kind', value),
        ),
      ),
      _whole('timeoutTicks', huiText('Dialog timeout ticks'), 1200, 0),
      if (action.extras['kind'] == 'multi_action')
        _whole('columns', huiText('Dialog columns'), 2, 1),
      SwitchField(
        id: _id('escape'),
        label: huiText('Close dialog with Escape'),
        value: action.extras['escape'] != false,
        onChanged: (bool value) => _set('escape', value),
      ),
      _list(
        huiText('Body'),
        'body',
        (int _) => <String, Object?>{'text': '', 'width': 200},
      ),
      _list(
        huiText('Inputs'),
        'inputs',
        (int index) => <String, Object?>{
          'key': 'input${index + 1}',
          'type': 'text',
          'label': 'Input',
          'maxLength': 32,
        },
      ),
      _list(
        huiText('Buttons'),
        'buttons',
        (int _) => <String, Object?>{
          'label': 'Continue',
          'actions': <Object?>[],
        },
      ),
    ]);
  }
}
