/// Inspector body for a Gloss bubble-style document: the look, wrap and
/// timing, the stack, behaviour, expression motion, the shimmer band, the
/// shared display editors and the optional select rule.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import '../../logic/bubble_motion.dart';
import '../../logic/gloss_show.dart';
import '../../logic/gloss_text.dart';
import '../../logic/validation.dart';
import '../../model/gloss_hologram_box.dart';
import '../../model/model.dart';
import '../../state/editor_store.dart';
import '../../ui/badge.dart';
import '../../ui/color_field.dart';
import '../../ui/color_value.dart';
import '../../ui/density.dart';
import '../../ui/duration_field.dart';
import '../../ui/duration_value.dart';
import '../../ui/field.dart';
import '../../ui/icon_button.dart';
import '../../ui/info_tip.dart';
import '../../ui/menu.dart';
import '../../ui/mode.dart';
import '../../ui/number_field.dart';
import '../../ui/pane_header.dart';
import '../../ui/section.dart';
import '../../ui/switch_field.dart';
import '../../ui/toggle_choice.dart';
import '../../ui/vec3_field.dart';
import '../../ui/technical_input.dart';
import '../gloss/gloss_text_line.dart';
import 'display_refresh_editor.dart';
import 'display_style_editor.dart';
import 'gloss_visibility_editor.dart';
import 'hologram_box_editor.dart';
import 'particle_layers_editor.dart';
import 'preview_expr_field.dart';

import 'package:gloss_editor/l10n/hui_localizations.dart';

const String _defaultFormat = '{message}';
const double _defaultStackDistance = 0.26;
const int _defaultMaxPerSender = 4;

class BubbleInspector extends StatelessWidget {
  const BubbleInspector({required this.store, super.key});

  final EditorStore store;

  List<HuiIssue> _issuesFor(String path) => store.issues
      .where((HuiIssue issue) => issue.path.startsWith(path))
      .toList();

  void _mutate(String label, void Function(GlossBubbleStyleDoc edited) edit) =>
      store.mutateBubbleStyle(label, edit);

  @override
  Widget build(BuildContext context) {
    final GlossBubbleStyleDoc? doc = store.bubbleStyleDoc;
    if (doc == null) return const dom.div(<Widget>[]);
    return DenseBox(
      classes: 'hui-inspector-body is-bubble',
      children: <Widget>[
        PaneHeader(
          kind: huiText('Bubble style'),
          title: store.menuId,
          docKey: 'bubble.id',
          status: AdvancedOnly(
            child: StatusBadge(
              huiText('revision {revision}', <String, Object?>{
                'revision': doc.revision,
              }),
            ),
          ),
        ),
        _look(doc),
        _timing(doc),
        _stack(doc),
        _behavior(doc),
        _motion(doc),
        _shimmer(doc),
        DisplayStyleEditor(
          style: doc.style,
          defaults: defaultHologramDisplayStyle(),
          issues: _issuesFor(r'$.style'),
          onChanged: (String label, HuiIconStyle? value) => _mutate(
            label,
            (GlossBubbleStyleDoc edited) =>
                edited.style = value ?? defaultHologramDisplayStyle(),
          ),
        ),
        HologramBoxEditor(
          box: doc.box,
          sectionKey: 'bubble.box',
          issues: _issuesFor(r'$.box'),
          mutate: (String label, void Function(GlossHologramBox) edit) =>
              _mutate(label, (GlossBubbleStyleDoc edited) => edit(edited.box)),
        ),
        ParticleLayersEditor(
          layers: doc.particleLayers,
          sectionKey: 'bubble.particleLayers',
          mutate:
              (String label, void Function(List<GlossParticleLayer>) edit) =>
                  _mutate(
                    label,
                    (GlossBubbleStyleDoc edited) => edit(edited.particleLayers),
                  ),
        ),
        _selection(doc),
        AdvancedOnly(
          child: DisplayRefreshEditor(
            refresh: doc.refresh,
            onChanged: (String label, GlossDisplayRefresh next) => _mutate(
              label,
              (GlossBubbleStyleDoc edited) => edited.refresh = next,
            ),
          ),
        ),
        AdvancedOnly(
          child: GlossVisibilityEditor(
            raw: doc.extras['show'],
            sectionKey: 'bubble.visibility',
            issues: store.issues
                .where((HuiIssue issue) => issue.path == r'$.show')
                .toList(),
            onChanged: (Object? value) => _mutate(
              'visibility',
              (GlossBubbleStyleDoc edited) =>
                  setGlossShow(edited.extras, value),
            ),
          ),
        ),
      ],
    );
  }

