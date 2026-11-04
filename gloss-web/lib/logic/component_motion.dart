/// A component's `motion` block, read the way `ComponentMotion` reads it on the
/// server: `x`, `y` and `z` add to the authored offset, `scale` multiplies the
/// icon and its hitbox, and each value is a JSON number or an expression.
///
/// The preview draws the value for the current tick. The easing over `ticks`
/// happens in the Minecraft client, so the preview steps where the game glides.
library;

import '../model/model.dart';
import 'gloss_show.dart';
import 'preview_expr.dart';
import 'validation.dart';

const int glossMotionDefaultTicks = 3;
const int glossMotionMaxTicks = 59;
const double glossMotionMinScale = 0.001;
const List<String> glossMotionValueKeys = <String>['x', 'y', 'z', 'scale'];

/// One tick's evaluated motion.
final class ComponentMotionSample {
  const ComponentMotionSample({
    required this.x,
    required this.y,
    required this.z,
    required this.scale,
  });

  final double x;
  final double y;
  final double z;
  final double scale;

  HuiComponent moved(HuiComponent component) => component.copy()
    ..offset.x += x
    ..offset.y += y
    ..offset.z += z;
}

/// Writes one motion key, dropping the block once nothing is left in it.
void setComponentMotionValue(
  Map<String, dynamic> extras,
  String key,
  Object? value,
) {
  final Object? current = extras['motion'];
  final Map<String, dynamic> motion = current is Map
      ? Map<String, dynamic>.from(current)
      : <String, dynamic>{};
  if (value == null || value == '') {
    motion.remove(key);
  } else {
    motion[key] = value;
  }
  if (motion.isEmpty) {
    extras.remove('motion');
  } else {
    extras['motion'] = motion;
  }
}

List<HuiIssue> validateComponentMotion(
  Object? raw, {
  required String path,
  String? componentId,
}) {
  if (raw == null) return const <HuiIssue>[];
  if (raw is! Map) {
    return <HuiIssue>[
      HuiIssue(
        severity: HuiSeverity.error,
        path: path,
        componentId: componentId,
        message: 'Motion must be an object; Gloss rejects the whole menu.',
      ),
    ];
  }
  final List<HuiIssue> issues = <HuiIssue>[];
  for (final String key in glossMotionValueKeys) {
    final Object? value = raw[key];
    if (value == null || value is num) continue;
    if (value is! String) {
      issues.add(
        HuiIssue(
          severity: HuiSeverity.error,
          path: '$path.$key',
          componentId: componentId,
          message: 'Expected a number or expression string',
        ),
      );
      continue;
    }
    try {
      parsePreviewExpr(_expressionSource(value));
    } on PExprException catch (error) {
      issues.add(
        HuiIssue(
          severity: HuiSeverity.error,
          path: '$path.$key',
          componentId: componentId,
          message: 'Invalid motion expression: {error}',
          messageArguments: <String, Object?>{
            'error': error.localizedMessageArgument,
          },
        ),
      );
    }
  }
  final Object? ticks = raw['ticks'];
  if (ticks != null && !_validTicks(ticks)) {
    issues.add(
      HuiIssue(
        severity: HuiSeverity.error,
        path: '$path.ticks',
        componentId: componentId,
        message: 'Motion ticks must be a whole number from 0 to {max}.',
        messageArguments: <String, Object?>{'max': glossMotionMaxTicks},
      ),
    );
  }
  return issues;
}

bool _validTicks(Object ticks) {
  if (ticks is num) return _ticksInRange(ticks);
  if (ticks is! String) return false;
  try {
    final PExpr expression = parsePreviewExpr(_expressionSource(ticks));
    return !isConstantExpr(expression) ||
        _ticksInRange(evalNumber(expression, GlossShowScope()));
  } on PExprException {
    return false;
  }
}

bool _ticksInRange(num value) =>
    value == value.roundToDouble() &&
    value >= 0 &&
    value <= glossMotionMaxTicks;

/// The motion for this tick, or null when the component has none or an
/// expression fails. A failing expression leaves the server's icon in place.
ComponentMotionSample? sampleComponentMotion(
  Object? raw, {
  PExprScope? scope,
  int nowMs = 0,
}) {
  if (raw is! Map) return null;
  if (!glossMotionValueKeys.any((String key) => raw[key] != null)) return null;
  final GlossShowScope showScope = GlossShowScope(scope: scope, nowMs: nowMs);
  try {
    final double x = _number(raw['x'], 0, showScope);
    final double y = _number(raw['y'], 0, showScope);
    final double z = _number(raw['z'], 0, showScope);
    final double scale = _number(raw['scale'], 1, showScope);
    if (!x.isFinite || !y.isFinite || !z.isFinite || !scale.isFinite) {
      return null;
    }
    return ComponentMotionSample(
      x: x,
      y: y,
      z: z,
      scale: scale < glossMotionMinScale ? glossMotionMinScale : scale,
    );
  } on PExprException {
    return null;
  }
}

double _number(Object? value, double fallback, PExprScope scope) {
  if (value == null) return fallback;
  if (value is num) return value.toDouble();
  if (value is! String) return fallback;
  return evalNumber(parsePreviewExpr(_expressionSource(value)), scope);
}

String _expressionSource(String value) {
  final String source = value.trim();
  if (source.startsWith('{{') && source.endsWith('}}')) {
    return source.substring(2, source.length - 2).trim();
  }
  return source;
}
