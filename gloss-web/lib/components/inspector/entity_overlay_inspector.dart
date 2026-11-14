/// Inspector body for the entity overlays document: the stacked lines with the
/// selected line's editor, health and placement, visibility, server limits,
/// the shared presentation editors and, in Advanced, the raw health bar and
/// conditional variant JSON.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import '../../l10n/hui_localizations.dart';
import '../../logic/entity_overlay_preview.dart';
import '../../logic/gloss_text.dart' show GlossAnimationResolver;
import '../../logic/validation.dart';
import '../../model/gloss_animation.dart';
import '../../model/gloss_entity_overlays.dart';
import '../../model/gloss_health_bar.dart';
import '../../model/gloss_presentation_variant.dart';
import '../../model/gloss_hologram_box.dart';
import '../../model/hui_icons.dart';
import '../../model/particle_layer.dart';
import '../../state/editor_store.dart';
import '../../ui/action_row.dart';
import '../../ui/badge.dart';
import '../../ui/density.dart';
import '../../ui/field.dart';
import '../../ui/list_row.dart';
import '../../ui/menu.dart';
import '../../ui/mode.dart';
import '../../ui/number_field.dart';
import '../../ui/pane_header.dart';
import '../../ui/reorder_list.dart';
import '../../ui/section.dart';
import '../../ui/switch_field.dart';
import '../../ui/toggle_choice.dart';
import '../../ui/technical_input.dart';
import 'display_style_editor.dart';
import 'extras_editor.dart';
import 'hologram_box_editor.dart';
import 'particle_layers_editor.dart';
import 'preview_expr_field.dart';
import 'text_icon_editor.dart';

/// The `{...}` values an entity overlay line can show.
const List<String> _entityValues = <String>[
  '{name}',
  '{bar}',
  '{health}',
  '{max_health}',
  '{count}',
  '{damage}',
  '{attack}',
  '{armor}',
  '{insight}',
  '{type}',
  '{distance}',
];

/// The most lines a document may hold.
const int _maxLines = 64;

class EntityOverlayInspector extends StatefulWidget {
  const EntityOverlayInspector({required this.store, super.key});
  final EditorStore store;

  @override
  State<EntityOverlayInspector> createState() => _EntityOverlayInspectorState();
}

class _EntityOverlayInspectorState extends State<EntityOverlayInspector> {
  int _selected = 0;
  EditorStore get _store => component.store;
  GlossEntityOverlaysDoc? get _doc => _store.entityOverlaysDoc;

  /// The inspector pane is dense; its text inputs match the kit fields.
  final ComponentSize _size = controlSize(Density.dense);

