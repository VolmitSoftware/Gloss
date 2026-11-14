/// Ordered action list, shared by the button's `actions`, the toggle's
/// `trueActions` / `falseActions`, behavior entries and the nested lists of
/// runtime actions.
///
/// Each action is one row: its number, type and what it is about. One row at
/// a time shows its fields. Converting between action types parks the
/// outgoing shape in the session cache, so flipping command -> sound ->
/// command gets the command string back.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../config/defaults.dart';
import '../../doctype/doctype.dart';
import '../../logic/action_list.dart';
import '../../logic/validation.dart';
import '../../model/model.dart';
import '../../services/catalogs.dart';
import '../../state/editor_store.dart';
import '../../state/workspace.dart';
import '../../ui/density.dart';
import '../../ui/field.dart';
import '../../ui/field_tip.dart';
import '../../ui/icon_button.dart';
import '../../ui/menu.dart';
import '../../ui/mode.dart';
import '../../ui/number_field.dart';
import '../../ui/picker.dart';
import '../../ui/reorder_list.dart';
import '../../ui/section.dart';
import '../../ui/slider_field.dart';
import '../../ui/technical_input.dart';
import '../../ui/toggle_choice.dart';
import '../../ui/vec3_field.dart';
import 'action_presets.dart';
import 'dialog_action_fields.dart';
import 'extras_editor.dart';
import 'inspector_session.dart';
import 'runtime_action_fields.dart';

import 'package:gloss_editor/l10n/hui_localizations.dart';
import '../../ui/toast.dart';

/// Which action list of a component is being edited.
enum ActionSlot { actions, trueActions, falseActions }

/// The types a row converts between; a runtime action keeps its own too.
const List<String> _convertibleTypes = <String>[
  'command',
  'sound',
  'navigate',
  'message',
  'teleport',
  'connect',
];

String _soundSourceLabel(String source) => switch (source) {
  'master' => huiTextKey('sound_category.master', 'Master'),
  'music' => huiTextKey('sound_category.music', 'Music'),
  'record' => huiTextKey('sound_category.record', 'Records'),
  'weather' => huiTextKey('sound_category.weather', 'Weather'),
  'block' => huiTextKey('sound_category.block', 'Blocks'),
  'hostile' => huiTextKey('sound_category.hostile', 'Hostile creatures'),
  'neutral' => huiTextKey('sound_category.neutral', 'Neutral creatures'),
  'player' => huiTextKey('sound_category.player', 'Players'),
  'ambient' => huiTextKey('sound_category.ambient', 'Ambient environment'),
  'voice' => huiTextKey('sound_category.voice', 'Voice'),
  _ => source,
};

String _navigationModeLabel(String mode) => switch (mode) {
  'push' => huiText('Push'),
  'replace' => huiText('Replace'),
  'back' => huiText('Back'),
  'home' => huiText('Home'),
  'close' => huiText('Close'),
  _ => mode,
};

String _triggerLabel(String trigger) => switch (trigger) {
  'any' => huiText('Any click'),
  'left_click' => huiText('Left click'),
  'right_click' => huiText('Right click'),
  'shift_left_click' => huiText('Sneak + left click'),
  'shift_right_click' => huiText('Sneak + right click'),
  _ => trigger,
};

String _typeLabel(String type) => switch (type) {
  'command' => huiText('Command'),
  'sound' => huiText('Sound'),
  'navigate' => huiText('Navigate'),
  'message' => huiText('Message'),
  'teleport' => huiText('Teleport'),
  'connect' => huiText('Connect'),
  'dialog' => huiText('Dialog'),
  'call' => huiText('Call named action'),
  _ => type,
};

extension ActionSlotNames on ActionSlot {
  String get jsonKey => switch (this) {
    ActionSlot.actions => 'actions',
    ActionSlot.trueActions => 'trueActions',
    ActionSlot.falseActions => 'falseActions',
  };

  String get label => switch (this) {
    ActionSlot.actions => huiText('Actions'),
    ActionSlot.trueActions => huiText('Actions on switch to TRUE'),
    ActionSlot.falseActions => huiText('Actions on switch to FALSE'),
  };

  /// Key into `huiFieldDocs`. A button's `actions` list has no doc of its own:
  /// the traps that need explaining are the toggle's two.
  String? get docKey => switch (this) {
    ActionSlot.actions => null,
    ActionSlot.trueActions => 'toggle.trueActions',
    ActionSlot.falseActions => 'toggle.falseActions',
  };
}

