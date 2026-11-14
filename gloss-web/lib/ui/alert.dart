/// A message in the page: an error with Retry, a warning, a note. On
/// shadcn's alert (full border, no side stripe). Not dismissible: a message
/// goes when its cause does.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

enum AlertTone { info, ok, warning, error }

class AppAlert extends StatelessWidget {
  const AppAlert({
    required this.message,
    this.title,
    this.tone = AlertTone.info,
    this.action,
    this.live = false,
    super.key,
  });

  final String? title;
  final String message;
  final AlertTone tone;

  /// One button: Retry, Save again.
  final Widget? action;

  /// Read out when it appears (`role=alert` for errors, `status` otherwise).
  final bool live;

  @override
  Widget build(BuildContext context) {
    final Widget alert = switch (tone) {
      AlertTone.info => ArcaneAlert.info(
        title: title,
        message: message,
        action: action,
      ),
      AlertTone.ok => ArcaneAlert.success(
        title: title,
        message: message,
        action: action,
      ),
      AlertTone.warning => ArcaneAlert.warning(
        title: title,
        message: message,
        action: action,
      ),
      AlertTone.error => ArcaneAlert.error(
        title: title,
        message: message,
        action: action,
      ),
    };
    return dom.div(
      attributes: live
          ? <String, String>{
              'role': tone == AlertTone.error ? 'alert' : 'status',
            }
          : const <String, String>{},
      <Widget>[alert],
    );
  }
}