  @override
  Widget build(BuildContext context) {
    final GlossEntityOverlaysDoc? doc = _doc;
    if (doc == null) return const dom.div(<Widget>[]);
    return DenseBox(
      classes: 'hui-inspector-body is-entity-overlays',
      children: <Widget>[
        PaneHeader(
          kind: huiText('Entity overlays'),
          title: _store.menuId,
          status: AdvancedOnly(
            child: StatusBadge(
              huiText('revision {revision}', <String, Object?>{
                'revision': doc.revision,
              }),
            ),
          ),
        ),
        _lines(doc),
        AppSection(
          title: huiText('Health and placement'),
          memoryKey: 'entity-overlays.health',
          children: <Widget>[
            _number(
              'Vertical offset',
              'verticalOffset',
              doc.verticalOffset,
              'Blocks above the entity bounds. -2..8.',
              (GlossEntityOverlaysDoc edited, double value) =>
                  edited.verticalOffset = value,
            ),
            _number(
              'Health segments',
              'healthSegments',
              doc.healthSegments,
              'Number of segments in the health bar. 1..40.',
              (GlossEntityOverlaysDoc edited, double value) =>
                  edited.healthSegments = value.round(),
              integer: true,
            ),
            _number(
              'Hit highlight duration',
              'hitHighlightMs',
              doc.hitHighlightMs,
              'Milliseconds to show lost health segments and damage. 0..10000.',
              (GlossEntityOverlaysDoc edited, double value) =>
                  edited.hitHighlightMs = value.round(),
              integer: true,
            ),
          ],
        ),
        AppSection(
          title: huiText('Visibility'),
          memoryKey: 'entity-overlays.visibility',
          children: <Widget>[
            _toggle(
              'Enabled',
              'enabled',
              doc.enabled,
              (GlossEntityOverlaysDoc edited, bool value) =>
                  edited.enabled = value,
            ),
            AdvancedOnly(
              child: PreviewExprField(
                label: huiText('Pane visibility'),
                raw: doc.show,
                kind: PreviewExprKind.boolean,
                showCondition: true,
                categoryVariableNames: entityOverlayVariables,
                scope: entityOverlayVariables.toSet(),
                issues: _issues(r'$.show'),
                onChanged: (Object? value) => _store.mutateEntityOverlays(
                  'pane visibility',
                  (GlossEntityOverlaysDoc edited) =>
                      edited.show = value ?? true,
                ),
              ),
            ),
            _toggle(
              'Include players',
              'includePlayers',
              doc.includePlayers,
              (GlossEntityOverlaysDoc edited, bool value) =>
                  edited.includePlayers = value,
            ),
            _toggle(
              'Override mob nametags',
              'overrideNametag',
              doc.overrideNametag,
              (GlossEntityOverlaysDoc edited, bool value) =>
                  edited.overrideNametag = value,
            ),
            _number(
              'Display range',
              'range',
              doc.range,
              'Nearby range in blocks. 1..64.',
              (GlossEntityOverlaysDoc edited, double value) =>
                  edited.range = value,
            ),
            _text(
              'Excluded worlds',
              doc.blacklistWorlds.join(', '),
              'World names, separated by commas.',
              (GlossEntityOverlaysDoc edited, String value) =>
                  edited.blacklistWorlds = _list(value),
              path: 'blacklistWorlds',
            ),
            _text(
              'Excluded entity types',
              doc.excludedEntityTypes.join(', '),
              'Bukkit entity types, separated by commas.',
              (GlossEntityOverlaysDoc edited, String value) =>
                  edited.excludedEntityTypes = _list(value)
                      .map((String type) => type.toUpperCase())
                      .toList(),
              path: 'excludedEntityTypes',
            ),
          ],
        ),
        AppSection(
          title: huiText('Limits'),
          memoryKey: 'entity-overlays.limits',
          open: false,
          children: <Widget>[
            _number(
              'Update interval',
              'updateIntervalTicks',
              doc.updateIntervalTicks,
              'Ticks between entity updates. 1..40.',
              (GlossEntityOverlaysDoc edited, double value) =>
                  edited.updateIntervalTicks = value.round(),
              integer: true,
            ),
            _number(
              'Maximum entities per viewer',
              'maxEntitiesPerViewer',
              doc.maxEntitiesPerViewer,
              'Maximum simultaneous overlays for each viewer. 1..256.',
              (GlossEntityOverlaysDoc edited, double value) =>
                  edited.maxEntitiesPerViewer = value.round(),
              integer: true,
            ),
            _number(
              'Snapshot read limit',
              'snapshotReadLimit',
              doc.snapshotReadLimit,
              'Distinct captured reads per entity. 16..65536.',
              (GlossEntityOverlaysDoc edited, double value) =>
                  edited.snapshotReadLimit = value.round(),
              integer: true,
            ),
            _number(
              'Maximum active overlays',
              'maxActiveOverlays',
              doc.maxActiveOverlays,
              'Maximum overlays tracked at once across every viewer. 16..16384.',
              (GlossEntityOverlaysDoc edited, double value) =>
                  edited.maxActiveOverlays = value.round(),
              integer: true,
            ),
          ],
        ),
        _box(doc),
        DisplayStyleEditor(
          style: doc.style,
          defaults: defaultEntityOverlayStyle(),
          entityOverlay: true,
          issues: _issues(r'$.style'),
          onChanged: (String label, HuiIconStyle? next) =>
              _store.mutateEntityOverlays(
                label,
                (GlossEntityOverlaysDoc edited) =>
                    edited.style = next ?? defaultEntityOverlayStyle(),
              ),
        ),
        ParticleLayersEditor(
          layers: doc.particleLayers,
          sectionKey: 'entity-overlays.particles',
          mutate:
              (String label, void Function(List<GlossParticleLayer>) edit) =>
                  _store.mutateEntityOverlays(
                    label,
                    (GlossEntityOverlaysDoc edited) =>
                        edit(edited.particleLayers),
                  ),
        ),
        AdvancedOnly(
          child: ExtrasEditor(
            title: huiText('Health bar'),
            extensionKeys: false,
            extras: doc.healthBar.toJson(),
            onChanged: (String label, Map<String, dynamic> next) => _store
                .mutateEntityOverlays(label, (GlossEntityOverlaysDoc edited) {
                  edited.healthBar = GlossHealthBar.fromJson(next);
                }),
          ),
        ),
        AdvancedOnly(
          child: ExtrasEditor(
            title: huiText('Conditional variants'),
            extensionKeys: false,
            extras: <String, Object?>{
              'variants': doc.variants
                  .map((GlossPresentationVariant variant) => variant.toJson())
                  .toList(),
            },
            onChanged: (String label, Map<String, dynamic> next) =>
                _store.replaceGlossDoc(
                  label,
                  GlossEntityOverlaysDoc.fromJson(<String, Object?>{
                    ...doc.toJson(),
                    ...next,
                  }),
                ),
          ),
        ),
      ],
    );
  }

