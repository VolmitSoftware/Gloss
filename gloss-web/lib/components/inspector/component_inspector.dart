/// Properties of the selected component: header and id, offset, motion, then
/// the per-type groups.
library;

import 'dart:convert';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/jaspr.dart' show Component;

import '../../config/defaults.dart';
import '../../logic/component_motion.dart';
import '../../logic/gloss_show.dart';
import '../../logic/validation.dart';
import '../../model/model.dart';
import '../../services/catalogs.dart';
import '../../services/clipboard.dart';
import '../../services/image_library.dart';
import '../../state/editor_store.dart';
import '../../ui/density.dart';
import '../../ui/duration_field.dart';
import '../../ui/duration_value.dart';
import '../../ui/field.dart';
import '../../ui/field_group.dart';
import '../../ui/icon_button.dart';
import '../../ui/menu.dart';
import '../../ui/mode.dart';
import '../../ui/number_field.dart';
import '../../ui/pane_header.dart';
import '../../ui/reorder_list.dart';
import '../../ui/section.dart';
import '../../ui/slider_field.dart';
import '../../ui/switch_field.dart';
import '../../ui/technical_input.dart';
import '../../ui/toggle_choice.dart';
import '../../ui/vec3_field.dart';
import 'actions_editor.dart';
import 'extras_editor.dart';
import 'gloss_visibility_editor.dart';
import 'icon_editor.dart';
import 'inspector_session.dart';
import 'preview_expr_field.dart';

import 'package:gloss_editor/l10n/hui_localizations.dart';
import '../../ui/toast.dart';

/// DOM id of the component id input. `inspector_pane.dart` focuses it when the
/// canvas asks the inspector to take over.
const String huiComponentIdFieldId = 'hui-component-id-field';

String _hoverEasingLabel(HuiHoverEasing easing) => switch (easing) {
  HuiHoverEasing.linear => huiText('Linear'),
  HuiHoverEasing.easeOutCubic => huiText('Ease out cubic'),
  HuiHoverEasing.easeInOutCubic => huiText('Ease in/out cubic'),
  HuiHoverEasing.backOut => huiText('Back out'),
};

String _typeLabel(HuiComponentData data) => switch (data) {
  HuiRuntimeComponentData() => data.type,
  HuiButtonData() => huiText('Button'),
  HuiDecorationData() => huiText('Decoration'),
  HuiToggleData() => huiText('Toggle'),
};

class ComponentInspector extends StatelessWidget {
  const ComponentInspector({
    required this.store,
    required this.images,
    required this.catalogs,
    required this.session,
    required this.target,
    this.catalogsLoading = false,
    super.key,
  });

  final EditorStore store;
  final ImageLibrary images;
  final HuiCatalogs catalogs;
  final InspectorSession session;

  /// True until the shipped catalogue assets have resolved. Only the pickers
  /// that read them care; every free-text control works from the first paint.
  final bool catalogsLoading;

  /// The selected component.
  final HuiComponent target;

  String get _id => target.id;

  List<HuiIssue> get _issues => store.issuesFor(_id);

  List<HuiIssue> _issuesFor(String suffix) =>
      _issues.where((HuiIssue issue) => issue.path.endsWith(suffix)).toList();

  @override
  Widget build(BuildContext context) => DenseBox(
    classes: 'hui-inspector-body is-component',
    // Inside the dense box, so the stock controls read its size.
    children: <Widget>[Builder(builder: _body)],
  );

  Widget _body(BuildContext context) => Component.fragment(<Widget>[
    _ComponentHeader(store: store, session: session, target: target),
    AdvancedOnly(
      child: GlossVisibilityEditor(
        raw: target.extras['show'],
        sectionKey: 'component.visibility',
        onChanged: (Object? value) => store.editComponent(
          _id,
          'visibility',
          (HuiComponent edited) => setGlossShow(edited.extras, value),
        ),
      ),
    ),
    _placement(),
    _motion(),
    ..._typeSections(context),
    AdvancedOnly(child: _extras()),
  ]);

  void _editForm(String key, Object? value) {
    store.editComponent(_id, key, (HuiComponent edited) {
      final Map<String, Object?> json = edited.data.toJson();
      json[key] = value;
      edited.data = HuiComponentData.fromJson(json);
    });
  }

