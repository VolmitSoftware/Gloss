/// A display entity's style in two sections: billboard, scale and glow first,
/// with lighting, ground shadow and culling under More; then the fields that
/// only text displays read. No style at all means the runtime defaults, and
/// Customize writes a copy of them. Problems show at their field; one at a
/// path no shown field owns is listed under the first section.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/jaspr.dart' show Component;

import '../../config/defaults.dart';
import '../../l10n/hui_localizations.dart';
import '../../logic/validation.dart';
import '../../model/model.dart';
import '../../ui/action_row.dart';
import '../../ui/color_field.dart';
import '../../ui/field.dart';
import '../../ui/icon_button.dart';
import '../../ui/mode.dart';
import '../../ui/number_field.dart';
import '../../ui/section.dart';
import '../../ui/switch_field.dart';
import '../../ui/toggle_choice.dart';
import '../../ui/vec3_field.dart';

int _nextStyleId = 0;

const Set<String> _mainKeys = <String>{
  'billboard',
  'scaleX',
  'scaleY',
  'scaleZ',
  'glowColor',
};
const Set<String> _moreKeys = <String>{
  'blockLight',
  'skyLight',
  'viewRange',
  'shadowRadius',
  'shadowStrength',
  'cullingWidth',
  'cullingHeight',
};
const Set<String> _textKeys = <String>{
  'shadow',
  'seeThrough',
  'textAlignment',
  'backgroundArgb',
  'textOpacity',
  'lineWidth',
};

class DisplayStyleEditor extends StatefulWidget {
  const DisplayStyleEditor({
    required this.style,
    required this.onChanged,
    required this.issues,
    this.defaults,
    this.title = 'Display style',
    this.sectionKey,
    this.entityOverlay = false,
    super.key,
  });

  final HuiIconStyle? style;
  final HuiIconStyle? defaults;
  final String title;
  final String? sectionKey;
  final bool entityOverlay;
  final void Function(String label, HuiIconStyle? style) onChanged;
  final List<HuiIssue> issues;

  @override
  State<DisplayStyleEditor> createState() => _DisplayStyleEditorState();
}

class _DisplayStyleEditorState extends State<DisplayStyleEditor> {
  final String _id = 'style-${_nextStyleId++}';

  HuiIconStyle get _defaultStyle =>
      component.defaults?.copy() ?? createDefaultIconStyle();

  /// Writes [update] onto a copy of the current style (or of the defaults
  /// when there is none yet).
  void _set(String label, void Function(HuiIconStyle style) update) {
    final HuiIconStyle next = component.style?.copy() ?? _defaultStyle;
    update(next);
    component.onChanged(label, next);
  }

  /// The last segment of an issue path: the style key it is about.
  static String _key(HuiIssue issue) =>
      issue.path.substring(issue.path.lastIndexOf('.') + 1);

  List<HuiIssue> _at(Set<String> keys) => component.issues
      .where((HuiIssue issue) => keys.contains(_key(issue)))
      .toList();

  List<ChoiceOption<String>> _choices(
    List<String> values,
    String current,
    String Function(String value) label,
  ) => <ChoiceOption<String>>[
    for (final String value in values)
      ChoiceOption<String>(value, label(value)),
    if (!values.contains(current))
      ChoiceOption<String>(
        current,
        huiText("{current} (unknown)", <String, Object?>{'current': current}),
      ),
  ];