/// Reads the list a slot addresses. The returned list is the live one.
List<HuiAction> readActionSlot(HuiComponentData data, ActionSlot slot) {
  if (data is HuiButtonData) return data.actions;
  if (data is HuiToggleData) {
    return slot == ActionSlot.falseActions
        ? data.falseActions
        : data.trueActions;
  }
  return <HuiAction>[];
}

class ActionsEditor extends StatefulWidget {
  const ActionsEditor({
    required this.store,
    required this.catalogs,
    required this.session,
    required this.sessionKey,
    required this.onEdit,
    this.clickContext = true,
    this.title,
    this.path,
    this.depth = 0,
    this.timedSteps = false,
    required this.slot,
    required this.actions,
    this.catalogsLoading = false,
    this.description,
    this.issues = const <HuiIssue>[],
    this.headerActions = const <Widget>[],
    super.key,
  });

  final EditorStore store;
  final HuiCatalogs catalogs;
  final InspectorSession session;
  final String sessionKey;
  final void Function(String label, void Function(List<HuiAction>) update)
  onEdit;
  final bool clickContext;
  final String? title;
  final String? path;
  final int depth;
  final bool timedSteps;
  final ActionSlot slot;
  final List<HuiAction> actions;

  /// True until `assets/catalog/sounds.json` resolves.
  final bool catalogsLoading;

  /// When this list fires; the heading's tip.
  final String? description;
  final List<HuiIssue> issues;

  /// Dense controls on the heading line before the Add menu (a Remove for
  /// the branch or case holding this list).
  final List<Widget> headerActions;

  @override
  State<ActionsEditor> createState() => _ActionsEditorState();
}

class _ActionsEditorState extends State<ActionsEditor> {
  /// The one row showing its fields.
  int? _open = 0;

  @override
  void didUpdateWidget(ActionsEditor oldComponent) {
    super.didUpdateWidget(oldComponent);
    if (oldComponent.sessionKey != component.sessionKey ||
        oldComponent.slot != component.slot) {
      _open = 0;
    }
  }

  List<HuiAction> get _actions => component.actions;

  /// The row's place in DOM ids and the session cache. The toggle's two
  /// lists share a session key, so their slot tells them apart.
  String _rowPath(int index) => component.slot == ActionSlot.actions
      ? '${component.sessionKey}/$index'
      : '${component.sessionKey}/${component.slot.jsonKey}/$index';

  List<HuiIssue> _issuesFor(int index) {
    // Leading dot matters: without it the `actions` marker also matches
    // `trueActions[0]` and `falseActions[0]`.
    final String marker =
        '${component.path ?? '.${component.slot.jsonKey}'}[$index]';
    return component.issues
        .where((HuiIssue issue) => issue.path.contains(marker))
        .toList();
  }

  void _edit(String label, void Function(List<HuiAction> list) fn) =>
      component.onEdit(label, fn);

  void _add(String type) {
    setState(() => _open = _actions.length);
    _edit('add $type action', (List<HuiAction> list) {
      list.add(createDefaultAction(type));
    });
  }

  void _insert(HuiAction action) {
    setState(() => _open = _actions.length);
    _edit('add ${action.type} action', (List<HuiAction> list) {
      list.add(action);
    });
  }

  /// Acts at once: Undo brings the row back, and the toast offers it.
  void _remove(int index) {
    if (index < 0 || index >= _actions.length) return;
    final String label = _typeLabel(_actions[index].type);
    setState(() => _open = openRowAfterRemove(_open, removed: index));
    _edit('remove action', (List<HuiAction> list) {
      if (index >= 0 && index < list.length) list.removeAt(index);
    });
    final EditorStore store = component.store;
    toastInfo(
      huiText('Deleted {id}.', <String, Object?>{'id': label}),
      action: ToastAction(
        label: huiText('Undo'),
        onPressed: () {
          store.performUndo();
        },
      ),
    );
  }

  void _move(int from, int to) {
    if (from == to || to < 0 || to >= _actions.length) return;
    setState(() => _open = openRowAfterMove(_open, from: from, to: to));
    _edit('reorder actions', (List<HuiAction> list) {
      if (from < 0 || from >= list.length) return;
      final HuiAction moved = list.removeAt(from);
      list.insert(to.clamp(0, list.length), moved);
    });
  }

  void _replace(int index, String label, HuiAction next) =>
      _edit(label, (List<HuiAction> list) {
        if (index >= 0 && index < list.length) list[index] = next;
      });

  void _convert(int index, String nextType) {
    final HuiAction current = _actions[index];
    if (current.type == nextType) return;
    final HuiAction next = component.session.switchAction(
      InspectorSession.actionSlot(
        component.sessionKey,
        component.slot.jsonKey,
        index,
      ),
      current,
      nextType,
    );
    _replace(index, 'action type $nextType', next);
  }

