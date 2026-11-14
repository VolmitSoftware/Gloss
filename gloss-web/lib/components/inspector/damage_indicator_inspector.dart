library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:gloss_editor/l10n/hui_localizations.dart';
import 'package:jaspr/dom.dart' as dom;

import '../../logic/damage_indicator_preview.dart';
import '../../logic/gloss_show.dart';
import '../../logic/gloss_text.dart';
import '../../logic/validation.dart';
import '../../model/gloss_hologram_box.dart';
import '../../model/model.dart';
import '../../state/editor_store.dart';
import '../../ui/action_row.dart';
import '../../ui/badge.dart';
import '../../ui/density.dart';
import '../../ui/duration_field.dart';
import '../../ui/duration_value.dart';
import '../../ui/field.dart';
import '../../ui/icon_button.dart';
import '../../ui/mode.dart';
import '../../ui/number_field.dart';
import '../../ui/pane_header.dart';
import '../../ui/section.dart';
import '../../ui/vec3_field.dart';
import '../../ui/technical_input.dart';
import '../gloss/gloss_text_line.dart';
import 'display_refresh_editor.dart';
import 'display_style_editor.dart';
import 'extras_editor.dart';
import 'gloss_visibility_editor.dart';
import 'hologram_box_editor.dart';
import 'particle_layers_editor.dart';

final RegExp _idUnsafe = RegExp(r'[^A-Za-z0-9]+');

/// An element id for the control at a JSON [path].
String _idFor(String path) =>
    'damage-indicator${path.replaceAll(_idUnsafe, '-')}';

class DamageIndicatorInspector extends StatelessWidget {
  const DamageIndicatorInspector({required this.store, super.key});

  final EditorStore store;

  List<HuiIssue> _issuesFor(String path) => store.issues
      .where((HuiIssue issue) => issue.path.startsWith(path))
      .toList();

  @override
  Widget build(BuildContext context) {
    final GlossDamageIndicatorsDoc? doc = store.damageIndicatorsDoc;
    if (doc == null) return const dom.div(<Widget>[]);
    return DenseBox(
      classes: 'hui-inspector-body is-damage-indicators',
      children: <Widget>[
        PaneHeader(
          kind: huiText('Damage indicators'),
          title: store.menuId,
          tip: huiText(
            'Conditional floating combat numbers with complete damage and healing variants.',
          ),
          status: AdvancedOnly(
            child: StatusBadge(
              huiText('revision {revision}', <String, Object?>{
                'revision': doc.revision,
              }),
            ),
          ),
        ),
        _limits(doc),
        _style(doc, doc.damage, healing: false),
        _style(doc, doc.healing, healing: true),
        _audience(doc),
        AdvancedOnly(
          child: ExtrasEditor(
            title: huiText('Limits'),
            extensionKeys: false,
            extras: doc.limits.toJson(),
            onChanged: (String label, Map<String, dynamic> next) =>
                _mutate(label, (GlossDamageIndicatorsDoc edited) {
                  edited.limits = GlossDamageIndicatorLimits.fromJson(next);
                }),
          ),
        ),
        AdvancedOnly(
          child: GlossVisibilityEditor(
            raw: doc.extras['show'],
            sectionKey: 'damage_indicator.visibility',
            issues: store.issues
                .where((HuiIssue issue) => issue.path == r'$.show')
                .toList(),
            onChanged: (Object? value) => _mutate(
              'visibility',
              (GlossDamageIndicatorsDoc edited) =>
                  setGlossShow(edited.extras, value),
            ),
          ),
        ),
      ],
    );
  }

