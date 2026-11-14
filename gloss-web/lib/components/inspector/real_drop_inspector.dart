library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/validation.dart';
import '../../model/model.dart';
import '../../model/gloss_hologram_box.dart';
import '../../state/editor_store.dart';
import '../../ui/action_row.dart';
import '../../ui/badge.dart';
import '../../ui/density.dart';
import '../../ui/field.dart';
import '../../ui/icon_button.dart';
import '../../ui/info_tip.dart';
import '../../ui/mode.dart';
import '../../ui/number_field.dart';
import '../../ui/pane_header.dart';
import '../../ui/reorder_list.dart';
import '../../ui/section.dart';
import '../../ui/switch_field.dart';
import '../../ui/toggle_choice.dart';
import '../../ui/technical_input.dart';
import 'extras_editor.dart';
import 'real_drop_expr_field.dart';
import 'real_drop_label_names_editor.dart';
import 'real_drop_animation_inspector.dart';
import 'particle_layers_editor.dart';
import 'display_style_editor.dart';
import 'display_refresh_editor.dart';
import 'hologram_box_editor.dart';
import 'gloss_visibility_editor.dart';

import 'package:gloss_editor/l10n/hui_localizations.dart';

class RealDropInspector extends StatefulWidget {
  const RealDropInspector({required this.store, super.key});

  final EditorStore store;

  @override
  State<RealDropInspector> createState() => _RealDropInspectorState();
}

class _RealDropInspectorState extends State<RealDropInspector> {
  static final RegExp _idSeparators = RegExp(r'[^A-Za-z0-9]+');

  int? _selectedVariant;

  EditorStore get _store => component.store;

  GlossRealDropSettingsDoc? get _doc => _store.realDropSettingsDoc;

  /// The inspector pane is dense; its text inputs match the kit fields.
  ComponentSize get _size => controlSize(Density.dense);

  String _selectedPath(String path) => _selectedVariant == null
      ? path
      : path.replaceFirst(
          r'$.presentation',
          '\$.variants[$_selectedVariant].presentation',
        );

  /// An element id for the control at [path], so its label names it.
  String _idFor(String path) =>
      'real-drops${path.replaceAll(_idSeparators, '-')}';

  List<HuiIssue> _issuesFor(String path) => _store.issues
      .where((HuiIssue issue) => issue.path.startsWith(_selectedPath(path)))
      .toList();

  /// Issues on exactly this path of the selected presentation. The prefix
  /// match [_issuesFor] does is wrong for an expression field:
  /// `$.presentation.script.scale` would swallow all three axes.
  List<HuiIssue> _exact(String path) => _at(_selectedPath(path));

  List<HuiIssue> _at(String path) =>
      _store.issues.where((HuiIssue issue) => issue.path == path).toList();

  @override
  Widget build(BuildContext context) {
    final GlossRealDropSettingsDoc? doc = _doc;
    if (doc == null) return const dom.div(<Widget>[]);
    final GlossRealDropPresentation presentation = _presentation(doc);
    return DenseBox(
      classes: 'hui-inspector-body is-real-drops',
      children: <Widget>[
        PaneHeader(
          kind: huiText('Real drops'),
          title: _store.menuId,
          status: AdvancedOnly(
            child: StatusBadge(
              huiText('revision {revision}', <String, Object?>{
                'revision': doc.revision,
              }),
            ),
          ),
        ),
        AdvancedOnly(
          child: GlossVisibilityEditor(
            raw: doc.show ?? true,
            issues: _issuesFor(r'$.show'),
            sectionKey: 'realDrops.show',
            onChanged: (Object? value) => _mutate(
              'show condition',
              (GlossRealDropSettingsDoc edited) => edited.show = value,
            ),
          ),
        ),
        AdvancedOnly(child: _conditionalVariants(doc)),
        AdvancedOnly(child: _audience(doc)),
        if (doc.variants.isNotEmpty) _presentationPicker(doc),
        ParticleLayersEditor(
          layers: presentation.particleLayers,
          sectionKey: 'real-drops.particleLayers',
          mutate:
              (String label, void Function(List<GlossParticleLayer>) edit) =>
                  _mutatePresentation(
                    label,
                    (GlossRealDropPresentation edited) =>
                        edit(edited.particleLayers),
                  ),
        ),
        _limits(presentation),
        _scales(presentation),
        _motion(presentation),
        _landing(presentation),
        _labels(presentation),
        DisplayStyleEditor(
          style: presentation.labels.style,
          defaults: defaultRealDropLabelStyle(),
          issues: _issuesFor(r'$.presentation.labels.style'),
          onChanged: (String label, HuiIconStyle? style) => _mutatePresentation(
            label,
            (GlossRealDropPresentation edited) =>
                edited.labels.style = style ?? defaultRealDropLabelStyle(),
          ),
        ),
        HologramBoxEditor(
          box: presentation.labels.box,
          sectionKey: 'realDrops.labels.box',
          issues: _issuesFor(r'$.presentation.labels.box'),
          mutate: (String label, void Function(GlossHologramBox) change) =>
              _mutatePresentation(
                label,
                (GlossRealDropPresentation edited) => change(edited.labels.box),
              ),
        ),
        AdvancedOnly(
          child: DisplayRefreshEditor(
            refresh: presentation.labels.refresh,
            onChanged: (String label, GlossDisplayRefresh next) =>
                _mutatePresentation(
                  label,
                  (GlossRealDropPresentation edited) =>
                      edited.labels.refresh = next,
                ),
          ),
        ),
        _filters(presentation),
        AdvancedOnly(child: _physics(presentation)),
        AdvancedOnly(child: _script(presentation)),
        RealDropAnimationInspector(
          store: _store,
          variantIndex: _selectedVariant,
        ),
      ],
    );
  }

