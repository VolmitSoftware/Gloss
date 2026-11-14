/// Particle layers on any in-game render: one kit section per layer, named
/// by its id, with its move and delete buttons on the title line. The fields
/// most layers change come first; spacing, offsets, timing details and
/// priority sit under More. The show condition and the raw particle object
/// are Advanced only.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/jaspr.dart' show Component;

import '../../l10n/hui_localizations.dart';
import '../../logic/gloss_show.dart';
import '../../model/json_codec.dart';
import '../../model/particle_layer.dart';
import '../../model/vec3.dart';
import '../../ui/color_field.dart';
import '../../ui/color_value.dart';
import '../../ui/density.dart';
import '../../ui/field.dart';
import '../../ui/icon_button.dart';
import '../../ui/mode.dart';
import '../../ui/number_field.dart';
import '../../ui/reorder_list.dart';
import '../../ui/section.dart';
import '../../ui/technical_input.dart';
import '../../ui/toggle_choice.dart';
import '../../ui/vec3_field.dart';
import 'extras_editor.dart';
import 'preview_expr_field.dart';

typedef ParticleLayersMutation = void Function(
  String label,
  void Function(List<GlossParticleLayer> layers) edit,
);

/// Characters a section key may hold that an element id should not.
final RegExp _idUnsafe = RegExp(r'[^A-Za-z0-9_-]');

class ParticleLayersEditor extends StatelessWidget {
  const ParticleLayersEditor({
    required this.layers,
    required this.mutate,
    this.sectionKey = 'particle-layers',
    this.initiallyOpen = false,
    super.key,
  });

  final List<GlossParticleLayer> layers;
  final ParticleLayersMutation mutate;
  final String sectionKey;
  final bool initiallyOpen;

  @override
  Widget build(BuildContext context) {
    final bool full = layers.length >= glossParticleMaxLayers;
    final String idBase = 'particles-${sectionKey.replaceAll(_idUnsafe, '-')}';
    return AppSection(
      title: huiText('Particle layers'),
      memoryKey: sectionKey,
      open: initiallyOpen,
      count: layers.length,
      actions: <Widget>[
        AppIconButton(
          icon: ArcaneIcon.plus(),
          label: huiText('Add particle layer'),
          disabled: full,
          onPressed: full
              ? null
              : () => mutate(
                  'add particle layer',
                  (List<GlossParticleLayer> edited) =>
                      edited.add(GlossParticleLayer(id: _nextId(edited))),
                ),
        ),
      ],
      children: <Widget>[
        for (int index = 0; index < layers.length; index++)
          _ParticleLayerSection(
            index: index,
            layer: layers[index],
            layerCount: layers.length,
            mutate: mutate,
            sectionKey: sectionKey,
            idBase: '$idBase-$index',
          ),
      ],
    );
  }

  String _nextId(List<GlossParticleLayer> existing) {
    int suffix = existing.length + 1;
    String candidate = 'particle-$suffix';
    while (existing.any((GlossParticleLayer layer) => layer.id == candidate)) {
      suffix++;
      candidate = 'particle-$suffix';
    }
    return candidate;
  }
}

class _ParticleLayerSection extends StatelessWidget {
  const _ParticleLayerSection({
    required this.index,
    required this.layer,
    required this.layerCount,
    required this.mutate,
    required this.sectionKey,
    required this.idBase,
  });

  final int index;
  final GlossParticleLayer layer;
  final int layerCount;
  final ParticleLayersMutation mutate;
  final String sectionKey;

  /// Prefix of every control id in this layer, unique on the page.
  final String idBase;

  void _edit(String label, void Function(GlossParticleLayer layer) edit) =>
      mutate(label, (List<GlossParticleLayer> layers) {
        if (index >= 0 && index < layers.length) edit(layers[index]);
      });

  void _move(int by) =>
      mutate('move particle layer', (List<GlossParticleLayer> layers) {
        final GlossParticleLayer moved = layers.removeAt(index);
        layers.insert(index + by, moved);
      });

