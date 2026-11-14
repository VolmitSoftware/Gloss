/// The centre forms of the strings and waypoint catalogs.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/jaspr.dart' show Component;

import '../../l10n/hui_localizations.dart';
import '../../logic/gloss_show.dart';
import '../../model/model.dart';
import '../../state/editor_store.dart';
import '../../ui/color_field.dart';
import '../../ui/color_value.dart';
import '../../ui/density.dart';
import '../../ui/field.dart';
import '../../ui/mode.dart';
import '../../ui/number_field.dart';
import '../../ui/section.dart';
import '../../ui/technical_input.dart';
import '../../ui/toggle_choice.dart';
import '../../ui/vec3_field.dart';
import 'catalog_form.dart';
import 'extras_editor.dart';
import 'gloss_visibility_editor.dart';
import 'preview_expr_field.dart';

class CatalogDocumentView extends StatelessWidget {
  const CatalogDocumentView({required this.store, super.key});
  final EditorStore store;

  @override
  Widget build(BuildContext context) =>
      CatalogFormHost(store: store, builder: _content);

  Widget _content(BuildContext context) {
    final GlossDoc? doc = store.glossDoc;
    if (doc is GlossStringsDoc) return _StringsForm(store: store, doc: doc);
    if (doc is GlossWaypointDoc) return _WaypointForm(store: store, doc: doc);
    return const Component.fragment(<Widget>[]);
  }
}

class _StringsForm extends StatelessWidget {
  const _StringsForm({required this.store, required this.doc});
  final EditorStore store;
  final GlossStringsDoc doc;

  void _edit(String label, void Function(GlossStringsDoc) edit) =>
      store.mutateGloss(label, (GlossDoc doc) {
        if (doc is GlossStringsDoc) edit(doc);
      });

  @override
  Widget build(BuildContext context) {
    final ComponentSize size = controlSize(DensityScope.of(context));
    return CatalogForm(
      kind: huiText('Strings'),
      title: store.menuId,
      revision: doc.revision,
      kindClass: 'is-strings',
      children: <Widget>[
        Field(
          label: huiText('Locale'),
          forId: 'strings-locale',
          child: TextInput(
            id: 'strings-locale',
            value: doc.locale,
            size: size,
            fullWidth: true,
            attributes: technicalInputAttributes,
            onChanged: (String value) =>
                _edit('locale', (GlossStringsDoc doc) => doc.locale = value),
          ),
        ),
        Field(
          label: huiText('Fallback'),
          forId: 'strings-fallback',
          child: TextInput(
            id: 'strings-fallback',
            value: doc.fallback,
            size: size,
            fullWidth: true,
            attributes: technicalInputAttributes,
            onChanged: (String value) => _edit(
              'fallback',
              (GlossStringsDoc doc) => doc.fallback = value,
            ),
          ),
        ),
        ExtrasEditor(
          open: true,
          title: huiText('Entries'),
          extensionKeys: false,
          stringsOnly: true,
          extras: Map<String, Object?>.of(doc.entries),
          onChanged: (String label, Map<String, Object?> values) => _edit(
            label,
            (GlossStringsDoc doc) => doc.entries = <String, String>{
              for (final MapEntry<String, Object?> entry in values.entries)
                entry.key: '${entry.value ?? ''}',
            },
          ),
        ),
      ],
    );
  }
}

enum _AnchorTarget { position, entity, player }

class _WaypointForm extends StatelessWidget {
  const _WaypointForm({required this.store, required this.doc});
  final EditorStore store;
  final GlossWaypointDoc doc;

  void _edit(String label, void Function(GlossWaypointDoc) edit) =>
      store.mutateGloss(label, (GlossDoc doc) {
        if (doc is GlossWaypointDoc) edit(doc);
      });

  _AnchorTarget get _target => doc.anchor.entity != null
      ? _AnchorTarget.entity
      : doc.anchor.player != null
      ? _AnchorTarget.player
      : _AnchorTarget.position;

  Widget _text({
    required String id,
    required String label,
    required String value,
    required void Function(String) onChanged,
    required ComponentSize size,
  }) => Field(
    label: label,
    forId: id,
    child: TextInput(
      id: id,
      value: value,
      size: size,
      fullWidth: true,
      attributes: technicalInputAttributes,
      onChanged: onChanged,
    ),
  );

