/// Inspector bodies for the lane kinds: inventories, nameplates, nametags and
/// markers. Raw JSON blocks and visibility conditions are Advanced only; they
/// keep every key they do not show.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/jaspr.dart' show Component;
import 'package:gloss_editor/l10n/hui_localizations.dart';

import '../../logic/gloss_show.dart';
import '../../logic/validation.dart';
import '../../model/model.dart';
import '../../state/editor_store.dart';
import '../../ui/badge.dart';
import '../../ui/color_field.dart';
import '../../ui/color_value.dart';
import '../../ui/density.dart';
import '../../ui/field.dart';
import '../../ui/mode.dart';
import '../../ui/number_field.dart';
import '../../ui/pane_header.dart';
import '../../ui/section.dart';
import '../../ui/switch_field.dart';
import '../../ui/technical_input.dart';
import '../../ui/vec3_field.dart';
import 'extras_editor.dart';
import 'gloss_visibility_editor.dart';
import 'identity_rules_editor.dart';
import 'line_list_section.dart';

Widget _revision(int revision) => AdvancedOnly(
  child: StatusBadge(
    huiText('revision {revision}', <String, Object?>{'revision': revision}),
  ),
);

List<HuiIssue> _issuesAt(EditorStore store, String path) => store.issues
    .where(
      (HuiIssue issue) =>
          issue.path == path ||
          issue.path.startsWith('$path.') ||
          issue.path.startsWith('$path['),
    )
    .toList();

Widget _visibility(
  EditorStore store,
  Object? raw,
  String key,
  void Function(Object? value) onChanged,
) => AdvancedOnly(
  child: GlossVisibilityEditor(
    raw: raw,
    sectionKey: key,
    issues: _issuesAt(store, r'$.show'),
    onChanged: onChanged,
  ),
);

/// A one-line text field in the dense inspector.
Widget laneTextField({
  required String id,
  required String label,
  required String value,
  required void Function(String value) onChanged,
  List<HuiIssue> problems = const <HuiIssue>[],
  String? tip,
  bool technical = false,
}) => Field(
  label: label,
  forId: id,
  tip: tip,
  problems: problems,
  child: TextInput(
    id: id,
    value: value,
    size: controlSize(Density.dense),
    fullWidth: true,
    attributes: technical ? technicalInputAttributes : null,
    onChanged: onChanged,
  ),
);

/// A pick from fixed JSON values, shown as written.
Widget laneSelectField({
  required String id,
  required String label,
  required String value,
  required Iterable<String> options,
  required void Function(String value) onChanged,
  List<HuiIssue> problems = const <HuiIssue>[],
  String? tip,
}) => Field(
  label: label,
  forId: id,
  tip: tip,
  problems: problems,
  child: ArcaneSelect(
    id: id,
    value: value,
    size: controlSize(Density.dense),
    fullWidth: true,
    options: <ArcaneSelectOption>[
      for (final String option in <String>{...options, value})
        ArcaneSelectOption(value: option, label: option),
    ],
    onChanged: onChanged,
  ),
);

class InventoryInspector extends StatelessWidget {
  const InventoryInspector({required this.store, super.key});

  final EditorStore store;