  @override
  Widget build(BuildContext context) {
    final String name = layer.id.isEmpty
        ? huiText('Layer {number}', <String, Object?>{'number': index + 1})
        : layer.id;
    final ComponentSize size = controlSize(DensityScope.of(context));
    final bool first = index == 0;
    final bool last = index >= layerCount - 1;
    return AppSection(
      title: name,
      memoryKey: '$sectionKey.$index',
      actions: <Widget>[
        AppIconButton(
          icon: ArcaneIcon.arrowUp(),
          label: huiText('Move {label} up', <String, Object?>{'label': name}),
          disabled: first,
          onPressed: first ? null : () => _move(-1),
        ),
        AppIconButton(
          icon: ArcaneIcon.arrowDown(),
          label: huiText('Move {label} down', <String, Object?>{'label': name}),
          disabled: last,
          onPressed: last ? null : () => _move(1),
        ),
        AppIconButton(
          icon: ArcaneIcon.trash2(),
          label: huiText('Delete {label}', <String, Object?>{'label': name}),
          onPressed: () => mutate('remove particle layer', (
            List<GlossParticleLayer> layers,
          ) {
            if (index >= 0 && index < layers.length) {
              layers.removeAt(index);
            }
          }),
        ),
      ],
      children: <Widget>[
        Field(
          label: huiText('Id'),
          forId: '$idBase-id',
          required: true,
          child: TextInput(
            id: '$idBase-id',
            value: layer.id,
            size: size,
            fullWidth: true,
            placeholder: 'green-glow',
            onChanged: (String value) =>
                _edit('particle layer id', (GlossParticleLayer edited) {
                  edited.id = value;
                }),
            styles: technicalInputStyles,
            attributes: technicalInputAttributes,
          ),
        ),
        AdvancedOnly(child: _showField()),
        ..._targetFields(size),
        ..._geometryFields(size),
        _placementField(),
        ..._particleFields(size),
        ..._emissionFields(size),
        MoreFields(memoryKey: '$sectionKey.more', children: _rareFields()),
        ExtrasEditor(
          title: huiText('Data'),
          extensionKeys: false,
          advancedOnly: true,
          extras: layer.particle.toJson(),
          onChanged: (String label, Map<String, dynamic> next) =>
              _edit(label, (GlossParticleLayer edited) {
                edited.particle = GlossParticleSpec.fromJson(next, 'particle');
              }),
        ),
      ],
    );
  }

  /// Blank is the default, always shown; `true` is stored as no key.
  Widget _showField() => PreviewExprField(
    label: huiText('Show condition'),
    raw: layer.show == true ? null : layer.show,
    kind: PreviewExprKind.boolean,
    showCondition: true,
    scope: glossShowVariables.toSet(),
    categoryVariableNames: glossShowVariables,
    help: huiText('Boolean or expression. Leave blank to always show.'),
    onChanged: (Object? value) =>
        _edit('particle layer show', (GlossParticleLayer edited) {
          edited.show = huiDeepCopy(value ?? true);
        }),
  );

  List<Widget> _targetFields(ComponentSize size) => <Widget>[
    _choiceField(
      size: size,
      label: huiText('Target'),
      id: '$idBase-target',
      value: layer.target.scope,
      values: glossParticleTargetScopes,
      labels: const <String, String>{
        'projection': 'Projection',
        'component': 'Component',
        'text': 'Text',
        'line': 'Text line',
        'span': 'Named text span',
        'label': 'Label',
        'model': 'Model',
        'local': 'Local geometry',
      },
      onChanged: (String value) => _edit('particle target scope', (
        GlossParticleLayer edited,
      ) {
        edited.target.scope = value;
        edited.target.name = value == 'span'
            ? edited.target.name ?? 'highlight'
            : null;
        edited.target.component = value == 'component'
            ? edited.target.component ?? 'component-id'
            : null;
        edited.target.line = value == 'line' ? edited.target.line ?? 1 : null;
      }),
    ),
    if (layer.target.scope == 'span')
      Field(
        label: huiText('Name'),
        forId: '$idBase-span',
        required: true,
        hint: '<particles:name>text</particles>',
        child: TextInput(
          id: '$idBase-span',
          value: layer.target.name ?? '',
          size: size,
          fullWidth: true,
          placeholder: 'highlight',
          onChanged: (String value) =>
              _edit('particle span name', (GlossParticleLayer edited) {
                edited.target.name = value;
              }),
          styles: technicalInputStyles,
          attributes: technicalInputAttributes,
        ),
      ),
    if (layer.target.scope == 'component')
      Field(
        label: huiText('Component id'),
        forId: '$idBase-component',
        required: true,
        child: TextInput(
          id: '$idBase-component',
          value: layer.target.component ?? '',
          size: size,
          fullWidth: true,
          placeholder: 'title',
          onChanged: (String value) =>
              _edit('particle component id', (GlossParticleLayer edited) {
                edited.target.component = value;
              }),
          styles: technicalInputStyles,
          attributes: technicalInputAttributes,
        ),
      ),
    if (layer.target.scope == 'line')
      _numberField(
        label: huiText('Line'),
        id: '$idBase-line',
        required: true,
        value: (layer.target.line ?? 1).toDouble(),
        integer: true,
        min: 1,
        onChanged: (double value) =>
            _edit('particle line target', (GlossParticleLayer edited) {
              edited.target.line = value.round();
            }),
      ),
  ];

