/// Inspector body for a Gloss scoreboard document: which board a viewer
/// gets, the default sidebar and its lines, the conditional variants that
/// replace it, and in Advanced the layout and native objectives.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import '../../logic/gloss_show.dart';
import '../../logic/gloss_text.dart';
import '../../logic/validation.dart';
import '../../model/model.dart';
import '../../services/catalogs.dart';
import '../../state/editor_store.dart';
import '../../ui/badge.dart';
import '../../ui/density.dart';
import '../../ui/field.dart';
import '../../ui/icon_button.dart';
import '../../ui/mode.dart';
import '../../ui/number_field.dart';
import '../../ui/pane_header.dart';
import '../../ui/reorder_list.dart';
import '../../ui/section.dart';
import '../../ui/switch_field.dart';
import '../../ui/technical_input.dart';
import '../../ui/toggle_choice.dart';
import '../gloss/gloss_text_line.dart';
import 'gloss_visibility_editor.dart';
import 'line_list_section.dart';
import 'scoreboard_advanced_editor.dart';

import 'package:gloss_editor/l10n/hui_localizations.dart';

typedef _PresentationEdit = void Function(
  void Function(GlossScoreboardPresentation),
);
typedef _RowsEdit = void Function(void Function(List<GlossScoreboardLine>));

class ScoreboardInspector extends StatefulWidget {
  const ScoreboardInspector({
    required this.store,
    required this.catalogs,
    super.key,
  });

  final EditorStore store;
  final HuiCatalogs catalogs;

  @override
  State<ScoreboardInspector> createState() => _ScoreboardInspectorState();
}

class _ScoreboardInspectorState extends State<ScoreboardInspector> {
  EditorStore get _store => component.store;

  List<HuiIssue> _issuesFor(String path) => _store.issues
      .where((HuiIssue issue) => issue.path.startsWith(path))
      .toList();

  /// Control size, read under the inspector's dense scope in [_build].
  ComponentSize _size = ComponentSize.sm;

  @override
  Widget build(BuildContext context) =>
      DensityScope.dense(child: Builder(builder: _build));

  Widget _build(BuildContext context) {
    _size = controlSize(DensityScope.of(context));
    final GlossScoreboardDoc? doc = _store.scoreboardDoc;
    if (doc == null) return const dom.div(<Widget>[]);
    void mutateDefault(void Function(GlossScoreboardPresentation) mutate) =>
        _store.mutateScoreboard(
          'scoreboard default presentation',
          (GlossScoreboardDoc edited) => mutate(edited.presentation),
        );
    return DenseBox(
      classes: 'hui-inspector-body is-scoreboard',
      children: <Widget>[
        PaneHeader(
          kind: huiText('Scoreboard'),
          title: _store.menuId,
          tip: huiText(
            'The highest-priority eligible board is selected per viewer; '
            'its highest-priority matching variant supplies the complete sidebar.',
          ),
          status: AdvancedOnly(
            child: StatusBadge(
              huiText('revision {revision}', <String, Object?>{
                'revision': doc.revision,
              }),
            ),
          ),
        ),
        GlossVisibilityEditor(
          raw: doc.extras['show'],
          sectionKey: 'scoreboard.visibility',
          issues: _store.issues
              .where((HuiIssue issue) => issue.path == r'$.show')
              .toList(),
          onChanged: (Object? value) => _store.mutateScoreboard(
            'visibility',
            (GlossScoreboardDoc edited) => setGlossShow(edited.extras, value),
          ),
        ),
        _selection(doc),
        AppSection(
          title: huiText('Default presentation'),
          memoryKey: 'scoreboard.presentation',
          children: _presentationFields(
            doc.presentation,
            r'$.presentation',
            mutateDefault,
          ),
        ),
        _linesSection(
          doc.presentation.lines,
          r'$.presentation',
          (void Function(List<GlossScoreboardLine>) change) =>
              mutateDefault((GlossScoreboardPresentation edited) {
                change(edited.lines);
              }),
        ),
        AdvancedOnly(
          child: ScoreboardLayoutEditor(
            presentation: doc.presentation,
            path: r'$.presentation',
            mutate: mutateDefault,
            rowsBuilder: _linesField,
          ),
        ),
        _variants(doc),
        AdvancedOnly(
          child: ScoreboardObjectivesEditor(
            raw: doc.extras['objectives'],
            onChanged: (Map<String, Object?> value) => _store.mutateScoreboard(
              'scoreboard native objectives',
              (GlossScoreboardDoc edited) =>
                  edited.extras['objectives'] = value,
            ),
          ),
        ),
      ],
    );
  }