  @override
  Widget build(BuildContext context) {
    final ComponentSize size = controlSize(DensityScope.of(context));
    final _AnchorTarget target = _target;
    return CatalogForm(
      kind: huiText('Waypoint'),
      title: store.menuId,
      revision: doc.revision,
      kindClass: 'is-waypoint',
      children: <Widget>[
        AppSection(
          title: huiText('Anchor'),
          memoryKey: 'waypoint.anchor',
          children: <Widget>[
            Field(
              label: huiText('Type'),
              child: ToggleChoice<_AnchorTarget>(
                label: huiText('Type'),
                value: target,
                options: <ChoiceOption<_AnchorTarget>>[
                  ChoiceOption<_AnchorTarget>(
                    _AnchorTarget.position,
                    huiText('Position'),
                  ),
                  ChoiceOption<_AnchorTarget>(
                    _AnchorTarget.entity,
                    huiText('Entity'),
                  ),
                  ChoiceOption<_AnchorTarget>(
                    _AnchorTarget.player,
                    huiText('Player'),
                  ),
                ],
                onChanged: (_AnchorTarget value) =>
                    _edit('anchor', (GlossWaypointDoc doc) {
                      doc.anchor = switch (value) {
                        _AnchorTarget.entity => GlossMarkerAnchor(entity: ''),
                        _AnchorTarget.player => GlossMarkerAnchor(player: ''),
                        _AnchorTarget.position => GlossMarkerAnchor(
                          world: 'world',
                          x: 0,
                          y: 64,
                          z: 0,
                        ),
                      };
                    }),
              ),
            ),
            if (target == _AnchorTarget.position) ...<Widget>[
              _text(
                id: 'waypoint-world',
                label: huiText('World'),
                value: doc.anchor.world ?? '',
                size: size,
                onChanged: (String value) => _edit(
                  'world',
                  (GlossWaypointDoc doc) => doc.anchor.world = value,
                ),
              ),
              Field(
                label: huiText('Position'),
                forId: 'waypoint-position',
                child: Vec3Field(
                  id: 'waypoint-position',
                  label: huiText('Position'),
                  value: Vec3(
                    doc.anchor.x ?? 0,
                    doc.anchor.y ?? 64,
                    doc.anchor.z ?? 0,
                  ),
                  onChanged: (Vec3 value) =>
                      _edit('position', (GlossWaypointDoc doc) {
                        doc.anchor.x = value.x;
                        doc.anchor.y = value.y;
                        doc.anchor.z = value.z;
                      }),
                ),
              ),
            ] else if (target == _AnchorTarget.entity)
              _text(
                id: 'waypoint-entity',
                label: huiText('Entity'),
                value: doc.anchor.entity ?? '',
                size: size,
                onChanged: (String value) => _edit(
                  'anchor',
                  (GlossWaypointDoc doc) => doc.anchor.entity = value,
                ),
              )
            else
              _text(
                id: 'waypoint-player',
                label: huiText('Player'),
                value: doc.anchor.player ?? '',
                size: size,
                onChanged: (String value) => _edit(
                  'anchor',
                  (GlossWaypointDoc doc) => doc.anchor.player = value,
                ),
              ),
          ],
        ),
        AppSection(
          title: huiText('Style'),
          memoryKey: 'waypoint.style',
          children: <Widget>[
            Field(
              label: huiText('Color'),
              forId: 'waypoint-color',
              child: ColorField(
                id: 'waypoint-color',
                label: huiText('Color'),
                value: doc.color,
                format: HuiColorFormat.rgb,
                onChanged: (String value) =>
                    _edit('color', (GlossWaypointDoc doc) => doc.color = value),
              ),
            ),
            _text(
              id: 'waypoint-style',
              label: huiText('Style'),
              value: doc.style,
              size: size,
              onChanged: (String value) =>
                  _edit('style', (GlossWaypointDoc doc) => doc.style = value),
            ),
            Field(
              label: huiText('Fallback style'),
              child: ToggleChoice<String>(
                label: huiText('Fallback style'),
                value: doc.fallbackStyle,
                options: <ChoiceOption<String>>[
                  for (final String style in <String>{
                    'default',
                    'bowtie',
                    doc.fallbackStyle,
                  })
                    ChoiceOption<String>(style, style),
                ],
                onChanged: (String value) => _edit(
                  'fallback style',
                  (GlossWaypointDoc doc) => doc.fallbackStyle = value,
                ),
              ),
            ),
            Field(
              label: huiText('Range'),
              forId: 'waypoint-range',
              child: NumberField(
                id: 'waypoint-range',
                label: huiText('Range'),
                value: doc.range,
                min: 0,
                onChanged: (double value) =>
                    _edit('range', (GlossWaypointDoc doc) => doc.range = value),
              ),
            ),
          ],
        ),
        GlossVisibilityEditor(
          raw: doc.show,
          sectionKey: 'waypoint.show',
          onChanged: (Object? value) =>
              _edit('visibility', (GlossWaypointDoc doc) => doc.show = value),
        ),
        AdvancedOnly(
          child: AppSection(
            title: huiText('Audience'),
            memoryKey: 'waypoint.audience',
            children: <Widget>[
              PreviewExprField(
                label: huiText('Audience'),
                raw: doc.audience,
                kind: PreviewExprKind.boolean,
                showCondition: true,
                scope: glossShowVariables.toSet(),
                categoryVariableNames: glossShowVariables,
                help: huiText(
                  'Boolean or expression. Leave blank to always show.',
                ),
                onChanged: (Object? value) => _edit(
                  'audience',
                  (GlossWaypointDoc doc) => doc.audience = value,
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }
}
