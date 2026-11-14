/// A duration typed in the unit Gloss stores (ticks or milliseconds), with
/// its reading in seconds beside it, so 4 reads as 0.2s before anyone
/// converts in their head.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../l10n/hui_localizations.dart';
import 'duration_value.dart';
import 'number_field.dart';

class DurationField extends StatelessWidget {
  const DurationField({
    required this.value,
    required this.onChanged,
    required this.unit,
    this.min,
    this.max,
    this.step = 1,
    this.perLabel,
    this.disabled = false,
    this.label,
    this.id,
    super.key,
  });

  /// The stored number, in [unit].
  final double value;
  final void Function(double value) onChanged;
  final HuiDurationUnit unit;

  /// Null leaves that side unbounded.
  final double? min;
  final double? max;
  final double step;

  /// After the reading when the duration is per something ("per frame").
  final String? perLabel;
  final bool disabled;
  final String? label;
  final String? id;

  @override
  Widget build(BuildContext context) =>
      dom.div(classes: 'ui-duration', <Widget>[
        NumberField(
          id: id,
          label: label,
          value: value,
          onChanged: onChanged,
          min: min,
          max: max,
          step: step,
          decimals: 0,
          integer: true,
          steppers: false,
          disabled: disabled,
          suffix: unit.suffix,
        ),
        dom.span(
          classes: 'ui-duration-reading',
          attributes: const <String, String>{'aria-hidden': 'true'},
          <Widget>[
            Component.text(
              perLabel == null
                  ? huiFormatDuration(value, unit)
                  : huiText('{duration} {perLabel}', <String, Object?>{
                      'duration': huiFormatDuration(value, unit),
                      'perLabel': perLabel,
                    }),
            ),
          ],
        ),
      ]);
}