  /// Picks the presentation every section below edits: the default one or a
  /// variant's complete copy.
  Widget _presentationPicker(GlossRealDropSettingsDoc doc) {
    const String id = 'real-drops-presentation';
    return Field(
      label: huiText('Presentation'),
      forId: id,
      docKey: 'realDrops.presentation',
      child: ArcaneSelect(
        id: id,
        value: _selectedVariant?.toString() ?? 'default',
        size: _size,
        fullWidth: true,
        options: <ArcaneSelectOption>[
          ArcaneSelectOption(
            value: 'default',
            label: huiText('Default presentation'),
          ),
          for (int index = 0; index < doc.variants.length; index++)
            ArcaneSelectOption(value: '$index', label: doc.variants[index].id),
        ],
        onChanged: (String value) =>
            setState(() => _selectedVariant = int.tryParse(value)),
      ),
    );
  }

  Widget _conditionalVariants(GlossRealDropSettingsDoc doc) => AppSection(
    title: huiText('Conditional variants'),
    memoryKey: 'realDrops.variants',
    docKey: 'realDrops.variants',
    count: doc.variants.length,
    actions: <Widget>[
      AppIconButton(
        icon: ArcaneIcon.plus(),
        label: huiText('Add variant'),
        tipAtEnd: true,
        onPressed: () => _mutate(
          'add real-drop variant',
          (GlossRealDropSettingsDoc edited) => edited.variants.add(
            GlossRealDropVariant(
              id: _nextVariantId(edited),
              when: "drop.material == 'DIAMOND'",
              presentation: edited.presentation.copy(),
            ),
          ),
        ),
      ),
    ],
    children: <Widget>[
      for (int index = 0; index < doc.variants.length; index++)
        ..._variant(doc, index),
    ],
  );

  List<Widget> _variant(GlossRealDropSettingsDoc doc, int index) {
    final GlossRealDropVariant variant = doc.variants[index];
    final String path = '\$.variants[$index]';
    final String id = _idFor(path);
    final bool editing = _selectedVariant == index;
    return <Widget>[
      ActionRow(
        dense: true,
        leading: <Widget>[
          dom.h3(classes: 'ui-section-title', <Widget>[Text(variant.id)]),
        ],
        secondary: <RowAction>[
          RowAction(
            huiText('Edit complete presentation'),
            () => setState(() => _selectedVariant = editing ? null : index),
            icon: ArcaneIcon.pencil(),
            iconOnly: true,
            pressed: editing,
          ),
          RowAction(
            huiText('Remove variant'),
            () => _removeVariant(index),
            icon: ArcaneIcon.trash2(),
            iconOnly: true,
            destructive: true,
          ),
        ],
      ),
      Field(
        label: huiText('Variant id'),
        forId: '$id-id',
        problems: _at('$path.id'),
        child: TextInput(
          id: '$id-id',
          value: variant.id,
          size: _size,
          fullWidth: true,
          placeholder: huiText('variant-id'),
          styles: technicalInputStyles,
          attributes: technicalInputAttributes,
          onChanged: (String value) => _mutate(
            'rename real-drop variant',
            (GlossRealDropSettingsDoc edited) =>
                edited.variants[index].id = value,
          ),
        ),
      ),
      Field(
        label: huiText('Priority'),
        forId: '$id-priority',
        problems: _at('$path.priority'),
        child: NumberField(
          id: '$id-priority',
          value: variant.priority.toDouble(),
          step: 1,
          decimals: 0,
          integer: true,
          onChanged: (double value) => _mutate(
            'real-drop variant priority',
            (GlossRealDropSettingsDoc edited) =>
                edited.variants[index].priority = value.round(),
          ),
        ),
      ),
      Field(
        label: huiText('Condition'),
        forId: '$id-when',
        problems: _at('$path.when'),
        child: TextInput(
          id: '$id-when',
          value: variant.when,
          size: _size,
          fullWidth: true,
          placeholder: huiText("drop.material == 'DIAMOND'"),
          styles: technicalInputStyles,
          attributes: technicalInputAttributes,
          onChanged: (String value) => _mutate(
            'real-drop variant condition',
            (GlossRealDropSettingsDoc edited) =>
                edited.variants[index].when = value,
          ),
        ),
      ),
    ];
  }

  void _removeVariant(int index) {
    _mutate(
      'remove real-drop variant',
      (GlossRealDropSettingsDoc edited) => edited.variants.removeAt(index),
    );
    setState(() {
      if (_selectedVariant == index) {
        _selectedVariant = null;
      } else if (_selectedVariant != null && _selectedVariant! > index) {
        _selectedVariant = _selectedVariant! - 1;
      }
    });
  }

  Widget _audience(GlossRealDropSettingsDoc doc) {
    const String id = 'real-drops-audience';
    return AppSection(
      title: huiText('Audience'),
      memoryKey: 'realDrops.audience',
      docKey: 'realDrops.audience',
      children: <Widget>[
        Field(
          label: huiText('Viewer condition'),
          forId: id,
          problems: _issuesFor(r'$.audience.when'),
          child: TextInput(
            id: id,
            value: doc.audience.when,
            size: _size,
            fullWidth: true,
            placeholder: huiText("hasPermission('viewer', 'gloss.drops')"),
            styles: technicalInputStyles,
            attributes: technicalInputAttributes,
            onChanged: (String value) => _mutate(
              'real-drop audience condition',
              (GlossRealDropSettingsDoc edited) => edited.audience.when = value,
            ),
          ),
        ),
      ],
    );
  }

  String _nextVariantId(GlossRealDropSettingsDoc doc) {
    int suffix = doc.variants.length + 1;
    String id = 'variant-$suffix';
    while (doc.variants.any((GlossRealDropVariant item) => item.id == id)) {
      suffix++;
      id = 'variant-$suffix';
    }
    return id;
  }

