/// The scoreboard's expert parts: a presentation's layout (reusable
/// sections, rotating pages, the overflow and refresh policy) and the native
/// player-list and below-name objectives. The inspector mounts both in
/// Advanced only; each writes single keys under `layout` or `objectives`.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';

import '../../l10n/hui_localizations.dart';
import '../../model/gloss_scoreboard.dart';
import '../../model/gloss_scoreboard_advanced.dart';
import '../../model/json_codec.dart';
import '../../ui/action_row.dart';
import '../../ui/density.dart';
import '../../ui/duration_field.dart';
import '../../ui/duration_value.dart';
import '../../ui/field.dart';
import '../../ui/field_group.dart';
import '../../ui/icon_button.dart';
import '../../ui/section.dart';
import '../../ui/switch_field.dart';
import '../../ui/technical_input.dart';
import '../../ui/toggle_choice.dart';

typedef ScoreboardRowsBuilder = Widget Function(
  List<GlossScoreboardLine> rows,
  String path,
  void Function(void Function(List<GlossScoreboardLine>)) mutate,
);

/// An element id for the control at JSON [path]: `$.variants[0].when`
/// becomes `scoreboard-variants-0-when`.
String scoreboardDomId(String path) {
  final String slug = path
      .replaceAll(RegExp(r'[^A-Za-z0-9_-]+'), '-')
      .replaceAll(RegExp(r'^-+|-+$'), '');
  return slug.isEmpty ? 'scoreboard' : 'scoreboard-$slug';
}

class ScoreboardLayoutEditor extends StatefulWidget {
  const ScoreboardLayoutEditor({
    required this.presentation,
    required this.path,
    required this.mutate,
    required this.rowsBuilder,
    super.key,
  });
  final GlossScoreboardPresentation presentation;
  final String path;
  final void Function(void Function(GlossScoreboardPresentation)) mutate;
  final ScoreboardRowsBuilder rowsBuilder;

  @override
  State<ScoreboardLayoutEditor> createState() => _ScoreboardLayoutEditorState();
}

class _ScoreboardLayoutEditorState extends State<ScoreboardLayoutEditor> {
  final Map<String, String> _names = <String, String>{};
  static final RegExp _id = RegExp(r'^[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}$');

  /// A variant's layout sits inside its variant: it starts closed and does
  /// not share the default presentation's remembered open state.
  bool get _nested => component.path != r'$.presentation';

  String? _memory(String name) => _nested ? null : 'scoreboard.$name';

  String get _idBase => scoreboardDomId('${component.path}.layout');

  void _edit(void Function(GlossScoreboardLayout) change) =>
      component.mutate((GlossScoreboardPresentation edited) {
        final GlossScoreboardLayout layout = GlossScoreboardLayout.fromJson(
          edited.extras['layout'],
        );
        change(layout);
        edited.extras['layout'] = layout.toJson();
      });