  Widget _look(GlossBubbleStyleDoc doc) {
    final ComponentSize size = controlSize(Density.dense);
    return AppSection(
      title: huiText('Look'),
      memoryKey: 'bubble.look',
      children: <Widget>[
        Field(
          label: huiText('Prefix'),
          forId: 'bubble-prefix',
          docKey: 'bubble.prefix',
          problems: _issuesFor(r'$.prefix'),
          child: dom.div(<Widget>[
            TextInput(
              id: 'bubble-prefix',
              value: doc.prefix,
              size: size,
              fullWidth: true,
              placeholder: '&7',
              attributes: const <String, String>{
                'autocomplete': 'off',
                'spellcheck': 'false',
              },
              onChanged: (String value) =>
                  _mutate('bubble prefix', (GlossBubbleStyleDoc edited) {
                    edited.prefix = value;
                    edited.absentKeys.remove('prefix');
                  }),
            ),
            ArcaneFieldDescription.child(
              GlossTextLine(
                render: renderGlossLine(
                  '${doc.effectivePrefix}${huiText('Like this bubble line')}',
                  richText: true,
                ),
              ),
            ),
          ]),
        ),
        Field(
          label: huiText('Message format'),
          forId: 'bubble-format',
          defaultValue: _defaultFormat,
          onReset: doc.format == _defaultFormat
              ? null
              : () => _mutate(
                  'bubble format',
                  (GlossBubbleStyleDoc edited) =>
                      edited.format = _defaultFormat,
                ),
          problems: _issuesFor(r'$.format'),
          child: TextInput(
            id: 'bubble-format',
            value: doc.format,
            size: size,
            fullWidth: true,
            placeholder: _defaultFormat,
            attributes: const <String, String>{
              'autocomplete': 'off',
              'spellcheck': 'false',
            },
            onChanged: (String value) => _mutate(
              'bubble format',
              (GlossBubbleStyleDoc edited) => edited.format = value,
            ),
          ),
        ),
        Field(
          label: huiText('Offset'),
          forId: 'bubble-offset',
          docKey: 'bubble.offset',
          problems: _issuesFor(r'$.offset'),
          child: Vec3Field(
            id: 'bubble-offset',
            label: huiText('Offset'),
            value: Vec3(doc.offset[0], doc.offset[1], doc.offset[2]),
            onChanged: (Vec3 value) => _mutate(
              'bubble offset',
              (GlossBubbleStyleDoc edited) =>
                  edited.setOffset(value.x, value.y, value.z),
            ),
          ),
        ),
      ],
    );
  }

  Widget _timing(GlossBubbleStyleDoc doc) => AppSection(
    title: huiText('Wrap and timing'),
    memoryKey: 'bubble.timing',
    children: <Widget>[
      _intField(
        label: huiText('Word wrap'),
        id: 'bubble-word-wrap',
        docKey: 'bubble.wordWrapChars',
        path: r'$.wordWrapChars',
        value: doc.wordWrapChars,
        onChanged: (int value) =>
            _mutate('bubble wrap', (GlossBubbleStyleDoc edited) {
              edited.wordWrapChars = value;
              edited.absentKeys.remove('wordWrapChars');
            }),
      ),
      _intField(
        label: huiText('Lifetime'),
        id: 'bubble-lifetime',
        docKey: 'bubble.maxAliveMs',
        path: r'$.maxAliveMs',
        value: doc.maxAliveMs,
        unit: HuiDurationUnit.milliseconds,
        step: 500,
        onChanged: (int value) =>
            _mutate('bubble lifetime', (GlossBubbleStyleDoc edited) {
              edited.maxAliveMs = value;
              edited.absentKeys.remove('maxAliveMs');
            }),
      ),
    ],
  );