  void _toggle(int index) =>
      setState(() => _open = _open == index ? null : index);

  Widget _addMenu() {
    final EditorStore store = component.store;
    final bool click = component.clickContext;
    final List<ActionPreset> presets = click
        ? actionPresets(
            menuId: store.menuId,
            recentMenus: recentMenuIds(
              store.workspace.docs,
              exclude: store.menuId,
            ),
          )
        : const <ActionPreset>[];
    return AppMenu(
      icon: ArcaneIcon.plus(),
      iconLabel: huiText('Add action'),
      alignEnd: true,
      entries: <AppMenuEntry>[
        AppMenuEntry.heading(huiText('Actions')),
        AppMenuEntry(
          _typeLabel('command'),
          () => _add('command'),
          icon: ArcaneIcon.terminal(),
        ),
        AppMenuEntry(
          _typeLabel('sound'),
          () => _add('sound'),
          icon: ArcaneIcon.volume2(),
        ),
        AppMenuEntry(
          _typeLabel('navigate'),
          () => _add('navigate'),
          icon: ArcaneIcon.externalLink(),
        ),
        AppMenuEntry(
          _typeLabel('message'),
          () => _add('message'),
          icon: ArcaneIcon.messageCircle(),
        ),
        AppMenuEntry(
          _typeLabel('teleport'),
          () => _add('teleport'),
          icon: ArcaneIcon.locateFixed(),
        ),
        AppMenuEntry(
          _typeLabel('connect'),
          () => _add('connect'),
          icon: ArcaneIcon.share2(),
        ),
        AppMenuEntry(
          _typeLabel('dialog'),
          () => _add('dialog'),
          icon: ArcaneIcon.appWindow(),
        ),
        if (click)
          AppMenuEntry(
            _typeLabel('call'),
            () => _add('call'),
            icon: ArcaneIcon.squareFunction(),
          ),
        if (presets.isNotEmpty) ...<AppMenuEntry>[
          const AppMenuEntry.separator(),
          AppMenuEntry.heading(huiText('Presets')),
          for (final ActionPreset preset in presets)
            AppMenuEntry(
              preset.label,
              () => _insert(preset.build()),
              icon: preset.icon,
            ),
        ],
        const AppMenuEntry.separator(),
        AppMenuEntry.heading(huiText('Advanced')),
        for (final String type in runtimeAuthoringTypes)
          AppMenuEntry(type, () => _add(type)),
      ],
    );
  }

  @override
  Widget build(BuildContext context) {
    final ActionsEditor editor = component;
    final String title = editor.title ?? editor.slot.label;
    return AppSection(
      title: title,
      count: _actions.length,
      tip: editor.description,
      docKey: editor.slot.docKey,
      description: _actions.isEmpty ? huiText('No actions') : null,
      classes: 'hui-actions',
      actions: <Widget>[...editor.headerActions, _addMenu()],
      children: <Widget>[
        if (_actions.isNotEmpty)
          ReorderList(
            label: title,
            itemCount: _actions.length,
            // Move up and Move down sit in each row's menu.
            tools: false,
            onReorder: _move,
            itemBuilder: _row,
          ),
      ],
    );
  }

  Widget _row(int index) {
    final HuiAction action = _actions[index];
    final bool open = _open == index;
    final String name = '${index + 1}. ${_typeLabel(action.type)}';
    final String summary = actionSummary(action);
    return dom.div(
      attributes: <String, String>{'data-action-path': _rowPath(index)},
      <Widget>[
        dom.div(classes: 'hui-item-head', <Widget>[
          dom.h4(classes: 'hui-item-title', <Widget>[Text(name)]),
          if (summary.isNotEmpty)
            dom.span(
              classes: 'hui-item-summary',
              attributes: const <String, String>{'dir': 'auto'},
              <Widget>[Text(summary)],
            ),
          dom.span(classes: 'hui-item-actions', <Widget>[
            AppIconButton(
              icon: open ? ArcaneIcon.chevronUp() : ArcaneIcon.chevronDown(),
              label: huiText('Edit action {number}', <String, Object?>{
                'number': index + 1,
              }),
              expanded: open,
              onPressed: () => _toggle(index),
            ),
            AppMenu(
              icon: ArcaneIcon.ellipsis(),
              iconLabel: huiText('Actions for {label}', <String, Object?>{
                'label': name,
              }),
              alignEnd: true,
              entries: <AppMenuEntry>[
                AppMenuEntry(
                  huiText('Move up'),
                  index == 0 ? null : () => _move(index, index - 1),
                  icon: ArcaneIcon.arrowUp(),
                ),
                AppMenuEntry(
                  huiText('Move down'),
                  index >= _actions.length - 1
                      ? null
                      : () => _move(index, index + 1),
                  icon: ArcaneIcon.arrowDown(),
                ),
                const AppMenuEntry.separator(),
                AppMenuEntry(
                  huiText('Remove'),
                  () => _remove(index),
                  destructive: true,
                  icon: ArcaneIcon.trash2(),
                ),
              ],
            ),
          ]),
        ]),
        if (open) dom.div(classes: 'hui-action-body', _fields(index, action)),
      ],
    );
  }