  @override
  Widget build(BuildContext context) {
    final GlossInventoryDoc? doc = store.inventoryDoc;
    if (doc == null) return const Component.fragment(<Widget>[]);
    return DenseBox(
      classes: 'hui-inspector-body is-inventory',
      children: <Widget>[
        PaneHeader(
          kind: huiText('Inventory'),
          title: store.menuId,
          status: _revision(doc.revision),
        ),
        AppSection(
          title: huiText('Chest window'),
          memoryKey: 'inventory.window',
          children: <Widget>[
            laneTextField(
              id: 'hui-inventory-title',
              label: huiText('Title'),
              value: doc.title,
              problems: _issuesAt(store, r'$.title'),
              onChanged: (String value) => store.mutateInventory(
                'title',
                (GlossInventoryDoc edited) => edited.title = value,
              ),
            ),
            laneSelectField(
              id: 'hui-inventory-resolution',
              label: huiText('Resolution'),
              value: doc.resolution,
              options: glossInventoryResolutions,
              problems: _issuesAt(store, r'$.resolution'),
              onChanged: (String value) => store.mutateInventory(
                'resolution',
                (GlossInventoryDoc edited) => edited.resolution = value,
              ),
            ),
            Field(
              label: huiText('Mask'),
              forId: 'hui-inventory-mask',
              problems: _issuesAt(store, r'$.mask'),
              child: TextArea(
                id: 'hui-inventory-mask',
                value: doc.mask.join('\n'),
                rows: doc.mask.length.clamp(2, 6),
                fullWidth: true,
                onChanged: (String value) => store.mutateInventory(
                  'mask',
                  (GlossInventoryDoc edited) => edited.mask = value.split('\n'),
                ),
              ),
            ),
            SwitchField(
              id: 'hui-inventory-close-on-teleport',
              label: huiText('Close on teleport'),
              value: doc.closeOnTeleport,
              onChanged: (bool value) => store.mutateInventory(
                'close on teleport',
                (GlossInventoryDoc edited) => edited.closeOnTeleport = value,
              ),
            ),
          ],
        ),
        AdvancedOnly(child: _refresh(doc)),
        _visibility(
          store,
          doc.extras['show'],
          'inventory.visibility',
          (Object? value) => store.mutateInventory(
            'visibility',
            (GlossInventoryDoc edited) => setGlossShow(edited.extras, value),
          ),
        ),
        AdvancedOnly(
          child: ExtrasEditor(
            title: huiText('Contents'),
            extensionKeys: false,
            extras: <String, Object?>{
              'slots': doc.slots,
              'list': doc.toJson()['list'],
              'variants': doc.variants,
            },
            onChanged: (String label, Map<String, dynamic> next) =>
                store.replaceGlossDoc(
                  label,
                  GlossInventoryDoc.fromJson(<String, Object?>{
                    ...doc.toJson(),
                    ...next,
                  }),
                ),
          ),
        ),
      ],
    );
  }

  /// Tick settings, kept with every key the file has.
  Widget _refresh(GlossInventoryDoc doc) {
    final Object? raw = doc.extras['refresh'];
    final Map<String, Object?> refresh = raw is Map<String, Object?>
        ? raw
        : <String, Object?>{};
    void change(String key, Object value) =>
        store.mutateInventory('inventory refresh', (GlossInventoryDoc edited) {
          edited.extras['refresh'] = <String, Object?>{...refresh, key: value};
        });
    return AppSection(
      title: huiText('Refresh'),
      memoryKey: 'inventory.refresh',
      children: <Widget>[
        laneSelectField(
          id: 'hui-inventory-refresh-mode',
          label: huiText('Mode'),
          tip: huiText(
            'Dynamic refreshes only changing content. Always also refreshes static content.',
          ),
          value: refresh['mode'] is String
              ? refresh['mode'] as String
              : 'dynamic',
          options: const <String>['dynamic', 'always'],
          onChanged: (String value) => change('mode', value),
        ),
        for (final (String field, String label) in <(String, String)>[
          ('titleTicks', huiText('Title')),
          ('slotsTicks', huiText('Slots')),
          ('conditionsTicks', huiText('Conditions')),
          ('listTicks', huiText('List')),
        ])
          Field(
            label: label,
            forId: 'hui-inventory-$field',
            tip: huiText(
              'Ticks between refreshes; 0 leaves this category to opening and manual updates.',
            ),
            child: NumberField(
              id: 'hui-inventory-$field',
              value: _ticks(
                refresh[field],
                field == 'listTicks' ? doc.list?.refreshTicks ?? 20 : 20,
              ),
              integer: true,
              min: 0,
              step: 1,
              decimals: 0,
              suffix: huiText('ticks'),
              onChanged: (double value) => change(field, value.round()),
            ),
          ),
      ],
    );
  }

  static double _ticks(Object? raw, int fallback) =>
      raw is num ? raw.toDouble() : fallback.toDouble();
}

class NameplateInspector extends StatelessWidget {
  const NameplateInspector({required this.store, super.key});

  final EditorStore store;

  void _edit(String label, void Function(GlossNameplatePresentation) edit) =>
      store.mutateNameplate(
        label,
        (GlossNameplateDoc edited) => edit(edited.presentation),
      );

