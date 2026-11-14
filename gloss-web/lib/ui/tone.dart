/// What a badge, alert or row state reports. Pure Dart.
library;

import '../logic/validation.dart' show HuiSeverity;

enum BadgeTone { neutral, ok, warning, error, info }

/// The badge tone of a validation issue.
BadgeTone badgeToneOf(HuiSeverity severity) => switch (severity) {
  HuiSeverity.error => BadgeTone.error,
  HuiSeverity.warning => BadgeTone.warning,
  HuiSeverity.info => BadgeTone.info,
};