  Widget _stack(GlossBubbleStyleDoc doc) => AppSection(
    title: huiText('Stack'),
    memoryKey: 'bubble.stack',
    children: <Widget>[
      Field(
        label: huiText('Stack distance'),
        forId: 'bubble-stack-distance',
        defaultValue: '$_defaultStackDistance',
        onReset: doc.stackDistance == _defaultStackDistance
            ? null
            : () => _mutate(
                'bubble stack distance',
                (GlossBubbleStyleDoc edited) =>
                    edited.stackDistance = _defaultStackDistance,
              ),
        problems: _issuesFor(r'$.stackDistance'),
        child: NumberField(
          id: 'bubble-stack-distance',
          value: doc.stackDistance,
          step: 0.01,
          decimals: 3,
          onChanged: (double value) => _mutate(
            'bubble stack distance',
            (GlossBubbleStyleDoc edited) => edited.stackDistance = value,
          ),
        ),
      ),
      _intField(
        label: huiText('Bubbles per sender'),
        id: 'bubble-max-per-sender',
        path: r'$.maxPerSender',
        value: doc.maxPerSender,
        defaultValue: '$_defaultMaxPerSender',
        onReset: doc.maxPerSender == _defaultMaxPerSender
            ? null
            : () => _mutate(
                'bubble max per sender',
                (GlossBubbleStyleDoc edited) =>
                    edited.maxPerSender = _defaultMaxPerSender,
              ),
        onChanged: (int value) => _mutate(
          'bubble max per sender',
          (GlossBubbleStyleDoc edited) => edited.maxPerSender = value,
        ),
      ),
      Field(
        label: huiText('Overflow'),
        problems: _issuesFor(r'$.overflow'),
        child: ToggleChoice<String>(
          label: huiText('Overflow'),
          value: doc.overflow.trim().toLowerCase(),
          onChanged: (String value) => _mutate(
            'overflow',
            (GlossBubbleStyleDoc edited) => edited.overflow = value,
          ),
          options: <ChoiceOption<String>>[
            ChoiceOption<String>('replace-oldest', huiText('Replace oldest')),
            ChoiceOption<String>('reject-new', huiText('Reject new')),
          ],
        ),
      ),
    ],
  );

  Widget _behavior(GlossBubbleStyleDoc doc) => AppSection(
    title: huiText('Behavior'),
    memoryKey: 'bubble.behavior',
    children: <Widget>[
      SwitchField(
        label: huiText('Follow player'),
        docKey: 'bubble.followPlayer',
        value: doc.followPlayer,
        onChanged: (bool value) =>
            _mutate('bubble followPlayer', (GlossBubbleStyleDoc edited) {
              edited.followPlayer = value;
              edited.absentKeys.remove('followPlayer');
            }),
      ),
      SwitchField(
        label: huiText('Hide own'),
        docKey: 'bubble.hideOwn',
        value: doc.hideOwn,
        onChanged: (bool value) =>
            _mutate('bubble hideOwn', (GlossBubbleStyleDoc edited) {
              edited.hideOwn = value;
              edited.absentKeys.remove('hideOwn');
            }),
      ),
      Field(
        label: huiText('Excluded worlds'),
        forId: 'bubble-excluded-worlds',
        tip: huiText('World names, separated by commas.'),
        problems: _issuesFor(r'$.blacklistWorlds'),
        child: TextInput(
          id: 'bubble-excluded-worlds',
          value: doc.blacklistWorlds.join(', '),
          size: controlSize(Density.dense),
          fullWidth: true,
          styles: technicalInputStyles,
          attributes: technicalInputAttributes,
          onChanged: (String value) => _mutate(
            'bubble excluded worlds',
            (GlossBubbleStyleDoc edited) => edited.blacklistWorlds = <String>[
              for (final String world in value.split(','))
                if (world.trim().isNotEmpty) world.trim(),
            ],
          ),
        ),
      ),
    ],
  );