  @override
  Widget build(BuildContext context) {
    final HuiIconStyle? current = component.style;
    final String base =
        component.sectionKey ??
        (component.entityOverlay ? 'entity-overlays.style' : 'icon.style');
    if (current == null) {
      return AppSection(
        title: huiText(component.title),
        memoryKey: base,
        description: huiText('Uses the runtime display-entity defaults.'),
        children: <Widget>[
          ActionRow(
            dense: true,
            secondary: <RowAction>[
              RowAction(
                huiText('Customize display'),
                () => component.onChanged('add display style', _defaultStyle),
                icon: ArcaneIcon.plus(),
              ),
            ],
          ),
        ],
      );
    }
    final HuiIconStyle defaults = _defaultStyle;
    final List<HuiIssue> rest = component.issues.where((HuiIssue issue) {
      final String key = _key(issue);
      return !_mainKeys.contains(key) &&
          !_moreKeys.contains(key) &&
          !_textKeys.contains(key);
    }).toList();
    return Component.fragment(<Widget>[
      AppSection(
        title: huiText(component.title),
        memoryKey: base,
        tip: component.entityOverlay
            ? huiText('Appearance of the entity pane and its box decoration.')
            : huiText('Native display metadata for this surface.'),
        actions: <Widget>[
          AppIconButton(
            icon: ArcaneIcon.rotateCcw(),
            label: huiText('Use defaults'),
            onPressed: () => component.onChanged('remove display style', null),
          ),
        ],
        children: <Widget>[
          _billboard(current, defaults),
          _scale(current, defaults),
          ..._glow(current),
          MoreFields(
            memoryKey: '$base.more',
            open: _at(_moreKeys).isNotEmpty,
            children: _lighting(current),
          ),
          for (final HuiIssue issue in distinctIssues(rest))
            issueNote(issue),
        ],
      ),
      AppSection(
        title: huiText('Text appearance'),
        memoryKey: '$base.text',
        open: _at(_textKeys).isNotEmpty,
        tip: component.entityOverlay
            ? null
            : huiText(
                'Text displays only. Item, custom-item and block icons ignore '
                'these; image icons use them, as Gloss draws them as text.',
              ),
        children: _text(current),
      ),
    ]);
  }

  Widget _billboard(HuiIconStyle current, HuiIconStyle defaults) => Field(
    label: huiText('Billboard'),
    // The field doc describes menu icons; an entity pane has its own line.
    tip: component.entityOverlay
        ? huiText('Fixed keeps the pane orientation; other modes face viewers.')
        : null,
    docKey: 'icon.style.billboard',
    problems: _at(const <String>{'billboard'}),
    defaultValue: _billboardLabel(defaults.billboard),
    onReset: current.billboard == defaults.billboard
        ? null
        : () => _set(
            'icon billboard',
            (HuiIconStyle next) => next.billboard = defaults.billboard,
          ),
    child: ToggleChoice<String>(
      id: '$_id-billboard',
      label: huiText('Billboard'),
      value: current.billboard,
      options: _choices(huiIconBillboards, current.billboard, _billboardLabel),
      onChanged: (String value) =>
          _set('icon billboard', (HuiIconStyle next) => next.billboard = value),
    ),
  );

  Widget _scale(HuiIconStyle current, HuiIconStyle defaults) => Field(
    label: huiText('Scale'),
    forId: '$_id-scale',
    tip: component.entityOverlay
        ? huiText('X changes width, Y changes height, and Z changes depth.')
        : huiText(
            'Multiplied by the server uiScale. X and Y also resize the '
            'automatic click plane; Z never does.',
          ),
    problems: _at(const <String>{'scaleX', 'scaleY', 'scaleZ'}),
    defaultValue: '${defaults.scaleX}, ${defaults.scaleY}, ${defaults.scaleZ}',
    onReset:
        current.scaleX == defaults.scaleX &&
            current.scaleY == defaults.scaleY &&
            current.scaleZ == defaults.scaleZ
        ? null
        : () => _set('icon scale', (HuiIconStyle next) {
            next.scaleX = defaults.scaleX;
            next.scaleY = defaults.scaleY;
            next.scaleZ = defaults.scaleZ;
          }),
    child: Vec3Field(
      id: '$_id-scale',
      label: huiText('Scale'),
      value: Vec3(current.scaleX, current.scaleY, current.scaleZ),
      step: 0.05,
      decimals: 2,
      axisHints: component.entityOverlay
          ? <String>[huiText('Width'), huiText('Height'), huiText('Depth')]
          : <String>[
              huiText('x: width, and the click plane with it'),
              huiText('y: height, which also re-spaces multi-line text'),
              huiText('z: depth. Visible on block and item icons only'),
            ],
      onChanged: (Vec3 value) => _set('icon scale', (HuiIconStyle next) {
        next.scaleX = value.x;
        next.scaleY = value.y;
        next.scaleZ = value.z;
      }),
    ),
  );

