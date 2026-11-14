/// A field with a short unit or axis letter beside it ("ticks", "X"), drawn
/// outside the input's border. The library draws affixes inside a bordered
/// container around a shorter borderless input; outside, the input keeps the
/// row's full control height and its own border, like every other field.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

class Affixed extends StatelessWidget {
  const Affixed({
    required this.child,
    this.prefix,
    this.prefixTip,
    this.suffix,
    super.key,
  });

  /// The input (or a stepper group around it).
  final Widget child;

  /// Before the input ("X").
  final String? prefix;

  /// A tooltip on [prefix]. Give the input the same text as its
  /// `aria-description`: the affix itself is hidden from assistive
  /// technology, the input's name already says it.
  final String? prefixTip;

  /// After the input ("ticks").
  final String? suffix;

  Widget _affix(String text, String? tip) => dom.span(
    classes: 'ui-affix',
    attributes: const <String, String>{'aria-hidden': 'true'},
    <Widget>[
      if (tip == null)
        Component.text(text)
      else
        ArcaneTooltip(text: tip, child: Component.text(text)),
    ],
  );

  @override
  Widget build(BuildContext context) {
    if (prefix == null && suffix == null) return child;
    return dom.div(classes: 'ui-affixed', <Widget>[
      if (prefix != null) _affix(prefix!, prefixTip),
      dom.div(classes: 'ui-affixed-input', <Widget>[child]),
      if (suffix != null) _affix(suffix!, null),
    ]);
  }
}