  Widget _selection(GlossScoreboardDoc doc) => AppSection(
    title: huiText('Board selection'),
    memoryKey: 'scoreboard.selection',
    tip: huiText(
      'Highest priority wins; equal priorities use the smaller board id. '
      'A condition error fails closed.',
    ),
    children: <Widget>[
      _priorityField(
        id: 'scoreboard-priority',
        value: doc.select.priority,
        path: r'$.select.priority',
        onChanged: (int value) => _store.mutateScoreboard(
          'scoreboard select priority',
          (GlossScoreboardDoc edited) => edited.select.priority = value,
        ),
      ),
      _conditionField(
        id: 'scoreboard-when',
        value: doc.select.when,
        path: r'$.select.when',
        onChanged: (String value) => _store.mutateScoreboard(
          'scoreboard select condition',
          (GlossScoreboardDoc edited) => edited.select.when = value,
        ),
      ),
    ],
  );

  Widget _variants(GlossScoreboardDoc doc) => AppSection(
    title: huiText('Conditional variants'),
    memoryKey: 'scoreboard.variants',
    count: doc.variants.length,
    tip: huiText(
      'Variants are complete presentations. Matching variants never merge; '
      'the highest priority wins and the default is the fallback.',
    ),
    actions: <Widget>[
      AppIconButton(
        icon: ArcaneIcon.plus(),
        label: huiText('Add variant'),
        onPressed: () => _store.mutateScoreboard(
          'add scoreboard variant',
          (GlossScoreboardDoc edited) => edited.variants.add(
            GlossScoreboardVariant(
              id: _nextVariantId(edited),
              when: 'viewer.healthPercent <= 25',
              presentation: edited.presentation.copy(),
            ),
          ),
        ),
      ),
    ],
    children: <Widget>[
      if (doc.variants.isEmpty)
        ArcaneFieldDescription(
          huiText('No variants. Every viewer sees the default presentation.'),
        ),
      for (int index = 0; index < doc.variants.length; index++)
        _variant(doc, index),
    ],
  );

