/// Inspector body for a Gloss hologram document.
///
/// Anchor world, position and view distance, then the reorderable line list
/// (each row an input plus a live Gloss-pipeline preview, with a placeholder
/// picker that inserts into the selected line), then presentation: the
/// shared display style, the yaw and pitch that only some billboard modes
/// read, the box, refresh and particles. The raw Contents block is Advanced
/// only. The revision is server-owned and shown, not edited.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import '../../logic/gloss_show.dart';
import '../../logic/gloss_hologram_scene.dart' show hologramLinePreview;
import '../../logic/validation.dart';
import '../../model/gloss_hologram_box.dart';
import '../../model/model.dart';
import '../../services/catalogs.dart';
import '../../state/editor_store.dart';
import '../../ui/badge.dart';
import '../../ui/density.dart';
import '../../ui/duration_field.dart';
import '../../ui/duration_value.dart';
import '../../ui/field.dart';
import '../../ui/mode.dart';
import '../../ui/number_field.dart';
import '../../ui/pane_header.dart';
import '../../ui/reorder_list.dart';
import '../../ui/section.dart';
import '../../ui/technical_input.dart';
import '../../ui/vec3_field.dart';
import '../gloss/gloss_text_line.dart';
import 'display_refresh_editor.dart';
import 'display_style_editor.dart';
import 'extras_editor.dart';
import 'gloss_visibility_editor.dart';
import 'hologram_box_editor.dart';
import 'line_list_section.dart';
import 'particle_layers_editor.dart';
import 'placeholder_picker.dart';

import 'package:gloss_editor/l10n/hui_localizations.dart';

class HologramInspector extends StatefulWidget {
  const HologramInspector({
    required this.store,
    required this.catalogs,
    super.key,
  });

  final EditorStore store;
  final HuiCatalogs catalogs;

  @override
  State<HologramInspector> createState() => _HologramInspectorState();
}

class _HologramInspectorState extends State<HologramInspector> {
  /// The row the placeholder picker inserts into: the last focused input.
  int _focusedLine = 0;

  EditorStore get _store => component.store;

  GlossHologramDoc? get _doc => _store.hologramDoc;

  List<HuiIssue> _issuesFor(String path) => _store.issues
      .where((HuiIssue issue) => issue.path.startsWith(path))
      .toList();

  @override
  Widget build(BuildContext context) =>
      DensityScope.dense(child: Builder(builder: _build));

