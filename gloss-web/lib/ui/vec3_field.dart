/// Three numbers (x, y, z) in one row, each a [NumberField] with its axis
/// letter inside and an optional tooltip saying which way the axis points.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import '../model/vec3.dart';
import '../l10n/hui_localizations.dart';
import 'number_field.dart';

/// The axis tooltips for an offset from the player.
List<String> offsetAxisHints() => <String>[
  huiText('x: right of the player'),
  huiText('y: up (1.7 is roughly eye level)'),
  huiText('z: forward, away from the player'),
];

class Vec3Field extends StatelessWidget {
  const Vec3Field({
    required this.value,
    required this.onChanged,
    this.labels = const <String>['X', 'Y', 'Z'],
    this.axisHints,
    this.step = 0.05,
    this.decimals = 3,
    this.disabled = false,
    this.label,
    this.id,
    super.key,
  });

  final Vec3 value;
  final void Function(Vec3 value) onChanged;

  /// The axis letters inside the inputs.
  final List<String> labels;

  /// One tooltip per axis ([offsetAxisHints] for an offset); null for none.
  final List<String>? axisHints;
  final double step;
  final int decimals;
  final bool disabled;

  /// What the three numbers are ("Offset"), in each input's name.
  final String? label;

  /// The first input's id, for `Field(forId:)`; the others add `-y`, `-z`.
  final String? id;

  Widget _axis(int index, double axis, void Function(double) apply) {
    final String letter = index < labels.length
        ? labels[index]
        : const <String>['X', 'Y', 'Z'][index];
    final List<String>? hints = axisHints;
    final String? hint = hints != null && index < hints.length
        ? hints[index]
        : null;
    return NumberField(
      id: id == null ? null : (index == 0 ? id : '$id-${letter.toLowerCase()}'),
      label: label == null ? letter : '$label $letter',
      value: axis,
      onChanged: apply,
      step: step,
      decimals: decimals,
      disabled: disabled,
      steppers: false,
      prefix: letter,
      prefixTip: hint == null || hint.isEmpty ? null : hint,
    );
  }

  @override
  Widget build(BuildContext context) => dom.div(classes: 'ui-vec3', <Widget>[
    _axis(0, value.x, (double x) => onChanged(Vec3(x, value.y, value.z))),
    _axis(1, value.y, (double y) => onChanged(Vec3(value.x, y, value.z))),
    _axis(2, value.z, (double z) => onChanged(Vec3(value.x, value.y, z))),
  ]);
}