  Widget _lines(GlossEntityOverlaysDoc doc) {
    final bool full = doc.lines.length >= _maxLines;
    final int selected = doc.lines.isEmpty
        ? 0
        : _selected.clamp(0, doc.lines.length - 1);
    // The open line's problems show at its fields; the rest show here.
    final List<HuiIssue> listIssues = distinctIssues(
      _issues(r'$.lines')
          .where(
            (HuiIssue issue) => !issue.path.startsWith('\$.lines[$selected].'),
          )
          .toList(),
    );
    return AppSection(
      title: huiText('Lines'),
      memoryKey: 'entity-overlays.lines',
      count: doc.lines.length,
      tip: huiText(
        'React supplies mob stack counts automatically. Adapt Discovery Insight adds details to its selected entity. Adapt can restrict the global overlays to active Insight targets through its own adaptation config.',
      ),
      children: <Widget>[
        // Least used first: the row folds from the start into its menu.
        ActionRow(
          dense: true,
          label: huiText('Add line'),
          secondary: <RowAction>[
            RowAction(
              huiText('Add spacer'),
              full ? null : () => _addLine('spacer'),
            ),
            RowAction(
              huiText('Add Insight'),
              full ? null : () => _addLine('insight'),
            ),
            RowAction(
              huiText('Add text'),
              full ? null : () => _addLine('text'),
            ),
          ],
        ),
        if (doc.lines.isEmpty)
          ArcaneFieldDescription(
            huiText('No lines are visible for this sample.'),
          )
        else ...<Widget>[
          ReorderList(
            label: huiText('Lines'),
            itemCount: doc.lines.length,
            onReorder: _moveLine,
            onRemove: _removeLine,
            itemBuilder: (int index) =>
                _lineRow(doc.lines[index], index, selected: index == selected),
          ),
          ..._lineEditor(doc, selected),
        ],
        for (final HuiIssue issue in listIssues) issueNote(issue),
      ],
    );
  }

  /// A line's row: picking it opens its editor under the list.
  Widget _lineRow(
    GlossEntityOverlayLine line,
    int index, {
    required bool selected,
  }) => ListRow(
    label: '${index + 1}. ${line.id}',
    selected: selected,
    onSelect: () => setState(() => _selected = index),
  );