  @override
  Widget build(BuildContext context) {
    final GlossScoreboardLayout layout;
    try {
      layout = GlossScoreboardLayout.fromJson(
        component.presentation.extras['layout'],
      );
    } on HuiFormatException {
      return ArcaneFieldDescription(
        huiText('Repair the layout in Code view to use these controls.'),
      );
    }
    return FieldStack(
      classes: const <String>['hui-scoreboard-layout'],
      children: <Widget>[
        AppSection(
          title: huiText('Reusable sections'),
          memoryKey: _memory('sections'),
          open: !_nested,
          count: layout.sections.length,
          actions: <Widget>[
            AppIconButton(
              icon: ArcaneIcon.plus(),
              label: huiText('Add section'),
              onPressed: layout.sections.length >= 64
                  ? null
                  : () => _edit(
                      (GlossScoreboardLayout next) =>
                          next.sections[_nextId(
                                'section',
                                next.sections.keys,
                              )] =
                              <GlossScoreboardLine>[],
                    ),
            ),
          ],
          children: <Widget>[
            if (layout.sections.isEmpty)
              ArcaneFieldDescription(
                huiText('No sections. A section row inserts one by name.'),
              ),
            for (final String name in layout.sections.keys)
              _section(context, layout, name),
          ],
        ),
        AppSection(
          title: huiText('Rotating pages'),
          memoryKey: _memory('pages'),
          open: !_nested,
          count: layout.pages.length,
          tip: huiText(
            'Eligible pages rotate in list order. Durations use ticks; 20 ticks is one second. With no eligible pages, the base lines remain visible.',
          ),
          actions: <Widget>[
            AppIconButton(
              icon: ArcaneIcon.plus(),
              label: huiText('Add page'),
              onPressed: layout.pages.length >= 64
                  ? null
                  : () => _edit(
                      (GlossScoreboardLayout next) => next.pages.add(
                        GlossScoreboardPage(
                          id: _nextId(
                            'page',
                            next.pages.map(
                              (GlossScoreboardPage page) => page.id,
                            ),
                          ),
                          durationTicks: 100,
                        ),
                      ),
                    ),
            ),
          ],
          children: <Widget>[
            if (layout.pages.isEmpty)
              ArcaneFieldDescription(
                huiText('No pages. The base lines stay on screen.'),
              ),
            for (int index = 0; index < layout.pages.length; index++)
              _page(context, layout, index),
          ],
        ),
        AppSection(
          title: huiText('Layout policy'),
          memoryKey: _memory('policy'),
          open: !_nested,
          tip: huiText(
            'Omitted intervals use automatic refresh. Explicit intervals accept 1–72000 ticks.',
          ),
          children: <Widget>[
            Field(
              label: huiText('Overflow'),
              tip: huiText(
                'Minecraft displays at most 15 sidebar rows. Reject overflow checks expanded rows before conditions.',
              ),
              child: ToggleChoice<String>(
                label: huiText('Overflow'),
                value: layout.overflow ?? 'truncate',
                options: <ChoiceOption<String>>[
                  ChoiceOption<String>('truncate', huiText('Truncate')),
                  ChoiceOption<String>('error', huiText('Reject overflow')),
                ],
                onChanged: (String value) => _edit(
                  (GlossScoreboardLayout next) => next.overflow = value,
                ),
              ),
            ),
            ..._interval(
              'title',
              huiText('Title'),
              layout.refresh.titleTicks,
              (GlossScoreboardRefresh next, int? value) =>
                  next.titleTicks = value,
            ),
            ..._interval(
              'text',
              huiText('Text'),
              layout.refresh.textTicks,
              (GlossScoreboardRefresh next, int? value) =>
                  next.textTicks = value,
            ),
            ..._interval(
              'value',
              huiText('Value'),
              layout.refresh.valueTicks,
              (GlossScoreboardRefresh next, int? value) =>
                  next.valueTicks = value,
            ),
          ],
        ),
      ],
    );
  }

  Widget _section(
    BuildContext context,
    GlossScoreboardLayout layout,
    String name,
  ) {
    final String proposed = _names[name] ?? name;
    final bool canRename =
        proposed != name &&
        _id.hasMatch(proposed) &&
        !layout.sections.containsKey(proposed);
    final String id = '$_idBase-section-${scoreboardDomId(name)}';
    return AppSection(
      title: name,
      classes: 'hui-scoreboard-section',
      actions: <Widget>[
        AppIconButton(
          icon: ArcaneIcon.trash2(),
          label: huiText('Delete section'),
          onPressed: () =>
              _edit((GlossScoreboardLayout next) => next.sections.remove(name)),
        ),
      ],
      children: <Widget>[
        Field(
          label: huiText('Section name'),
          forId: id,
          child: TextInput(
            id: id,
            value: proposed,
            size: controlSize(DensityScope.of(context)),
            fullWidth: true,
            onChanged: (String value) => setState(() => _names[name] = value),
            styles: technicalInputStyles,
            attributes: technicalInputAttributes,
          ),
        ),
        ActionRow(
          dense: true,
          secondary: <RowAction>[
            RowAction(
              huiText('Rename section'),
              !canRename
                  ? null
                  : () {
                      component.mutate((GlossScoreboardPresentation edited) {
                        final GlossScoreboardLayout next =
                            GlossScoreboardLayout.fromJson(
                              edited.extras['layout'],
                            );
                        next.renameSection(name, proposed, edited.lines);
                        edited.extras['layout'] = next.toJson();
                      });
                      setState(() => _names.remove(name));
                    },
            ),
          ],
        ),
        component.rowsBuilder(
          layout.sections[name]!,
          '${component.path}.layout.sections.$name',
          (void Function(List<GlossScoreboardLine>) change) => _edit(
            (GlossScoreboardLayout next) => change(next.sections[name]!),
          ),
        ),
      ],
    );
  }

