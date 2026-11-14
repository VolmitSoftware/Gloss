/// [FieldStack] is shadcn's FieldGroup at the root of a form (a dialog or
/// sheet body, the inspector pane, a catalog page) with the kit's 1rem
/// rhythm: rows 1rem apart, and the width that every field inside follows
/// (label beside the control from 28rem). [FieldGroup] is an always-open
/// group: a plain heading with its (i) tip over fields, no disclosure and no
/// box. Groups a reader may close are [AppSection]s. Header actions (an Add
/// button, an [AppMenu]) sit at the end of the heading line.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import 'density.dart';
import 'info_tip.dart';

class FieldStack extends StatelessWidget {
  const FieldStack({required this.children, this.classes, super.key});

  final List<Widget> children;
  final List<String>? classes;

  @override
  Widget build(BuildContext context) => ArcaneFieldGroup(
    classes: classes,
    styles: const ArcaneStyleData(gap: Gap.md),
    children: children,
  );
}

class FieldGroup extends StatelessWidget {
  const FieldGroup({
    required this.title,
    required this.children,
    this.tip,
    this.docKey,
    this.count,
    this.actions = const <Widget>[],
    this.classes,
    super.key,
  });

  final String title;
  final List<Widget> children;
  final String? tip;
  final String? docKey;

  /// A number after the title (items inside).
  final int? count;

  /// Dense controls at the end of the heading line.
  final List<Widget> actions;
  final String? classes;

  @override
  Widget build(BuildContext context) => dom.section(
    classes: 'ui-section ui-group${classes == null ? '' : ' $classes'}',
    <Widget>[
      dom.div(classes: 'ui-section-head', <Widget>[
        dom.h3(classes: 'ui-section-title', <Widget>[
          TipLabel(label: Component.text(title), tip: tip, docKey: docKey),
        ]),
        if (count != null)
          dom.span(classes: 'ui-section-count', <Widget>[
            Component.text('$count'),
          ]),
        if (actions.isNotEmpty)
          DensityScope.dense(
            child: dom.div(classes: 'ui-section-actions', actions),
          ),
      ]),
      dom.div(classes: 'ui-section-body', children),
    ],
  );
}
