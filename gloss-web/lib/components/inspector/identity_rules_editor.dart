/// Who wears a nameplate or nametag style: the assignment rule and the
/// permission variants. Ids, priorities, conditions and raw JSON are Advanced
/// only; a variant is a heading and its fields, separated by a rule.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../l10n/hui_localizations.dart';
import '../../logic/player_identity_preview.dart';
import '../../model/gloss_hologram_box.dart';
import '../../model/model.dart';
import '../../state/editor_store.dart';
import '../../ui/action_row.dart';
import '../../ui/density.dart';
import '../../ui/field.dart';
import '../../ui/field_group.dart';
import '../../ui/icon_button.dart';
import '../../ui/mode.dart';
import '../../ui/number_field.dart';
import '../../ui/section.dart';
import 'extras_editor.dart';
import 'lane_inspectors.dart'
    show laneSelectField, laneTextField, nameplateSwitches;

class IdentityRulesEditor extends StatelessWidget {
  const IdentityRulesEditor({required this.store, super.key});

  final EditorStore store;

  /// The store edits the live document in place, so a change applied to an
  /// object read from it lands in one undoable step.
  void _change(String label, void Function() change) =>
      store.mutateGloss(label, (GlossDoc doc) => change());

  Widget _text(
    String id,
    String label,
    String value,
    void Function(String) changed, {
    String? tip,
    bool technical = false,
  }) => laneTextField(
    id: id,
    label: label,
    value: value,
    tip: tip,
    technical: technical,
    onChanged: (String next) => _change(label, () => changed(next)),
  );

  Widget _priority(String id, int value, void Function(int) changed) =>
      AdvancedOnly(
        child: Field(
          label: huiText('Priority'),
          forId: id,
          tip: 'Higher matching priorities win.',
          child: NumberField(
            id: id,
            value: value.toDouble(),
            min: -1000,
            max: 1000,
            integer: true,
            step: 1,
            decimals: 0,
            onChanged: (double next) =>
                _change('priority', () => changed(next.round())),
          ),
        ),
      );

  List<Widget> _nametag(String id, GlossNametagPresentation value) => <Widget>[
    _text(
      '$id-prefix',
      huiText('Prefix'),
      value.prefix,
      (String next) => value.prefix = next,
    ),
    _text(
      '$id-suffix',
      huiText('Suffix'),
      value.suffix,
      (String next) => value.suffix = next,
    ),
    laneSelectField(
      id: '$id-color',
      label: 'Name color',
      value: value.color,
      options: glossTeamColors.keys,
      onChanged: (String next) =>
          _change('name color', () => value.color = next),
    ),
    laneSelectField(
      id: '$id-visibility',
      label: huiText('Name visibility'),
      value: value.nameTagVisibility,
      options: glossNametagVisibilities,
      onChanged: (String next) =>
          _change('visibility', () => value.nameTagVisibility = next),
    ),
    laneSelectField(
      id: '$id-collision',
      label: huiText('Collision'),
      value: value.collision,
      options: glossNametagCollisions,
      onChanged: (String next) =>
          _change('collision', () => value.collision = next),
    ),
  ];

