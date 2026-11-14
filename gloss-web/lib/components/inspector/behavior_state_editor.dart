/// The state declarations of a behavior: one group per key with its scope,
/// type and optional default. Removing a state acts at once; Undo restores it.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';

import '../../l10n/hui_localizations.dart';
import '../../logic/validation.dart' show HuiIssue;
import '../../model/behavior_authoring.dart';
import '../../model/gloss_behavior.dart';
import '../../model/gloss_doc.dart';
import '../../state/editor_store.dart';
import '../../ui/density.dart';
import '../../ui/field.dart';
import '../../ui/icon_button.dart';
import '../../ui/section.dart';
import '../../ui/switch_field.dart';
import '../../ui/technical_input.dart';

class BehaviorStateEditor extends StatelessWidget {
  const BehaviorStateEditor({required this.store, super.key});
  final EditorStore store;

  ComponentSize get _size => controlSize(Density.dense);

  void _change(String key, void Function(BehaviorStateDeclaration) edit) =>
      store.mutateGloss('state declaration', (GlossDoc raw) {
        final GlossBehaviorDoc doc = raw as GlossBehaviorDoc;
        final BehaviorStateDeclaration? state = BehaviorStateDeclaration.read(
          doc.state[key],
        );
        if (state == null) return;
        edit(state);
        doc.state[key] = state.toJson();
      });

  List<HuiIssue> _issues(String path) => <HuiIssue>[
    for (final HuiIssue issue in store.issues)
      if (issue.path == path) issue,
  ];

  /// Every issue under the state's key, so a nested one is not lost.
  List<HuiIssue> _stateIssues(String key) => <HuiIssue>[
    for (final HuiIssue issue in store.issues)
      if (issue.path.startsWith('\$.state.$key') &&
          issue.path != '\$.state.$key.default')
        issue,
  ];

  @override
  Widget build(BuildContext context) {
    final GlossBehaviorDoc doc = store.glossDoc! as GlossBehaviorDoc;
    return AppSection(
      title: huiText('State declarations'),
      memoryKey: 'behavior.state',
      count: doc.state.length,
      actions: <Widget>[
        AppIconButton(
          icon: ArcaneIcon.plus(),
          label: huiText('Add state'),
          onPressed: () => store.mutateGloss('add state', (GlossDoc raw) {
            final GlossBehaviorDoc next = raw as GlossBehaviorDoc;
            int index = 1;
            while (next.state.containsKey('state$index')) {
              index++;
            }
            next.state['state$index'] = BehaviorStateDeclaration(
              scope: 'player',
              type: 'number',
            ).toJson();
          }),
        ),
      ],
      children: <Widget>[
        for (final MapEntry<String, Object?> entry in doc.state.entries)
          _state(entry.key, BehaviorStateDeclaration.read(entry.value)),
      ],
    );
  }

  Widget _state(String key, BehaviorStateDeclaration? state) {
    final String id = 'behavior-state-$key';
    return AppSection(
      title: key,
      memoryKey: 'behavior.state.$key',
      attributes: <String, String>{'data-behavior-state': key},
      description: state == null
          ? huiText(
              'Repair state declarations in Code view to use these controls.',
            )
          : null,
      actions: <Widget>[
        AppIconButton(
          icon: ArcaneIcon.trash(),
          label: huiText('Remove state'),
          onPressed: () => store.mutateGloss(
            'remove state',
            (GlossDoc raw) => (raw as GlossBehaviorDoc).state.remove(key),
          ),
        ),
      ],
      children: <Widget>[
        Field(
          label: huiText('State key'),
          forId: '$id-key',
          problems: _stateIssues(key),
          child: TextInput(
            id: '$id-key',
            value: key,
            size: _size,
            fullWidth: true,
            attributes: technicalInputAttributes,
            onChanged: (String value) =>
                store.mutateGloss('rename state', (GlossDoc raw) {
                  final GlossBehaviorDoc next = raw as GlossBehaviorDoc;
                  if (value == key || next.state.containsKey(value)) return;
                  next.state = <String, Object?>{
                    for (final MapEntry<String, Object?> entry
                        in next.state.entries)
                      entry.key == key ? value : entry.key: entry.value,
                  };
                }),
          ),
        ),
        if (state != null) ...<Widget>[
          _select(
            huiText('Scope'),
            '$id-scope',
            state.scope,
            <String>{'player', 'world', 'global', state.scope},
            (String value) => _change(
              key,
              (BehaviorStateDeclaration next) => next.scope = value,
            ),
          ),
          _select(
            huiText('Type'),
            '$id-type',
            state.type,
            <String>{'number', 'string', 'boolean', state.type},
            (String value) => _change(
              key,
              (BehaviorStateDeclaration next) => next.type = value,
            ),
          ),
          SwitchField(
            id: '$id-override',
            label: huiText('Override'),
            value: state.hasDefault,
            onChanged: (bool value) =>
                _change(key, (BehaviorStateDeclaration next) {
                  next.hasDefault = value;
                  if (value && next.defaultValue == null) {
                    next.defaultValue = next.resolvedDefault;
                  }
                }),
          ),
          if (state.hasDefault && state.type == 'boolean')
            SwitchField(
              id: '$id-default',
              label: huiText('Value'),
              value: state.resolvedDefault == true,
              problems: _issues('\$.state.$key.default'),
              onChanged: (bool value) => _change(
                key,
                (BehaviorStateDeclaration next) => next.defaultValue = value,
              ),
            )
          else if (state.hasDefault)
            Field(
              label: huiText('Value'),
              forId: '$id-default',
              problems: _issues('\$.state.$key.default'),
              child: TextInput(
                id: '$id-default',
                value: '${state.defaultValue ?? ''}',
                size: _size,
                fullWidth: true,
                attributes: technicalInputAttributes,
                onChanged: (String value) {
                  final Object? parsed = state.type == 'number'
                      ? double.tryParse(value)
                      : value;
                  if (parsed != null && (parsed is! num || parsed.isFinite)) {
                    _change(
                      key,
                      (BehaviorStateDeclaration next) =>
                          next.defaultValue = parsed,
                    );
                  }
                },
              ),
            ),
        ],
      ],
    );
  }

  Widget _select(
    String label,
    String id,
    String value,
    Set<String> options,
    void Function(String) changed,
  ) => Field(
    label: label,
    forId: id,
    child: ArcaneSelect(
      id: id,
      value: value,
      size: _size,
      fullWidth: true,
      options: <ArcaneSelectOption>[
        for (final String option in options)
          ArcaneSelectOption(value: option, label: option),
      ],
      onChanged: changed,
    ),
  );
}