  Widget _motion(GlossBubbleStyleDoc doc) => AppSection(
    title: huiText('Motion'),
    memoryKey: 'bubble.motion',
    tip: huiText(
      'Every field is a live expression. Use t (0..1), remaining, ageMs, '
      'lifetimeMs, stackIndex, stackCount, lineCount, stackY, seed and pi.',
    ),
    actions: <Widget>[
      AppMenu(
        id: 'hui-bubble-motion-preset',
        label: huiText('Preset'),
        alignEnd: true,
        entries: <AppMenuEntry>[
          _motionPreset(huiText('Static'), GlossBubbleMotion.identity),
          _motionPreset(
            huiText('Runtime fly-away'),
            GlossBubbleMotion.runtimeDefaults,
          ),
          _motionPreset(huiText('Fly up'), _flyUpMotion),
          _motionPreset(huiText('Fade away'), _fadeMotion),
          _motionPreset(huiText('Shrink away'), _shrinkMotion),
          _motionPreset(huiText('Arc + fall'), _arcMotion),
        ],
      ),
    ],
    children: <Widget>[
      ..._motionVector(
        title: huiText('Translation (blocks)'),
        path: r'$.motion.translation',
        vector: doc.motion.translation,
        onEdit: (GlossBubbleMotion edited) => edited.translation,
      ),
      ..._motionVector(
        title: huiText('Scale'),
        path: r'$.motion.scale',
        vector: doc.motion.scale,
        onEdit: (GlossBubbleMotion edited) => edited.scale,
      ),
      ..._motionVector(
        title: huiText('Rotation (degrees)'),
        path: r'$.motion.rotation',
        vector: doc.motion.rotation,
        onEdit: (GlossBubbleMotion edited) => edited.rotation,
      ),
      PreviewExprField(
        label: huiText('Opacity'),
        raw: doc.motion.opacity,
        kind: PreviewExprKind.string,
        required: true,
        placeholder: huiText('1 - smoothstep(0.7, 1, t)'),
        help: huiText('Runtime clamps the result to 0..1.'),
        scope: glossBubbleMotionVariables,
        issues: _issuesFor(r'$.motion.opacity'),
        onChanged: (Object? value) => _mutate(
          'bubble motion opacity',
          (GlossBubbleStyleDoc edited) =>
              edited.motion.opacity = value as String? ?? '1',
        ),
      ),
    ],
  );

  AppMenuEntry _motionPreset(
    String label,
    GlossBubbleMotion Function() create,
  ) => AppMenuEntry(
    label,
    () => _mutate(
      'bubble motion preset',
      (GlossBubbleStyleDoc edited) => edited.motion = create(),
    ),
  );

  /// One motion vector: its heading, then an expression per axis.
  List<Widget> _motionVector({
    required String title,
    required String path,
    required GlossBubbleMotionVector vector,
    required GlossBubbleMotionVector Function(GlossBubbleMotion motion) onEdit,
  }) => <Widget>[
    dom.h3(classes: 'ui-section-title', <Widget>[Text(title)]),
    for (final (String, String) axis in <(String, String)>[
      ('X', vector.x),
      ('Y', vector.y),
      ('Z', vector.z),
    ])
      PreviewExprField(
        label: huiText("{title} {value}", <String, Object?>{
          'title': title,
          'value': axis.$1,
        }),
        raw: axis.$2,
        kind: PreviewExprKind.string,
        required: true,
        placeholder: axis.$1 == 'Y' ? '8 * t' : '0',
        scope: glossBubbleMotionVariables,
        issues: _issuesFor('$path.${axis.$1.toLowerCase()}'),
        onChanged: (Object? value) => _mutate(
          'bubble motion ${axis.$1.toLowerCase()}',
          (GlossBubbleStyleDoc edited) {
            final GlossBubbleMotionVector target = onEdit(edited.motion);
            final String next =
                value as String? ?? (path.endsWith('scale') ? '1' : '0');
            switch (axis.$1) {
              case 'X':
                target.x = next;
              case 'Y':
                target.y = next;
              case 'Z':
                target.z = next;
            }
          },
        ),
      ),
  ];

