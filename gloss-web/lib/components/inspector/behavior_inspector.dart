/// The behavior form: switches, chat matching limits (Advanced), one group
/// per trigger with its actions, and the state declarations. A catalog kind,
/// so it edits in the centre (see `catalog_form.dart`); removing a trigger
/// acts at once and Undo brings it back.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import '../../l10n/hui_localizations.dart';
import '../../logic/behavior_validation.dart';
import '../../logic/validation.dart' show HuiIssue;
import '../../model/behavior_authoring.dart';
import '../../model/gloss_behavior.dart';
import '../../model/gloss_doc.dart';
import '../../model/hui_actions.dart';
import '../../state/editor_store.dart';
import '../../ui/density.dart';
import '../../ui/field.dart';
import '../../ui/icon_button.dart';
import '../../ui/mode.dart';
import '../../ui/number_field.dart';
import '../../ui/section.dart';
import '../../ui/switch_field.dart';
import '../../ui/technical_input.dart';
import 'actions_editor.dart';
import 'behavior_state_editor.dart';
import 'catalog_form.dart';
import 'extras_editor.dart';
import 'inspector_session.dart';

/// Keys every trigger carries, edited by their own fields.
const Set<String> _entryKeys = <String>{
  'trigger',
  'pattern',
  'when',
  'permission',
  'do',
};

class BehaviorView extends StatefulWidget {
  const BehaviorView({required this.store, super.key});
  final EditorStore store;

  @override
  State<BehaviorView> createState() => _BehaviorViewState();
}

class _BehaviorViewState extends State<BehaviorView> {
  EditorStore get store => component.store;

  /// The actions editors' open rows, kept across rebuilds.
  final InspectorSession session = InspectorSession();

  ComponentSize get _size => controlSize(Density.dense);

  void _edit(String label, void Function(GlossBehaviorDoc doc) update) => store
      .mutateGloss(label, (GlossDoc doc) => update(doc as GlossBehaviorDoc));

  List<HuiIssue> _issues(String path) => <HuiIssue>[
    for (final HuiIssue issue in store.issues)
      if (issue.path == path) issue,
  ];

  Widget _text(
    String label,
    String id,
    String value,
    String path,
    void Function(GlossBehaviorDoc doc, String value) update,
  ) => Field(
    label: label,
    forId: id,
    problems: _issues(path),
    child: TextInput(
      id: id,
      value: value,
      size: _size,
      fullWidth: true,
      attributes: technicalInputAttributes,
      onChanged: (String next) =>
          _edit(label, (GlossBehaviorDoc doc) => update(doc, next)),
    ),
  );

  /// One matching limit, [name] its JSON key. Expert tuning, shown in
  /// Advanced only; the labels match the chat channel's filter limits.
  Widget _limit(
    String name,
    String label,
    int value,
    void Function(GlossBehaviorMatching matching, int value) update,
  ) => Field(
    label: label,
    forId: 'behavior-matching-$name',
    problems: _issues('\$.matching.$name'),
    child: NumberField(
      id: 'behavior-matching-$name',
      value: value.toDouble(),
      integer: true,
      step: 1,
      decimals: 0,
      onChanged: (double next) => _edit(
        name,
        (GlossBehaviorDoc doc) => update(doc.matching, next.toInt()),
      ),
    ),
  );

  @override
  Widget build(BuildContext context) =>
      CatalogFormHost(store: store, builder: _form);

