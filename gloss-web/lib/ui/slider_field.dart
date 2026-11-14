/// A track and a number box for one value, on shadcn's slider. The track
/// covers [SliderField.min] to [SliderField.max]; the box may go past it
/// when the number bounds allow (Gloss clamps some values itself and
/// validation says so). A drag commits the same rounded numbers the box
/// does.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import 'density.dart';
import 'number_field.dart';
import 'number_value.dart';

class SliderField extends StatelessWidget {
  const SliderField({
    required this.label,
    required this.value,
    required this.onChanged,
    required this.min,
    required this.max,
    this.step = 0.01,
    this.decimals = 3,
    this.numberMin,
    this.numberMax,
    this.disabled = false,
    this.id,
    super.key,
  });

  /// Names the track and the box.
  final String label;
  final double value;
  final void Function(double value) onChanged;
  final double min;
  final double max;
  final double step;
  final int decimals;

  /// Bounds for the typed number; null leaves it unbounded.
  final double? numberMin;
  final double? numberMax;
  final bool disabled;

  /// The number box's id, for `Field(forId:)`.
  final String? id;

  @override
  Widget build(BuildContext context) => dom.div(
    classes: 'ui-slider-field',
    <Widget>[
      dom.div(classes: 'ui-slider-track', <Widget>[
        ArcaneSlider(
          value: value.clamp(min, max).toDouble(),
          min: min,
          max: max,
          step: step,
          ariaLabel: label,
          showValue: false,
          showBounds: false,
          disabled: disabled,
          size: controlSize(DensityScope.of(context)),
          onChanged: (double raw) {
            final double next = snapToStep(raw, step: step, decimals: decimals);
            if (next != value) onChanged(next);
          },
        ),
      ]),
      NumberField(
        id: id,
        label: label,
        value: value,
        onChanged: onChanged,
        min: numberMin,
        max: numberMax,
        step: step,
        decimals: decimals,
        steppers: false,
        disabled: disabled,
      ),
    ],
  );
}