  /// A text field holding an optional whole number, written under [key] of
  /// the action's extras and removed when cleared.
  Widget _tickField(
    int index,
    HuiAction action,
    String key,
    String label,
    String undoLabel,
  ) {
    final String id = 'hui-action-${_rowPath(index)}/$key';
    return Field(
      label: label,
      forId: id,
      child: TextInput(
        id: id,
        value: '${action.extras[key] ?? ''}',
        size: controlSize(DensityScope.of(context)),
        fullWidth: true,
        attributes: const <String, String>{'inputmode': 'numeric'},
        onChanged: (String value) {
          final int? ticks = int.tryParse(value);
          if (ticks == null && value.trim().isNotEmpty) return;
          final HuiAction next = action.copy();
          if (ticks == null) {
            next.extras.remove(key);
          } else {
            next.extras[key] = ticks;
          }
          _replace(index, undoLabel, next);
        },
      ),
    );
  }

  List<Widget> _fields(int index, HuiAction action) {
    final ActionsEditor editor = component;
    final ComponentSize size = controlSize(DensityScope.of(context));
    final String path = _rowPath(index);
    final String typeId = 'hui-action-$path/type';
    final String triggerId = 'hui-action-$path/trigger';
    final String whenId = 'hui-action-$path/when';
    final List<HuiIssue> issues = _issuesFor(index);
    void onChanged(String label, HuiAction next) =>
        _replace(index, label, next);
    return <Widget>[
      Field(
        label: huiText('Type'),
        forId: '$typeId-trigger',
        child: ArcaneSelect(
          id: typeId,
          value: action.type,
          size: size,
          fullWidth: true,
          options: <ArcaneSelectOption>[
            if (action is HuiRuntimeAction)
              ArcaneSelectOption(
                value: action.type,
                label: _typeLabel(action.type),
              ),
            for (final String type in _convertibleTypes)
              ArcaneSelectOption(value: type, label: _typeLabel(type)),
          ],
          onChanged: (String type) => _convert(index, type),
        ),
      ),
      if (!editor.clickContext && action is! HuiRuntimeAction) ...<Widget>[
        Field(
          label: huiText('Condition'),
          forId: whenId,
          child: TextInput(
            id: whenId,
            value: '${action.extras['when'] ?? ''}',
            size: size,
            fullWidth: true,
            attributes: technicalInputAttributes,
            styles: technicalInputStyles,
            onChanged: (String value) {
              final HuiAction next = action.copy();
              if (value.isEmpty) {
                next.extras.remove('when');
              } else {
                next.extras['when'] = value;
              }
              _replace(index, 'action condition', next);
            },
          ),
        ),
        AdvancedOnly(
          child: _tickField(
            index,
            action,
            'cooldownTicks',
            huiText('Cooldown ticks'),
            'action cooldown',
          ),
        ),
      ],
      if (editor.timedSteps)
        AdvancedOnly(
          child: _tickField(
            index,
            action,
            'atTicks',
            huiText('At ticks'),
            'sequence cue',
          ),
        ),
      if (editor.clickContext)
        Field(
          label: huiText('Click trigger'),
          forId: '$triggerId-trigger',
          tip: huiText(
            'Any matches left, right, and both sneak-modified clicks.',
          ),
          problems: issues
              .where((HuiIssue issue) => issue.path.endsWith('.trigger'))
              .toList(),
          child: ArcaneSelect(
            id: triggerId,
            value: action.trigger,
            size: size,
            fullWidth: true,
            onChanged: (String value) {
              final HuiAction next = action.copy()..trigger = value;
              _replace(index, 'action click trigger', next);
            },
            options: <ArcaneSelectOption>[
              for (final String trigger in huiActionTriggers)
                ArcaneSelectOption(
                  label: _triggerLabel(trigger),
                  value: trigger,
                ),
              if (!huiActionTriggers.contains(action.trigger))
                ArcaneSelectOption(
                  label: huiText("{trigger} (unknown)", <String, Object?>{
                    'trigger': action.trigger,
                  }),
                  value: action.trigger,
                ),
            ],
          ),
        ),
      switch (action) {
        final HuiCommandAction command => _CommandActionFields(
          action: command,
          path: path,
          issues: issues,
          onChanged: onChanged,
        ),
        final HuiSoundAction sound => _SoundActionFields(
          action: sound,
          path: path,
          catalogs: editor.catalogs,
          catalogsLoading: editor.catalogsLoading,
          issues: issues,
          onChanged: onChanged,
        ),
        final HuiMessageAction message => _MessageActionFields(
          action: message,
          path: path,
          issues: issues,
          onChanged: onChanged,
        ),
        final HuiTeleportAction teleport => _TeleportActionFields(
          action: teleport,
          path: path,
          issues: issues,
          onChanged: onChanged,
        ),
        final HuiConnectAction connect => _ConnectActionFields(
          action: connect,
          path: path,
          issues: issues,
          onChanged: onChanged,
        ),
        final HuiRuntimeAction runtime =>
          runtime.type == 'dialog'
              ? DialogActionFields(
                  action: runtime,
                  path: path,
                  onChanged: (HuiRuntimeAction next) =>
                      _replace(index, 'dialog', next),
                )
              : RuntimeActionFields(
                  action: runtime,
                  store: editor.store,
                  session: editor.session,
                  sessionKey: path,
                  depth: editor.depth,
                  clickContext: editor.clickContext,
                  onChanged: (HuiRuntimeAction next) =>
                      _replace(index, 'runtime action', next),
                ),
        final HuiNavigateAction navigation => _NavigateActionFields(
          action: navigation,
          path: path,
          store: editor.store,
          issues: issues,
          // Navigation ends the list for its click: rows below never run.
          blocksLater: index < _actions.length - 1,
          onChanged: onChanged,
        ),
      },
      AdvancedOnly(
        child: ExtrasEditor(
          title: huiText('Action'),
          extensionKeys: action is! HuiRuntimeAction,
          extras: <String, Object?>{
            for (final MapEntry<String, Object?> field in action.extras.entries)
              if (action is! HuiRuntimeAction ||
                  !runtimeManagedKeys(action.type).contains(field.key))
                field.key: field.value,
          },
          onChanged: (String label, Map<String, dynamic> next) =>
              _edit(label, (List<HuiAction> list) {
                if (index >= 0 && index < list.length) {
                  list[index].extras = <String, Object?>{
                    if (list[index] is HuiRuntimeAction)
                      for (final MapEntry<String, Object?> field
                          in list[index].extras.entries)
                        if (runtimeManagedKeys(list[index].type)
                            .contains(field.key))
                          field.key: field.value,
                    ...next,
                  };
                }
              }),
        ),
      ),
    ];
  }
}

