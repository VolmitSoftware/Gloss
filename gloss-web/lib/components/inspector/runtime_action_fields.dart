import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/jaspr.dart' show Component;

import '../../l10n/hui_localizations.dart';
import '../../model/behavior_authoring.dart';
import '../../model/hui_actions.dart';
import '../../state/editor_store.dart';
import '../../ui/action_row.dart';
import '../../ui/density.dart';
import '../../ui/field.dart';
import '../../ui/icon_button.dart';
import '../../ui/mode.dart';
import '../../ui/section.dart';
import '../../ui/switch_field.dart';
import 'actions_editor.dart';
import 'inspector_session.dart';

const List<String> runtimeAuthoringTypes = <String>[
  'sequence',
  'parallel',
  'repeat',
  'if',
  'switch',
  'chance',
  'delay',
  'cooldown',
  'setState',
  'addState',
  'clearState',
  'emit',
  'stop',
];

Set<String> runtimeManagedKeys(String type) => <String>{
  if (runtimeAuthoringTypes.contains(type) || type == 'call') ...<String>[
    'when',
    'cooldownTicks',
  ],
  ...switch (type) {
    'sequence' => <String>{'steps', 'onSkip', 'skippable', 'once'},
    'parallel' => <String>{'branches'},
    'repeat' => <String>{'times', 'everyTicks', 'while', 'steps'},
    'if' => <String>{'then', 'else'},
    'switch' => <String>{'on', 'cases', 'default'},
    'chance' => <String>{'percent', 'then', 'else'},
    'delay' => <String>{'ticks'},
    'cooldown' => <String>{'key', 'ticks', 'then', 'else'},
    'setState' || 'addState' => <String>{'key', 'value', 'target'},
    'clearState' => <String>{'key', 'target'},
    'emit' => <String>{'name'},
    'call' => <String>{'action'},
    _ => <String>{},
  },
};

/// The fields of a flow action (sequence, if, repeat, state and the rest)
/// and of a named-action call; nested lists are [ActionsEditor]s of their own.
class RuntimeActionFields extends StatelessWidget {
  const RuntimeActionFields({
    required this.action,
    required this.store,
    required this.session,
    required this.sessionKey,
    required this.onChanged,
    required this.depth,
    required this.clickContext,
    super.key,
  });
  final HuiRuntimeAction action;
  final EditorStore store;
  final InspectorSession session;

  /// The action's path in its list; field ids end with `<sessionKey>/<key>`.
  final String sessionKey;
  final void Function(HuiRuntimeAction next) onChanged;
  final int depth;
  final bool clickContext;

  String _id(String key) => 'hui-action-$sessionKey/$key';

  void _change(String key, Object? value) {
    final HuiRuntimeAction next = action.copy();
    if (value == null) {
      next.extras.remove(key);
    } else {
      next.extras[key] = value;
    }
    onChanged(next);
  }

  Widget _text(ComponentSize size, String key, String label) => Field(
    label: label,
    forId: _id(key),
    child: TextInput(
      id: _id(key),
      value: '${action.extras[key] ?? ''}',
      size: size,
      fullWidth: true,
      onChanged: (String value) => _change(key, value.isEmpty ? null : value),
    ),
  );

  /// An optional number: cleared, the key leaves the action.
  Widget _number(
    ComponentSize size,
    String key,
    String label, {
    bool decimal = false,
  }) => Field(
    label: label,
    forId: _id(key),
    child: TextInput(
      id: _id(key),
      value: '${action.extras[key] ?? ''}',
      size: size,
      fullWidth: true,
      attributes: <String, String>{
        'inputmode': decimal ? 'decimal' : 'numeric',
      },
      onChanged: (String value) {
        final num? parsed = decimal
            ? double.tryParse(value)
            : int.tryParse(value);
        if (parsed?.isFinite == true || value.trim().isEmpty) {
          _change(key, parsed);
        }
      },
    ),
  );

  /// Shown where the stored list is not one these controls can edit.
  Widget _repair(String title) => AppSection(
    title: title,
    description: huiText('Repair actions in Code view to use these controls.'),
    children: const <Widget>[],
  );

  Widget _actions(
    String key,
    Object? raw,
    void Function(List<Map<String, Object?>> next) update, {
    String? label,
    Widget? remove,
  }) {
    final HuiActionSourceList? source = HuiActionSourceList.read(raw);
    if (source == null || depth >= 32) return _repair(label ?? key);
    return ActionsEditor(
      store: store,
      catalogs: store.catalogs,
      session: session,
      sessionKey: '$sessionKey/$key',
      slot: ActionSlot.actions,
      actions: source.actions,
      clickContext: clickContext,
      title: label ?? key,
      depth: depth + 1,
      timedSteps: action.type == 'sequence' && key == 'steps',
      headerActions: <Widget>[?remove],
      onEdit: (String label, void Function(List<HuiAction>) edit) {
        final HuiActionSourceList? current = HuiActionSourceList.read(raw);
        if (current != null) update(current.edit(edit));
      },
    );
  }

  Widget _list(String key) => _actions(
    key,
    action.extras[key],
    (List<Map<String, Object?>> next) => _change(key, next),
  );