  Widget _page(BuildContext context, GlossScoreboardLayout layout, int index) {
    final GlossScoreboardPage page = layout.pages[index];
    void edit(void Function(GlossScoreboardPage) change) =>
        _edit((GlossScoreboardLayout next) => change(next.pages[index]));
    final String id = '$_idBase-page-$index';
    final ComponentSize size = controlSize(DensityScope.of(context));
    return AppSection(
      title: page.id.isEmpty
          ? huiText('Page {number}', <String, Object?>{'number': index + 1})
          : page.id,
      classes: 'hui-scoreboard-page',
      actions: <Widget>[
        AppIconButton(
          icon: ArcaneIcon.arrowUp(),
          label: huiText('Move page up'),
          onPressed: index == 0 ? null : () => _movePage(index, index - 1),
        ),
        AppIconButton(
          icon: ArcaneIcon.arrowDown(),
          label: huiText('Move page down'),
          onPressed: index == layout.pages.length - 1
              ? null
              : () => _movePage(index, index + 1),
        ),
        AppIconButton(
          icon: ArcaneIcon.trash2(),
          label: huiText('Delete page'),
          onPressed: () =>
              _edit((GlossScoreboardLayout next) => next.pages.removeAt(index)),
        ),
      ],
      children: <Widget>[
        Field(
          label: huiText('Id'),
          forId: '$id-id',
          child: TextInput(
            id: '$id-id',
            value: page.id,
            size: size,
            fullWidth: true,
            onChanged: (String value) =>
                edit((GlossScoreboardPage next) => next.id = value),
            styles: technicalInputStyles,
            attributes: technicalInputAttributes,
          ),
        ),
        SwitchField(
          id: '$id-inherit-title',
          label: huiText('Inherit presentation title'),
          value: page.title == null,
          onChanged: (bool value) => edit(
            (GlossScoreboardPage next) =>
                next.title = value ? null : component.presentation.title,
          ),
        ),
        if (page.title != null)
          Field(
            label: huiText('Title'),
            forId: '$id-title',
            child: TextInput(
              id: '$id-title',
              value: page.title!,
              size: size,
              fullWidth: true,
              onChanged: (String value) =>
                  edit((GlossScoreboardPage next) => next.title = value),
              attributes: const <String, String>{
                'autocomplete': 'off',
                'spellcheck': 'false',
              },
            ),
          ),
        Field(
          label: huiText('Show condition'),
          forId: '$id-show',
          docKey: 'condition.when',
          child: TextInput(
            id: '$id-show',
            value: _conditionText(page.show),
            size: size,
            fullWidth: true,
            onChanged: (String value) => edit(
              (GlossScoreboardPage next) => next.show = _condition(value),
            ),
            styles: technicalInputStyles,
            attributes: technicalInputAttributes,
          ),
        ),
        Field(
          label: huiText('Duration'),
          forId: '$id-duration',
          child: DurationField(
            id: '$id-duration',
            value: (page.durationTicks ?? 100).toDouble(),
            unit: HuiDurationUnit.ticks,
            min: 1,
            max: 72000,
            onChanged: (double value) => edit(
              (GlossScoreboardPage next) => next.durationTicks = value.toInt(),
            ),
          ),
        ),
        component.rowsBuilder(
          page.lines,
          '${component.path}.layout.pages[$index]',
          (void Function(List<GlossScoreboardLine>) change) =>
              edit((GlossScoreboardPage next) => change(next.lines)),
        ),
      ],
    );
  }

  void _movePage(int from, int to) => _edit((GlossScoreboardLayout next) {
    next.pages.insert(to, next.pages.removeAt(from));
  });