  Widget _form(BuildContext context) {
    final GlossDoc? active = store.glossDoc;
    if (active is! GlossBehaviorDoc) return const dom.div(<Widget>[]);
    final GlossBehaviorDoc doc = active;
    return CatalogForm(
      kind: huiText('Behaviors'),
      title: store.menuId,
      revision: doc.revision,
      kindClass: 'is-behavior',
      tip: huiText(
        'Events run on the server. The browser validates matching rules without executing actions.',
      ),
      children: <Widget>[
        AppSection(
          title: huiText('Behavior'),
          memoryKey: 'behavior.main',
          children: <Widget>[
            SwitchField(
              id: 'behavior-enabled',
              label: huiText('Enabled'),
              value: doc.enabled,
              onChanged: (bool value) => _edit(
                'enabled',
                (GlossBehaviorDoc next) => next.enabled = value,
              ),
            ),
            SwitchField(
              id: 'behavior-server-commands',
              label: huiText('Allow server commands'),
              value: doc.allowServerCommands,
              onChanged: (bool value) => _edit(
                'server commands',
                (GlossBehaviorDoc next) => next.allowServerCommands = value,
              ),
            ),
          ],
        ),
        AdvancedOnly(
          child: AppSection(
            title: huiText('Chat matching'),
            memoryKey: 'behavior.matching',
            tip: huiText(
              'RE2 patterns support bounded matching. Lookaround and backreferences are unsupported. Exhausted matching budgets skip behaviors and retain ordinary chat.',
            ),
            children: <Widget>[
              _limit(
                'maxInputCharacters',
                huiText('Maximum input characters'),
                doc.matching.maxInputCharacters,
                (GlossBehaviorMatching limits, int value) =>
                    limits.maxInputCharacters = value,
              ),
              _limit(
                'maxPatternCharacters',
                huiText('Maximum pattern characters'),
                doc.matching.maxPatternCharacters,
                (GlossBehaviorMatching limits, int value) =>
                    limits.maxPatternCharacters = value,
              ),
              _limit(
                'maxProgramSize',
                huiText('Maximum expression program size'),
                doc.matching.maxProgramSize,
                (GlossBehaviorMatching limits, int value) =>
                    limits.maxProgramSize = value,
              ),
              _limit(
                'maxNestingDepth',
                huiText('Maximum expression nesting'),
                doc.matching.maxNestingDepth,
                (GlossBehaviorMatching limits, int value) =>
                    limits.maxNestingDepth = value,
              ),
              _limit(
                'maxWorkUnits',
                huiText('Maximum search work units'),
                doc.matching.maxWorkUnits,
                (GlossBehaviorMatching limits, int value) =>
                    limits.maxWorkUnits = value,
              ),
            ],
          ),
        ),
        AppSection(
          title: huiText('Triggers'),
          memoryKey: 'behavior.on',
          count: doc.on.length,
          actions: <Widget>[
            AppIconButton(
              icon: ArcaneIcon.plus(),
              label: huiText('Add trigger'),
              onPressed: () => _edit(
                'add trigger',
                (GlossBehaviorDoc next) => next.on.add(GlossBehaviorEntry()),
              ),
            ),
          ],
          children: <Widget>[
            for (int index = 0; index < doc.on.length; index++)
              _entry(doc.on[index], index),
          ],
        ),
        BehaviorStateEditor(store: store),
      ],
    );
  }

  Widget _actions(HuiActionSourceList source, int index) => ActionsEditor(
    store: store,
    catalogs: store.catalogs,
    session: session,
    sessionKey: '${store.menuId}/on/$index/do',
    slot: ActionSlot.actions,
    actions: source.actions,
    clickContext: false,
    title: huiText('Actions {index}', <String, Object?>{'index': index + 1}),
    path: '\$.on[$index].do',
    issues: store.issues,
    onEdit: (String label, void Function(List<HuiAction>) edit) =>
        _edit(label, (GlossBehaviorDoc next) {
          final HuiActionSourceList? current = HuiActionSourceList.read(
            next.on[index].actions,
          );
          if (current != null) next.on[index].actions = current.edit(edit);
        }),
  );

