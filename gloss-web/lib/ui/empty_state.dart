/// An empty list or pane: an icon, one line, one action ("No documents yet."
/// [New document]). On shadcn's empty state.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

class EmptyState extends StatelessWidget {
  const EmptyState({required this.message, this.icon, this.action, super.key});

  /// One short line that says why it is empty.
  final String message;
  final ArcaneGlyph? icon;

  /// The one action that fills it.
  final Widget? action;

  @override
  Widget build(BuildContext context) => dom.div(
    classes: 'ui-empty',
    attributes: const <String, String>{'role': 'status'},
    <Widget>[
      ArcaneEmptyState(
        title: message,
        icon: icon,
        action: action,
        size: EmptyStateSize.sm,
      ),
    ],
  );
}