  /// One refresh override: the switch, and its interval while it is on.
  List<Widget> _interval(
    String key,
    String label,
    int? value,
    void Function(GlossScoreboardRefresh, int?) change,
  ) => <Widget>[
    SwitchField(
      id: '$_idBase-$key-override',
      label: huiText('Override {label} interval', <String, Object?>{
        'label': label,
      }),
      value: value != null,
      onChanged: (bool enabled) => _edit(
        (GlossScoreboardLayout next) =>
            change(next.refresh, enabled ? 20 : null),
      ),
    ),
    if (value != null)
      Field(
        label: huiText('{label} ticks', <String, Object?>{'label': label}),
        forId: '$_idBase-$key-ticks',
        child: DurationField(
          id: '$_idBase-$key-ticks',
          value: value.toDouble(),
          unit: HuiDurationUnit.ticks,
          min: 1,
          max: 72000,
          onChanged: (double ticks) => _edit(
            (GlossScoreboardLayout next) => change(next.refresh, ticks.toInt()),
          ),
        ),
      ),
  ];
}

class ScoreboardObjectivesEditor extends StatelessWidget {
  const ScoreboardObjectivesEditor({
    required this.raw,
    required this.onChanged,
    super.key,
  });
  final Object? raw;
  final void Function(Map<String, Object?>) onChanged;

  void _edit(void Function(GlossScoreboardObjectives) change) {
    final GlossScoreboardObjectives next = GlossScoreboardObjectives.fromJson(
      raw,
    );
    change(next);
    onChanged(next.toJson());
  }

  @override
  Widget build(BuildContext context) {
    GlossScoreboardObjectives? objectives;
    try {
      objectives = GlossScoreboardObjectives.fromJson(raw);
    } on HuiFormatException {
      objectives = null;
    }
    return AppSection(
      title: huiText('Native objectives'),
      memoryKey: 'scoreboard.objectives',
      tip: huiText(
        'Player-list and below-name objectives are independent of sidebar variants. Preview their client appearance and slot conflicts in Minecraft.',
      ),
      children: objectives == null
          ? <Widget>[
              ArcaneFieldDescription(
                huiText(
                  'Repair native objectives in Code view to use these controls.',
                ),
              ),
            ]
          : <Widget>[
              _slot(
                context,
                huiText('Player list'),
                objectives.playerList,
                true,
              ),
              _slot(
                context,
                huiText('Below name'),
                objectives.belowName,
                false,
              ),
            ],
    );
  }