  static GlossBubbleMotion _flyUpMotion() => GlossBubbleMotion(
    translation: GlossBubbleMotionVector(
      x: '0',
      y: '8 * smoothstep(0, 1, t)',
      z: '0',
    ),
    scale: GlossBubbleMotionVector.scaleDefaults(),
    rotation: GlossBubbleMotionVector.rotationDefaults(),
    opacity: '1',
  );

  static GlossBubbleMotion _fadeMotion() => GlossBubbleMotion(
    translation: GlossBubbleMotionVector(x: '0', y: '1.5 * t', z: '0'),
    scale: GlossBubbleMotionVector.scaleDefaults(),
    rotation: GlossBubbleMotionVector.rotationDefaults(),
    opacity: '1 - smoothstep(0.65, 1, t)',
  );

  static GlossBubbleMotion _shrinkMotion() => GlossBubbleMotion(
    translation: GlossBubbleMotionVector(x: '0', y: '2 * t', z: '0'),
    scale: GlossBubbleMotionVector(
      x: '1 - smoothstep(0.45, 1, t)',
      y: '1 - smoothstep(0.45, 1, t)',
      z: '1 - smoothstep(0.45, 1, t)',
    ),
    rotation: GlossBubbleMotionVector.rotationDefaults(),
    opacity: '1',
  );

  static GlossBubbleMotion _arcMotion() => GlossBubbleMotion(
    translation: GlossBubbleMotionVector(
      x: '4 * t',
      y: '5 * sin(pi * t) - 6 * t',
      z: '2 * sin(pi * 2 * t)',
    ),
    scale: GlossBubbleMotionVector(
      x: '1 - 0.35 * t',
      y: '1 - 0.35 * t',
      z: '1 - 0.35 * t',
    ),
    rotation: GlossBubbleMotionVector(x: '45 * t', y: '90 * t', z: '180 * t'),
    opacity: '1 - smoothstep(0.8, 1, t)',
  );