  /// One variant: its heading carries the delete, its id and priority are
  /// Advanced only, and its complete presentation follows as plain fields.
  Widget _variant(GlossScoreboardDoc doc, int index) {
    final GlossScoreboardVariant variant = doc.variants[index];
    final String path =
        r'$.variants['
        '$index]';
    final String id = scoreboardDomId(path);
    void mutate(void Function(GlossScoreboardPresentation) change) =>
        _store.mutateScoreboard(
          'scoreboard variant presentation',
          (GlossScoreboardDoc edited) =>
              change(edited.variants[index].presentation),
        );
    return AppSection(
      title: huiText('Variant {id}', <String, Object?>{
        'id': variant.id.isEmpty ? index + 1 : variant.id,
      }),
      classes: 'hui-scoreboard-variant',
      actions: <Widget>[
        AppIconButton(
          icon: ArcaneIcon.trash2(),
          label: huiText('Delete variant'),
          onPressed: () => _store.mutateScoreboard(
            'delete scoreboard variant',
            (GlossScoreboardDoc edited) => edited.variants.removeAt(index),
          ),
        ),
      ],
      children: <Widget>[
        AdvancedOnly(
          child: Field(
            label: huiText('Id'),
            forId: '$id-id',
            problems: _issuesFor('$path.id'),
            child: TextInput(
              id: '$id-id',
              value: variant.id,
              size: _size,
              fullWidth: true,
              onChanged: (String value) => _store.mutateScoreboard(
                'scoreboard variant id',
                (GlossScoreboardDoc edited) =>
                    edited.variants[index].id = value,
              ),
              styles: technicalInputStyles,
              attributes: technicalInputAttributes,
            ),
          ),
        ),
        AdvancedOnly(
          child: _priorityField(
            id: '$id-priority',
            value: variant.priority,
            path: '$path.priority',
            onChanged: (int value) => _store.mutateScoreboard(
              'scoreboard variant priority',
              (GlossScoreboardDoc edited) =>
                  edited.variants[index].priority = value,
            ),
          ),
        ),
        _conditionField(
          id: '$id-when',
          value: variant.when,
          path: '$path.when',
          onChanged: (String value) => _store.mutateScoreboard(
            'scoreboard variant condition',
            (GlossScoreboardDoc edited) => edited.variants[index].when = value,
          ),
        ),
        ..._presentationFields(
          variant.presentation,
          '$path.presentation',
          mutate,
        ),
        _linesField(
          variant.presentation.lines,
          '$path.presentation',
          (void Function(List<GlossScoreboardLine>) change) =>
              mutate((GlossScoreboardPresentation edited) {
                change(edited.lines);
              }),
        ),
        AdvancedOnly(
          child: ScoreboardLayoutEditor(
            presentation: variant.presentation,
            path: '$path.presentation',
            mutate: mutate,
            rowsBuilder: _linesField,
          ),
        ),
      ],
    );
  }

  /// The title (with its rendered preview) and the score-number switch of
  /// one presentation; its lines are drawn by the caller.
  List<Widget> _presentationFields(
    GlossScoreboardPresentation presentation,
    String path,
    _PresentationEdit mutate,
  ) {
    final String id = scoreboardDomId(path);
    return <Widget>[
      Field(
        label: huiText('Title'),
        forId: '$id-title',
        docKey: 'scoreboard.presentation.title',
        problems: _issuesFor('$path.title'),
        // The same input-over-game-sample pair as a line row.
        child: dom.div(classes: 'hui-line-row', <Widget>[
          TextInput(
            id: '$id-title',
            value: presentation.title,
            size: _size,
            fullWidth: true,
            placeholder: huiText('&d&lGloss'),
            onChanged: (String value) =>
                mutate((GlossScoreboardPresentation edited) {
                  edited.title = value;
                }),
            attributes: const <String, String>{
              'autocomplete': 'off',
              'spellcheck': 'false',
            },
          ),
          dom.div(classes: 'hui-line-row-preview', <Widget>[
            GlossTextLine(
              render: renderGlossScoreboardTitle(
                presentation.title,
                animations: _store.workspaceAnimations,
                emoji: _store.workspaceEmoji,
              ),
            ),
          ]),
        ]),
      ),
      SwitchField(
        id: '$id-hide-numbers',
        label: huiText('Hide score numbers'),
        docKey: 'scoreboard.presentation.hideNumbers',
        value: presentation.hideNumbers,
        onChanged: (bool value) => mutate((GlossScoreboardPresentation edited) {
          edited.hideNumbers = value;
        }),
      ),
    ];
  }

  /// The default presentation's lines: their own section in the pane.
  Widget _linesSection(
    List<GlossScoreboardLine> rows,
    String path,
    _RowsEdit mutate,
  ) => LineListSection(
    title: huiText('Lines'),
    docKey: 'scoreboard.presentation.lines',
    sectionKey: 'scoreboard.lines',
    addLabel: huiText('Add line'),
    itemCount: rows.length,
    issues: _issuesFor('$path.lines'),
    emptyBody: huiText('Add a line to put content under the title.'),
    onAdd: () => mutate(
      (List<GlossScoreboardLine> edited) => edited.add(GlossScoreboardLine()),
    ),
    onReorder: (int from, int to) => mutate((List<GlossScoreboardLine> edited) {
      edited.insert(to, edited.removeAt(from));
    }),
    itemBuilder: (int index) => _rowAt(rows, index, path, mutate),
  );