  List<Widget> _geometryFields(ComponentSize size) {
    final GlossParticleGeometry geometry = layer.geometry;
    return <Widget>[
      _choiceField(
        size: size,
        label: huiText('Shape'),
        id: '$idBase-shape',
        value: geometry.type,
        values: glossParticleGeometryTypes,
        labels: const <String, String>{
          'point': 'Point',
          'line': 'Line',
          'polyline': 'Polyline',
          'outline': 'Frame outline',
          'filledPlane': 'Filled plane',
          'cuboid': 'Cuboid',
          'letterBounds': 'Letter boxes',
          'glyphOutline': 'Glyph outlines',
          'glyphFill': 'Glyph fill',
        },
        onChanged: _setGeometryType,
      ),
      if (geometry.type == 'line') ...<Widget>[
        _vectorField(
          label: huiText('Start'),
          id: '$idBase-from',
          value: geometry.from ?? Vec3.zero(),
          onChanged: (Vec3 value) =>
              _edit('particle line start', (GlossParticleLayer edited) {
                edited.geometry.from = value;
              }),
        ),
        _vectorField(
          label: huiText('End'),
          id: '$idBase-to',
          value: geometry.to ?? Vec3(1, 0, 0),
          onChanged: (Vec3 value) =>
              _edit('particle line end', (GlossParticleLayer edited) {
                edited.geometry.to = value;
              }),
        ),
      ],
      if (geometry.type == 'polyline') _polylineField(geometry),
      if (<String>{
        'outline',
        'filledPlane',
        'cuboid',
      }.contains(geometry.type)) ...<Widget>[
        _optionalDimension(
          label: huiText('Width'),
          id: '$idBase-width',
          value: geometry.width,
          suggested: 1,
          onChanged: (double? value) =>
              _edit('particle geometry width', (GlossParticleLayer edited) {
                edited.geometry.width = value;
              }),
        ),
        _optionalDimension(
          label: huiText('Height'),
          id: '$idBase-height',
          value: geometry.height,
          suggested: 1,
          onChanged: (double? value) =>
              _edit('particle geometry height', (GlossParticleLayer edited) {
                edited.geometry.height = value;
              }),
        ),
        if (geometry.type == 'cuboid')
          _optionalDimension(
            label: huiText('Depth'),
            id: '$idBase-depth',
            value: geometry.depth,
            suggested: 1,
            onChanged: (double? value) =>
                _edit('particle geometry depth', (GlossParticleLayer edited) {
                  edited.geometry.depth = value;
                }),
          ),
      ],
    ];
  }

  void _setGeometryType(String value) =>
      _edit('particle geometry shape', (GlossParticleLayer edited) {
        final GlossParticleGeometry geometry = edited.geometry;
        geometry.type = value;
        if (value == 'line') {
          geometry.from ??= Vec3.zero();
          geometry.to ??= Vec3(1, 0, 0);
        }
        if (value == 'polyline' && geometry.points.length < 2) {
          geometry.points = <Vec3>[Vec3.zero(), Vec3(1, 0, 0)];
        }
      });

