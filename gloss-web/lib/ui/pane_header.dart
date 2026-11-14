/// The head of the inspector pane: the document or component kind as a
/// small line, its name as the title with an (i) tip, a status (the
/// revision, in Advanced only), and a dense [ActionRow] with the `...` menu,
/// secondary buttons and the primary one last. No crumbs, rename or view
/// switch: the top bar owns those.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import 'action_row.dart';
import 'info_tip.dart';
import 'menu.dart';

class PaneHeader extends StatelessWidget {
  const PaneHeader({
    required this.title,
    this.kind,
    this.tip,
    this.docKey,
    this.status,
    this.actions = const <AppMenuEntry>[],
    this.secondary = const <RowAction>[],
    this.primary,
    this.menuId,
    super.key,
  });

  /// The id or name of what the pane edits.
  final String title;

  /// What it is ("Button", "Scoreboard").
  final String? kind;
  final String? tip;
  final String? docKey;

  /// A badge after the title; wrap a revision in `AdvancedOnly`.
  final Widget? status;

  /// The `...` menu.
  final List<AppMenuEntry> actions;
  final List<RowAction> secondary;
  final RowAction? primary;

  /// The `...` menu's id; its button is `<menuId>-button`.
  final String? menuId;

  @override
  Widget build(BuildContext context) {
    final bool hasActions =
        actions.isNotEmpty || secondary.isNotEmpty || primary != null;
    return dom.header(classes: 'ui-pane-header', <Widget>[
      dom.div(classes: 'ui-pane-heading', <Widget>[
        if (kind != null)
          dom.span(classes: 'ui-pane-kind', <Widget>[Component.text(kind!)]),
        dom.div(classes: 'ui-pane-title-line', <Widget>[
          dom.h2(classes: 'ui-pane-title', <Widget>[
            TipLabel(label: Component.text(title), tip: tip, docKey: docKey),
          ]),
          ?status,
        ]),
      ]),
      if (hasActions)
        ActionRow(
          dense: true,
          more: actions,
          moreId: menuId,
          secondary: secondary,
          primary: primary,
        ),
    ]);
  }
}