  Widget _formText(
    ComponentSize size,
    String label,
    String key,
    String value, {
    bool multiline = false,
  }) {
    final String id = 'component-form-$key';
    return Field(
      label: label,
      forId: id,
      child: multiline
          ? TextArea(
              id: id,
              value: value,
              rows: 2,
              onChanged: (String next) => _editForm(key, next),
            )
          : TextInput(
              id: id,
              value: value,
              size: size,
              fullWidth: true,
              onChanged: (String next) => _editForm(key, next),
            ),
    );
  }

  Widget _formNumber(
    String label,
    String key,
    double value, {
    bool integer = false,
  }) {
    final String id = 'component-form-$key';
    return Field(
      label: label,
      forId: id,
      child: NumberField(
        id: id,
        value: value,
        integer: integer,
        step: integer ? 1 : 0.05,
        decimals: integer ? 0 : 3,
        onChanged: (double next) =>
            _editForm(key, integer ? next.round() : next),
      ),
    );
  }

  List<Widget> _formControls(
    BuildContext context,
    HuiRuntimeComponentData data,
  ) {
    final ComponentSize size = controlSize(DensityScope.of(context));
    final Map<String, Object?> json = data.toJson();
    const String fieldTypeId = 'component-form-prompt';
    return <Widget>[
      FieldGroup(
        title: huiText('Component'),
        children: <Widget>[
          _formText(
            size,
            huiText('Session variable'),
            'var',
            json['var'] as String? ?? '',
          ),
          if (data is HuiSliderData) ...<Widget>[
            _formText(
              size,
              huiText('Label'),
              'label',
              data.label ?? '',
              multiline: true,
            ),
            _formNumber(huiText('Minimum'), 'min', data.min ?? 0),
            _formNumber(huiText('Maximum'), 'max', data.max ?? 100),
            _formNumber(huiText('Step'), 'step', data.step ?? 1),
            _formNumber(huiText('Click width'), 'width', data.width ?? 2),
          ],
          if (data is HuiFieldData) ...<Widget>[
            _formText(
              size,
              huiText('Label'),
              'label',
              data.label ?? '',
              multiline: true,
            ),
            _formText(
              size,
              huiText('Initial value'),
              'initial',
              data.initial ?? '',
            ),
            Field(
              label: huiText('Type'),
              forId: '$fieldTypeId-trigger',
              child: ArcaneSelect(
                id: fieldTypeId,
                value: data.prompt ?? 'sign',
                fullWidth: true,
                size: size,
                options: <ArcaneSelectOption>[
                  for (final String prompt in <String>['sign', 'anvil', 'chat'])
                    ArcaneSelectOption(label: prompt, value: prompt),
                ],
                onChanged: (String next) => _editForm('prompt', next),
              ),
            ),
          ],
          if (data is HuiTabsData)
            _formNumber(huiText('Spacing'), 'spacing', data.spacing ?? 1),
          if (data is HuiListData) ...<Widget>[
            _formText(
              size,
              huiText('Source expression'),
              'source',
              data.source ?? '',
            ),
            _formNumber(
              huiText('Page size'),
              'pageSize',
              (data.pageSize ?? 6).toDouble(),
              integer: true,
            ),
          ],
        ],
      ),
      if (data is HuiTabsData) _tabs(size, data),
    ];
  }

  List<Object?> _tabsJson(HuiTabsData data) => <Object?>[
    for (final HuiTab tab in data.tabs ?? <HuiTab>[]) tab.toJson(),
  ];

  Widget _tabs(ComponentSize size, HuiTabsData data) {
    final List<HuiTab> tabs = data.tabs ?? <HuiTab>[];
    return FieldGroup(
      title: huiText('Pages'),
      count: tabs.length,
      actions: <Widget>[
        AppIconButton(
          icon: ArcaneIcon.plus(),
          label: huiText('Add'),
          onPressed: () => _editForm('tabs', <Object?>[
            ..._tabsJson(data),
            <String, Object?>{
              'id': 'tab-${tabs.length + 1}',
              'label': 'New tab',
            },
          ]),
        ),
      ],
      children: <Widget>[
        if (tabs.isNotEmpty)
          ReorderList(
            label: huiText('Pages'),
            itemCount: tabs.length,
            onReorder: (int from, int to) {
              final List<Object?> next = _tabsJson(data);
              next.insert(to, next.removeAt(from));
              _editForm('tabs', next);
            },
            onRemove: (int index) =>
                _editForm('tabs', _tabsJson(data)..removeAt(index)),
            itemBuilder: (int index) => Component.fragment(<Widget>[
              Field(
                label: huiText('Name'),
                forId: 'component-tab-$index-id',
                child: TextInput(
                  id: 'component-tab-$index-id',
                  value: tabs[index].id,
                  size: size,
                  fullWidth: true,
                  attributes: technicalInputAttributes,
                  styles: technicalInputStyles,
                  onChanged: (String value) =>
                      _editTab(data, index, 'id', value),
                ),
              ),
              Field(
                label: huiText('Label'),
                forId: 'component-tab-$index-label',
                child: TextInput(
                  id: 'component-tab-$index-label',
                  value: tabs[index].label ?? tabs[index].id,
                  size: size,
                  fullWidth: true,
                  onChanged: (String value) =>
                      _editTab(data, index, 'label', value),
                ),
              ),
            ]),
          ),
      ],
    );
  }