  Widget _shimmer(GlossBubbleStyleDoc doc) => AppSection(
    title: huiText('Shimmer'),
    memoryKey: 'bubble.shimmer',
    tip: huiText(
      'One solid three-glyph wave crosses the complete wrapped message. The '
      'first pass waits 400 ms after chat; the second runs during the final '
      '700 ms. Wrapped rows share one continuous band instead of restarting.',
    ),
    actions: <Widget>[
      AppMenu(
        id: 'hui-bubble-shimmer-preset',
        label: huiText('Preset'),
        alignEnd: true,
        entries: <AppMenuEntry>[
          AppMenuEntry(
            huiText('Original Gloss'),
            () => _mutate(
              'original Gloss shimmer preset',
              (GlossBubbleStyleDoc edited) =>
                  edited.shimmer = GlossBubbleShimmer(),
            ),
          ),
        ],
      ),
    ],
    children: <Widget>[
      SwitchField(
        label: huiText('At spawn'),
        tip: huiText(
          'Run one complete left-to-right sweep after the configured spawn '
          'delay.',
        ),
        value: doc.shimmer.spawn,
        onChanged: (bool value) => _mutate(
          'bubble spawn shimmer',
          (GlossBubbleStyleDoc edited) => edited.shimmer.spawn = value,
        ),
      ),
      SwitchField(
        label: huiText('At fly-away'),
        docKey: 'bubble.shimmer.flyAway',
        value: doc.shimmer.flyAway,
        onChanged: (bool value) => _mutate(
          'bubble fly-away shimmer',
          (GlossBubbleStyleDoc edited) => edited.shimmer.flyAway = value,
        ),
      ),
      Field(
        label: huiText('Band color'),
        forId: 'bubble-shimmer-color',
        docKey: 'bubble.shimmer.color',
        defaultValue: glossBubbleShimmerDefaultColor,
        onReset: doc.shimmer.color == glossBubbleShimmerDefaultColor
            ? null
            : () => _editShimmer(
                'color',
                (GlossBubbleShimmer shimmer) =>
                    shimmer.color = glossBubbleShimmerDefaultColor,
              ),
        problems: _issuesFor(r'$.shimmer.color'),
        child: ColorField(
          id: 'bubble-shimmer-color',
          value: doc.shimmer.color,
          format: HuiColorFormat.rgb,
          label: huiText('shimmer band colour'),
          placeholder: glossBubbleShimmerDefaultColor,
          onChanged: (String value) => _mutate(
            'bubble shimmer color',
            (GlossBubbleStyleDoc edited) => edited.shimmer.color = value,
          ),
        ),
      ),
      _intField(
        label: huiText('Band width'),
        id: 'bubble-shimmer-width',
        tip: huiText(
          'Visible characters lit at once. The original Gloss width is 3. 1..16.',
        ),
        path: r'$.shimmer.width',
        value: doc.shimmer.width,
        defaultValue: '3',
        onReset: doc.shimmer.width == 3
            ? null
            : () => _editShimmer(
                'band width',
                (GlossBubbleShimmer shimmer) => shimmer.width = 3,
              ),
        onChanged: (int value) => _editShimmer(
          'band width',
          (GlossBubbleShimmer shimmer) => shimmer.width = value,
        ),
      ),
      _intField(
        label: huiText('Sweep duration'),
        id: 'bubble-shimmer-duration',
        docKey: 'bubble.shimmer.durationMs',
        path: r'$.shimmer.durationMs',
        value: doc.shimmer.durationMs,
        unit: HuiDurationUnit.milliseconds,
        step: 50,
        defaultValue: '$glossBubbleShimmerDefaultDurationMs ms',
        onReset: doc.shimmer.durationMs == glossBubbleShimmerDefaultDurationMs
            ? null
            : () => _editShimmer(
                'sweep duration',
                (GlossBubbleShimmer shimmer) =>
                    shimmer.durationMs = glossBubbleShimmerDefaultDurationMs,
              ),
        onChanged: (int value) => _editShimmer(
          'sweep duration',
          (GlossBubbleShimmer shimmer) => shimmer.durationMs = value,
        ),
      ),
      _intField(
        label: huiText('Spawn delay'),
        id: 'bubble-shimmer-spawn-delay',
        tip: huiText(
          'Milliseconds after the bubble appears before the first sweep.',
        ),
        path: r'$.shimmer.spawnDelayMs',
        value: doc.shimmer.spawnDelayMs,
        unit: HuiDurationUnit.milliseconds,
        step: 50,
        defaultValue: '$glossBubbleShimmerDefaultSpawnDelayMs ms',
        onReset:
            doc.shimmer.spawnDelayMs == glossBubbleShimmerDefaultSpawnDelayMs
            ? null
            : () => _editShimmer(
                'spawn delay',
                (GlossBubbleShimmer shimmer) => shimmer.spawnDelayMs =
                    glossBubbleShimmerDefaultSpawnDelayMs,
              ),
        onChanged: (int value) => _editShimmer(
          'spawn delay',
          (GlossBubbleShimmer shimmer) => shimmer.spawnDelayMs = value,
        ),
      ),
      _intField(
        label: huiText('Fly-away lead'),
        id: 'bubble-shimmer-fly-away-lead',
        tip: huiText(
          'Milliseconds before expiry when the second sweep starts from '
          'the left edge. Match or exceed the sweep duration to show a '
          'complete pass.',
        ),
        path: r'$.shimmer.flyAwayLeadMs',
        value: doc.shimmer.flyAwayLeadMs,
        unit: HuiDurationUnit.milliseconds,
        step: 50,
        defaultValue: '$glossBubbleShimmerDefaultFlyAwayLeadMs ms',
        onReset:
            doc.shimmer.flyAwayLeadMs == glossBubbleShimmerDefaultFlyAwayLeadMs
            ? null
            : () => _editShimmer(
                'fly-away lead',
                (GlossBubbleShimmer shimmer) => shimmer.flyAwayLeadMs =
                    glossBubbleShimmerDefaultFlyAwayLeadMs,
              ),
        onChanged: (int value) => _editShimmer(
          'fly-away lead',
          (GlossBubbleShimmer shimmer) => shimmer.flyAwayLeadMs = value,
        ),
      ),
    ],
  );

