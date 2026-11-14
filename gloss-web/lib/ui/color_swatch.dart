/// The kit's colour swatch: the browser's colour picker in a square chip.
/// The library has no colour picker, so this is the one native colour input.
/// [ColorField] draws it beside its hex text; an expression field or a text
/// editor can draw it alone.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

class ColorSwatch extends StatelessWidget {
  const ColorSwatch({
    required this.label,
    required this.rgb,
    required this.onPicked,
    this.fill,
    this.clear = false,
    this.disabled = false,
    this.commit = false,
    super.key,
  });

  /// The picker's name and tooltip ("Pick a glow colour").
  final String label;

  /// The picker's value, `#rrggbb`. Lowercase: the browser lowercases it,
  /// and an uppercase value would be rewritten on every rebuild.
  final String rgb;

  /// The chip's fill (any CSS colour); [rgb] when null.
  final String? fill;

  /// Chequered: the colour is see-through or not a colour at all.
  final bool clear;
  final bool disabled;

  /// Report only the final pick (the picker's change event) instead of
  /// every step while the picker is open.
  final bool commit;

  /// Receives the browser's `#rrggbb`.
  final void Function(String rgb) onPicked;

  @override
  Widget build(BuildContext context) => ArcaneTooltip(
    text: label,
    child: dom.span(
      classes: 'ui-color-swatch${clear ? ' is-clear' : ''}',
      styles: dom.Styles(raw: <String, String>{'--ui-swatch': fill ?? rgb}),
      <Widget>[
        // A `color` input is the one type whose event value jaspr decodes
        // into a `Color`, so the type argument says so.
        dom.input<dom.Color>(
          type: dom.InputType.color,
          value: rgb.toLowerCase(),
          disabled: disabled,
          onInput: commit ? null : (dom.Color next) => onPicked(next.value),
          onChange: commit ? (dom.Color next) => onPicked(next.value) : null,
          attributes: <String, String>{'aria-label': label},
        ),
      ],
    ),
  );
}