  Widget _slot(
    BuildContext context,
    String label,
    GlossScoreboardObjective? slot,
    bool playerList,
  ) {
    void edit(void Function(GlossScoreboardObjective) change) =>
        _edit((GlossScoreboardObjectives next) {
          change((playerList ? next.playerList : next.belowName)!);
        });
    final String id = playerList
        ? 'scoreboard-objective-player-list'
        : 'scoreboard-objective-below-name';
    final ComponentSize size = controlSize(DensityScope.of(context));
    return FieldOwner(
      name: label,
      child: AppSection(
        title: label,
        memoryKey: playerList
            ? 'scoreboard.objectives.playerList'
            : 'scoreboard.objectives.belowName',
        classes: 'hui-scoreboard-objective',
        children: <Widget>[
          SwitchField(
            id: '$id-enabled',
            label: huiText('Enabled'),
            value: slot != null,
            onChanged: (bool enabled) =>
                _edit((GlossScoreboardObjectives next) {
                  final GlossScoreboardObjective? value = enabled
                      ? GlossScoreboardObjective()
                      : null;
                  if (playerList) {
                    next.playerList = value;
                  } else {
                    next.belowName = value;
                  }
                }),
          ),
          if (slot != null) ...<Widget>[
            Field(
              label: huiText('Title'),
              forId: '$id-title',
              child: TextInput(
                id: '$id-title',
                value: slot.title ?? '',
                size: size,
                fullWidth: true,
                onChanged: (String value) =>
                    edit((GlossScoreboardObjective next) => next.title = value),
                attributes: const <String, String>{
                  'autocomplete': 'off',
                  'spellcheck': 'false',
                },
              ),
            ),
            Field(
              label: huiText('Numeric value'),
              forId: '$id-value',
              child: TextInput(
                id: '$id-value',
                value: slot.value ?? 'subject.health',
                size: size,
                fullWidth: true,
                onChanged: (String value) =>
                    edit((GlossScoreboardObjective next) => next.value = value),
                styles: technicalInputStyles,
                attributes: technicalInputAttributes,
              ),
            ),
            Field(
              label: huiText('Render type'),
              child: ToggleChoice<String>(
                label: huiText('Render type'),
                value: slot.renderType?.toLowerCase() ?? 'integer',
                options: <ChoiceOption<String>>[
                  ChoiceOption<String>('integer', huiText('Integer')),
                  ChoiceOption<String>('hearts', huiText('Hearts')),
                ],
                onChanged: (String value) => edit(
                  (GlossScoreboardObjective next) => next.renderType = value,
                ),
              ),
            ),
            Field(
              label: huiText('Score format'),
              child: ToggleChoice<String>(
                label: huiText('Score format'),
                value: slot.format?.trim().toLowerCase() ?? 'number',
                options: <ChoiceOption<String>>[
                  ChoiceOption<String>('number', huiText('Number')),
                  ChoiceOption<String>('blank', huiText('Blank')),
                  ChoiceOption<String>('fixed', huiText('Fixed')),
                  ChoiceOption<String>('styled', huiText('Styled')),
                ],
                onChanged: (String value) => edit(
                  (GlossScoreboardObjective next) => next.format = value,
                ),
              ),
            ),
            Field(
              label: huiText('Formatted value'),
              forId: '$id-value-text',
              child: TextInput(
                id: '$id-value-text',
                value: slot.valueText ?? '',
                size: size,
                fullWidth: true,
                onChanged: (String value) => edit(
                  (GlossScoreboardObjective next) => next.valueText = value,
                ),
                attributes: const <String, String>{
                  'autocomplete': 'off',
                  'spellcheck': 'false',
                },
              ),
            ),
            Field(
              label: huiText('Viewer condition'),
              forId: '$id-show',
              docKey: 'condition.when',
              child: TextInput(
                id: '$id-show',
                value: _conditionText(slot.show),
                size: size,
                fullWidth: true,
                onChanged: (String value) => edit(
                  (GlossScoreboardObjective next) =>
                      next.show = _condition(value),
                ),
                styles: technicalInputStyles,
                attributes: technicalInputAttributes,
              ),
            ),
            Field(
              label: huiText('Subject condition'),
              forId: '$id-subjects',
              docKey: 'condition.when',
              child: TextInput(
                id: '$id-subjects',
                value: _conditionText(slot.subjects),
                size: size,
                fullWidth: true,
                onChanged: (String value) => edit(
                  (GlossScoreboardObjective next) =>
                      next.subjects = _condition(value),
                ),
                styles: technicalInputStyles,
                attributes: technicalInputAttributes,
              ),
            ),
            Field(
              label: huiText('Refresh ticks'),
              forId: '$id-refresh',
              child: DurationField(
                id: '$id-refresh',
                value: (slot.refreshTicks ?? 20).toDouble(),
                unit: HuiDurationUnit.ticks,
                min: 1,
                max: 72000,
                onChanged: (double value) => edit(
                  (GlossScoreboardObjective next) =>
                      next.refreshTicks = value.toInt(),
                ),
              ),
            ),
            Field(
              label: huiText('Slot conflict'),
              tip: huiText(
                'Yield preserves another plugin’s objective. Override takes this display slot.',
              ),
              child: ToggleChoice<String>(
                label: huiText('Slot conflict'),
                value: slot.conflict?.toLowerCase() ?? 'yield',
                options: <ChoiceOption<String>>[
                  ChoiceOption<String>('yield', huiText('Yield')),
                  ChoiceOption<String>('override', huiText('Override')),
                ],
                onChanged: (String value) => edit(
                  (GlossScoreboardObjective next) => next.conflict = value,
                ),
              ),
            ),
          ],
        ],
      ),
    );
  }
}

String _nextId(String prefix, Iterable<String> ids) {
  int number = 1;
  while (ids.contains('$prefix-$number')) {
    number++;
  }
  return '$prefix-$number';
}

String _conditionText(Object? value) => value?.toString() ?? '';
Object? _condition(String value) => switch (value.trim()) {
  '' => null,
  'true' => true,
  'false' => false,
  _ => value,
};