  void _editTab(HuiTabsData data, int index, String key, String value) {
    final List<Object?> tabs = _tabsJson(data);
    (tabs[index] as Map<String, Object?>)[key] = value;
    _editForm('tabs', tabs);
  }

  void _editMotion(String key, Object? value) => store.editComponent(
    _id,
    'motion',
    (HuiComponent edited) => setComponentMotionValue(edited.extras, key, value),
  );

  Widget _motionValue(Map<Object?, Object?> motion, String key, String label) =>
      PreviewExprField(
        label: label,
        raw: motion[key],
        docKey: 'component.motion.$key',
        scope: glossShowVariables.toSet(),
        categoryVariableNames: glossShowVariables,
        issues: _issuesFor('.motion.$key'),
        onChanged: (Object? value) => _editMotion(key, value),
      );

  Widget _motion() {
    final Object? raw = target.extras['motion'];
    final Map<Object?, Object?> motion = raw is Map
        ? raw
        : const <Object?, Object?>{};
    return AppSection(
      title: huiText('Motion'),
      memoryKey: 'component.motion',
      open: raw != null,
      tip: huiText(
        'Slides and resizes this icon without respawning it. Numbers or '
        'expressions, read every tick. The preview steps per tick; the '
        'game eases between them.',
      ),
      children: <Widget>[
        _motionValue(motion, 'x', huiText('X')),
        _motionValue(motion, 'y', huiText('Y')),
        _motionValue(motion, 'z', huiText('Z')),
        _motionValue(motion, 'scale', huiText('Scale')),
        AdvancedOnly(
          child: PreviewExprField(
            label: huiText('Easing ticks'),
            raw: motion['ticks'],
            docKey: 'component.motion.ticks',
            help: huiText(
              '0 jumps at once. Blank uses {ticks}.',
              <String, Object?>{'ticks': glossMotionDefaultTicks},
            ),
            scope: glossShowVariables.toSet(),
            categoryVariableNames: glossShowVariables,
            issues: _issuesFor('.motion.ticks'),
            onChanged: (Object? value) => _editMotion('ticks', value),
          ),
        ),
      ],
    );
  }

  Widget _placement() => FieldGroup(
    title: huiText('Offset'),
    tip: huiText(
      'z moves the component toward or away from the player. It is '
      'invisible in a flat view but still changes draw order, so two '
      'components at the same x and y stack by z.',
    ),
    children: <Widget>[
      Field(
        label: huiText('From the menu centre'),
        forId: 'component-offset',
        required: true,
        docKey: 'component.offset',
        problems: _issuesFor('.offset'),
        child: Vec3Field(
          id: 'component-offset',
          label: huiText('Offset'),
          // Typed entry bypasses grid snapping on purpose: an exact number
          // in a field should survive, unlike a canvas drag.
          value: target.offset,
          onChanged: (Vec3 value) => store.editComponent(
            _id,
            'offset',
            (HuiComponent edited) => edited.offset = value,
          ),
        ),
      ),
    ],
  );