  /// Lines inside an item (a variant, a reusable section, a page): one field
  /// of that item, so the pane never nests a section in a section's item.
  Widget _linesField(
    List<GlossScoreboardLine> rows,
    String path,
    _RowsEdit mutate,
  ) => Field(
    label: huiText('Lines'),
    docKey: 'scoreboard.presentation.lines',
    problems: _issuesFor('$path.lines'),
    trailing: AppIconButton(
      icon: ArcaneIcon.plus(),
      label: huiText('Add line'),
      onPressed: () => mutate(
        (List<GlossScoreboardLine> edited) => edited.add(GlossScoreboardLine()),
      ),
    ),
    child: rows.isEmpty
        ? ArcaneFieldDescription(
            huiText('Add a line to put content under the title.'),
          )
        : ReorderList(
            label: huiText('Lines'),
            itemCount: rows.length,
            onReorder: (int from, int to) =>
                mutate((List<GlossScoreboardLine> edited) {
                  edited.insert(to, edited.removeAt(from));
                }),
            itemBuilder: (int index) => _rowAt(rows, index, path, mutate),
            inlineTools: true,
          ),
  );

  Widget _rowAt(
    List<GlossScoreboardLine> rows,
    int index,
    String path,
    _RowsEdit mutate,
  ) => _line(
    rows[index],
    index,
    '${scoreboardDomId('$path.lines')}-$index',
    (void Function(GlossScoreboardLine) change) =>
        mutate((List<GlossScoreboardLine> edited) => change(edited[index])),
    () => mutate((List<GlossScoreboardLine> edited) => edited.removeAt(index)),
  );

  Widget _priorityField({
    required String id,
    required int value,
    required String path,
    required void Function(int) onChanged,
  }) => Field(
    label: huiText('Priority'),
    forId: id,
    problems: _issuesFor(path),
    child: NumberField(
      id: id,
      value: value.toDouble(),
      integer: true,
      step: 1,
      onChanged: (double next) => onChanged(next.round()),
    ),
  );

  Widget _conditionField({
    required String id,
    required String value,
    required String path,
    required void Function(String) onChanged,
  }) => Field(
    label: huiText('When'),
    forId: id,
    docKey: 'condition.when',
    problems: _issuesFor(path),
    child: TextInput(
      id: id,
      value: value,
      size: _size,
      fullWidth: true,
      placeholder: huiText("viewer.world == 'world'"),
      onChanged: onChanged,
      styles: technicalInputStyles,
      attributes: technicalInputAttributes,
    ),
  );

  String _nextVariantId(GlossScoreboardDoc doc) {
    int suffix = doc.variants.length + 1;
    String id = 'variant-$suffix';
    while (doc.variants.any((GlossScoreboardVariant item) => item.id == id)) {
      suffix++;
      id = 'variant-$suffix';
    }
    return id;
  }