  Widget _limits(GlossDamageIndicatorsDoc doc) => AppSection(
    title: huiText('Admission and formatting'),
    memoryKey: 'damage-indicators.limits',
    children: <Widget>[
      AdvancedOnly(
        child: _integer(
          label: huiText('Aggregation ticks'),
          tip: huiText(
            'Sample net health change over 1..200 ticks. One pending sample per target.',
          ),
          path: r'$.limits.aggregationTicks',
          value: doc.limits.aggregationTicks,
          onChanged: (int value) => _mutate(
            'indicator aggregation',
            (GlossDamageIndicatorsDoc edited) =>
                edited.limits.aggregationTicks = value,
          ),
        ),
      ),
      AdvancedOnly(
        child: _integer(
          label: huiText('Maximum pending samples'),
          tip: huiText(
            'Global pending target limit, 1..16384. New samples are rejected when full.',
          ),
          path: r'$.limits.maxPendingSamples',
          value: doc.limits.maxPendingSamples,
          onChanged: (int value) => _mutate(
            'indicator sample capacity',
            (GlossDamageIndicatorsDoc edited) =>
                edited.limits.maxPendingSamples = value,
          ),
        ),
      ),
      _integer(
        label: huiText('Maximum per second'),
        tip: huiText('Global spawn budget. 1..1000.'),
        path: r'$.limits.maxPerSecond',
        value: doc.limits.maxPerSecond,
        onChanged: (int value) => _mutate(
          'indicator spawn budget',
          (GlossDamageIndicatorsDoc edited) =>
              edited.limits.maxPerSecond = value,
        ),
      ),
      Field(
        label: huiText('Lifetime'),
        forId: _idFor(r'$.limits.lifetimeMs'),
        tip: huiText('Visible lifetime. 250..30000 ms.'),
        problems: _issuesFor(r'$.limits.lifetimeMs'),
        child: DurationField(
          id: _idFor(r'$.limits.lifetimeMs'),
          value: doc.limits.lifetimeMs.toDouble(),
          unit: HuiDurationUnit.milliseconds,
          onChanged: (double value) => _mutate(
            'indicator lifetime',
            (GlossDamageIndicatorsDoc edited) =>
                edited.limits.lifetimeMs = value.round(),
          ),
        ),
      ),
      _decimal(
        label: huiText('Minimum health change'),
        tip: huiText('Smaller changes do not spawn an indicator. 0..1000.'),
        path: r'$.limits.minimumDelta',
        value: doc.limits.minimumDelta,
        step: 0.001,
        decimals: 3,
        onChanged: (double value) => _mutate(
          'indicator minimum delta',
          (GlossDamageIndicatorsDoc edited) =>
              edited.limits.minimumDelta = value,
        ),
      ),
      _integer(
        label: huiText('Decimal places'),
        tip: huiText('Digits after the decimal point. 0..4.'),
        path: r'$.limits.decimals',
        value: doc.limits.decimals,
        onChanged: (int value) => _mutate(
          'indicator decimals',
          (GlossDamageIndicatorsDoc edited) => edited.limits.decimals = value,
        ),
      ),
      _decimal(
        label: huiText('View range'),
        path: r'$.limits.viewRange',
        value: doc.limits.viewRange,
        step: 1,
        decimals: 2,
        onChanged: (double value) => _mutate(
          'indicator view range',
          (GlossDamageIndicatorsDoc edited) => edited.limits.viewRange = value,
        ),
      ),
    ],
  );

  Widget _style(
    GlossDamageIndicatorsDoc doc,
    GlossDamageIndicatorStyle style, {
    required bool healing,
  }) {
    final String path = healing ? r'$.healing' : r'$.damage';
    final String noun = healing ? huiText('Healing') : huiText('Damage');
    return AppSection(
      title: noun,
      memoryKey: healing
          ? 'damage-indicators.healing'
          : 'damage-indicators.damage',
      count: style.variants.isEmpty ? null : style.variants.length,
      actions: <Widget>[
        AppIconButton(
          icon: ArcaneIcon.plus(),
          label: huiText('Add variant'),
          tipAtEnd: true,
          onPressed: () => _mutateStyle(
            healing,
            'add ${noun.toLowerCase()} variant',
            (GlossDamageIndicatorStyle edited) => edited.variants.add(
              GlossDamageIndicatorVariant(
                id: 'variant-${edited.variants.length + 1}',
                when: 'false',
                presentation: edited.presentation.copy(),
              ),
            ),
          ),
        ),
      ],
      children: <Widget>[
        _condition(
          label: huiText('Event condition'),
          value: style.when,
          path: '$path.when',
          onInput: (String value) => _mutateStyle(
            healing,
            '${noun.toLowerCase()} condition',
            (GlossDamageIndicatorStyle edited) => edited.when = value,
          ),
        ),
        dom.h3(classes: 'ui-section-title', <Widget>[
          Text(huiText('Default presentation')),
        ]),
        ..._presentationFields(
          doc,
          style.presentation,
          '$path.presentation',
          (
            String label,
            void Function(GlossDamageIndicatorPresentation) edit,
          ) => _mutateStyle(
            healing,
            label,
            (GlossDamageIndicatorStyle edited) => edit(edited.presentation),
          ),
        ),
        for (int index = 0; index < style.variants.length; index++)
          ..._variant(doc, style, healing, index, path),
      ],
    );
  }

