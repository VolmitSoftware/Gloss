/// The number fields' arithmetic: parse what was typed, round it to the
/// field's precision, clamp it, and write it back without float noise.
/// Pure Dart.
library;

/// [raw] as a finite number, or null for anything else (empty, `1e400`).
double? parseNumber(String raw) {
  final double? parsed = double.tryParse(raw.trim());
  if (parsed == null || !parsed.isFinite) return null;
  return parsed == 0 ? 0 : parsed;
}

double _pow10(int exponent) {
  double out = 1;
  for (int index = 0; index < exponent; index++) {
    out *= 10;
  }
  return out;
}

/// [value] rounded to [decimals] (or a whole number), then clamped to
/// [min] and [max] when they are set. Null bounds leave it unbounded: Gloss
/// clamps some values itself and validation explains it.
double normalizeNumber(
  double value, {
  int decimals = 3,
  bool integer = false,
  double? min,
  double? max,
}) {
  double out = integer
      ? value.roundToDouble()
      : (value * _pow10(decimals.clamp(0, 10))).roundToDouble() /
            _pow10(decimals.clamp(0, 10));
  if (min != null && out < min) out = min;
  if (max != null && out > max) out = max;
  return out == 0 ? 0 : out;
}

/// What a field commits while [raw] is being typed: the rounded value, or
/// null while it is empty, unfinished or outside [min] and [max]. Bounds
/// apply on commit instead, so the 0 of 0.85 never becomes the minimum.
double? typedNumber(
  String raw, {
  int decimals = 3,
  bool integer = false,
  double? min,
  double? max,
}) {
  final double? parsed = parseNumber(raw);
  if (parsed == null) return null;
  final double rounded = normalizeNumber(
    parsed,
    decimals: decimals,
    integer: integer,
  );
  if ((min != null && rounded < min) || (max != null && rounded > max)) {
    return null;
  }
  return rounded;
}

/// [value] as the field shows it: at most [decimals] digits, no trailing
/// zeros, never `-0`.
String formatNumber(double value, {int decimals = 3, bool integer = false}) {
  if (!value.isFinite) return '0';
  if (integer) return value.round().toString();
  String out = value.toStringAsFixed(decimals.clamp(0, 10));
  if (out.contains('.')) {
    out = out.replaceFirst(RegExp(r'0+$'), '');
    if (out.endsWith('.')) out = out.substring(0, out.length - 1);
  }
  return out == '-0' ? '0' : out;
}

/// A slider's raw value put on its [step] and rounded to [decimals], so a
/// drag commits the same numbers the typed entry does.
double snapToStep(double raw, {required double step, int decimals = 3}) {
  final double stepped = step <= 0 ? raw : (raw / step).roundToDouble() * step;
  return normalizeNumber(stepped, decimals: decimals);
}