  List<Widget> _glow(HuiIconStyle current) => <Widget>[
    SwitchField(
      id: '$_id-glow-on',
      label: huiText('Glow outline'),
      value: current.glowColor != null,
      docKey: 'icon.style.glowColor',
      problems: current.glowColor == null
          ? _at(const <String>{'glowColor'})
          : const <HuiIssue>[],
      onChanged: (bool value) => _set(
        'glow outline',
        (HuiIconStyle next) => next.glowColor = value ? '#FFFFFFFF' : null,
      ),
    ),
    if (current.glowColor != null)
      Field(
        label: huiText('Glow colour'),
        forId: '$_id-glow',
        docKey: 'icon.style.glowColor',
        problems: _at(const <String>{'glowColor'}),
        child: ColorField(
          id: '$_id-glow',
          value: current.glowColor!,
          label: huiText('glow colour'),
          placeholder: '#FFFFFFFF',
          onChanged: (String value) =>
              _set('glow color', (HuiIconStyle next) => next.glowColor = value),
        ),
      ),
  ];

  List<Widget> _lighting(HuiIconStyle current) => <Widget>[
    SwitchField(
      id: '$_id-brightness',
      label: huiText('Override brightness'),
      value: current.hasBrightnessOverride,
      docKey: 'icon.style.blockLight',
      problems: current.hasBrightnessOverride
          ? const <HuiIssue>[]
          : _at(const <String>{'blockLight', 'skyLight'}),
      onChanged: (bool value) =>
          _set('brightness override', (HuiIconStyle next) {
            next.blockLight = value ? 15 : null;
            next.skyLight = value ? 15 : null;
          }),
    ),
    if (current.hasBrightnessOverride) ...<Widget>[
      _number(
        key: 'blockLight',
        label: huiText('Block light'),
        value: (current.blockLight ?? 15).toDouble(),
        max: 15,
        integer: true,
        onChanged: (double value) => _set(
          'block light',
          (HuiIconStyle next) => next.blockLight = value.round(),
        ),
      ),
      _number(
        key: 'skyLight',
        label: huiText('Sky light'),
        value: (current.skyLight ?? 15).toDouble(),
        max: 15,
        integer: true,
        onChanged: (double value) => _set(
          'sky light',
          (HuiIconStyle next) => next.skyLight = value.round(),
        ),
      ),
    ],
    _number(
      key: 'viewRange',
      label: huiText('Display view range'),
      value: current.viewRange,
      min: 0.01,
      max: 64,
      step: 0.05,
      defaultValue: '1',
      onReset: current.viewRange == 1
          ? null
          : () => _set(
              'display view range',
              (HuiIconStyle next) => next.viewRange = 1,
            ),
      onChanged: (double value) => _set(
        'display view range',
        (HuiIconStyle next) => next.viewRange = value,
      ),
    ),
    _number(
      key: 'shadowRadius',
      label: huiText('Shadow radius'),
      value: current.shadowRadius,
      max: 64,
      onChanged: (double value) => _set(
        'shadow radius',
        (HuiIconStyle next) => next.shadowRadius = value,
      ),
    ),
    _number(
      key: 'shadowStrength',
      label: huiText('Shadow strength'),
      value: current.shadowStrength,
      max: 1,
      onChanged: (double value) => _set(
        'shadow strength',
        (HuiIconStyle next) => next.shadowStrength = value,
      ),
    ),
    _number(
      key: 'cullingWidth',
      label: huiText('Cull width'),
      value: current.cullingWidth,
      max: 4096,
      onChanged: (double value) => _set(
        'culling width',
        (HuiIconStyle next) => next.cullingWidth = value,
      ),
    ),
    _number(
      key: 'cullingHeight',
      label: huiText('Cull height'),
      value: current.cullingHeight,
      max: 4096,
      onChanged: (double value) => _set(
        'culling height',
        (HuiIconStyle next) => next.cullingHeight = value,
      ),
    ),
  ];