  List<Widget> _typeSections(BuildContext context) {
    final HuiComponentData data = target.data;
    // Icon first: it is the most-edited group of the pane by a wide margin.
    if (data is HuiButtonData) {
      return <Widget>[
        _iconEditor(IconSlot.icon),
        _highlight(context, data),
        _hitbox(data),
        AdvancedOnly(
          child: ExtrasEditor(
            title: huiText('Tooltip'),
            extensionKeys: false,
            extras: huiReadObject(
              data.extras['tooltip'] ??
                  <String, Object?>{
                    'delayTicks': 10,
                    'lines': <String>[],
                    'style': <String, Object?>{},
                    'box': <String, Object?>{},
                  },
              'tooltip',
            ),
            onChanged: (String label, Map<String, dynamic> next) =>
                store.editComponent(_id, label, (HuiComponent edited) {
                  edited.data.extras['tooltip'] = huiDeepCopyMap(next);
                }),
          ),
        ),
        _actions(
          ActionSlot.actions,
          data.actions,
          description: huiText(
            'Matching actions run in order for each configured click.',
          ),
        ),
      ];
    }
    if (data is HuiDecorationData) return <Widget>[_iconEditor(IconSlot.icon)];
    if (data is HuiRuntimeComponentData) {
      return <Widget>[
        ..._formControls(context, data),
        AdvancedOnly(
          child: ExtrasEditor(
            title: huiText('Component'),
            extensionKeys: false,
            extras: data.toJson()..remove('type'),
            onChanged: (String label, Map<String, dynamic> next) =>
                store.editComponent(_id, label, (HuiComponent edited) {
                  edited.data = HuiComponentData.fromJson(<String, Object?>{
                    ...next,
                    'type': data.type,
                  });
                }),
          ),
        ),
      ];
    }
    final HuiToggleData toggle = data as HuiToggleData;
    return <Widget>[
      _condition(context, toggle),
      _toggleIcons(),
      _highlight(context, toggle),
      _hitbox(toggle),
      _actions(ActionSlot.trueActions, toggle.trueActions),
      _actions(ActionSlot.falseActions, toggle.falseActions),
    ];
  }

  Widget _actions(
    ActionSlot slot,
    List<HuiAction> actions, {
    String? description,
  }) => ActionsEditor(
    store: store,
    catalogs: catalogs,
    catalogsLoading: catalogsLoading,
    session: session,
    sessionKey: _id,
    onEdit: (String label, void Function(List<HuiAction>) update) =>
        store.editComponent(
          _id,
          label,
          (HuiComponent edited) => update(readActionSlot(edited.data, slot)),
        ),
    slot: slot,
    actions: actions,
    description: description,
    issues: _issues,
  );

  Widget _highlight(BuildContext context, HuiComponentData data) {
    final double value = switch (data) {
      HuiButtonData(:final double highlightModifier) => highlightModifier,
      HuiToggleData(:final double highlightModifier) => highlightModifier,
      HuiDecorationData() || HuiRuntimeComponentData() => 0,
    };
    final int duration = switch (data) {
      HuiButtonData(:final int hoverDurationTicks) => hoverDurationTicks,
      HuiToggleData(:final int hoverDurationTicks) => hoverDurationTicks,
      HuiDecorationData() ||
      HuiRuntimeComponentData() => huiRuntimeDefaultHoverDurationTicks,
    };
    final HuiHoverEasing easing = switch (data) {
      HuiButtonData(:final HuiHoverEasing hoverEasing) => hoverEasing,
      HuiToggleData(:final HuiHoverEasing hoverEasing) => hoverEasing,
      HuiDecorationData() ||
      HuiRuntimeComponentData() => huiRuntimeDefaultHoverEasing,
    };
    const String easingId = 'component-hover-easing';
    return AppSection(
      title: huiText('Highlight'),
      memoryKey: 'component.highlight',
      children: <Widget>[
        Field(
          label: huiText('Highlight modifier'),
          docKey: 'button.highlightModifier',
          // The shipped-example value and what a new component is born with.
          defaultValue: '$huiDefaultHighlightModifier',
          onReset: value == huiDefaultHighlightModifier
              ? null
              : () => _editHover(
                  'highlight modifier',
                  modifier: huiDefaultHighlightModifier,
                ),
          problems: _issuesFor('.highlightModifier'),
          child: SliderField(
            label: huiText('Highlight modifier'),
            value: value,
            min: 0,
            max: 1,
            step: 0.01,
            decimals: 3,
            onChanged: (double next) =>
                _editHover('highlight modifier', modifier: next),
          ),
        ),
        Field(
          label: huiText('Duration'),
          forId: 'component-hover-duration',
          docKey: 'button.hoverDurationTicks',
          defaultValue: huiPlural(
            'duration.tick_count',
            huiRuntimeDefaultHoverDurationTicks,
            oneEnglish: '{count} tick',
            otherEnglish: '{count} ticks',
          ),
          onReset: duration == huiRuntimeDefaultHoverDurationTicks
              ? null
              : () => _editHover(
                  'hover duration',
                  duration: huiRuntimeDefaultHoverDurationTicks,
                ),
          problems: _issuesFor('.hoverDurationTicks'),
          child: DurationField(
            id: 'component-hover-duration',
            value: duration.toDouble(),
            unit: HuiDurationUnit.ticks,
            min: 0,
            max: 40,
            onChanged: (double next) =>
                _editHover('hover duration', duration: next.round()),
          ),
        ),
        Field(
          label: huiText('Easing'),
          forId: '$easingId-trigger',
          docKey: 'button.hoverEasing',
          defaultValue: _hoverEasingLabel(huiRuntimeDefaultHoverEasing),
          onReset: easing == huiRuntimeDefaultHoverEasing
              ? null
              : () => _editHover(
                  'hover easing',
                  easing: huiRuntimeDefaultHoverEasing,
                ),
          child: ArcaneSelect(
            id: easingId,
            value: easing.jsonValue,
            fullWidth: true,
            size: controlSize(DensityScope.of(context)),
            options: <ArcaneSelectOption>[
              for (final HuiHoverEasing value in HuiHoverEasing.values)
                ArcaneSelectOption(
                  label: _hoverEasingLabel(value),
                  value: value.jsonValue,
                ),
            ],
            onChanged: (String next) => _editHover(
              'hover easing',
              easing: HuiHoverEasing.fromJson(next),
            ),
          ),
        ),
      ],
    );
  }