  List<Widget> _variant(
    GlossDamageIndicatorsDoc doc,
    GlossDamageIndicatorStyle style,
    bool healing,
    int index,
    String stylePath,
  ) {
    final GlossDamageIndicatorVariant variant = style.variants[index];
    final String path = '$stylePath.variants[$index]';
    return <Widget>[
      ActionRow(
        dense: true,
        leading: <Widget>[
          dom.h3(classes: 'ui-section-title', <Widget>[
            Text(
              huiText('Variant {id}', <String, Object?>{
                'id': variant.id.isEmpty ? index + 1 : variant.id,
              }),
            ),
          ]),
        ],
        secondary: <RowAction>[
          RowAction(
            huiText('Delete variant'),
            () => _mutateStyle(healing, 'delete indicator variant', (
              GlossDamageIndicatorStyle edited,
            ) {
              if (index < edited.variants.length) {
                edited.variants.removeAt(index);
              }
            }),
            icon: ArcaneIcon.trash2(),
            iconOnly: true,
          ),
        ],
      ),
      AdvancedOnly(
        child: Field(
          label: huiText('Variant id'),
          forId: _idFor('$path.id'),
          problems: _issuesFor('$path.id'),
          child: TextInput(
            id: _idFor('$path.id'),
            value: variant.id,
            size: controlSize(Density.dense),
            fullWidth: true,
            attributes: technicalInputAttributes,
            onChanged: (String value) => _mutateVariant(
              healing,
              index,
              'indicator variant id',
              (GlossDamageIndicatorVariant edited) => edited.id = value,
            ),
          ),
        ),
      ),
      AdvancedOnly(
        child: _integer(
          label: huiText('Priority'),
          tip: huiText('Highest matching priority wins.'),
          path: '$path.priority',
          value: variant.priority,
          onChanged: (int value) => _mutateVariant(
            healing,
            index,
            'indicator variant priority',
            (GlossDamageIndicatorVariant edited) => edited.priority = value,
          ),
        ),
      ),
      _condition(
        label: huiText('Condition'),
        value: variant.when,
        path: '$path.when',
        onInput: (String value) => _mutateVariant(
          healing,
          index,
          'indicator variant condition',
          (GlossDamageIndicatorVariant edited) => edited.when = value,
        ),
      ),
      ..._presentationFields(
        doc,
        variant.presentation,
        '$path.presentation',
        (String label, void Function(GlossDamageIndicatorPresentation) edit) =>
            _mutateVariant(
              healing,
              index,
              label,
              (GlossDamageIndicatorVariant edited) => edit(edited.presentation),
            ),
      ),
    ];
  }