  List<Widget> _text(HuiIconStyle current) => <Widget>[
    SwitchField(
      id: '$_id-shadow',
      label: huiText('Text shadow'),
      value: current.shadow,
      docKey: 'icon.style.shadow',
      problems: _at(const <String>{'shadow'}),
      onChanged: (bool value) =>
          _set('text shadow', (HuiIconStyle next) => next.shadow = value),
    ),
    SwitchField(
      id: '$_id-see-through',
      label: huiText('See through blocks'),
      value: current.seeThrough,
      docKey: 'icon.style.seeThrough',
      problems: _at(const <String>{'seeThrough'}),
      onChanged: (bool value) => _set(
        'text see through',
        (HuiIconStyle next) => next.seeThrough = value,
      ),
    ),
    Field(
      label: huiText('Alignment'),
      docKey: 'icon.style.textAlignment',
      problems: _at(const <String>{'textAlignment'}),
      defaultValue: _alignmentLabel('center'),
      onReset: current.textAlignment == 'center'
          ? null
          : () => _set(
              'text alignment',
              (HuiIconStyle next) => next.textAlignment = 'center',
            ),
      child: ToggleChoice<String>(
        id: '$_id-alignment',
        label: huiText('Alignment'),
        value: current.textAlignment,
        options: _choices(
          huiIconTextAlignments,
          current.textAlignment,
          _alignmentLabel,
        ),
        onChanged: (String value) => _set(
          'text alignment',
          (HuiIconStyle next) => next.textAlignment = value,
        ),
      ),
    ),
    Field(
      label: huiText('Background'),
      forId: '$_id-background',
      docKey: 'icon.style.backgroundArgb',
      problems: _at(const <String>{'backgroundArgb'}),
      defaultValue: '#00000000',
      onReset: current.backgroundArgb == '#00000000'
          ? null
          : () => _set(
              'text background',
              (HuiIconStyle next) => next.backgroundArgb = '#00000000',
            ),
      child: ColorField(
        id: '$_id-background',
        value: current.backgroundArgb,
        label: huiText('text background'),
        placeholder: '#00000000',
        onChanged: (String value) => _set(
          'text background',
          (HuiIconStyle next) => next.backgroundArgb = value,
        ),
      ),
    ),
    _number(
      key: 'textOpacity',
      label: huiText('Opacity'),
      value: current.textOpacity.toDouble(),
      max: 255,
      integer: true,
      defaultValue: '255',
      onReset: current.textOpacity == 255
          ? null
          : () => _set(
              'text opacity',
              (HuiIconStyle next) => next.textOpacity = 255,
            ),
      onChanged: (double value) => _set(
        'text opacity',
        (HuiIconStyle next) => next.textOpacity = value.round(),
      ),
    ),
    _number(
      key: 'lineWidth',
      label: huiText('Line width'),
      value: current.lineWidth.toDouble(),
      min: 1,
      max: 16384,
      integer: true,
      defaultValue: '16384',
      onReset: current.lineWidth == 16384
          ? null
          : () => _set(
              'text line width',
              (HuiIconStyle next) => next.lineWidth = 16384,
            ),
      onChanged: (double value) => _set(
        'text line width',
        (HuiIconStyle next) => next.lineWidth = value.round(),
      ),
    ),
  ];

  /// One number at style key [key]: its field doc, its problems, its id.
  Widget _number({
    required String key,
    required String label,
    required double value,
    required double max,
    required void Function(double value) onChanged,
    double min = 0,
    double step = 0.05,
    bool integer = false,
    String? defaultValue,
    void Function()? onReset,
  }) => Field(
    label: label,
    forId: '$_id-$key',
    docKey: 'icon.style.$key',
    problems: _at(<String>{key}),
    defaultValue: defaultValue,
    onReset: onReset,
    child: NumberField(
      id: '$_id-$key',
      value: value,
      min: min,
      max: max,
      step: integer ? 1 : step,
      integer: integer,
      onChanged: onChanged,
    ),
  );

  static String _billboardLabel(String value) => switch (value) {
    'fixed' => huiText('Fixed'),
    'vertical' => huiText('Vertical'),
    'horizontal' => huiText('Horizontal'),
    'center' => huiTextKey('billboard.center', 'Center'),
    _ => value,
  };

  static String _alignmentLabel(String value) => switch (value) {
    'center' => huiTextKey('alignment.center', 'Center'),
    'left' => huiText('Left'),
    'right' => huiText('Right'),
    _ => value,
  };
}