List<HuiIssue> _endingWith(List<HuiIssue> issues, String suffix) =>
    issues.where((HuiIssue issue) => issue.path.endsWith(suffix)).toList();

class _CommandActionFields extends StatelessWidget {
  const _CommandActionFields({
    required this.action,
    required this.path,
    required this.onChanged,
    required this.issues,
  });

  final HuiCommandAction action;
  final String path;
  final void Function(String label, HuiAction action) onChanged;
  final List<HuiIssue> issues;

  HuiCommandAction _with({String? command, String? source}) => HuiCommandAction(
    command ?? action.command,
    source ?? action.source,
    action.trigger,
  )..extras = huiDeepCopyMap(action.extras);

  @override
  Widget build(BuildContext context) {
    final String id = 'hui-action-$path/command';
    return Component.fragment(<Widget>[
      Field(
        label: huiText('Command'),
        forId: id,
        required: true,
        docKey: 'action.command.command',
        problems: _endingWith(issues, '.command'),
        child: TextInput(
          id: id,
          value: action.command,
          size: controlSize(DensityScope.of(context)),
          fullWidth: true,
          placeholder: huiText('warp shop'),
          attributes: technicalInputAttributes,
          styles: technicalInputStyles,
          onChanged: (String value) =>
              onChanged('command', _with(command: value)),
        ),
      ),
      Field(
        label: huiText('Run as'),
        docKey: 'action.command.source',
        problems: _endingWith(issues, '.source'),
        child: ToggleChoice<String>(
          label: huiText('Run as'),
          value: action.source,
          onChanged: (String value) =>
              onChanged('command source', _with(source: value)),
          options: <ChoiceOption<String>>[
            ChoiceOption<String>(
              'player',
              huiText('Player'),
              tip: huiText(
                'player.performCommand - the player runs it with their own '
                'permissions.',
              ),
            ),
            ChoiceOption<String>(
              'server',
              huiText('Server console'),
              tip: huiText(
                'Bukkit.dispatchCommand from console - full '
                'privileges, no permission check against the player.',
              ),
            ),
            if (!huiCommandSources.contains(action.source))
              ChoiceOption<String>(
                action.source,
                huiText('Unknown'),
                tip: huiText(
                  "Unrecognized source \"{source}\"; Gloss uses the player default.",
                  <String, Object?>{'source': action.source},
                ),
              ),
          ],
        ),
      ),
    ]);
  }
}