  /// The points in order; a polyline keeps at least two, so Remove goes
  /// away at two.
  Widget _polylineField(GlossParticleGeometry geometry) => Field(
    label: huiText('Points'),
    required: true,
    trailing: AppIconButton(
      icon: ArcaneIcon.plus(),
      label: huiText('Add point'),
      onPressed: () =>
          _edit('add particle polyline point', (GlossParticleLayer edited) {
            final Vec3 next = edited.geometry.points.isEmpty
                ? Vec3.zero()
                : edited.geometry.points.last.copy();
            edited.geometry.points.add(next);
          }),
    ),
    child: ReorderList(
      label: huiText('Points'),
      itemCount: geometry.points.length,
      onReorder: (int from, int to) =>
          _edit('move particle polyline point', (GlossParticleLayer edited) {
            final List<Vec3> points = edited.geometry.points;
            if (from < 0 || from >= points.length) return;
            final Vec3 moved = points.removeAt(from);
            points.insert(to.clamp(0, points.length), moved);
          }),
      onRemove: geometry.points.length <= 2
          ? null
          : (int pointIndex) => _edit('remove particle polyline point', (
              GlossParticleLayer edited,
            ) {
              if (pointIndex < edited.geometry.points.length) {
                edited.geometry.points.removeAt(pointIndex);
              }
            }),
      itemBuilder: (int pointIndex) => Vec3Field(
        id: '$idBase-point-$pointIndex',
        label: '${huiText('Points')} ${pointIndex + 1}',
        value: geometry.points[pointIndex],
        step: 0.1,
        decimals: 3,
        onChanged: (Vec3 value) =>
            _edit('particle polyline point', (GlossParticleLayer edited) {
              if (pointIndex < edited.geometry.points.length) {
                edited.geometry.points[pointIndex] = value;
              }
            }),
      ),
    ),
  );

  Widget _placementField() => Field(
    label: huiText('Placement'),
    child: ToggleChoice<String>(
      id: '$idBase-placement',
      label: huiText('Placement'),
      value: layer.placement.layer,
      options: <ChoiceOption<String>>[
        for (final String value in <String>[
          ...glossParticlePlacementLayers,
          if (!glossParticlePlacementLayers.contains(layer.placement.layer))
            layer.placement.layer,
        ])
          ChoiceOption<String>(
            value,
            const <String, String>{
                  'behind': 'Behind',
                  'front': 'Front',
                  'center': 'Centered',
                }[value] ??
                value,
          ),
      ],
      onChanged: (String value) =>
          _edit('particle placement plane', (GlossParticleLayer edited) {
            edited.placement.layer = value;
          }),
    ),
  );

  List<Widget> _particleFields(ComponentSize size) {
    final bool dust =
        layer.particle.key.trim().toLowerCase() == 'minecraft:dust';
    return <Widget>[
      Field(
        label: huiText('Particle'),
        forId: '$idBase-particle',
        required: true,
        child: Component.fragment(<Widget>[
          ToggleChoice<String>(
            label: huiText('Particle'),
            value: dust
                ? 'minecraft:dust'
                : layer.particle.key == 'minecraft:soul'
                ? 'minecraft:soul'
                : 'custom',
            options: <ChoiceOption<String>>[
              const ChoiceOption<String>('minecraft:dust', 'dust'),
              const ChoiceOption<String>('minecraft:soul', 'soul'),
              ChoiceOption<String>('custom', huiText('Custom')),
            ],
            onChanged: (String value) {
              if (value == 'custom') return;
              _setParticleKey(value);
            },
          ),
          TextInput(
            id: '$idBase-particle',
            value: layer.particle.key,
            size: size,
            fullWidth: true,
            placeholder: 'minecraft:dust',
            onChanged: _setParticleKey,
            styles: technicalInputStyles,
            attributes: technicalInputAttributes,
          ),
        ]),
      ),
      if (dust) ...<Widget>[
        Field(
          label: huiText('Color'),
          forId: '$idBase-color',
          required: true,
          child: ColorField(
            id: '$idBase-color',
            value: layer.particle.color ?? '#ffffff',
            format: HuiColorFormat.rgb,
            label: huiText('Color'),
            onChanged: (String value) =>
                _edit('particle dust color', (GlossParticleLayer edited) {
                  edited.particle.color = value;
                }),
          ),
        ),
        _numberField(
          label: huiText('Size'),
          id: '$idBase-size',
          value: layer.particle.size ?? 1,
          min: 0.01,
          max: 4,
          step: 0.05,
          decimals: 2,
          onChanged: (double value) =>
              _edit('particle dust size', (GlossParticleLayer edited) {
                edited.particle.size = value;
              }),
        ),
      ],
      _numberField(
        label: huiText('Count'),
        id: '$idBase-count',
        value: layer.particle.count.toDouble(),
        integer: true,
        min: 1,
        max: 64,
        onChanged: (double value) =>
            _edit('particle count', (GlossParticleLayer edited) {
              edited.particle.count = value.round();
            }),
      ),
    ];
  }