  Widget _entry(GlossBehaviorEntry entry, int index) {
    final String path = '\$.on[$index]';
    final String id = 'behavior-on-$index';
    final Set<String> accepted =
        behaviorTriggerOptions[entry.trigger] ?? const <String>{};
    final Map<String, Object?> json = entry.toJson();
    final Map<String, Object?> options = Map<String, Object?>.of(json)
      ..removeWhere(
        (String key, Object? _) =>
            _entryKeys.contains(key) || accepted.contains(key),
      );
    final HuiActionSourceList? source = HuiActionSourceList.read(entry.actions);
    return AppSection(
      title: '${index + 1}. ${entry.trigger}',
      memoryKey: 'behavior.on.$index',
      description: source == null
          ? huiText('Repair actions in Code view to use these controls.')
          : null,
      actions: <Widget>[
        AppIconButton(
          icon: ArcaneIcon.trash(),
          label: huiText('Remove trigger'),
          onPressed: () => _edit(
            'remove trigger',
            (GlossBehaviorDoc doc) => doc.on.removeAt(index),
          ),
        ),
      ],
      children: <Widget>[
        Field(
          label: huiText('Trigger'),
          forId: '$id-trigger',
          problems: _issues('$path.trigger'),
          child: ArcaneSelect(
            id: '$id-trigger',
            value: entry.trigger,
            size: _size,
            fullWidth: true,
            options: <ArcaneSelectOption>[
              for (final String trigger in behaviorTriggerOptions.keys)
                ArcaneSelectOption(value: trigger, label: trigger),
            ],
            onChanged: (String value) => _edit(
              'trigger',
              (GlossBehaviorDoc doc) => doc.on[index].trigger = value,
            ),
          ),
        ),
        if (entry.trigger == 'chat' || entry.pattern != null)
          _text(
            huiText('Chat pattern {index}', <String, Object?>{
              'index': index + 1,
            }),
            '$id-pattern',
            entry.pattern ?? '',
            '$path.pattern',
            (GlossBehaviorDoc doc, String value) =>
                doc.on[index].pattern = value.isEmpty ? null : value,
          ),
        _text(
          huiText('Condition {index}', <String, Object?>{'index': index + 1}),
          '$id-when',
          entry.when ?? '',
          '$path.when',
          (GlossBehaviorDoc doc, String value) =>
              doc.on[index].when = value.isEmpty ? null : value,
        ),
        _text(
          huiText('Permission {index}', <String, Object?>{'index': index + 1}),
          '$id-permission',
          entry.permission ?? '',
          '$path.permission',
          (GlossBehaviorDoc doc, String value) =>
              doc.on[index].permission = value.isEmpty ? null : value,
        ),
        for (final String key in accepted.where(
          (String key) => key != 'pattern',
        ))
          _text(
            '$key ${index + 1}',
            '$id-$key',
            '${json[key] ?? ''}',
            '$path.$key',
            (GlossBehaviorDoc doc, String value) {
              final Map<String, Object?> next = doc.on[index].toJson();
              if (value.isEmpty) {
                next.remove(key);
              } else if (key == 'everyTicks') {
                final int? ticks = int.tryParse(value);
                if (ticks == null) return;
                next[key] = ticks;
              } else {
                next[key] = value;
              }
              doc.on[index] = GlossBehaviorEntry.fromJson(next);
            },
          ),
        AdvancedOnly(
          child: ExtrasEditor(
            title: huiText('Trigger options {index}', <String, Object?>{
              'index': index + 1,
            }),
            extensionKeys: false,
            extras: options,
            onChanged: (String label, Map<String, Object?> values) =>
                _edit(label, (GlossBehaviorDoc doc) {
                  final Map<String, Object?> kept = doc.on[index].toJson()
                    ..removeWhere(
                      (String key, Object? _) =>
                          !_entryKeys.contains(key) && !accepted.contains(key),
                    );
                  doc.on[index] = GlossBehaviorEntry.fromJson(<String, Object?>{
                    ...kept,
                    ...values,
                  });
                }),
          ),
        ),
        if (source != null) _actions(source, index),
      ],
    );
  }
}