class _SoundActionFields extends StatelessWidget {
  const _SoundActionFields({
    required this.action,
    required this.path,
    required this.catalogs,
    required this.onChanged,
    required this.issues,
    this.catalogsLoading = false,
  });

  final HuiSoundAction action;
  final String path;
  final HuiCatalogs catalogs;
  final void Function(String label, HuiAction action) onChanged;
  final List<HuiIssue> issues;
  final bool catalogsLoading;

  HuiSoundAction _with({
    String? sound,
    String? source,
    double? volume,
    double? pitch,
  }) => HuiSoundAction(
    sound ?? action.sound,
    source ?? action.source,
    volume ?? action.volume,
    pitch ?? action.pitch,
    action.trigger,
  )..extras = huiDeepCopyMap(action.extras);

  @override
  Widget build(BuildContext context) {
    final String soundId = 'hui-action-$path/sound';
    final String sourceId = 'hui-action-$path/source';
    return Component.fragment(<Widget>[
      Field(
        label: huiText('Sound'),
        forId: soundId,
        required: true,
        docKey: 'action.sound.sound',
        problems: _endingWith(issues, '.sound'),
        child: Picker(
          id: soundId,
          value: action.sound,
          placeholder: huiText('ui.button.click'),
          searchPlaceholder: huiText('Search sounds'),
          emptyMessage: catalogsLoading ? huiText('Loading') : null,
          lowercase: true,
          search: (String query) => <PickerOption>[
            for (final String sound in catalogs.searchSounds(query))
              PickerOption(sound),
          ],
          onChanged: (String value) => onChanged('sound', _with(sound: value)),
        ),
      ),
      Field(
        label: huiText('Category'),
        forId: '$sourceId-trigger',
        required: true,
        docKey: 'action.sound.source',
        problems: _endingWith(issues, '.source'),
        child: ArcaneSelect(
          id: sourceId,
          value: action.source,
          size: controlSize(DensityScope.of(context)),
          fullWidth: true,
          onChanged: (String value) =>
              onChanged('sound category', _with(source: value)),
          options: <ArcaneSelectOption>[
            for (final String source in huiSoundSources)
              ArcaneSelectOption(
                label: _soundSourceLabel(source),
                value: source,
              ),
            // Keeps an imported but unrecognised category visible instead
            // of silently showing the first entry.
            if (!huiSoundSources.contains(action.source))
              ArcaneSelectOption(
                label: huiText(
                  "{source} (not a known category)",
                  <String, Object?>{'source': action.source},
                ),
                value: action.source,
              ),
          ],
        ),
      ),
      // Both reset to 1, the sound as recorded, not the format default of
      // 0: a zero volume is silence and a zero pitch clamps to 0.5.
      Field(
        label: huiText('Volume'),
        docKey: 'action.sound.volume',
        defaultValue: '1',
        onReset: action.volume == 1
            ? null
            : () => onChanged('sound volume', _with(volume: 1)),
        problems: _endingWith(issues, '.volume'),
        child: SliderField(
          label: huiText('Volume'),
          value: action.volume,
          min: 0,
          max: 2,
          step: 0.05,
          decimals: 2,
          numberMin: 0,
          onChanged: (double value) =>
              onChanged('sound volume', _with(volume: value)),
        ),
      ),
      Field(
        label: huiTextKey('field.pitch.sound', 'Pitch'),
        docKey: 'action.sound.pitch',
        defaultValue: '1',
        onReset: action.pitch == 1
            ? null
            : () => onChanged('sound pitch', _with(pitch: 1)),
        problems: _endingWith(issues, '.pitch'),
        child: SliderField(
          label: huiTextKey('field.pitch.sound', 'Pitch'),
          value: action.pitch,
          min: 0.5,
          max: 2,
          step: 0.05,
          decimals: 2,
          numberMin: 0,
          onChanged: (double value) =>
              onChanged('sound pitch', _with(pitch: value)),
        ),
      ),
    ]);
  }
}