  void _setParticleKey(String value) =>
      _edit('particle type', (GlossParticleLayer edited) {
        edited.particle.key = value;
        if (value.trim().toLowerCase() == 'minecraft:dust') {
          edited.particle.color ??= '#ffffff';
          edited.particle.size ??= 1;
        } else {
          edited.particle.color = null;
          edited.particle.size = null;
        }
      });

  List<Widget> _emissionFields(ComponentSize size) => <Widget>[
    _choiceField(
      size: size,
      label: huiText('Pattern'),
      id: '$idBase-pattern',
      value: layer.emission.pattern,
      values: glossParticleEmissionPatterns,
      labels: const <String, String>{
        'steady': 'Steady',
        'chase': 'Chase',
        'pulse': 'Pulse',
        'twinkle': 'Twinkle',
        'scan': 'Scan',
        'corners': 'Corners',
      },
      onChanged: (String value) =>
          _edit('particle emission pattern', (GlossParticleLayer edited) {
            edited.emission.pattern = value;
          }),
    ),
    _numberField(
      label: huiText('Interval'),
      id: '$idBase-interval',
      value: layer.emission.intervalTicks.toDouble(),
      integer: true,
      min: 1,
      max: 200,
      suffix: huiText('ticks'),
      onChanged: (double value) =>
          _edit('particle emission interval', (GlossParticleLayer edited) {
            edited.emission.intervalTicks = value.round();
          }),
    ),
  ];

  /// Rare but not expert: under the layer's More.
  List<Widget> _rareFields() => <Widget>[
    _numberField(
      label: huiText('View distance'),
      id: '$idBase-view',
      value: layer.viewDistance,
      min: 4,
      max: 128,
      step: 1,
      suffix: huiText('blocks'),
      onChanged: (double value) =>
          _edit('particle view distance', (GlossParticleLayer edited) {
            edited.viewDistance = value;
          }),
    ),
    _numberField(
      label: huiText('Padding'),
      id: '$idBase-padding',
      value: layer.geometry.padding,
      min: 0,
      max: 16,
      step: 0.01,
      onChanged: (double value) =>
          _edit('particle geometry padding', (GlossParticleLayer edited) {
            edited.geometry.padding = value;
          }),
    ),
    _numberField(
      label: huiText('Spacing'),
      id: '$idBase-spacing',
      value: layer.geometry.spacing,
      min: 0.02,
      max: 16,
      step: 0.01,
      onChanged: (double value) =>
          _edit('particle geometry spacing', (GlossParticleLayer edited) {
            edited.geometry.spacing = value;
          }),
    ),
    _numberField(
      label: huiText('Distance'),
      id: '$idBase-plane-depth',
      value: layer.placement.depth,
      min: 0,
      max: 16,
      step: 0.01,
      onChanged: (double value) =>
          _edit('particle placement depth', (GlossParticleLayer edited) {
            edited.placement.depth = value;
          }),
    ),
    _vectorField(
      label: huiText('Offset'),
      id: '$idBase-offset',
      value: layer.placement.offset,
      onChanged: (Vec3 value) =>
          _edit('particle placement offset', (GlossParticleLayer edited) {
            edited.placement.offset = value;
          }),
    ),
    _numberField(
      label: huiText('Speed'),
      id: '$idBase-speed',
      value: layer.particle.speed,
      min: 0,
      max: 10,
      step: 0.05,
      onChanged: (double value) =>
          _edit('particle speed', (GlossParticleLayer edited) {
            edited.particle.speed = value;
          }),
    ),
    _vectorField(
      label: huiText('Spread'),
      id: '$idBase-spread',
      value: layer.particle.spread,
      onChanged: (Vec3 value) =>
          _edit('particle spread', (GlossParticleLayer edited) {
            edited.particle.spread = value;
          }),
    ),
    _numberField(
      label: huiText('Cycle'),
      id: '$idBase-period',
      value: layer.emission.periodTicks.toDouble(),
      integer: true,
      min: 1,
      max: 72000,
      suffix: huiText('ticks'),
      onChanged: (double value) =>
          _edit('particle emission period', (GlossParticleLayer edited) {
            edited.emission.periodTicks = value.round();
          }),
    ),
    _numberField(
      label: huiText('Seed'),
      id: '$idBase-seed',
      value: layer.emission.seed.toDouble(),
      integer: true,
      onChanged: (double value) =>
          _edit('particle emission seed', (GlossParticleLayer edited) {
            edited.emission.seed = value.round();
          }),
    ),
    _numberField(
      label: huiText('Priority'),
      id: '$idBase-priority',
      value: layer.priority.toDouble(),
      integer: true,
      min: -1000,
      max: 1000,
      onChanged: (double value) =>
          _edit('particle layer priority', (GlossParticleLayer edited) {
            edited.priority = value.round();
          }),
    ),
  ];