  List<Widget> _presentationFields(
    GlossDamageIndicatorsDoc doc,
    GlossDamageIndicatorPresentation presentation,
    String path,
    void Function(
      String label,
      void Function(GlossDamageIndicatorPresentation presentation) edit,
    )
    mutate,
  ) => <Widget>[
    Field(
      label: huiText('Text format'),
      forId: _idFor('$path.format'),
      tip: huiText('Minecraft text. Use {amount} for the health change.'),
      problems: _issuesFor('$path.format'),
      child: dom.div(<Widget>[
        TextInput(
          id: _idFor('$path.format'),
          value: presentation.format,
          size: controlSize(Density.dense),
          fullWidth: true,
          styles: technicalInputStyles,
          attributes: technicalInputAttributes,
          onChanged: (String value) => mutate(
            'indicator format',
            (GlossDamageIndicatorPresentation edited) => edited.format = value,
          ),
        ),
        ArcaneFieldDescription.child(
          GlossTextLine(
            render: renderGlossLine(
              richText: true,
              renderDamageIndicatorText(
                presentation,
                7.25,
                doc.limits.decimals,
              ),
              animations: store.workspaceAnimations,
              emoji: store.workspaceEmoji,
            ),
          ),
        ),
      ]),
    ),
    Field(
      label: huiText('Spawn offset'),
      forId: _idFor('$path.offset'),
      problems: _issuesFor('$path.offset'),
      child: Vec3Field(
        id: _idFor('$path.offset'),
        label: huiText('Spawn offset'),
        value: presentation.offset,
        labels: <String>[huiText('X'), huiText('Y'), huiText('Z')],
        onChanged: (Vec3 value) => mutate(
          'indicator offset',
          (GlossDamageIndicatorPresentation edited) => edited.offset = value,
        ),
      ),
    ),
    _decimal(
      label: huiText('Horizontal speed'),
      tip: huiText('Outward speed. 0..16.'),
      path: '$path.motion.horizontalSpeed',
      value: presentation.motion.horizontalSpeed,
      onChanged: (double value) => mutate(
        'indicator horizontal speed',
        (GlossDamageIndicatorPresentation edited) =>
            edited.motion.horizontalSpeed = value,
      ),
    ),
    _decimal(
      label: huiText('Vertical speed'),
      tip: huiText('Initial vertical speed. -16..16.'),
      path: '$path.motion.verticalSpeed',
      value: presentation.motion.verticalSpeed,
      onChanged: (double value) => mutate(
        'indicator vertical speed',
        (GlossDamageIndicatorPresentation edited) =>
            edited.motion.verticalSpeed = value,
      ),
    ),
    _decimal(
      label: huiText('Vertical acceleration'),
      tip: huiText('Vertical acceleration. -32..32.'),
      path: '$path.motion.verticalAcceleration',
      value: presentation.motion.verticalAcceleration,
      onChanged: (double value) => mutate(
        'indicator vertical acceleration',
        (GlossDamageIndicatorPresentation edited) =>
            edited.motion.verticalAcceleration = value,
      ),
    ),
    _decimal(
      label: huiText('Spin'),
      tip: huiText('Degrees per second. -1440..1440.'),
      path: '$path.motion.spinDegreesPerSecond',
      value: presentation.motion.spinDegreesPerSecond,
      step: 5,
      onChanged: (double value) => mutate(
        'indicator spin',
        (GlossDamageIndicatorPresentation edited) =>
            edited.motion.spinDegreesPerSecond = value,
      ),
    ),
    _decimal(
      label: huiText('Start scale'),
      tip: huiText('Scale at spawn. 0..16.'),
      path: '$path.transform.startScale',
      value: presentation.transform.startScale,
      onChanged: (double value) => mutate(
        'indicator start scale',
        (GlossDamageIndicatorPresentation edited) =>
            edited.transform.startScale = value,
      ),
    ),
    _decimal(
      label: huiText('End scale'),
      tip: huiText('Scale at expiry. 0..16.'),
      path: '$path.transform.endScale',
      value: presentation.transform.endScale,
      onChanged: (double value) => mutate(
        'indicator end scale',
        (GlossDamageIndicatorPresentation edited) =>
            edited.transform.endScale = value,
      ),
    ),
    _decimal(
      label: huiText('Fade start'),
      tip: huiText('Lifetime fraction where fading begins. 0..1.'),
      path: '$path.transform.fadeStartFraction',
      value: presentation.transform.fadeStartFraction,
      onChanged: (double value) => mutate(
        'indicator fade start',
        (GlossDamageIndicatorPresentation edited) =>
            edited.transform.fadeStartFraction = value,
      ),
    ),
    DisplayStyleEditor(
      style: presentation.style,
      defaults: defaultHologramDisplayStyle(),
      issues: _issuesFor('$path.style'),
      onChanged: (String label, HuiIconStyle? value) => mutate(
        label,
        (GlossDamageIndicatorPresentation edited) =>
            edited.style = value ?? defaultHologramDisplayStyle(),
      ),
    ),
    HologramBoxEditor(
      box: presentation.box,
      sectionKey: '$path.box',
      issues: _issuesFor('$path.box'),
      mutate: (String label, void Function(GlossHologramBox) edit) => mutate(
        label,
        (GlossDamageIndicatorPresentation edited) => edit(edited.box),
      ),
    ),
    ParticleLayersEditor(
      layers: presentation.particleLayers,
      sectionKey: 'damage-indicators.$path.particleLayers',
      mutate: (String label, void Function(List<GlossParticleLayer>) edit) =>
          mutate(
            label,
            (GlossDamageIndicatorPresentation edited) =>
                edit(edited.particleLayers),
          ),
    ),
    AdvancedOnly(
      child: DisplayRefreshEditor(
        refresh: presentation.refresh,
        onChanged: (String label, GlossDisplayRefresh next) => mutate(
          label,
          (GlossDamageIndicatorPresentation edited) => edited.refresh = next,
        ),
      ),
    ),
  ];