  Widget _limits(GlossRealDropPresentation doc) => AppSection(
    title: huiText('Performance and density'),
    memoryKey: 'realDrops.limits',
    children: <Widget>[
      AdvancedOnly(
        child: _integer(
          label: huiText('Moving update interval'),
          docKey: 'realDrops.limits.updateIntervalTicks',
          path: r'$.presentation.limits.updateIntervalTicks',
          value: doc.limits.updateIntervalTicks,
          onChanged: (int value) => _mutatePresentation(
            'drop update interval',
            (GlossRealDropPresentation edited) =>
                edited.limits.updateIntervalTicks = value,
          ),
        ),
      ),
      AdvancedOnly(
        child: _integer(
          label: huiText('Settled poll interval'),
          docKey: 'realDrops.limits.settledPollIntervalTicks',
          path: r'$.presentation.limits.settledPollIntervalTicks',
          value: doc.limits.settledPollIntervalTicks,
          onChanged: (int value) => _mutatePresentation(
            'settled drop interval',
            (GlossRealDropPresentation edited) =>
                edited.limits.settledPollIntervalTicks = value,
          ),
        ),
      ),
      _integer(
        label: huiText('Models per stack'),
        docKey: 'realDrops.limits.maxVisualsPerStack',
        path: r'$.presentation.limits.maxVisualsPerStack',
        value: doc.limits.maxVisualsPerStack,
        onChanged: (int value) => _mutatePresentation(
          'models per drop',
          (GlossRealDropPresentation edited) =>
              edited.limits.maxVisualsPerStack = value,
        ),
      ),
      _integer(
        label: huiText('Models per chunk'),
        docKey: 'realDrops.limits.maxVisualsPerChunk',
        path: r'$.presentation.limits.maxVisualsPerChunk',
        value: doc.limits.maxVisualsPerChunk,
        onChanged: (int value) => _mutatePresentation(
          'models per chunk',
          (GlossRealDropPresentation edited) =>
              edited.limits.maxVisualsPerChunk = value,
        ),
      ),
      _decimal(
        label: huiText('Model view range'),
        docKey: 'realDrops.limits.viewRange',
        path: r'$.presentation.limits.viewRange',
        value: doc.limits.viewRange,
        step: 1,
        onChanged: (double value) => _mutatePresentation(
          'drop model range',
          (GlossRealDropPresentation edited) => edited.limits.viewRange = value,
        ),
      ),
      _decimal(
        label: huiText('Stack spread'),
        docKey: 'realDrops.limits.spread',
        path: r'$.presentation.limits.spread',
        value: doc.limits.spread,
        onChanged: (double value) => _mutatePresentation(
          'drop model spread',
          (GlossRealDropPresentation edited) => edited.limits.spread = value,
        ),
      ),
    ],
  );

  Widget _scales(GlossRealDropPresentation doc) => AppSection(
    title: huiText('Model scale'),
    memoryKey: 'realDrops.scale',
    children: <Widget>[
      _decimal(
        label: huiText('Default items'),
        docKey: 'realDrops.scale.defaultScale',
        path: r'$.presentation.scale.defaultScale',
        value: doc.scale.defaultScale,
        onChanged: (double value) => _mutatePresentation(
          'default drop scale',
          (GlossRealDropPresentation edited) =>
              edited.scale.defaultScale = value,
        ),
      ),
      _decimal(
        label: huiText('Flat items'),
        docKey: 'realDrops.scale.flatItems',
        path: r'$.presentation.scale.flatItems',
        value: doc.scale.flatItems,
        onChanged: (double value) => _mutatePresentation(
          'flat item scale',
          (GlossRealDropPresentation edited) => edited.scale.flatItems = value,
        ),
      ),
      _decimal(
        label: huiText('Thin blocks'),
        docKey: 'realDrops.scale.thinBlocks',
        path: r'$.presentation.scale.thinBlocks',
        value: doc.scale.thinBlocks,
        onChanged: (double value) => _mutatePresentation(
          'thin block scale',
          (GlossRealDropPresentation edited) => edited.scale.thinBlocks = value,
        ),
      ),
    ],
  );