  @override
  Widget build(BuildContext context) {
    final GlossNameplateDoc? doc = store.nameplateDoc;
    if (doc == null) return const Component.fragment(<Widget>[]);
    final GlossNameplatePresentation presentation = doc.presentation;
    return DenseBox(
      classes: 'hui-inspector-body is-nameplate',
      children: <Widget>[
        PaneHeader(
          kind: huiText('Nameplate'),
          title: store.menuId,
          status: _revision(doc.revision),
        ),
        LineListSection(
          title: huiText('Lines'),
          addLabel: huiText('Add line'),
          itemCount: presentation.lines.length,
          onAdd: () => _edit(
            'add line',
            (GlossNameplatePresentation edited) => edited.lines.add(
              GlossNameplateLine(text: '&f{{ subject.name }}'),
            ),
          ),
          itemBuilder: (int index) => LineRow(
            value: presentation.lines[index].text,
            placeholder: huiText('Nameplate line'),
            removeLabel: huiText('Delete line {number}', <String, Object?>{
              'number': index + 1,
            }),
            onChanged: (String value) =>
                _edit('line', (GlossNameplatePresentation edited) {
                  if (index < edited.lines.length) {
                    edited.lines[index].text = value;
                  }
                }),
            onRemove: () =>
                _edit('remove line', (GlossNameplatePresentation edited) {
                  if (index < edited.lines.length) {
                    edited.lines.removeAt(index);
                  }
                }),
          ),
        ),
        AppSection(
          title: huiText('Default presentation'),
          memoryKey: 'nameplate.presentation',
          children: <Widget>[
            Field(
              label: huiText('Offset'),
              forId: 'hui-nameplate-offset',
              problems: _issuesAt(store, r'$.presentation.offset'),
              child: NumberField(
                id: 'hui-nameplate-offset',
                value: presentation.offset,
                min: -2,
                max: 8,
                onChanged: (double value) => _edit(
                  'offset',
                  (GlossNameplatePresentation edited) => edited.offset = value,
                ),
              ),
            ),
            Field(
              label: huiText('Health segments'),
              forId: 'hui-nameplate-health-segments',
              problems: _issuesAt(store, r'$.presentation.healthSegments'),
              child: NumberField(
                id: 'hui-nameplate-health-segments',
                value: presentation.healthSegments.toDouble(),
                integer: true,
                min: 1,
                max: 40,
                step: 1,
                decimals: 0,
                onChanged: (double value) => _edit(
                  'health segments',
                  (GlossNameplatePresentation edited) =>
                      edited.healthSegments = value.round(),
                ),
              ),
            ),
            ...nameplateSwitches(
              presentation,
              (String label, void Function() change) => store.mutateNameplate(
                label,
                (GlossNameplateDoc _) => change(),
              ),
              idPrefix: 'hui-nameplate',
            ),
          ],
        ),
        IdentityRulesEditor(store: store),
        _visibility(
          store,
          doc.extras['show'],
          'nameplate.visibility',
          (Object? value) => store.mutateNameplate(
            'visibility',
            (GlossNameplateDoc edited) => setGlossShow(edited.extras, value),
          ),
        ),
        AdvancedOnly(
          child: ExtrasEditor(
            title: huiText('Presentation'),
            extensionKeys: false,
            extras: presentation.toJson(),
            onChanged: (String label, Map<String, dynamic> next) =>
                store.mutateNameplate(label, (GlossNameplateDoc edited) {
                  edited.presentation = GlossNameplatePresentation.fromJson(
                    next,
                    r'$.presentation',
                  );
                }),
          ),
        ),
      ],
    );
  }
}