  Widget _audience(GlossDamageIndicatorsDoc doc) => AppSection(
    title: huiText('Audience'),
    memoryKey: 'damage-indicators.audience',
    tip: huiText('Evaluated independently for every viewer.'),
    children: <Widget>[
      _condition(
        label: huiText('Viewer condition'),
        value: doc.audience.when,
        path: r'$.audience.when',
        onInput: (String value) => _mutate(
          'indicator audience condition',
          (GlossDamageIndicatorsDoc edited) => edited.audience.when = value,
        ),
      ),
    ],
  );

  Widget _condition({
    required String label,
    required String value,
    required String path,
    required void Function(String value) onInput,
  }) => Field(
    label: label,
    forId: _idFor(path),
    docKey: 'condition.when',
    problems: _issuesFor(path),
    child: TextInput(
      id: _idFor(path),
      value: value,
      size: controlSize(Density.dense),
      fullWidth: true,
      placeholder: huiText("viewer.world == 'world'"),
      onChanged: onInput,
      styles: technicalInputStyles,
      attributes: technicalInputAttributes,
    ),
  );

  Widget _integer({
    required String label,
    required String path,
    required int value,
    required void Function(int value) onChanged,
    String? tip,
  }) => Field(
    label: label,
    forId: _idFor(path),
    tip: tip,
    problems: _issuesFor(path),
    child: NumberField(
      id: _idFor(path),
      value: value.toDouble(),
      step: 1,
      decimals: 0,
      integer: true,
      onChanged: (double parsed) => onChanged(parsed.round()),
    ),
  );

  Widget _decimal({
    required String label,
    required String path,
    required double value,
    required void Function(double value) onChanged,
    String? tip,
    double step = 0.05,
    int decimals = 2,
  }) => Field(
    label: label,
    forId: _idFor(path),
    tip: tip,
    problems: _issuesFor(path),
    child: NumberField(
      id: _idFor(path),
      value: value,
      step: step,
      decimals: decimals,
      onChanged: onChanged,
    ),
  );

  void _mutate(
    String label,
    void Function(GlossDamageIndicatorsDoc doc) change,
  ) => store.mutateDamageIndicators(label, change);

  void _mutateStyle(
    bool healing,
    String label,
    void Function(GlossDamageIndicatorStyle style) change,
  ) => _mutate(
    label,
    (GlossDamageIndicatorsDoc doc) =>
        change(healing ? doc.healing : doc.damage),
  );

  void _mutateVariant(
    bool healing,
    int index,
    String label,
    void Function(GlossDamageIndicatorVariant variant) change,
  ) => _mutateStyle(healing, label, (GlossDamageIndicatorStyle style) {
    if (index < style.variants.length) change(style.variants[index]);
  });
}
