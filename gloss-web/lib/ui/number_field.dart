/// A number on shadcn's text input. What is typed stays local until it
/// parses and fits the bounds: an empty, broken or out-of-range entry never
/// reaches the document and shows the input's error state. Blur and Enter
/// clamp it, or put back the last good value, so a document never gets NaN.
/// Bounds are optional; Gloss clamps some values itself and validation says
/// so. Step buttons sit either side, as tall as the input ([DensityScope]).
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import '../l10n/hui_localizations.dart';
import 'affixed.dart';
import 'density.dart';
import 'icon_button.dart';
import 'number_value.dart';

class NumberField extends StatefulWidget {
  const NumberField({
    required this.value,
    required this.onChanged,
    this.min,
    this.max,
    this.step = 0.05,
    this.decimals = 3,
    this.integer = false,
    this.steppers = true,
    this.disabled = false,
    this.placeholder,
    this.prefix,
    this.prefixTip,
    this.suffix,
    this.label,
    this.id,
    super.key,
  });

  final double value;
  final void Function(double value) onChanged;

  /// Null leaves that side unbounded.
  final double? min;
  final double? max;

  /// What the step buttons and the arrow keys add.
  final double step;

  /// Digits kept when the value is written back.
  final int decimals;
  final bool integer;
  final bool steppers;
  final bool disabled;
  final String? placeholder;

  /// A short mark before the number (an axis letter), beside the input.
  final String? prefix;

  /// A tooltip on [prefix] ("x: right of the player"), also the input's
  /// description. A tooltip around the whole input would shrink it.
  final String? prefixTip;

  /// A unit after the number, beside the input.
  final String? suffix;

  /// The accessible name when no `<label for>` names it.
  final String? label;

  /// The input's id; inside a [Field] the library gives it the field's.
  final String? id;

  @override
  State<NumberField> createState() => _NumberFieldState();
}

class _NumberFieldState extends State<NumberField> {
  late String _text = _format(component.value);

  String _format(double value) => formatNumber(
    value,
    decimals: component.decimals,
    integer: component.integer,
  );

  double _normalize(double value) => normalizeNumber(
    value,
    decimals: component.decimals,
    integer: component.integer,
    min: component.min,
    max: component.max,
  );

  double? _typed(String raw) => typedNumber(
    raw,
    decimals: component.decimals,
    integer: component.integer,
    min: component.min,
    max: component.max,
  );

  @override
  void didUpdateWidget(NumberField oldComponent) {
    super.didUpdateWidget(oldComponent);
    // Only replace what is being typed when the value really differs, so a
    // commit round trip never fights the caret. Text that cannot commit yet
    // stays until the value changes from somewhere else.
    final double? typed = _typed(_text);
    if (typed == null
        ? component.value != oldComponent.value
        : _format(typed) != _format(component.value)) {
      _text = _format(component.value);
    }
  }

  void _input(String raw) {
    setState(() => _text = raw);
    final double? next = _typed(raw);
    if (next != null && next != component.value) component.onChanged(next);
  }

  void _commit() {
    final double? parsed = parseNumber(_text);
    final double next = parsed == null ? component.value : _normalize(parsed);
    if (next != component.value) component.onChanged(next);
    final String shown = _format(next);
    if (shown != _text) setState(() => _text = shown);
  }

  void _step(int direction) {
    final double base = component.value.isFinite ? component.value : 0;
    final double next = _normalize(base + component.step * direction);
    setState(() => _text = _format(next));
    if (next != component.value) component.onChanged(next);
  }

  @override
  Widget build(BuildContext context) {
    final NumberField field = component;
    final Density density = DensityScope.of(context);
    final Widget input = TextInput(
      id: field.id,
      type: TextInputType.number,
      value: _text,
      onChanged: _input,
      onBlur: _commit,
      onSubmitted: (String _) => _commit(),
      disabled: field.disabled,
      size: controlSize(density),
      fullWidth: true,
      placeholder: field.placeholder,
      attributes: <String, String>{
        'aria-label': ?field.label,
        'aria-description': ?field.prefixTip,
        'inputmode': field.integer ? 'numeric' : 'decimal',
        'autocomplete': 'off',
        if (_typed(_text) == null) 'aria-invalid': 'true',
        'step': field.integer ? '1' : _format(field.step),
        if (field.min != null) 'min': _format(field.min!),
        if (field.max != null) 'max': _format(field.max!),
      },
    );
    return Affixed(
      prefix: field.prefix,
      prefixTip: field.prefixTip,
      suffix: field.suffix,
      child: field.steppers
          ? _withSteppers(field, input)
          : dom.div(classes: 'ui-number', <Widget>[input]),
    );
  }

  Widget _withSteppers(NumberField field, Widget input) =>
      dom.div(classes: 'ui-number has-steppers', <Widget>[
        AppIconButton(
          icon: ArcaneIcon.minus(),
          label: huiText('Decrease'),
          outline: true,
          disabled: field.disabled,
          onPressed: () => _step(-1),
        ),
        dom.div(classes: 'ui-number-input', <Widget>[input]),
        AppIconButton(
          icon: ArcaneIcon.plus(),
          label: huiText('Increase'),
          outline: true,
          disabled: field.disabled,
          tipAtEnd: true,
          onPressed: () => _step(1),
        ),
      ]);
}