/// The five nameplate audience switches, shared by the default presentation
/// and each permission variant. [edit] runs the change inside one undoable
/// store mutation; the store edits [value] in place.
List<Widget> nameplateSwitches(
  GlossNameplatePresentation value,
  void Function(String label, void Function() change) edit, {
  required String idPrefix,
}) {
  Widget toggle(
    String id,
    String label,
    String undo,
    bool current,
    void Function(GlossNameplatePresentation target, bool next) apply,
  ) => SwitchField(
    id: '$idPrefix-$id',
    label: label,
    value: current,
    onChanged: (bool next) => edit(undo, () => apply(value, next)),
  );
  return <Widget>[
    toggle(
      'hide-sneaking',
      huiText('Hide while sneaking'),
      'hide sneaking',
      value.hideSneaking,
      (GlossNameplatePresentation target, bool next) =>
          target.hideSneaking = next,
    ),
    toggle(
      'hide-invisible',
      'Hide invisible players',
      'hide invisible players',
      value.hideInvisible,
      (GlossNameplatePresentation target, bool next) =>
          target.hideInvisible = next,
    ),
    toggle(
      'hide-spectators',
      'Hide spectators',
      'hide spectators',
      value.hideSpectator,
      (GlossNameplatePresentation target, bool next) =>
          target.hideSpectator = next,
    ),
    toggle(
      'include-npcs',
      'Include NPCs',
      'include npcs',
      value.includeNpcs,
      (GlossNameplatePresentation target, bool next) =>
          target.includeNpcs = next,
    ),
    toggle(
      'show-self',
      'Show own plate',
      'show own plate',
      value.showSelf,
      (GlossNameplatePresentation target, bool next) => target.showSelf = next,
    ),
  ];
}

class NametagInspector extends StatelessWidget {
  const NametagInspector({required this.store, super.key});

  final EditorStore store;

  @override
  Widget build(BuildContext context) {
    final GlossNametagDoc? doc = store.nametagDoc;
    if (doc == null) return const Component.fragment(<Widget>[]);
    final GlossNametagPresentation presentation = doc.presentation;
    return DenseBox(
      classes: 'hui-inspector-body is-nametag',
      children: <Widget>[
        PaneHeader(
          kind: huiText('Nametag'),
          title: store.menuId,
          status: _revision(doc.revision),
        ),
        AppSection(
          title: huiText('Default presentation'),
          memoryKey: 'nametag.presentation',
          children: <Widget>[
            laneTextField(
              id: 'hui-nametag-prefix',
              label: huiText('Prefix'),
              value: presentation.prefix,
              problems: _issuesAt(store, r'$.presentation.prefix'),
              onChanged: (String value) => store.mutateNametag(
                'prefix',
                (GlossNametagDoc edited) => edited.presentation.prefix = value,
              ),
            ),
            laneTextField(
              id: 'hui-nametag-suffix',
              label: huiText('Suffix'),
              value: presentation.suffix,
              problems: _issuesAt(store, r'$.presentation.suffix'),
              onChanged: (String value) => store.mutateNametag(
                'suffix',
                (GlossNametagDoc edited) => edited.presentation.suffix = value,
              ),
            ),
            laneTextField(
              id: 'hui-nametag-color',
              label: huiText('Team color'),
              value: presentation.color,
              technical: true,
              problems: _issuesAt(store, r'$.presentation.color'),
              onChanged: (String value) => store.mutateNametag(
                'color',
                (GlossNametagDoc edited) => edited.presentation.color = value,
              ),
            ),
            laneSelectField(
              id: 'hui-nametag-visibility',
              label: huiText('Name visibility'),
              value: presentation.nameTagVisibility,
              options: glossNametagVisibilities,
              problems: _issuesAt(store, r'$.presentation.nameTagVisibility'),
              onChanged: (String value) => store.mutateNametag(
                'visibility rule',
                (GlossNametagDoc edited) =>
                    edited.presentation.nameTagVisibility = value,
              ),
            ),
            laneSelectField(
              id: 'hui-nametag-collision',
              label: huiText('Collision'),
              value: presentation.collision,
              options: glossNametagCollisions,
              problems: _issuesAt(store, r'$.presentation.collision'),
              onChanged: (String value) => store.mutateNametag(
                'collision',
                (GlossNametagDoc edited) =>
                    edited.presentation.collision = value,
              ),
            ),
          ],
        ),
        IdentityRulesEditor(store: store),
        _visibility(
          store,
          doc.extras['show'],
          'nametag.visibility',
          (Object? value) => store.mutateNametag(
            'visibility',
            (GlossNametagDoc edited) => setGlossShow(edited.extras, value),
          ),
        ),
      ],
    );
  }
}

class MarkerInspector extends StatelessWidget {
  const MarkerInspector({required this.store, super.key});

  final EditorStore store;