  Widget _branches() {
    final Object? raw = action.extras['branches'];
    if (raw != null && raw is! List) return _repair('branches');
    final List<Object?> branches = raw is List
        ? List<Object?>.of(raw)
        : <Object?>[];
    return Component.fragment(<Widget>[
      for (final (int index, Object? branch) in branches.indexed)
        _actions(
          'branches/$index',
          branch,
          (List<Map<String, Object?>> next) {
            final List<Object?> changed = List<Object?>.of(branches);
            changed[index] = next;
            _change('branches', changed);
          },
          label: 'branches[$index]',
          remove: AppIconButton(
            icon: ArcaneIcon.trash2(),
            label: huiText('Remove branch'),
            onPressed: () => _change(
              'branches',
              List<Object?>.of(branches)..removeAt(index),
            ),
          ),
        ),
      ActionRow(
        dense: true,
        secondary: <RowAction>[
          RowAction(
            huiText('Add branch'),
            () => _change('branches', <Object?>[...branches, <Object?>[]]),
            icon: ArcaneIcon.plus(),
          ),
        ],
      ),
    ]);
  }

  Widget _cases(ComponentSize size) {
    final Object? raw = action.extras['cases'];
    if (raw != null && raw is! Map<String, Object?>) return _repair('cases');
    final Map<String, Object?> cases = raw is Map<String, Object?>
        ? raw
        : <String, Object?>{};
    return Component.fragment(<Widget>[
      for (final MapEntry<String, Object?> entry in cases.entries) ...<Widget>[
        Field(
          label: huiText('Case key'),
          forId: _id('cases/${entry.key}/key'),
          child: TextInput(
            id: _id('cases/${entry.key}/key'),
            value: entry.key,
            size: size,
            fullWidth: true,
            onChanged: (String value) {
              if (value == entry.key || cases.containsKey(value)) return;
              _change('cases', <String, Object?>{
                for (final MapEntry<String, Object?> current in cases.entries)
                  current.key == entry.key ? value : current.key: current.value,
              });
            },
          ),
        ),
        _actions(
          'cases/${entry.key}',
          entry.value,
          (List<Map<String, Object?>> next) =>
              _change('cases', <String, Object?>{...cases, entry.key: next}),
          label: entry.key,
          remove: AppIconButton(
            icon: ArcaneIcon.trash2(),
            label: huiText('Remove case'),
            onPressed: () => _change(
              'cases',
              Map<String, Object?>.of(cases)..remove(entry.key),
            ),
          ),
        ),
      ],
      ActionRow(
        dense: true,
        secondary: <RowAction>[
          RowAction(huiText('Add case'), () {
            int index = 1;
            while (cases.containsKey('case$index')) {
              index++;
            }
            _change('cases', <String, Object?>{
              ...cases,
              'case$index': <Object?>[],
            });
          }, icon: ArcaneIcon.plus()),
        ],
      ),
    ]);
  }

  @override
  Widget build(BuildContext context) {
    if (!runtimeAuthoringTypes.contains(action.type) && action.type != 'call') {
      return const Component.fragment(<Widget>[]);
    }
    final ComponentSize size = controlSize(DensityScope.of(context));
    final String targetId = _id('target');
    return Component.fragment(<Widget>[
      _text(size, 'when', huiText('Condition')),
      AdvancedOnly(
        child: _number(size, 'cooldownTicks', huiText('Cooldown ticks')),
      ),
      if (action.type == 'call') _text(size, 'action', huiText('Named action')),
      if (<String>{
        'setState',
        'addState',
        'clearState',
        'cooldown',
      }.contains(action.type))
        _text(size, 'key', huiText('State key')),
      if (<String>{'setState', 'addState'}.contains(action.type))
        _text(size, 'value', huiText('Value')),
      if (<String>{'setState', 'addState', 'clearState'}.contains(action.type))
        Field(
          label: huiText('Target'),
          forId: '$targetId-trigger',
          child: ArcaneSelect(
            id: targetId,
            value: '${action.extras['target'] ?? 'viewer'}',
            size: size,
            fullWidth: true,
            options: <ArcaneSelectOption>[
              for (final String role in <String>['viewer', 'subject', 'source'])
                ArcaneSelectOption(value: role, label: role),
            ],
            onChanged: (String value) => _change('target', value),
          ),
        ),
      if (action.type == 'emit') _text(size, 'name', huiText('Name')),
      if (action.type == 'delay' || action.type == 'cooldown')
        _number(size, 'ticks', huiText('Ticks')),
      if (action.type == 'repeat') ...<Widget>[
        _number(size, 'times', huiText('Count')),
        _number(size, 'everyTicks', huiText('Interval')),
        _text(size, 'while', huiText('Condition')),
        _list('steps'),
      ],
      if (action.type == 'sequence') ...<Widget>[
        SwitchField(
          id: _id('skippable'),
          label: huiText('Skippable'),
          value: action.extras['skippable'] == true,
          onChanged: (bool value) => _change('skippable', value),
        ),
        _text(size, 'once', huiText('State key')),
        _list('steps'),
        _list('onSkip'),
      ],
      if (action.type == 'parallel') _branches(),
      if (action.type == 'chance')
        _number(size, 'percent', huiText('Percent'), decimal: true),
      if (<String>{
        'if',
        'chance',
        'cooldown',
      }.contains(action.type)) ...<Widget>[_list('then'), _list('else')],
      if (action.type == 'switch') ...<Widget>[
        _text(size, 'on', huiText('Expression')),
        _cases(size),
        _list('default'),
      ],
    ]);
  }
}