  void _editHover(
    String label, {
    double? modifier,
    int? duration,
    HuiHoverEasing? easing,
  }) {
    store.editComponent(_id, label, (HuiComponent edited) {
      switch (edited.data) {
        case HuiRuntimeComponentData():
          break;

        case final HuiButtonData data:
          if (modifier != null) data.highlightModifier = modifier;
          if (duration != null) data.hoverDurationTicks = duration;
          if (easing != null) data.hoverEasing = easing;
        case final HuiToggleData data:
          if (modifier != null) data.highlightModifier = modifier;
          if (duration != null) data.hoverDurationTicks = duration;
          if (easing != null) data.hoverEasing = easing;
        case HuiDecorationData():
          break;
      }
    });
  }

  Widget _hitbox(HuiComponentData data) {
    final HuiHitbox? hitbox = switch (data) {
      HuiButtonData(:final HuiHitbox? hitbox) => hitbox,
      HuiToggleData(:final HuiHitbox? hitbox) => hitbox,
      HuiDecorationData() || HuiRuntimeComponentData() => null,
    };
    final bool detached = hitbox?.anchor == HuiHitboxAnchor.menu;
    final bool custom = hitbox?.hasCustomSize ?? false;
    return AppSection(
      title: huiText('Hitbox'),
      memoryKey: 'component.hitbox',
      tip: huiText(
        'The click plane stays fixed while the icon animates on hover.',
      ),
      children: <Widget>[
        SwitchField(
          id: 'component-hitbox-linked',
          label: huiText('Move with component'),
          value: !detached,
          docKey: 'button.hitbox',
          onChanged: (bool linked) => store.setHitboxAnchor(
            _id,
            linked ? HuiHitboxAnchor.button : HuiHitboxAnchor.menu,
          ),
        ),
        Field(
          label: detached
              ? huiText('From the menu centre')
              : huiText('From the component'),
          forId: 'component-hitbox-offset',
          tip: huiText('Right, up and forward in blocks at uiScale 1.'),
          problems: _issuesFor('.hitbox.offset'),
          child: Vec3Field(
            id: 'component-hitbox-offset',
            label: huiText('Hitbox'),
            value: hitbox?.offset ?? Vec3.zero(),
            onChanged: (Vec3 value) => store.setHitboxOffset(_id, value),
          ),
        ),
        SwitchField(
          id: 'component-hitbox-custom',
          label: huiText('Custom size'),
          value: custom,
          docKey: 'button.hitbox',
          onChanged: (bool enabled) => store.editComponent(
            _id,
            enabled ? 'enable custom hitbox' : 'use automatic hitbox',
            (HuiComponent edited) {
              final HuiComponentData editedData = edited.data;
              final HuiHitbox? existing = switch (editedData) {
                HuiButtonData(:final HuiHitbox? hitbox) => hitbox,
                HuiToggleData(:final HuiHitbox? hitbox) => hitbox,
                HuiDecorationData() || HuiRuntimeComponentData() => null,
              };
              final HuiHitbox editedHitbox = existing ?? HuiHitbox();
              editedHitbox
                ..width = enabled ? huiDefaultHitboxWidth : null
                ..height = enabled ? huiDefaultHitboxHeight : null;
              final HuiHitbox? next = editedHitbox.isDefault
                  ? null
                  : editedHitbox;
              switch (editedData) {
                case HuiButtonData():
                  editedData.hitbox = next;
                case HuiToggleData():
                  editedData.hitbox = next;
                case HuiDecorationData() || HuiRuntimeComponentData():
                  break;
              }
            },
          ),
        ),
        if (custom) ...<Widget>[
          // A new custom hitbox is born at these sizes, so they are the
          // ones worth getting back to.
          _hitboxSize(
            huiText('Width'),
            'width',
            hitbox!.width!,
            huiDefaultHitboxWidth,
            (HuiHitbox edited, double value) => edited.width = value,
          ),
          _hitboxSize(
            huiText('Height'),
            'height',
            hitbox.height!,
            huiDefaultHitboxHeight,
            (HuiHitbox edited, double value) => edited.height = value,
          ),
        ],
      ],
    );
  }