  Widget _motion(GlossRealDropPresentation doc) => AppSection(
    title: huiText('Tumble'),
    memoryKey: 'realDrops.motion',
    children: <Widget>[
      SwitchField(
        label: huiText('Tumble while moving'),
        docKey: 'realDrops.motion.tumble',
        value: doc.motion.tumble,
        onChanged: (bool value) => _mutatePresentation(
          'drop tumble',
          (GlossRealDropPresentation edited) => edited.motion.tumble = value,
        ),
      ),
      _decimal(
        label: huiText('Animation speed'),
        docKey: 'realDrops.motion.speedMultiplier',
        path: r'$.presentation.motion.speedMultiplier',
        value: doc.motion.speedMultiplier,
        onChanged: (double value) => _mutatePresentation(
          'drop animation speed',
          (GlossRealDropPresentation edited) =>
              edited.motion.speedMultiplier = value,
        ),
      ),
      _decimal(
        label: huiText('X rotation'),
        docKey: 'realDrops.motion.degreesPerSecondX',
        path: r'$.presentation.motion.degreesPerSecondX',
        value: doc.motion.degreesPerSecondX,
        step: 5,
        onChanged: (double value) => _mutatePresentation(
          'drop x rotation',
          (GlossRealDropPresentation edited) =>
              edited.motion.degreesPerSecondX = value,
        ),
      ),
      _decimal(
        label: huiText('Y rotation'),
        docKey: 'realDrops.motion.degreesPerSecondY',
        path: r'$.presentation.motion.degreesPerSecondY',
        value: doc.motion.degreesPerSecondY,
        step: 5,
        onChanged: (double value) => _mutatePresentation(
          'drop y rotation',
          (GlossRealDropPresentation edited) =>
              edited.motion.degreesPerSecondY = value,
        ),
      ),
      _decimal(
        label: huiText('Z rotation'),
        docKey: 'realDrops.motion.degreesPerSecondZ',
        path: r'$.presentation.motion.degreesPerSecondZ',
        value: doc.motion.degreesPerSecondZ,
        step: 5,
        onChanged: (double value) => _mutatePresentation(
          'drop z rotation',
          (GlossRealDropPresentation edited) =>
              edited.motion.degreesPerSecondZ = value,
        ),
      ),
      _decimal(
        label: huiText('Per-item variance'),
        docKey: 'realDrops.motion.variance',
        path: r'$.presentation.motion.variance',
        value: doc.motion.variance,
        onChanged: (double value) => _mutatePresentation(
          'drop tumble variance',
          (GlossRealDropPresentation edited) => edited.motion.variance = value,
        ),
      ),
      SwitchField(
        label: huiText('Change tumble on bounce'),
        docKey: 'realDrops.motion.changeOnBounce',
        value: doc.motion.changeOnBounce,
        onChanged: (bool value) => _mutatePresentation(
          'bounce tumble change',
          (GlossRealDropPresentation edited) =>
              edited.motion.changeOnBounce = value,
        ),
      ),
      _decimal(
        label: huiText('Throw momentum'),
        docKey: 'realDrops.motion.velocityInfluence',
        path: r'$.presentation.motion.velocityInfluence',
        value: doc.motion.velocityInfluence,
        onChanged: (double value) => _mutatePresentation(
          'drop throw momentum',
          (GlossRealDropPresentation edited) =>
              edited.motion.velocityInfluence = value,
        ),
      ),
      _decimal(
        label: huiText('Submerged spin'),
        docKey: 'realDrops.motion.submergedSpinMultiplier',
        path: r'$.presentation.motion.submergedSpinMultiplier',
        value: doc.motion.submergedSpinMultiplier,
        onChanged: (double value) => _mutatePresentation(
          'drop submerged spin',
          (GlossRealDropPresentation edited) =>
              edited.motion.submergedSpinMultiplier = value,
        ),
      ),
      _decimal(
        label: huiText('Ground roll'),
        docKey: 'realDrops.motion.groundRollMultiplier',
        path: r'$.presentation.motion.groundRollMultiplier',
        value: doc.motion.groundRollMultiplier,
        onChanged: (double value) => _mutatePresentation(
          'drop ground roll',
          (GlossRealDropPresentation edited) =>
              edited.motion.groundRollMultiplier = value,
        ),
      ),
    ],
  );

  Widget _landing(GlossRealDropPresentation doc) => AppSection(
    title: huiText('Landing'),
    memoryKey: 'realDrops.landing',
    children: <Widget>[
      Field(
        label: huiText('Landing mode'),
        docKey: 'realDrops.landing.mode',
        problems: _issuesFor(r'$.presentation.landing.mode'),
        child: ToggleChoice<String>(
          label: huiText('Landing mode'),
          value: doc.landing.mode,
          options: <ChoiceOption<String>>[
            ChoiceOption<String>('NATURAL', huiText('Natural')),
            ChoiceOption<String>('FLAT', huiText('Flat')),
            ChoiceOption<String>('UPRIGHT', huiText('Upright')),
          ],
          onChanged: (String value) => _mutatePresentation(
            'drop landing mode',
            (GlossRealDropPresentation edited) => edited.landing.mode = value,
          ),
        ),
      ),
      _decimal(
        label: huiText('Natural face rotation'),
        docKey: 'realDrops.landing.tiltDegrees',
        path: r'$.presentation.landing.tiltDegrees',
        value: doc.landing.tiltDegrees,
        step: 1,
        onChanged: (double value) => _mutatePresentation(
          'drop landing tilt',
          (GlossRealDropPresentation edited) =>
              edited.landing.tiltDegrees = value,
        ),
      ),
      SwitchField(
        label: huiText('Random landing yaw'),
        docKey: 'realDrops.landing.randomYaw',
        value: doc.landing.randomYaw,
        onChanged: (bool value) => _mutatePresentation(
          'drop landing yaw',
          (GlossRealDropPresentation edited) =>
              edited.landing.randomYaw = value,
        ),
      ),
      _integer(
        label: huiText('Landing transition'),
        docKey: 'realDrops.landing.transitionTicks',
        path: r'$.presentation.landing.transitionTicks',
        value: doc.landing.transitionTicks,
        onChanged: (int value) => _mutatePresentation(
          'drop landing transition',
          (GlossRealDropPresentation edited) =>
              edited.landing.transitionTicks = value,
        ),
      ),
      _decimal(
        label: huiText('Resting face pull'),
        docKey: 'realDrops.landing.faceAttraction',
        path: r'$.presentation.landing.faceAttraction',
        value: doc.landing.faceAttraction,
        onChanged: (double value) => _mutatePresentation(
          'drop resting face attraction',
          (GlossRealDropPresentation edited) =>
              edited.landing.faceAttraction = value,
        ),
      ),
      _decimal(
        label: huiText('Moving face pull'),
        docKey: 'realDrops.landing.movingFaceAttraction',
        path: r'$.presentation.landing.movingFaceAttraction',
        value: doc.landing.movingFaceAttraction,
        onChanged: (double value) => _mutatePresentation(
          'drop moving face attraction',
          (GlossRealDropPresentation edited) =>
              edited.landing.movingFaceAttraction = value,
        ),
      ),
      _decimal(
        label: huiText('Face snap tolerance'),
        docKey: 'realDrops.landing.alignmentDegrees',
        path: r'$.presentation.landing.alignmentDegrees',
        value: doc.landing.alignmentDegrees,
        onChanged: (double value) => _mutatePresentation(
          'drop face snap tolerance',
          (GlossRealDropPresentation edited) =>
              edited.landing.alignmentDegrees = value,
        ),
      ),
      _integer(
        label: huiText('Stable delay'),
        docKey: 'realDrops.landing.settleDelayTicks',
        path: r'$.presentation.landing.settleDelayTicks',
        value: doc.landing.settleDelayTicks,
        onChanged: (int value) => _mutatePresentation(
          'drop stable delay',
          (GlossRealDropPresentation edited) =>
              edited.landing.settleDelayTicks = value,
        ),
      ),
    ],
  );