  List<Widget> _lineEditor(GlossEntityOverlaysDoc doc, int index) {
    final GlossEntityOverlayLine line = doc.lines[index];
    final String id = 'entity-line-$index';
    return <Widget>[
      Field(
        key: ValueKey<String>('$id-id'),
        label: huiText('Line id'),
        forId: '$id-id',
        problems: _issues('\$.lines[$index].id'),
        child: TextInput(
          id: '$id-id',
          value: line.id,
          size: _size,
          fullWidth: true,
          attributes: technicalInputAttributes,
          onChanged: (String value) => _editLine(
            index,
            'line id',
            (GlossEntityOverlayLine edited) => edited.id = value,
          ),
        ),
      ),
      Field(
        key: ValueKey<String>('$id-type'),
        label: huiText('Line type'),
        problems: _issues('\$.lines[$index].type'),
        child: ToggleChoice<String>(
          label: huiText('Line type'),
          value: line.type,
          options: <ChoiceOption<String>>[
            ChoiceOption<String>('text', huiText('Text')),
            ChoiceOption<String>(
              'insight',
              huiText('Insight'),
              tip: huiText(
                'This line repeats once for each detail supplied by Adapt Insight.',
              ),
            ),
            ChoiceOption<String>('spacer', huiText('Spacer')),
          ],
          onChanged: (String value) =>
              _editLine(index, 'line type', (GlossEntityOverlayLine edited) {
                edited.type = value;
                if (value == 'insight' && edited.text.isEmpty) {
                  edited.text = '{insight}';
                }
              }),
        ),
      ),
      AdvancedOnly(
        child: PreviewExprField(
          key: ValueKey<String>('$id-show'),
          label: huiText('Line visibility'),
          raw: line.show,
          kind: PreviewExprKind.boolean,
          showCondition: true,
          categoryVariableNames: entityOverlayVariables,
          scope: entityOverlayVariables.toSet(),
          issues: _issues('\$.lines[$index].show'),
          onChanged: (Object? value) => _editLine(
            index,
            'line visibility',
            (GlossEntityOverlayLine edited) => edited.show = value ?? true,
          ),
        ),
      ),
      if (line.type != 'spacer') ...<Widget>[
        TextIconEditor(
          key: ValueKey<String>('$id-text'),
          fieldId: '$id-text',
          text: line.text,
          emoji: _store.workspaceEmoji,
          label: huiText('Line text'),
          issues: _issues('\$.lines[$index].text'),
          onChanged: (String label, String value) => _editLine(
            index,
            label,
            (GlossEntityOverlayLine edited) => edited.text = value,
          ),
        ),
        ActionRow(
          dense: true,
          leading: <Widget>[
            AppMenu(
              label: huiText('Insert entity value'),
              outline: true,
              entries: <AppMenuEntry>[
                for (final String token in _entityValues)
                  AppMenuEntry(token, () => _append(index, token)),
              ],
            ),
            ?_animationMenu(index),
          ],
        ),
      ],
    ];
  }

  /// The workspace's animations as `|animation.<id>|` insertions; none when
  /// the workspace has no animation documents.
  Widget? _animationMenu(int index) {
    final GlossAnimationResolver animations = _store.workspaceAnimations;
    if (animations.ids.isEmpty) return null;
    return AppMenu(
      label: huiText('Animations'),
      icon: ArcaneIcon.film(),
      outline: true,
      entries: <AppMenuEntry>[
        for (final String animationId in animations.ids)
          AppMenuEntry(
            huiText('|animation.{id}|', <String, Object?>{'id': animationId}),
            () => _append(index, '|animation.$animationId|'),
            description: _animationSummary(animations.byId(animationId)),
          ),
      ],
    );
  }

  static String _animationSummary(GlossAnimationDoc? doc) => doc == null
      ? huiText('unreadable document')
      : huiPlural(
          'animation.reference.frame_summary',
          doc.frames.length,
          oneEnglish: '{count} frame · {interval} ms · {mode}',
          otherEnglish: '{count} frames · {interval} ms · {mode}',
          arguments: <String, Object?>{
            'interval': doc.effectiveFrameIntervalMs,
            'mode': doc.normalizedMode ?? doc.mode,
          },
        );

  void _append(int index, String value) => _editLine(
    index,
    value.startsWith('|animation.')
        ? 'insert animation'
        : 'insert entity value',
    (GlossEntityOverlayLine edited) => edited.text += value,
  );