  List<Widget> _nameplate(String id, GlossNameplatePresentation value) =>
      <Widget>[
        for (int index = 0; index < value.lines.length; index++) ...<Widget>[
          Field(
            label: 'Line ${index + 1}',
            forId: '$id-line-$index',
            trailing: AppIconButton(
              icon: ArcaneIcon.trash2(),
              label: huiText('Delete line {number}', <String, Object?>{
                'number': index + 1,
              }),
              onPressed: () =>
                  _change('remove line', () => value.lines.removeAt(index)),
            ),
            child: TextInput(
              id: '$id-line-$index',
              value: value.lines[index].text,
              size: controlSize(Density.dense),
              fullWidth: true,
              onChanged: (String next) =>
                  _change('line', () => value.lines[index].text = next),
            ),
          ),
          AdvancedOnly(
            child: _text(
              '$id-line-$index-show',
              'Line ${index + 1} condition',
              '${value.lines[index].show ?? 'true'}',
              (String next) => value.lines[index].show = next,
              technical: true,
            ),
          ),
        ],
        ActionRow(
          dense: true,
          secondary: <RowAction>[
            RowAction(
              huiText('Add line'),
              () => _change(
                'add line',
                () => value.lines.add(
                  GlossNameplateLine(text: '&f{{ subject.name }}'),
                ),
              ),
              icon: ArcaneIcon.plus(),
            ),
          ],
        ),
        Field(
          label: huiText('Offset'),
          forId: '$id-offset',
          child: NumberField(
            id: '$id-offset',
            value: value.offset,
            min: -2,
            max: 8,
            onChanged: (double next) =>
                _change('offset', () => value.offset = next),
          ),
        ),
        Field(
          label: huiText('Health segments'),
          forId: '$id-health-segments',
          child: NumberField(
            id: '$id-health-segments',
            value: value.healthSegments.toDouble(),
            integer: true,
            min: 1,
            max: 40,
            step: 1,
            decimals: 0,
            onChanged: (double next) => _change(
              'health segments',
              () => value.healthSegments = next.round(),
            ),
          ),
        ),
        ...nameplateSwitches(value, _change, idPrefix: id),
        AdvancedOnly(
          child: ExtrasEditor(
            title: huiText('Presentation'),
            extensionKeys: false,
            extras: <String, Object?>{
              'healthBar': value.healthBar.toJson(),
              'style': value.style.toJson(),
              'box': value.box.toJson(),
              'relations': value.relations
                  .map((GlossNameplateRelation relation) => relation.toJson())
                  .toList(),
            },
            onChanged: (String label, Map<String, dynamic> next) =>
                _change(label, () {
                  value.healthBar = GlossHealthBar.fromJson(next['healthBar']);
                  value.style =
                      HuiIconStyle.fromJsonOrNull(next['style']) ??
                      defaultHologramDisplayStyle();
                  value.box = GlossHologramBox.fromJson(next['box']);
                  value.relations = <GlossNameplateRelation>[
                    for (final Object? raw in huiReadList(next['relations']))
                      GlossNameplateRelation.fromJson(raw, 'relations'),
                  ];
                }),
          ),
        ),
      ];

  void _addVariant(GlossNametagDoc? nametag, GlossNameplateDoc? nameplate) =>
      _change('add permission variant', () {
        if (nametag != null) {
          int number = nametag.variants.length + 1;
          while (nametag.variants.any(
            (GlossNametagVariant entry) => entry.id == 'rank-$number',
          )) {
            number++;
          }
          nametag.variants.add(
            GlossNametagVariant(
              id: 'rank-$number',
              priority: 10,
              presentation: nametag.presentation.copy(),
            )..permission = 'gloss.nametag.rank$number',
          );
        } else if (nameplate != null) {
          int number = nameplate.variants.length + 1;
          while (nameplate.variants.any(
            (GlossNameplateVariant entry) => entry.id == 'rank-$number',
          )) {
            number++;
          }
          nameplate.variants.add(
            GlossNameplateVariant(
              id: 'rank-$number',
              priority: 10,
              presentation: nameplate.presentation.copy(),
            )..permission = 'gloss.nameplate.rank$number',
          );
        }
      });