  /// One row: the text (or section name) with its preview, then in
  /// Advanced the row's own keys in a closed "Row settings" group.
  Widget _line(
    GlossScoreboardLine line,
    int index,
    String id,
    void Function(void Function(GlossScoreboardLine)) mutate,
    void Function() remove,
  ) => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    mainAxisSize: MainAxisSize.min,
    spacing: 8,
    children: <Widget>[
      LineRow(
        value: line.section ?? line.text,
        placeholder: line.section == null
            ? huiText('&fText or {{ expression }}')
            : huiText('Section name'),
        label: huiText('Line {number}', <String, Object?>{'number': index + 1}),
        removeLabel: huiText('Delete line {number}', <String, Object?>{
          'number': index + 1,
        }),
        onChanged: (String value) => mutate((GlossScoreboardLine edited) {
          if (edited.section == null) {
            edited.text = value;
          } else {
            edited.section = value;
          }
        }),
        preview: line.section == null
            ? GlossTextLine(
                render: renderGlossScoreboardLine(
                  line.text,
                  animations: _store.workspaceAnimations,
                  emoji: _store.workspaceEmoji,
                ),
              )
            : Text(
                huiText('Reusable section: {name}', <String, Object?>{
                  'name': line.section,
                }),
              ),
        onRemove: remove,
      ),
      // The next line starts below the open settings, not against them.
      AdvancedOnly(
        child: Padding(
          padding: const EdgeInsets.only(bottom: 12),
          child: MoreFields(
            label: huiText('Row settings'),
            children: <Widget>[
              Field(
                label: huiText('Row type'),
                child: ToggleChoice<String>(
                  label: huiText('Row type'),
                  value: line.section == null ? 'text' : 'section',
                  options: <ChoiceOption<String>>[
                    ChoiceOption<String>('text', huiText('Text')),
                    ChoiceOption<String>(
                      'section',
                      huiText('Section reference'),
                    ),
                  ],
                  onChanged: (String value) =>
                      mutate((GlossScoreboardLine edited) {
                        edited.section = value == 'section' ? '' : null;
                        if (value == 'section') {
                          edited.text = '';
                          edited.value = null;
                          edited.format = null;
                          edited.id = null;
                        }
                      }),
                ),
              ),
              if (line.section == null) ...<Widget>[
                _rowText(
                  id: '$id-id',
                  label: huiText('Row ID'),
                  value: line.id ?? '',
                  onChanged: (String value) => mutate(
                    (GlossScoreboardLine edited) =>
                        edited.id = value.isEmpty ? null : value,
                  ),
                ),
                _rowText(
                  id: '$id-value',
                  label: huiText('Value'),
                  value: line.value ?? '',
                  onChanged: (String value) => mutate(
                    (GlossScoreboardLine edited) =>
                        edited.value = value.isEmpty ? null : value,
                  ),
                ),
                Field(
                  label: huiText('Score format'),
                  forId: '$id-format',
                  child: ArcaneSelect(
                    id: '$id-format',
                    value: line.format ?? '',
                    size: _size,
                    fullWidth: true,
                    options: <ArcaneSelectOption>[
                      ArcaneSelectOption(
                        value: '',
                        label: huiText('Automatic'),
                      ),
                      ArcaneSelectOption(
                        value: 'number',
                        label: huiText('Number'),
                      ),
                      ArcaneSelectOption(
                        value: 'blank',
                        label: huiText('Blank'),
                      ),
                      ArcaneSelectOption(
                        value: 'fixed',
                        label: huiText('Fixed'),
                      ),
                      ArcaneSelectOption(
                        value: 'styled',
                        label: huiText('Styled'),
                      ),
                    ],
                    onChanged: (String value) => mutate(
                      (GlossScoreboardLine edited) =>
                          edited.format = value.isEmpty ? null : value,
                    ),
                  ),
                ),
              ],
              _rowText(
                id: '$id-show',
                label: huiText('Show condition'),
                value: line.show.toString(),
                onChanged: (String value) => mutate(
                  (GlossScoreboardLine edited) =>
                      edited.show = value.isEmpty || value == 'true'
                      ? true
                      : value == 'false'
                      ? false
                      : value,
                ),
              ),
            ],
          ),
        ),
      ),
    ],
  );

  /// A row key: ids, values and conditions are all code, so left to right.
  Widget _rowText({
    required String id,
    required String label,
    required String value,
    required void Function(String) onChanged,
  }) => Field(
    label: label,
    forId: id,
    child: TextInput(
      id: id,
      value: value,
      size: _size,
      fullWidth: true,
      onChanged: onChanged,
      styles: technicalInputStyles,
      attributes: technicalInputAttributes,
    ),
  );
}