  Widget _build(BuildContext context) {
    final GlossHologramDoc? doc = _doc;
    if (doc == null) return const dom.div(<Widget>[]);
    return DenseBox(
      classes: 'hui-inspector-body is-hologram',
      children: <Widget>[
        PaneHeader(
          kind: huiText('Hologram'),
          title: _store.menuId,
          tip: huiText(
            'Every line joins into one TextDisplay at a world anchor.',
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
          sectionKey: 'hologram.visibility',
          issues: _store.issues
              .where((HuiIssue issue) => issue.path == r'$.show')
              .toList(),
          onChanged: (Object? value) => _store.mutateHologram(
            'visibility',
            (GlossHologramDoc edited) => setGlossShow(edited.extras, value),
          ),
        ),
        _anchor(context, doc),
        _lines(doc),
        DisplayStyleEditor(
          style: doc.style,
          defaults: defaultHologramDisplayStyle(),
          issues: _issuesFor(r'$.style'),
          onChanged: (String label, HuiIconStyle? value) =>
              _store.mutateHologram(
                label,
                (GlossHologramDoc edited) =>
                    edited.style = value ?? defaultHologramDisplayStyle(),
              ),
        ),
        _presentation(doc),
        HologramBoxEditor(
          box: doc.box,
          sectionKey: 'hologram.box',
          issues: _issuesFor(r'$.box'),
          mutate: (String label, void Function(GlossHologramBox) edit) =>
              _store.mutateHologram(
                label,
                (GlossHologramDoc edited) => edit(edited.box),
              ),
        ),
        DisplayRefreshEditor(
          refresh: doc.refresh,
          onChanged: (String label, GlossDisplayRefresh next) =>
              _store.mutateHologram(
                label,
                (GlossHologramDoc edited) => edited.refresh = next,
              ),
        ),
        ParticleLayersEditor(
          layers: doc.particleLayers,
          sectionKey: 'hologram.particleLayers',
          mutate:
              (String label, void Function(List<GlossParticleLayer>) edit) =>
                  _store.mutateHologram(
                    label,
                    (GlossHologramDoc edited) => edit(edited.particleLayers),
                  ),
        ),
        AdvancedOnly(
          child: ExtrasEditor(
            title: huiText('Contents'),
            extensionKeys: false,
            extras: <String, Object?>{
              'pages': doc.extras['pages'] ?? <Object?>[],
              'actions': doc.extras['actions'] ?? <Object?>[],
              'hitbox': doc.extras['hitbox'] ?? <String, Object?>{},
              'variants': doc.variants
                  .map((GlossPresentationVariant variant) => variant.toJson())
                  .toList(),
            },
            onChanged: (String label, Map<String, dynamic> next) =>
                _store.replaceGlossDoc(
                  label,
                  GlossHologramDoc.fromJson(<String, Object?>{
                    ...doc.toJson(),
                    ...next,
                  }),
                ),
          ),
        ),
      ],
    );
  }

  Widget _anchor(BuildContext context, GlossHologramDoc doc) {
    final List<double> position = doc.anchor.position;
    return AppSection(
      title: huiText('Anchor'),
      memoryKey: 'hologram.anchor',
      children: <Widget>[
        Field(
          label: huiText('World'),
          forId: 'hologram-world',
          required: true,
          docKey: 'hologram.anchor.world',
          problems: _issuesFor(r'$.anchor.world'),
          child: TextInput(
            id: 'hologram-world',
            value: doc.anchor.world,
            size: controlSize(DensityScope.of(context)),
            fullWidth: true,
            placeholder: huiText('world'),
            onChanged: (String value) => _store.mutateHologram(
              'hologram world',
              (GlossHologramDoc edited) => edited.anchor.world = value,
            ),
            styles: technicalInputStyles,
            attributes: technicalInputAttributes,
          ),
        ),
        Field(
          label: huiText('Position'),
          forId: 'hologram-position',
          required: true,
          docKey: 'hologram.anchor.position',
          problems: _issuesFor(r'$.anchor.position'),
          child: Vec3Field(
            id: 'hologram-position',
            label: huiText('Position'),
            value: Vec3(position[0], position[1], position[2]),
            step: 0.5,
            decimals: 2,
            // World coordinates, not a menu-space offset: the shared hints
            // would say "right of the player", which is wrong here.
            axisHints: <String>[
              huiText('x: world east'),
              huiText('y: world height; the stack grows upward from here'),
              huiText('z: world south'),
            ],
            onChanged: (Vec3 value) => _store.mutateHologram(
              'hologram position',
              (GlossHologramDoc edited) =>
                  edited.anchor.setPosition(value.x, value.y, value.z),
            ),
          ),
        ),
        Field(
          label: huiText('View distance'),
          forId: 'hologram-view-distance',
          problems: _issuesFor(r'$.viewDistance'),
          defaultValue: '48',
          onReset: doc.viewDistance == 48 ? null : () => _setViewDistance(48),
          child: NumberField(
            id: 'hologram-view-distance',
            value: doc.viewDistance,
            step: 1,
            decimals: 1,
            suffix: huiText('blocks'),
            onChanged: _setViewDistance,
          ),
        ),
      ],
    );
  }

  void _setViewDistance(double value) => _store.mutateHologram(
    'hologram view distance',
    (GlossHologramDoc edited) => edited.viewDistance = value,
  );

  void _setRefreshTicks(int value) => _store.mutateHologram(
    'hologram refresh ticks',
    (GlossHologramDoc edited) => edited.refreshTicks = value,
  );

  Widget _presentation(GlossHologramDoc doc) => AppSection(
    title: huiText('Presentation'),
    memoryKey: 'hologram.presentation',
    children: <Widget>[
      _angle(
        id: 'hologram-yaw',
        label: huiText('Yaw'),
        docKey: 'hologram.yaw',
        path: r'$.yaw',
        value: doc.yaw,
        used:
            doc.style.billboard == 'fixed' ||
            doc.style.billboard == 'horizontal',
        onChanged: (double value) =>
            _store.mutateHologram('hologram yaw', (GlossHologramDoc edited) {
              edited.yaw = value;
              edited.absentKeys.remove('yaw');
            }),
      ),
      _angle(
        id: 'hologram-pitch',
        label: huiTextKey('field.pitch.orientation', 'Pitch'),
        docKey: 'hologram.pitch',
        path: r'$.pitch',
        value: doc.pitch,
        used:
            doc.style.billboard == 'fixed' || doc.style.billboard == 'vertical',
        onChanged: (double value) =>
            _store.mutateHologram('hologram pitch', (GlossHologramDoc edited) {
              edited.pitch = value;
              edited.absentKeys.remove('pitch');
            }),
      ),
    ],
  );

  /// One orientation angle. The row stays put and says so when the current
  /// billboard mode turns that axis itself, because a control that vanishes
  /// with the mode hides the value the author is about to need again.
  Widget _angle({
    required String id,
    required String label,
    required String docKey,
    required String path,
    required double value,
    required bool used,
    required void Function(double value) onChanged,
  }) => Field(
    label: label,
    forId: id,
    docKey: docKey,
    hint: used ? null : huiText('Unused by this billboard mode'),
    problems: _issuesFor(path),
    defaultValue: '0',
    onReset: value == 0 ? null : () => onChanged(0),
    child: NumberField(
      id: id,
      value: value,
      step: 15,
      decimals: 2,
      suffix: '°',
      onChanged: onChanged,
    ),
  );

  Widget _lines(GlossHologramDoc doc) => LineListSection(
    title: huiText('Lines'),
    docKey: 'hologram.lines',
    sectionKey: 'hologram.lines',
    addLabel: huiText('Add line'),
    itemCount: doc.lines.length,
    issues: _issuesFor('lines['),
    emptyBody: huiText(
      'Gloss loads the file and draws nothing at the anchor. Add a line '
      'to give the hologram something to say.',
    ),
    onAdd: () {
      final int next = doc.lines.length;
      _store.mutateHologram(
        'add line',
        (GlossHologramDoc edited) => edited.lines.add(''),
      );
      setState(() => _focusedLine = next);
    },
    tools: <Widget>[
      PlaceholderPicker(
        catalogs: component.catalogs,
        onPicked: _insertPlaceholder,
      ),
    ],
    onReorder: (int from, int to) =>
        _store.mutateHologram('reorder line', (GlossHologramDoc edited) {
          final Object? moved = edited.lines.removeAt(from);
          edited.lines.insert(to, moved);
        }),
    itemBuilder: (int index) => _lineRow(doc, index),
    footer: <Widget>[
      // How often the lines re-render: a tick interval, so Advanced only.
      AdvancedOnly(
        child: Field(
          label: huiText('Refresh ticks'),
          forId: 'hologram-refresh-ticks',
          problems: _issuesFor(r'$.refreshTicks'),
          defaultValue: '10',
          onReset: doc.refreshTicks == 10 ? null : () => _setRefreshTicks(10),
          child: DurationField(
            id: 'hologram-refresh-ticks',
            value: doc.refreshTicks.toDouble(),
            unit: HuiDurationUnit.ticks,
            onChanged: (double value) => _setRefreshTicks(value.round()),
          ),
        ),
      ),
    ],
  );

  Widget _lineRow(GlossHologramDoc doc, int index) {
    final Object? raw = doc.lines[index];
    if (raw is Map && !raw.containsKey('text')) {
      // An object line without text has no line row: its move tools follow
      // its keys.
      return dom.div(<Widget>[
        ExtrasEditor(
          title: '${huiText('Line')} ${index + 1}',
          extensionKeys: false,
          extras: huiReadObject(raw, '\$.lines[$index]'),
          onChanged: (String label, Map<String, dynamic> next) =>
              _store.mutateHologram(label, (GlossHologramDoc edited) {
                if (index < edited.lines.length) edited.lines[index] = next;
              }),
        ),
        const ReorderRowTools(),
      ]);
    }
    final String line = glossHologramLineText(raw);
    return LineRow(
      value: line,
      placeholder: huiText('&fText, %papi%, |animation.id|, {{ expression }}'),
      label: huiText('Line {number}', <String, Object?>{'number': index + 1}),
      removeLabel: huiText('Delete line {number}', <String, Object?>{
        'number': index + 1,
      }),
      onChanged: (String value) => _editLine(index, value),
      onFocus: () => _focusedLine = index,
      preview: GlossTextLine(
        render: hologramLinePreview(
          doc,
          index,
          animations: _store.workspaceAnimations,
          emoji: _store.workspaceEmoji,
        ),
      ),
      onRemove: () =>
          _store.mutateHologram('delete line', (GlossHologramDoc edited) {
            if (index < edited.lines.length) edited.lines.removeAt(index);
          }),
    );
  }

  void _editLine(int index, String value) =>
      _store.mutateHologram('edit line', (GlossHologramDoc edited) {
        if (index >= edited.lines.length) return;
        final Object? line = edited.lines[index];
        if (line is Map && line.containsKey('text')) {
          edited.lines[index] = <String, Object?>{
            ...huiReadObject(line, '\$.lines[$index]'),
            'text': value,
          };
        } else if (line is String) {
          edited.lines[index] = value;
        }
      });

  void _insertPlaceholder(String token) {
    final GlossHologramDoc? doc = _doc;
    if (doc == null || doc.lines.isEmpty) return;
    final int index = _focusedLine.clamp(0, doc.lines.length - 1);
    final Object? line = doc.lines[index];
    if (line is String || (line is Map && line.containsKey('text'))) {
      _editLine(index, glossHologramLineText(line) + token);
    }
  }
}