class _MessageActionFields extends StatelessWidget {
  const _MessageActionFields({
    required this.action,
    required this.path,
    required this.onChanged,
    required this.issues,
  });

  final HuiMessageAction action;
  final String path;
  final void Function(String label, HuiAction action) onChanged;
  final List<HuiIssue> issues;

  HuiMessageAction _with(String message) =>
      HuiMessageAction(message, action.trigger)
        ..extras = huiDeepCopyMap(action.extras);

  @override
  Widget build(BuildContext context) {
    final String id = 'hui-action-$path/message';
    return Field(
      label: huiText('Message'),
      forId: id,
      required: true,
      docKey: 'action.message.message',
      problems: _endingWith(issues, '.message'),
      child: TextArea(
        id: id,
        value: action.message,
        rows: 3,
        fullWidth: true,
        resize: TextAreaResize.vertical,
        placeholder: huiText('<gold>Hello %player%</gold>'),
        onChanged: (String value) => onChanged('message', _with(value)),
      ),
    );
  }
}

class _TeleportActionFields extends StatelessWidget {
  const _TeleportActionFields({
    required this.action,
    required this.path,
    required this.onChanged,
    required this.issues,
  });

  final HuiTeleportAction action;
  final String path;
  final void Function(String label, HuiAction action) onChanged;
  final List<HuiIssue> issues;

  HuiTeleportAction _with({
    String? world,
    double? x,
    double? y,
    double? z,
    double? yaw,
    double? pitch,
  }) => HuiTeleportAction(
    world ?? action.world,
    x ?? action.x,
    y ?? action.y,
    z ?? action.z,
    yaw ?? action.yaw,
    pitch ?? action.pitch,
    action.trigger,
  )..extras = huiDeepCopyMap(action.extras);

  Widget _angle(String id, String label, String key, double value) => Field(
    label: label,
    forId: id,
    required: true,
    problems: _endingWith(issues, '.$key'),
    child: NumberField(
      id: id,
      value: value,
      step: 1,
      decimals: 3,
      suffix: '°',
      onChanged: (double next) => onChanged(
        'teleport $key',
        key == 'yaw' ? _with(yaw: next) : _with(pitch: next),
      ),
    ),
  );

  @override
  Widget build(BuildContext context) {
    final String worldId = 'hui-action-$path/world';
    final String positionId = 'hui-action-$path/position';
    return Component.fragment(<Widget>[
      Field(
        label: huiText('World key'),
        forId: worldId,
        required: true,
        docKey: 'action.teleport.world',
        problems: _endingWith(issues, '.world'),
        child: TextInput(
          id: worldId,
          value: action.world,
          size: controlSize(DensityScope.of(context)),
          fullWidth: true,
          placeholder: huiText('minecraft:overworld'),
          attributes: technicalInputAttributes,
          styles: technicalInputStyles,
          onChanged: (String value) =>
              onChanged('teleport world', _with(world: value)),
        ),
      ),
      Field(
        label: huiText('Coordinates'),
        forId: positionId,
        required: true,
        problems: <HuiIssue>[
          ..._endingWith(issues, '.x'),
          ..._endingWith(issues, '.y'),
          ..._endingWith(issues, '.z'),
        ],
        child: Vec3Field(
          id: positionId,
          label: huiText('Coordinates'),
          value: Vec3(action.x, action.y, action.z),
          step: 1,
          decimals: 3,
          onChanged: (Vec3 value) => onChanged(
            'teleport coordinates',
            _with(x: value.x, y: value.y, z: value.z),
          ),
        ),
      ),
      _angle('hui-action-$path/yaw', huiText('Yaw'), 'yaw', action.yaw),
      _angle(
        'hui-action-$path/pitch',
        huiTextKey('field.pitch.orientation', 'Pitch'),
        'pitch',
        action.pitch,
      ),
    ]);
  }
}

class _ConnectActionFields extends StatelessWidget {
  const _ConnectActionFields({
    required this.action,
    required this.path,
    required this.onChanged,
    required this.issues,
  });

  final HuiConnectAction action;
  final String path;
  final void Function(String label, HuiAction action) onChanged;
  final List<HuiIssue> issues;

  HuiConnectAction _with(String server) =>
      HuiConnectAction(server, action.trigger)
        ..extras = huiDeepCopyMap(action.extras);