  @override
  Widget build(BuildContext context) {
    final GlossNametagDoc? nametag = store.nametagDoc;
    final GlossNameplateDoc? nameplate = store.nameplateDoc;
    final GlossPrioritySelect? select = nametag?.select ?? nameplate?.select;
    if (select == null) return const Component.fragment(<Widget>[]);
    final int variantCount =
        nametag?.variants.length ?? nameplate?.variants.length ?? 0;
    return Component.fragment(<Widget>[
      AppSection(
        title: 'Assignment',
        memoryKey: 'identity.assignment',
        children: <Widget>[
          _text(
            'hui-identity-permission',
            'Required permission',
            select.permission,
            (String value) => select.permission = value,
            tip: 'Granted to the player wearing this style. Blank allows everyone.',
            technical: true,
          ),
          _priority(
            'hui-identity-priority',
            select.priority,
            (int value) => select.priority = value,
          ),
          AdvancedOnly(
            child: _text(
              'hui-identity-condition',
              huiText('Condition'),
              select.when,
              (String value) => select.when = value,
              tip: 'The permission and this condition must both match.',
              technical: true,
            ),
          ),
        ],
      ),
      AppSection(
        title: huiText('Variants'),
        memoryKey: 'identity.variants',
        count: variantCount,
        actions: <Widget>[
          AppIconButton(
            icon: ArcaneIcon.plus(),
            label: huiText('Add variant'),
            onPressed: () => _addVariant(nametag, nameplate),
          ),
        ],
        children: <Widget>[
          if (variantCount == 0)
            dom.p(classes: 'ui-section-description', <Widget>[
              Text(huiText('Nothing here yet')),
            ]),
          if (nametag != null)
            for (int index = 0; index < nametag.variants.length; index++)
              _nametagVariant(nametag, index),
          if (nameplate != null)
            for (int index = 0; index < nameplate.variants.length; index++)
              _nameplateVariant(nameplate, index),
        ],
      ),
    ]);
  }

  /// One variant: a plain heading (its id and the remove button) over its
  /// fields, a rule between variants, no box.
  Widget _variant(String id, void Function() remove, List<Widget> fields) =>
      dom.div(
        classes: 'hui-variant',
        attributes: <String, String>{'data-variant': id},
        <Widget>[
          FieldGroup(
            title: id,
            actions: <Widget>[
              AppIconButton(
                icon: ArcaneIcon.trash2(),
                label: huiText('Remove variant'),
                onPressed: remove,
              ),
            ],
            children: fields,
          ),
        ],
      );

  List<Widget> _variantRule(
    String id,
    String variantId,
    String permission,
    int priority,
    String when, {
    required void Function(String) setId,
    required void Function(String) setPermission,
    required void Function(int) setPriority,
    required void Function(String) setWhen,
  }) => <Widget>[
    AdvancedOnly(
      child: _text(
        '$id-id',
        huiText('Variant id'),
        variantId,
        setId,
        technical: true,
      ),
    ),
    _text(
      '$id-permission',
      'Variant permission',
      permission,
      setPermission,
      technical: true,
    ),
    _priority('$id-priority', priority, setPriority),
    AdvancedOnly(
      child: _text(
        '$id-when',
        huiText('Variant condition'),
        when,
        setWhen,
        technical: true,
      ),
    ),
  ];

  Widget _nametagVariant(GlossNametagDoc doc, int index) {
    final GlossNametagVariant variant = doc.variants[index];
    final String id = 'hui-identity-variant-$index';
    return _variant(
      variant.id,
      () => _change('remove variant', () => doc.variants.removeAt(index)),
      <Widget>[
        ..._variantRule(
          id,
          variant.id,
          variant.permission,
          variant.priority,
          variant.when,
          setId: (String value) => variant.id = value,
          setPermission: (String value) => variant.permission = value,
          setPriority: (int value) => variant.priority = value,
          setWhen: (String value) => variant.when = value,
        ),
        ..._nametag(id, variant.presentation),
      ],
    );
  }

  Widget _nameplateVariant(GlossNameplateDoc doc, int index) {
    final GlossNameplateVariant variant = doc.variants[index];
    final String id = 'hui-identity-variant-$index';
    return _variant(
      variant.id,
      () => _change('remove variant', () => doc.variants.removeAt(index)),
      <Widget>[
        ..._variantRule(
          id,
          variant.id,
          variant.permission,
          variant.priority,
          variant.when,
          setId: (String value) => variant.id = value,
          setPermission: (String value) => variant.permission = value,
          setPriority: (int value) => variant.priority = value,
          setWhen: (String value) => variant.when = value,
        ),
        ..._nameplate(id, variant.presentation),
      ],
    );
  }
}