  /// The label's own fields. Its style, box and refresh are sections of
  /// their own below this one, so no disclosure sits inside another.
  Widget _labels(GlossRealDropPresentation doc) => AppSection(
    title: huiText('Labels'),
    memoryKey: 'realDrops.labels',
    children: <Widget>[
      SwitchField(
        label: huiText('Enabled'),
        docKey: 'realDrops.labels.enabled',
        value: doc.labels.enabled,
        onChanged: (bool value) => _mutatePresentation(
          'label visibility',
          (GlossRealDropPresentation edited) => edited.labels.enabled = value,
        ),
      ),
      _decimal(
        label: huiText('Vertical offset'),
        docKey: 'realDrops.labels.yOffset',
        path: r'$.presentation.labels.yOffset',
        value: doc.labels.yOffset,
        onChanged: (double value) => _mutatePresentation(
          'label offset',
          (GlossRealDropPresentation edited) => edited.labels.yOffset = value,
        ),
      ),
      _text(
        label: huiText('Format'),
        docKey: 'realDrops.labels.format',
        path: r'$.presentation.labels.format',
        value: doc.labels.format,
        fallback: glossRealDropLabelFormatDefault,
        onChanged: (String value) => _mutatePresentation(
          'label format',
          (GlossRealDropPresentation edited) => edited.labels.format = value,
        ),
      ),
      SwitchField(
        label: huiText('Use item display names'),
        docKey: 'realDrops.labels.useItemDisplayNames',
        value: doc.labels.useItemDisplayNames,
        onChanged: (bool value) => _mutatePresentation(
          'label item display names',
          (GlossRealDropPresentation edited) =>
              edited.labels.useItemDisplayNames = value,
        ),
      ),
      RealDropLabelNamesEditor(
        names: doc.labels.names,
        issues: _issuesFor(r'$.presentation.labels.names'),
        onChanged: (String label, Map<String, String> next) =>
            _mutatePresentation(
              label,
              (GlossRealDropPresentation edited) => edited.labels.names = next,
            ),
      ),
      ..._bundle(doc.labels.bundle),
      AdvancedOnly(
        child: ExtrasEditor(
          title: huiText('Settings'),
          extensionKeys: false,
          extras: <String, Object?>{
            'show': doc.labels.show,
            'preserveCustomNames': doc.labels.preserveCustomNames,
          },
          onChanged: (String label, Map<String, dynamic> next) =>
              _mutatePresentation(label, (GlossRealDropPresentation edited) {
                edited.labels.show = huiDeepCopy(next['show'] ?? true);
                edited.labels.preserveCustomNames =
                    next['preserveCustomNames'] == null ||
                    huiReadBool(next, 'preserveCustomNames');
              }),
        ),
      ),
    ],
  );

  /// `labels.bundle`: the label of a dropped bundle that carries stacks.
  List<Widget> _bundle(GlossRealDropLabelBundle bundle) => <Widget>[
    _subhead(huiText('Bundle'), 'realDrops.labels.bundle'),
    _text(
      label: huiText('Single-line format'),
      docKey: 'realDrops.labels.bundle.format',
      path: r'$.presentation.labels.bundle.format',
      value: bundle.format,
      fallback: glossRealDropBundleFormatDefault,
      onChanged: (String value) => _mutatePresentation(
        'bundle label format',
        (GlossRealDropPresentation edited) =>
            edited.labels.bundle.format = value,
      ),
    ),
    _integer(
      label: huiText('Entry limit'),
      docKey: 'realDrops.labels.bundle.entryLimit',
      path: r'$.presentation.labels.bundle.entryLimit',
      value: bundle.entryLimit,
      onChanged: (int value) => _mutatePresentation(
        'bundle label entry limit',
        (GlossRealDropPresentation edited) =>
            edited.labels.bundle.entryLimit = value,
      ),
    ),
    SwitchField(
      label: huiText('One line per material'),
      docKey: 'realDrops.labels.bundle.vertical',
      value: bundle.vertical,
      onChanged: (bool value) => _mutatePresentation(
        'bundle label lines',
        (GlossRealDropPresentation edited) =>
            edited.labels.bundle.vertical = value,
      ),
    ),
    _text(
      label: huiText('Header format'),
      docKey: 'realDrops.labels.bundle.headerFormat',
      path: r'$.presentation.labels.bundle.headerFormat',
      value: bundle.headerFormat,
      fallback: glossRealDropBundleHeaderFormatDefault,
      onChanged: (String value) => _mutatePresentation(
        'bundle label header format',
        (GlossRealDropPresentation edited) =>
            edited.labels.bundle.headerFormat = value,
      ),
    ),
    _text(
      label: huiText('Entry format'),
      docKey: 'realDrops.labels.bundle.entryFormat',
      path: r'$.presentation.labels.bundle.entryFormat',
      value: bundle.entryFormat,
      fallback: glossRealDropBundleEntryFormatDefault,
      onChanged: (String value) => _mutatePresentation(
        'bundle label entry format',
        (GlossRealDropPresentation edited) =>
            edited.labels.bundle.entryFormat = value,
      ),
    ),
    _text(
      label: huiText('More format'),
      docKey: 'realDrops.labels.bundle.moreFormat',
      path: r'$.presentation.labels.bundle.moreFormat',
      value: bundle.moreFormat,
      fallback: glossRealDropBundleMoreFormatDefault,
      onChanged: (String value) => _mutatePresentation(
        'bundle label more format',
        (GlossRealDropPresentation edited) =>
            edited.labels.bundle.moreFormat = value,
      ),
    ),
  ];