  @override
  Widget build(BuildContext context) {
    final String id = 'hui-action-$path/server';
    return Field(
      label: huiText('Proxy server'),
      forId: id,
      required: true,
      docKey: 'action.connect.server',
      problems: _endingWith(issues, '.server'),
      child: TextInput(
        id: id,
        value: action.server,
        size: controlSize(DensityScope.of(context)),
        fullWidth: true,
        placeholder: huiText('lobby'),
        attributes: technicalInputAttributes,
        styles: technicalInputStyles,
        onChanged: (String value) => onChanged('proxy server', _with(value)),
      ),
    );
  }
}

class _NavigateActionFields extends StatelessWidget {
  const _NavigateActionFields({
    required this.action,
    required this.path,
    required this.store,
    required this.onChanged,
    required this.issues,
    required this.blocksLater,
  });

  final HuiNavigateAction action;
  final String path;
  final EditorStore store;
  final void Function(String label, HuiAction action) onChanged;
  final List<HuiIssue> issues;

  /// Rows follow this one in its list, and navigation stops them.
  final bool blocksLater;

  HuiNavigateAction _with({String? target, String? mode}) => HuiNavigateAction(
    target ?? action.target,
    mode ?? action.mode,
    action.trigger,
  )..extras = huiDeepCopyMap(action.extras);

  List<PickerOption> _searchMenus(String query) {
    final String normalized = query.trim().toLowerCase();
    return <PickerOption>[
      for (final WorkspaceDoc doc in store.workspace.docs)
        if (doc.kind == DocumentTypes.menu.kind &&
            doc.runtimeId != null &&
            (normalized.isEmpty ||
                doc.runtimeId!.toLowerCase().contains(normalized) ||
                doc.title.toLowerCase().contains(normalized)))
          PickerOption(
            doc.runtimeId!,
            description: doc.title == doc.runtimeId ? null : doc.title,
          ),
    ];
  }

  @override
  Widget build(BuildContext context) {
    final ComponentSize size = controlSize(DensityScope.of(context));
    final String modeId = 'hui-action-$path/mode';
    final String targetId = 'hui-action-$path/target';
    return Component.fragment(<Widget>[
      Field(
        label: huiText('Mode'),
        forId: '$modeId-trigger',
        required: true,
        tip: tipWithDoc(
          huiText('Push keeps a Back entry; replace swaps without adding one.'),
          'action.navigation',
        ),
        defaultValue: huiText('Push'),
        onReset: action.mode == 'push'
            ? null
            : () => onChanged('navigation mode', _with(mode: 'push')),
        problems: <HuiIssue>[
          ..._endingWith(issues, '.mode'),
          if (blocksLater)
            const HuiIssue(
              severity: HuiSeverity.warning,
              path: '',
              message: 'Navigation is terminal: actions below it in this list do not run.',
            ),
        ],
        child: ArcaneSelect(
          id: modeId,
          value: action.mode,
          size: size,
          fullWidth: true,
          onChanged: (String value) =>
              onChanged('navigation mode', _with(mode: value)),
          options: <ArcaneSelectOption>[
            for (final String mode in huiNavigationModes)
              ArcaneSelectOption(
                label: _navigationModeLabel(mode),
                value: mode,
              ),
            if (!huiNavigationModes.contains(action.mode))
              ArcaneSelectOption(
                label: huiText("{mode} (unknown)", <String, Object?>{
                  'mode': action.mode,
                }),
                value: action.mode,
              ),
          ],
        ),
      ),
      if (action.targetIsMenuId)
        Field(
          label: huiText('Target menu'),
          forId: targetId,
          required: true,
          docKey: 'action.navigation.target',
          problems: _endingWith(issues, '.target'),
          child: Picker(
            id: targetId,
            value: action.target,
            placeholder: huiText('shops/confirm'),
            searchPlaceholder: huiText('Search workspace menus'),
            emptyMessage: huiText('No matching menu in this workspace.'),
            search: _searchMenus,
            onChanged: (String value) =>
                onChanged('navigation target', _with(target: value)),
          ),
        )
      // Page mode addresses a page inside the surface already open, not a
      // menu, so the menu picker would be wrong here.
      else if (action.requiresTarget)
        Field(
          label: huiText('Target'),
          forId: targetId,
          required: true,
          problems: _endingWith(issues, '.target'),
          child: TextInput(
            id: targetId,
            value: action.target,
            size: size,
            fullWidth: true,
            attributes: technicalInputAttributes,
            styles: technicalInputStyles,
            onChanged: (String value) =>
                onChanged('navigation target', _with(target: value)),
          ),
        ),
    ]);
  }
}