  Widget _hitboxSize(
    String label,
    String key,
    double value,
    double fallback,
    void Function(HuiHitbox hitbox, double value) write,
  ) {
    final String id = 'component-hitbox-$key';
    return Field(
      label: label,
      forId: id,
      required: true,
      defaultValue: '$fallback',
      onReset: value == fallback
          ? null
          : () => _editHitbox(
              'hitbox $key',
              (HuiHitbox edited) => write(edited, fallback),
            ),
      problems: _issuesFor('.hitbox.$key'),
      child: NumberField(
        id: id,
        value: value,
        min: 0.01,
        step: 0.05,
        suffix: huiText('blocks'),
        onChanged: (double next) => _editHitbox(
          'hitbox $key',
          (HuiHitbox edited) => write(edited, next),
        ),
      ),
    );
  }

  void _editHitbox(String label, void Function(HuiHitbox hitbox) edit) {
    store.editComponent(_id, label, (HuiComponent edited) {
      final HuiComponentData data = edited.data;
      final HuiHitbox? hitbox = switch (data) {
        HuiButtonData(:final HuiHitbox? hitbox) => hitbox,
        HuiToggleData(:final HuiHitbox? hitbox) => hitbox,
        HuiDecorationData() || HuiRuntimeComponentData() => null,
      };
      if (hitbox != null) edit(hitbox);
    });
  }

  Widget _iconEditor(IconSlot slot) => IconEditor(
    store: store,
    images: images,
    catalogs: catalogs,
    catalogsLoading: catalogsLoading,
    session: session,
    componentId: _id,
    slot: slot,
    icon: readIconSlot(target.data, slot),
    issues: _issues,
  );

  void _setCondition(String value) =>
      store.editComponent(_id, 'toggle condition', (HuiComponent edited) {
        final HuiComponentData data = edited.data;
        if (data is HuiToggleData) data.condition = value;
      });

  Widget _condition(BuildContext context, HuiToggleData toggle) {
    final ComponentSize size = controlSize(DensityScope.of(context));
    final List<(String, String)> placeholders = catalogs.placeholders;
    return FieldGroup(
      title: huiText('Condition'),
      tip: <String>[
        huiText('Decides which icon the toggle starts on.'),
        huiText(
          'The condition is read once at open and never again. After that '
          'the state only changes when a player clicks. Placeholders '
          'outside Gloss need PlaceholderAPI installed; without it the '
          'string is compared literally.',
        ),
        huiText(
          'String equality is the only comparison in the format. There '
          'are no operators, no ranges and no visibility conditions on '
          'other component types.',
        ),
      ].join(' '),
      children: <Widget>[
        Field(
          label: huiText('Placeholder'),
          forId: 'component-toggle-condition',
          required: true,
          docKey: 'toggle.condition',
          problems: _issuesFor('.condition'),
          // Picking one replaces the condition outright: it is compared
          // whole, so there is nothing to append to.
          trailing: placeholders.isEmpty
              ? null
              : AppMenu(
                  icon: ArcaneIcon.hash(),
                  iconLabel: huiText('Placeholders'),
                  alignEnd: true,
                  entries: <AppMenuEntry>[
                    for (final (String token, String about) in placeholders)
                      AppMenuEntry(
                        token,
                        () => _setCondition(token),
                        description: about,
                      ),
                  ],
                ),
          child: TextInput(
            id: 'component-toggle-condition',
            value: toggle.condition,
            size: size,
            fullWidth: true,
            placeholder: huiText('%essentials_fly%'),
            onChanged: _setCondition,
            attributes: technicalInputAttributes,
            styles: technicalInputStyles,
          ),
        ),
        Field(
          label: huiText('Expected value'),
          forId: 'component-toggle-expected',
          required: true,
          docKey: 'toggle.expectedValue',
          problems: _issuesFor('.expectedValue'),
          child: TextInput(
            id: 'component-toggle-expected',
            value: toggle.expectedValue,
            size: size,
            fullWidth: true,
            placeholder: huiText('true'),
            onChanged: (String value) => store.editComponent(
              _id,
              'toggle expected value',
              (HuiComponent edited) {
                final HuiComponentData data = edited.data;
                if (data is HuiToggleData) data.expectedValue = value;
              },
            ),
            attributes: technicalInputAttributes,
            styles: technicalInputStyles,
          ),
        ),
      ],
    );
  }