  Widget _filters(GlossRealDropPresentation doc) => AppSection(
    title: huiText('Filters'),
    memoryKey: 'realDrops.filters',
    children: <Widget>[
      SwitchField(
        label: huiText('Only player-thrown drops'),
        docKey: 'realDrops.filters.onlyPlayerDrops',
        value: doc.filters.onlyPlayerDrops,
        onChanged: (bool value) => _mutatePresentation(
          'drop source filter',
          (GlossRealDropPresentation edited) =>
              edited.filters.onlyPlayerDrops = value,
        ),
      ),
      _list(
        label: huiText('Disabled worlds'),
        docKey: 'realDrops.filters.disabledWorlds',
        path: r'$.presentation.filters.disabledWorlds',
        placeholder: 'world_nether, world_the_end',
        value: doc.filters.disabledWorlds,
        onChanged: (List<String> value) => _mutatePresentation(
          'disabled drop worlds',
          (GlossRealDropPresentation edited) =>
              edited.filters.disabledWorlds = value,
        ),
      ),
      _list(
        label: huiText('Material blacklist'),
        docKey: 'realDrops.filters.materialBlacklist',
        path: r'$.presentation.filters.materialBlacklist',
        placeholder: 'BEDROCK, BARRIER',
        value: doc.filters.materialBlacklist,
        onChanged: (List<String> value) => _mutatePresentation(
          'drop material blacklist',
          (GlossRealDropPresentation edited) =>
              edited.filters.materialBlacklist = value,
        ),
      ),
    ],
  );

  // --- physics --------------------------------------------------------------

  /// The block that moves the real `Item` entity.
  ///
  /// Both optional blocks are absent from a document until somebody touches
  /// them, so every control here reads through the null and every edit
  /// materialises the block. A file written before the feature existed stays
  /// byte-identical until it is actually changed.
  Widget _physics(GlossRealDropPresentation doc) {
    final GlossRealDropPhysics? physics = doc.physics;
    final bool enabled = physics?.enabled ?? false;
    return AppSection(
      title: huiText('Item physics'),
      memoryKey: 'realDrops.physics',
      tip: huiText(
        'Real changes to how the dropped item moves. These write to the '
        'entity, so its position, its collision and its pickup radius all '
        'follow.',
      ),
      children: <Widget>[
        SwitchField(
          label: huiText('Move the item entity'),
          docKey: 'realDrops.physics.enabled',
          value: enabled,
          onChanged: (bool value) => _mutatePhysics(
            'drop physics',
            (GlossRealDropPhysics edited) => edited.enabled = value,
          ),
        ),
        _physicsNumber(
          label: huiText('Gravity multiplier'),
          docKey: 'realDrops.physics.gravityMultiplier',
          path: r'$.presentation.physics.gravityMultiplier',
          value: physics?.gravityMultiplier ?? 1,
          fallback: '1',
          onChanged: (double value) => _mutatePhysics(
            'drop gravity',
            (GlossRealDropPhysics edited) => edited.gravityMultiplier = value,
          ),
        ),
        _physicsNumber(
          label: huiText('Bounce'),
          docKey: 'realDrops.physics.bounce',
          path: r'$.presentation.physics.bounce',
          value: physics?.bounce ?? 0,
          fallback: '0',
          onChanged: (double value) => _mutatePhysics(
            'drop bounce',
            (GlossRealDropPhysics edited) => edited.bounce = value,
          ),
        ),
        _physicsNumber(
          label: huiText('Water buoyancy'),
          docKey: 'realDrops.physics.waterBuoyancy',
          path: r'$.presentation.physics.waterBuoyancy',
          value: physics?.waterBuoyancy ?? 0,
          fallback: '0',
          onChanged: (double value) => _mutatePhysics(
            'drop buoyancy',
            (GlossRealDropPhysics edited) => edited.waterBuoyancy = value,
          ),
        ),
        _physicsNumber(
          label: huiText('Water drag'),
          docKey: 'realDrops.physics.waterDrag',
          path: r'$.presentation.physics.waterDrag',
          value: physics?.waterDrag ?? 0,
          fallback: '0',
          onChanged: (double value) => _mutatePhysics(
            'drop water drag',
            (GlossRealDropPhysics edited) => edited.waterDrag = value,
          ),
        ),
      ],
    );
  }

  Widget _physicsNumber({
    required String label,
    required String docKey,
    required String path,
    required double value,
    required String fallback,
    required void Function(double value) onChanged,
  }) {
    final String id = _idFor(path);
    return Field(
      label: label,
      forId: id,
      docKey: docKey,
      defaultValue: fallback,
      onReset: value == double.parse(fallback)
          ? null
          : () => onChanged(double.parse(fallback)),
      problems: _issuesFor(path),
      child: NumberField(
        id: id,
        value: value,
        step: 0.05,
        decimals: 2,
        onChanged: onChanged,
      ),
    );
  }

  // --- script ---------------------------------------------------------------