  void _editShimmer(String label, void Function(GlossBubbleShimmer s) edit) =>
      _mutate(
        'bubble shimmer $label',
        (GlossBubbleStyleDoc edited) => edit(edited.shimmer),
      );

  /// A whole number; time-valued ones read back in seconds. Out-of-range
  /// values show validation's clamp warning, which names what Gloss runs.
  Widget _intField({
    required String label,
    required String id,
    required String path,
    required int value,
    required void Function(int value) onChanged,
    String? docKey,
    String? tip,
    String? defaultValue,
    void Function()? onReset,
    HuiDurationUnit? unit,
    double step = 1,
  }) => Field(
    label: label,
    forId: id,
    docKey: docKey,
    tip: tip,
    defaultValue: defaultValue,
    onReset: onReset,
    problems: _issuesFor(path),
    child: unit == null
        ? NumberField(
            id: id,
            value: value.toDouble(),
            step: step,
            decimals: 0,
            integer: true,
            onChanged: (double parsed) => onChanged(parsed.round()),
          )
        : DurationField(
            id: id,
            value: value.toDouble(),
            unit: unit,
            step: step,
            onChanged: (double parsed) => onChanged(parsed.round()),
          ),
  );

  Widget _selection(GlossBubbleStyleDoc doc) {
    final GlossBubbleSelect? select = doc.select;
    return AppSection(
      title: huiText('Selection'),
      memoryKey: 'bubble.selection',
      actions: <Widget>[
        if (select == null)
          AppIconButton(
            icon: ArcaneIcon.plus(),
            label: huiText('Add select rule'),
            tipAtEnd: true,
            onPressed: () => _mutate(
              'add select',
              (GlossBubbleStyleDoc edited) => edited.select = GlossBubbleSelect(
                when: "viewer.world == 'world'",
              ),
            ),
          )
        else
          AppIconButton(
            icon: ArcaneIcon.trash2(),
            label: huiText('Remove select rule'),
            tipAtEnd: true,
            onPressed: () => _mutate(
              'remove select',
              (GlossBubbleStyleDoc edited) => edited.select = null,
            ),
          ),
      ],
      children: <Widget>[
        if (select == null)
          ArcaneFieldDescription.child(
            TipLabel(
              label: Text(huiText('No select rule')),
              tip: huiText(
                'No select rule: players reach this style only by explicit '
                'choice, or as the fallback when the document is named '
                '"default".',
              ),
            ),
          )
        else ...<Widget>[
          Field(
            label: huiText('When'),
            forId: 'bubble-select-when',
            docKey: 'condition.when',
            problems: _issuesFor(r'$.select.when'),
            child: TextInput(
              id: 'bubble-select-when',
              value: select.when,
              size: controlSize(Density.dense),
              fullWidth: true,
              placeholder: huiText(
                "viewer.world == 'world' && inGroup('viewer', 'vip')",
              ),
              styles: technicalInputStyles,
              attributes: technicalInputAttributes,
              onChanged: (String value) =>
                  _mutate('select condition', (GlossBubbleStyleDoc edited) {
                    final GlossBubbleSelect? live = edited.select;
                    if (live != null) live.when = value;
                  }),
            ),
          ),
          AdvancedOnly(
            child: Field(
              label: huiText('Priority'),
              forId: 'bubble-select-priority',
              docKey: 'bubble.select.priority',
              problems: _issuesFor(r'$.select.priority'),
              child: NumberField(
                id: 'bubble-select-priority',
                value: select.priority.toDouble(),
                step: 1,
                decimals: 0,
                integer: true,
                onChanged: (double parsed) =>
                    _mutate('select priority', (GlossBubbleStyleDoc edited) {
                      final GlossBubbleSelect? live = edited.select;
                      if (live != null) live.priority = parsed.round();
                    }),
              ),
            ),
          ),
        ],
      ],
    );
  }
}
