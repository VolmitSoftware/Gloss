/// State labels on shadcn's status badge: Valid, 3 problems, Connected,
/// Applied. A badge is text first; colour only repeats what it says.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import 'tone.dart';

class StatusBadge extends StatelessWidget {
  const StatusBadge(
    this.label, {
    this.tone = BadgeTone.neutral,
    this.dot = false,
    this.attributes = const <String, String>{},
    super.key,
  });

  final String label;
  final BadgeTone tone;

  /// A leading dot for live state (online, applying); off for labels.
  final bool dot;

  /// Kept on a wrapper (the library badge takes none): `role`, `data-*`.
  final Map<String, String> attributes;

  @override
  Widget build(BuildContext context) {
    final Widget badge = ArcaneStatusBadge(
      label: label,
      size: ComponentSize.sm,
      variant: tone == BadgeTone.neutral
          ? BadgeVariant.outline
          : BadgeVariant.status,
      status: switch (tone) {
        BadgeTone.ok => StatusType.success,
        BadgeTone.warning => StatusType.warning,
        BadgeTone.error => StatusType.error,
        BadgeTone.info || BadgeTone.neutral => StatusType.info,
      },
      showDot: dot,
    );
    return dom.span(attributes: attributes, <Widget>[badge]);
  }
}

/// A badge that opens what it counts (the problems panel from "3 problems").