  /// Both icons at once: they are built together at open
  /// (`ToggleComponent.java:43-44`), so an editor that only showed the
  /// previewed one hid half of what the menu has to load. The switch on the
  /// heading only picks which one the canvas draws.
  Widget _toggleIcons() {
    final bool showTrue = store.togglePreviewFor(_id);
    return AppSection(
      title: huiText('Icons'),
      memoryKey: 'component.toggleIcons',
      tip: huiText(
        'Both are built at open, so a broken one breaks the '
        'open even if it is never shown.',
      ),
      actions: <Widget>[
        ToggleChoice<bool>(
          label: huiText('Preview'),
          value: showTrue,
          onChanged: (bool value) => store.setTogglePreview(_id, value),
          options: <ChoiceOption<bool>>[
            ChoiceOption<bool>(
              true,
              huiText('True'),
              tip: huiText('Preview the true icon on the canvas.'),
            ),
            ChoiceOption<bool>(
              false,
              huiText('False'),
              tip: huiText('Preview the false icon on the canvas.'),
            ),
          ],
        ),
        AppMenu(
          icon: ArcaneIcon.ellipsis(),
          alignEnd: true,
          entries: <AppMenuEntry>[
            AppMenuEntry.heading(huiText('Copy icon')),
            AppMenuEntry(
              huiText('True to false'),
              () => _copyIcon(from: IconSlot.trueIcon, to: IconSlot.falseIcon),
              icon: ArcaneIcon.arrowDown(),
            ),
            AppMenuEntry(
              huiText('False to true'),
              () => _copyIcon(from: IconSlot.falseIcon, to: IconSlot.trueIcon),
              icon: ArcaneIcon.arrowUp(),
            ),
          ],
        ),
      ],
      children: <Widget>[
        _iconEditor(IconSlot.trueIcon),
        _iconEditor(IconSlot.falseIcon),
      ],
    );
  }

  /// Plain overwrite, no confirm: the toast names what happened and undo takes
  /// it back in one step.
  void _copyIcon({required IconSlot from, required IconSlot to}) {
    final String fromName = from == IconSlot.trueIcon ? 'true' : 'false';
    final String toName = to == IconSlot.trueIcon ? 'true' : 'false';
    store.editComponent(_id, 'copy $fromName icon to $toName', (
      HuiComponent edited,
    ) {
      writeIconSlot(edited.data, to, readIconSlot(edited.data, from)?.copy());
    });
    toastSuccess(
      huiText(
        "Copied the {fromName} icon onto the {toName} icon.",
        <String, Object?>{'fromName': fromName, 'toName': toName},
      ),
    );
  }

  /// Two objects carry unknown keys here, and they are not the same object: the
  /// wrapper holds `id`, `offset` and `data`, while the data holds the type's
  /// own fields. A key on the wrong one still round-trips, it just sits
  /// somewhere else in the exported JSON.
  Widget _extras() => AppSection(
    title: huiText('Extra keys'),
    // Closed until asked for: unknown keys are a round-trip guarantee, not
    // something an author edits on the way past.
    memoryKey: 'component.extras',
    open: false,
    children: <Widget>[
      ExtrasEditor(
        title: huiText('Component'),
        extras: target.extras,
        knownKeys: const <String>{'show', 'motion'},
        onChanged: (String label, Map<String, dynamic> next) =>
            store.editComponent(
              _id,
              label,
              (HuiComponent edited) => edited.extras = next,
            ),
      ),
      ExtrasEditor(
        title: huiText("{type} data", <String, Object?>{
          'type': huiText(target.data.type),
        }),
        extras: target.data.extras,
        onChanged: (String label, Map<String, dynamic> next) =>
            store.editComponent(
              _id,
              label,
              (HuiComponent edited) => edited.data.extras = next,
            ),
      ),
    ],
  );
}