  Widget _box(GlossEntityOverlaysDoc doc) => HologramBoxEditor(
    box: doc.box,
    sectionKey: 'entity-overlays.box',
    issues: _issues(r'$.box'),
    mutate: (String label, void Function(GlossHologramBox) edit) =>
        _store.mutateEntityOverlays(
          label,
          (GlossEntityOverlaysDoc edited) => edit(edited.box),
        ),
  );

  void _addLine(String type) {
    final GlossEntityOverlaysDoc? doc = _doc;
    if (doc == null) return;
    int suffix = doc.lines.length + 1;
    while (doc.lines.any(
      (GlossEntityOverlayLine line) => line.id == 'line-$suffix',
    )) {
      suffix++;
    }
    _store.mutateEntityOverlays(
      'add line',
      (GlossEntityOverlaysDoc edited) => edited.lines.add(
        GlossEntityOverlayLine(
          id: 'line-$suffix',
          type: type,
          text: type == 'insight' ? '{insight}' : '',
        ),
      ),
    );
    setState(() => _selected = doc.lines.length - 1);
  }

  void _moveLine(int from, int to) {
    _store.mutateEntityOverlays('reorder line', (
      GlossEntityOverlaysDoc edited,
    ) {
      final GlossEntityOverlayLine moved = edited.lines.removeAt(from);
      edited.lines.insert(to, moved);
    });
    setState(() => _selected = to);
  }

  /// Undo brings the line back, so this just acts.
  void _removeLine(int index) {
    _store.mutateEntityOverlays(
      'delete line',
      (GlossEntityOverlaysDoc edited) => edited.lines.removeAt(index),
    );
    if (index < _selected) setState(() => _selected--);
  }

  void _editLine(
    int index,
    String label,
    void Function(GlossEntityOverlayLine) edit,
  ) => _store.mutateEntityOverlays(
    label,
    (GlossEntityOverlaysDoc edited) => edit(edited.lines[index]),
  );

  List<HuiIssue> _issues(String path) => _store.issues
      .where((HuiIssue issue) => issue.path.startsWith(path))
      .toList();

  Widget _toggle(
    String label,
    String path,
    bool value,
    void Function(GlossEntityOverlaysDoc, bool) edit,
  ) => SwitchField(
    label: huiText(label),
    value: value,
    problems: _issues('\$.$path'),
    onChanged: (bool next) => _store.mutateEntityOverlays(
      label,
      (GlossEntityOverlaysDoc doc) => edit(doc, next),
    ),
  );

  Widget _number(
    String label,
    String path,
    num value,
    String tip,
    void Function(GlossEntityOverlaysDoc, double) edit, {
    bool integer = false,
  }) => Field(
    label: huiText(label),
    forId: 'entity-overlays-$path',
    tip: huiText(tip),
    problems: _issues('\$.$path'),
    child: NumberField(
      id: 'entity-overlays-$path',
      value: value.toDouble(),
      integer: integer,
      decimals: integer ? 0 : 2,
      step: integer ? 1 : 0.05,
      onChanged: (double next) => _store.mutateEntityOverlays(
        label,
        (GlossEntityOverlaysDoc doc) => edit(doc, next),
      ),
    ),
  );

  /// The l10n catalog reads [label] and [tip] as literal arguments 0 and 2.
  Widget _text(
    String label,
    String value,
    String tip,
    void Function(GlossEntityOverlaysDoc, String) edit, {
    required String path,
  }) => Field(
    label: huiText(label),
    forId: 'entity-overlays-$path',
    tip: huiText(tip),
    problems: _issues('\$.$path'),
    child: TextInput(
      id: 'entity-overlays-$path',
      value: value,
      size: _size,
      fullWidth: true,
      attributes: technicalInputAttributes,
      onChanged: (String next) => _store.mutateEntityOverlays(
        label,
        (GlossEntityOverlaysDoc doc) => edit(doc, next),
      ),
    ),
  );

  static List<String> _list(String source) => source
      .split(',')
      .map((String value) => value.trim())
      .where((String value) => value.isNotEmpty)
      .toList();
}