  /// Five or more fixed values: a select. A value outside the list stays
  /// selectable, so an unknown one is shown rather than replaced.
  Widget _choiceField({
    required ComponentSize size,
    required String label,
    required String id,
    required String value,
    required List<String> values,
    required Map<String, String> labels,
    required void Function(String value) onChanged,
  }) => Field(
    label: label,
    forId: id,
    child: ArcaneSelect(
      id: id,
      value: value,
      size: size,
      fullWidth: true,
      options: <ArcaneSelectOption>[
        for (final String option in <String>[
          ...values,
          if (!values.contains(value)) value,
        ])
          ArcaneSelectOption(label: labels[option] ?? option, value: option),
      ],
      onChanged: onChanged,
    ),
  );

  Widget _numberField({
    required String label,
    required String id,
    required double value,
    required void Function(double value) onChanged,
    double? min,
    double? max,
    double step = 0.05,
    int decimals = 3,
    bool integer = false,
    bool required = false,
    String? suffix,
  }) => Field(
    label: label,
    forId: id,
    required: required,
    child: NumberField(
      id: id,
      value: value,
      min: min,
      max: max,
      step: integer ? 1 : step,
      decimals: decimals,
      integer: integer,
      suffix: suffix,
      onChanged: onChanged,
    ),
  );

  Widget _vectorField({
    required String label,
    required String id,
    required Vec3 value,
    required void Function(Vec3 value) onChanged,
  }) => Field(
    label: label,
    forId: id,
    child: Vec3Field(
      id: id,
      label: label,
      value: value,
      step: 0.1,
      decimals: 3,
      onChanged: onChanged,
    ),
  );

  /// Width, height or depth that fits the target when omitted: Automatic
  /// removes the key, Custom writes [suggested] and shows the number.
  Widget _optionalDimension({
    required String label,
    required String id,
    required double? value,
    required double suggested,
    required void Function(double? value) onChanged,
  }) => Field(
    label: label,
    forId: value == null ? null : id,
    child: Component.fragment(<Widget>[
      ToggleChoice<bool>(
        label: label,
        value: value == null,
        options: <ChoiceOption<bool>>[
          ChoiceOption<bool>(true, huiText('Automatic')),
          ChoiceOption<bool>(false, huiText('Custom')),
        ],
        onChanged: (bool automatic) {
          if (automatic == (value == null)) return;
          onChanged(automatic ? null : suggested);
        },
      ),
      if (value != null)
        NumberField(
          id: id,
          value: value,
          min: 0,
          max: 128,
          step: 0.05,
          decimals: 3,
          suffix: huiText('blocks'),
          onChanged: onChanged,
        ),
    ]),
  );
}