  /// The block that moves the picture and nothing else.
  ///
  /// Every expression here is compiled and type-checked by the server whether
  /// or not the switch is on, so the validation panel reports a broken one
  /// either way, which is why the switch does not gate any of these controls.
  Widget _script(GlossRealDropPresentation doc) {
    final GlossRealDropScript? script = doc.script;
    return AppSection(
      title: huiText('Script'),
      memoryKey: 'realDrops.script',
      tip: huiText(
        'Expressions are compiled once; stack-shared and settled-static '
        'results are reused when their inputs do not change. They move the '
        'displays only: the item, its collision and its pickup radius stay '
        'where Minecraft put them.',
      ),
      children: <Widget>[
        SwitchField(
          label: huiText('Run the script'),
          docKey: 'realDrops.script.enabled',
          value: script?.enabled ?? false,
          onChanged: (bool value) => _mutateScript(
            'drop script',
            (GlossRealDropScript edited) => edited.enabled = value,
          ),
        ),
        ..._vars(script),
        ..._axis(
          title: huiText('Offset'),
          docKey: 'realDrops.script.offset',
          field: 'offset',
          axis: script?.offset,
          neutral: '0',
        ),
        ..._axis(
          title: huiText('Rotation'),
          docKey: 'realDrops.script.rotation',
          field: 'rotation',
          axis: script?.rotation,
          neutral: '0',
        ),
        ..._axis(
          title: huiText('Scale'),
          docKey: 'realDrops.script.scale',
          field: 'scale',
          axis: script?.scale,
          neutral: '1',
        ),
        RealDropExprField(
          id: _idFor(r'$.presentation.script.glow'),
          label: huiText('Glow'),
          docKey: 'realDrops.script.glow',
          value: script?.glow ?? '',
          placeholder: huiText("materialIs('torch') ? #FFAA55 : 0"),
          issues: _exact(r'$.presentation.script.glow'),
          onChanged: (String value) => _mutateScript(
            'drop script glow',
            (GlossRealDropScript edited) => edited.glow = value,
          ),
        ),
        RealDropExprField(
          id: _idFor(r'$.presentation.script.visible'),
          label: huiText('Visible'),
          docKey: 'realDrops.script.visible',
          value: script?.visible ?? 'true',
          neutral: 'true',
          issues: _exact(r'$.presentation.script.visible'),
          onChanged: (String value) => _mutateScript(
            'drop script visible',
            (GlossRealDropScript edited) =>
                edited.visible = value.isEmpty ? 'true' : value,
          ),
        ),
      ],
    );
  }

  /// One axis object: three expressions under one heading, each with the
  /// shared `axis` doc entry so the three vectors never drift apart in the
  /// help.
  List<Widget> _axis({
    required String title,
    required String docKey,
    required String field,
    required GlossRealDropScriptAxis? axis,
    required String neutral,
  }) => <Widget>[
    _subhead(title, docKey),
    for (final String name in const <String>['x', 'y', 'z'])
      RealDropExprField(
        id: _idFor('\$.presentation.script.$field.$name'),
        label: name.toUpperCase(),
        docKey: 'realDrops.axis.$name',
        neutral: neutral,
        value: switch (name) {
          'x' => axis?.x ?? neutral,
          'y' => axis?.y ?? neutral,
          _ => axis?.z ?? neutral,
        },
        issues: _exact('\$.presentation.script.$field.$name'),
        onChanged: (String value) => _mutateScript('drop script $field $name', (
          GlossRealDropScript edited,
        ) {
          final GlossRealDropScriptAxis target = switch (field) {
            'offset' => edited.offset,
            'rotation' => edited.rotation,
            _ => edited.scale,
          };
          final String next = value.isEmpty ? neutral : value;
          switch (name) {
            case 'x':
              target.x = next;
            case 'y':
              target.y = next;
            default:
              target.z = next;
          }
        }),
      ),
  ];

  /// The `vars` list: add, remove and reorder, and never a sort.
  ///
  /// Declaration order decides what each entry can read (an expression sees
  /// every var declared before it and none declared after), so the order in
  /// this list is the order in the file, and moving a row is a real edit to
  /// the document rather than a view preference.
  List<Widget> _vars(GlossRealDropScript? script) {
    final List<GlossRealDropScriptVar> vars =
        script?.vars ?? const <GlossRealDropScriptVar>[];
    return <Widget>[
      ActionRow(
        dense: true,
        leading: <Widget>[
          dom.h3(classes: 'ui-section-title', <Widget>[
            TipLabel(
              label: Text(huiText('Variables')),
              docKey: 'realDrops.script.vars',
            ),
          ]),
        ],
        secondary: <RowAction>[
          RowAction(
            huiText('Add variable'),
            vars.length >= GlossRealDropScript.maxVars
                ? null
                : () => _mutateScript(
                    'add drop script variable',
                    (GlossRealDropScript edited) => edited.vars.add(
                      GlossRealDropScriptVar(
                        name: _freshVarName(edited.vars),
                        expression: '0',
                      ),
                    ),
                  ),
            icon: ArcaneIcon.plus(),
          ),
        ],
      ),
      for (final HuiIssue issue in distinctIssues(
        _exact(r'$.presentation.script.vars'),
      ))
        issueNote(issue),
      if (vars.isNotEmpty)
        ReorderList(
          label: huiText('Variables'),
          itemCount: vars.length,
          onReorder: (int from, int to) => _mutateScript(
            'reorder drop script variables',
            (GlossRealDropScript edited) =>
                edited.vars.insert(to, edited.vars.removeAt(from)),
          ),
          onRemove: (int index) => _mutateScript(
            'remove drop script variable',
            (GlossRealDropScript edited) => edited.vars.removeAt(index),
          ),
          itemBuilder: (int index) => _varRow(vars, index),
        ),
    ];
  }

