/// Durations in the unit Gloss stores (server ticks or milliseconds) and
/// their human reading. Pure Dart.
library;

import '../l10n/hui_localizations.dart';

/// The unit the document stores.
enum HuiDurationUnit {
  /// Server ticks. One tick is 50 ms, and the server never subdivides one.
  ticks,

  /// Wall-clock milliseconds.
  milliseconds,
}

extension HuiDurationUnitNames on HuiDurationUnit {
  /// Suffix shown inside the input.
  String get suffix => this == HuiDurationUnit.ticks ? huiText('ticks') : 'ms';

  /// Milliseconds one unit is worth.
  double get millisecondsEach => this == HuiDurationUnit.ticks ? 50 : 1;
}

/// `4` ticks reads as `0.2s`; `60000` ms reads as `60s`. Sub-millisecond
/// precision is never useful here, and neither is a trailing `.00`.
String huiFormatDuration(double value, HuiDurationUnit unit) {
  final double seconds = value * unit.millisecondsEach / 1000;
  if (seconds == 0) return huiText('instant');
  if (seconds < 1) return '${(seconds * 1000).round()}ms';
  final String text = seconds.toStringAsFixed(seconds < 10 ? 2 : 1);
  return '${text.replaceFirst(RegExp(r'\.?0+$'), '')}s';
}