/// The pane head (type, id, the `...` menu) and the id field, with live
/// duplicate detection.
class _ComponentHeader extends StatefulWidget {
  const _ComponentHeader({
    required this.store,
    required this.session,
    required this.target,
  });

  final EditorStore store;
  final InspectorSession session;
  final HuiComponent target;

  @override
  State<_ComponentHeader> createState() => _ComponentHeaderState();
}

class _ComponentHeaderState extends State<_ComponentHeader> {
  late String _draft;

  @override
  void initState() {
    super.initState();
    _draft = component.target.id;
  }

  @override
  void didUpdateWidget(_ComponentHeader oldComponent) {
    super.didUpdateWidget(oldComponent);
    // Only follow the document when the id actually changed elsewhere, so a
    // rename round trip never fights what is being typed.
    if (oldComponent.target.id != component.target.id &&
        _draft != component.target.id) {
      _draft = component.target.id;
    }
  }

  EditorStore get _store => component.store;

  String get _currentId => component.target.id;

  bool get _isDuplicate {
    final String sanitized = sanitizeComponentId(_draft);
    int count = 0;
    for (final HuiComponent other in _store.menu.components) {
      if (other.id == sanitized) count++;
    }
    return sanitized == _currentId ? count > 1 : count > 0;
  }

  void _commit() {
    final String sanitized = sanitizeComponentId(_draft);
    if (sanitized == _currentId) {
      if (_draft != sanitized) setState(() => _draft = sanitized);
      return;
    }
    component.session.renameComponent(_currentId, sanitized);
    _store.renameComponent(_currentId, sanitized);
    setState(() => _draft = sanitized);
  }

  Future<void> _copyJson() async {
    final bool copied = await copyText(
      const JsonEncoder.withIndent('  ').convert(component.target.toJson()),
    );
    if (copied) {
      toastSuccess(
        huiText('{noun} JSON copied to the clipboard', <String, Object?>{
          'noun': _typeLabel(component.target.data),
        }),
      );
    } else {
      toastError(huiText('The browser refused clipboard access.'));
    }
  }

  /// Acts at once: Undo brings the component back, and the toast offers it.
  void _delete() {
    final String id = _currentId;
    final EditorStore store = _store;
    store.deleteComponent(id);
    toastInfo(
      huiText('Deleted {id}.', <String, Object?>{'id': id}),
      action: ToastAction(
        label: huiText('Undo'),
        onPressed: () {
          store.performUndo();
        },
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final HuiComponentData data = component.target.data;
    final String tip = <String>[
      huiText(huiComponentTypeDescriptions[data.type] ?? ''),
      if (data is HuiDecorationData)
        huiText(
          'Draw-only: no hitbox, never receives a click and never leans '
          'toward the player. Use a button if you need either.',
        ),
    ].join(' ');
    return Component.fragment(<Widget>[
      PaneHeader(
        kind: _typeLabel(data),
        title: _currentId,
        tip: tip.trim().isEmpty ? null : tip,
        menuId: 'hui-component-menu',
        actions: <AppMenuEntry>[
          AppMenuEntry(
            huiText('Duplicate'),
            () => _store.duplicateComponent(_currentId),
            icon: ArcaneIcon.copy(),
          ),
          AppMenuEntry(
            huiText('Copy JSON'),
            _copyJson,
            icon: ArcaneIcon.clipboardCopy(),
          ),
          const AppMenuEntry.separator(),
          AppMenuEntry(
            huiText('Delete'),
            _delete,
            destructive: true,
            icon: ArcaneIcon.trash2(),
          ),
        ],
      ),
      Field(
        label: huiText('Id'),
        forId: huiComponentIdFieldId,
        docKey: 'component.id',
        hint: huiText('Letters, digits, dot, dash and underscore.'),
        error: _isDuplicate
            ? huiText('Another component already uses this id.')
            : null,
        child: TextInput(
          // Fixed id: a canvas double-click pulls focus here through
          // `hui-inspector-focus`, which can only address the DOM.
          id: huiComponentIdFieldId,
          value: _draft,
          size: controlSize(DensityScope.of(context)),
          fullWidth: true,
          placeholder: huiText('component-id'),
          onChanged: (String value) => setState(() => _draft = value),
          onBlur: _commit,
          onSubmitted: (String _) => _commit(),
          attributes: technicalInputAttributes,
          styles: technicalInputStyles,
        ),
      ),
    ]);
  }
}