  @override
  Widget build(BuildContext context) {
    final GlossMarkerDoc? doc = store.markerDoc;
    if (doc == null) return const Component.fragment(<Widget>[]);
    return DenseBox(
      classes: 'hui-inspector-body is-marker',
      children: <Widget>[
        PaneHeader(
          kind: huiText('Marker'),
          title: store.menuId,
          status: _revision(doc.revision),
        ),
        AppSection(
          title: huiText('World pin'),
          memoryKey: 'marker.pin',
          children: <Widget>[
            laneTextField(
              id: 'hui-marker-label',
              label: huiText('Label'),
              value: doc.label,
              problems: _issuesAt(store, r'$.label'),
              onChanged: (String value) => store.mutateMarker(
                'label',
                (GlossMarkerDoc edited) => edited.label = value,
              ),
            ),
            Field(
              label: huiText('Color'),
              forId: 'hui-marker-color',
              problems: _issuesAt(store, r'$.color'),
              child: ColorField(
                id: 'hui-marker-color',
                value: doc.color,
                format: HuiColorFormat.rgb,
                label: huiText('Color'),
                onChanged: (String value) => store.mutateMarker(
                  'color',
                  (GlossMarkerDoc edited) => edited.color = value,
                ),
              ),
            ),
            SwitchField(
              id: 'hui-marker-beam',
              label: huiText('Beam'),
              value: doc.beam.enabled,
              onChanged: (bool value) => store.mutateMarker(
                'beam',
                (GlossMarkerDoc edited) => edited.beam.enabled = value,
              ),
            ),
            laneTextField(
              id: 'hui-marker-world',
              label: huiText('World'),
              value: doc.anchor.world ?? '',
              technical: true,
              problems: _issuesAt(store, r'$.anchor.world'),
              onChanged: (String value) => store.mutateMarker(
                'world',
                (GlossMarkerDoc edited) =>
                    edited.anchor.world = value.trim().isEmpty ? null : value,
              ),
            ),
            Field(
              label: huiText('Position'),
              problems: _issuesAt(store, r'$.anchor'),
              child: Vec3Field(
                id: 'hui-marker-position',
                label: huiText('Position'),
                value: Vec3(
                  doc.anchor.x ?? 0,
                  doc.anchor.y ?? 64,
                  doc.anchor.z ?? 0,
                ),
                onChanged: (Vec3 value) =>
                    store.mutateMarker('position', (GlossMarkerDoc edited) {
                      edited.anchor.x = value.x;
                      edited.anchor.y = value.y;
                      edited.anchor.z = value.z;
                    }),
              ),
            ),
            Field(
              label: huiText('Hide within (blocks)'),
              forId: 'hui-marker-hide-within',
              problems: _issuesAt(store, r'$.hideWithin'),
              child: NumberField(
                id: 'hui-marker-hide-within',
                value: doc.hideWithin,
                min: 0,
                onChanged: (double value) => store.mutateMarker(
                  'hide within',
                  (GlossMarkerDoc edited) => edited.hideWithin = value,
                ),
              ),
            ),
            Field(
              label: huiText('Max distance'),
              forId: 'hui-marker-max-distance',
              problems: _issuesAt(store, r'$.maxDistance'),
              child: NumberField(
                id: 'hui-marker-max-distance',
                value: doc.maxDistance,
                min: 1,
                onChanged: (double value) => store.mutateMarker(
                  'max distance',
                  (GlossMarkerDoc edited) => edited.maxDistance = value,
                ),
              ),
            ),
          ],
        ),
        _visibility(
          store,
          doc.extras['show'],
          'marker.visibility',
          (Object? value) => store.mutateMarker(
            'visibility',
            (GlossMarkerDoc edited) => setGlossShow(edited.extras, value),
          ),
        ),
        AdvancedOnly(
          child: ExtrasEditor(
            title: huiText('Settings'),
            extensionKeys: false,
            extras: <String, Object?>{
              'anchor': doc.anchor.toJson(),
              'beam': doc.beam.toJson(),
              'edge': doc.edge.toJson(),
              'trail': doc.trail.toJson(),
              'style': doc.style.toJson(),
              'box': doc.box.toJson(),
              'icon': doc.extras['icon'],
              'lifetimeTicks': doc.lifetimeTicks,
            },
            onChanged: (String label, Map<String, dynamic> next) =>
                store.replaceGlossDoc(
                  label,
                  GlossMarkerDoc.fromJson(<String, Object?>{
                    ...doc.toJson(),
                    ...next,
                  }),
                ),
          ),
        ),
      ],
    );
  }
}