  Widget _varRow(List<GlossRealDropScriptVar> vars, int index) {
    final GlossRealDropScriptVar variable = vars[index];
    final String id = 'real-drops-var-$index';
    return Component.fragment(<Widget>[
      Field(
        label: huiText('Variable name'),
        forId: '$id-name',
        child: TextInput(
          id: '$id-name',
          value: variable.name,
          size: _size,
          fullWidth: true,
          placeholder: huiText('name'),
          styles: technicalInputStyles,
          attributes: technicalInputAttributes,
          onChanged: (String value) => _mutateScript(
            'rename drop script variable',
            (GlossRealDropScript edited) =>
                edited.vars[index].name = value.trim(),
          ),
        ),
      ),
      RealDropExprField(
        id: '$id-expression',
        label: huiText('Expression'),
        value: variable.expression,
        placeholder: huiText('expression'),
        issues: _exact('\$.presentation.script.vars.${variable.name}'),
        onChanged: (String value) => _mutateScript(
          'edit drop script variable',
          (GlossRealDropScript edited) => edited.vars[index].expression = value,
        ),
      ),
    ]);
  }

  /// A name nothing in the list already uses, so adding a row twice in a row
  /// does not produce two entries the server refuses as declared twice.
  static String _freshVarName(List<GlossRealDropScriptVar> vars) {
    final Set<String> taken = <String>{
      for (final GlossRealDropScriptVar entry in vars) entry.name,
    };
    for (int index = 1; ; index++) {
      final String name = 'value$index';
      if (!taken.contains(name)) return name;
    }
  }

  /// A heading over a few fields inside a section.
  Widget _subhead(String title, String docKey) => dom.h3(
    classes: 'ui-section-title',
    <Widget>[TipLabel(label: Text(title), docKey: docKey)],
  );

  Widget _integer({
    required String label,
    required String docKey,
    required String path,
    required int value,
    required void Function(int value) onChanged,
  }) {
    final String id = _idFor(path);
    return Field(
      label: label,
      forId: id,
      docKey: docKey,
      problems: _issuesFor(path),
      child: NumberField(
        id: id,
        value: value.toDouble(),
        step: 1,
        decimals: 0,
        integer: true,
        onChanged: (double parsed) => onChanged(parsed.round()),
      ),
    );
  }

  Widget _decimal({
    required String label,
    required String docKey,
    required String path,
    required double value,
    required void Function(double value) onChanged,
    double step = 0.05,
  }) {
    final String id = _idFor(path);
    return Field(
      label: label,
      forId: id,
      docKey: docKey,
      problems: _issuesFor(path),
      child: NumberField(
        id: id,
        value: value,
        step: step,
        decimals: 2,
        onChanged: onChanged,
      ),
    );
  }

  /// One label text field. A blank value is what the server swaps for
  /// [fallback], so the placeholder shows it and the reset restores it. The
  /// formats are too long for the field's default chip, so the reset stands
  /// alone at the end of the label line.
  Widget _text({
    required String label,
    required String docKey,
    required String path,
    required String value,
    required String fallback,
    required void Function(String value) onChanged,
  }) {
    final String id = _idFor(path);
    return Field(
      label: label,
      forId: id,
      docKey: docKey,
      problems: _issuesFor(path),
      trailing: AppIconButton(
        icon: ArcaneIcon.rotateCcw(),
        label: huiText('Reset {label} to {defaultValue}', <String, Object?>{
          'label': label,
          'defaultValue': fallback,
        }),
        tipAtEnd: true,
        onPressed: value == fallback ? null : () => onChanged(fallback),
      ),
      child: TextInput(
        id: id,
        value: value,
        size: _size,
        fullWidth: true,
        placeholder: fallback,
        styles: technicalInputStyles,
        attributes: technicalInputAttributes,
        onChanged: onChanged,
      ),
    );
  }

  Widget _list({
    required String label,
    required String docKey,
    required String path,
    required String placeholder,
    required List<String> value,
    required void Function(List<String> value) onChanged,
  }) {
    final String id = _idFor(path);
    return Field(
      label: label,
      forId: id,
      docKey: docKey,
      problems: _issuesFor(path),
      child: TextInput(
        id: id,
        value: value.join(', '),
        size: _size,
        fullWidth: true,
        placeholder: placeholder,
        styles: technicalInputStyles,
        attributes: technicalInputAttributes,
        onChanged: (String raw) => onChanged(
          raw
              .split(',')
              .map((String entry) => entry.trim())
              .where((String entry) => entry.isNotEmpty)
              .toList(),
        ),
      ),
    );
  }

  void _mutatePresentation(
    String label,
    void Function(GlossRealDropPresentation doc) change,
  ) => _store.mutateRealDropSettings(
    label,
    (GlossRealDropSettingsDoc doc) => change(_presentation(doc)),
  );

  void _mutate(String label, void Function(GlossRealDropSettingsDoc) change) =>
      _store.mutateRealDropSettings(label, change);

  GlossRealDropPresentation _presentation(GlossRealDropSettingsDoc doc) {
    final int? index = _selectedVariant;
    if (index == null) return doc.presentation;
    if (index >= doc.variants.length) {
      _selectedVariant = null;
      return doc.presentation;
    }
    return doc.variants[index].presentation;
  }

  /// Edits the physics block, creating it if the document never had one. This
  /// is the only place the block comes into existence, which is what keeps an
  /// untouched document byte-identical through the editor.
  void _mutatePhysics(
    String label,
    void Function(GlossRealDropPhysics physics) change,
  ) => _mutatePresentation(
    label,
    (GlossRealDropPresentation doc) =>
        change(doc.physics ??= GlossRealDropPhysics()),
  );

  /// The same, for the script block.
  void _mutateScript(
    String label,
    void Function(GlossRealDropScript script) change,
  ) => _mutatePresentation(
    label,
    (GlossRealDropPresentation doc) =>
        change(doc.script ??= GlossRealDropScript()),
  );
}
