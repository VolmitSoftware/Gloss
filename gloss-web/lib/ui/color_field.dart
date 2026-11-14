/// A colour as Gloss stores it: hex text (`#AARRGGBB` or `#RRGGBB`) that may
/// also be an expression. The text box is the truth; the swatch beside it
/// is a native colour picker that writes only the RGB part and keeps the
/// alpha byte the text already had. A value the picker cannot show (an
/// expression, a half-typed hex) draws an empty, chequered swatch.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import '../l10n/hui_localizations.dart';
import 'color_swatch.dart';
import 'color_value.dart';
import 'density.dart';

class ColorField extends StatelessWidget {
  const ColorField({
    required this.value,
    required this.onChanged,
    this.format = HuiColorFormat.argb,
    this.placeholder,
    this.label,
    this.disabled = false,
    this.id,
    super.key,
  });

  /// The document's own text, passed through untouched.
  final String value;
  final void Function(String value) onChanged;
  final HuiColorFormat format;
  final String? placeholder;

  /// What the colour is for ("glow colour"), in the picker's name
  /// ("Pick a glow colour").
  final String? label;
  final bool disabled;

  /// The text box's id, for `Field(forId:)`.
  final String? id;

  @override
  Widget build(BuildContext context) {
    final HuiColorParts? parts = huiParseHexColor(value);
    final String pickLabel = label == null
        ? huiText('Pick a colour')
        : huiText('Pick a {label}', <String, Object?>{'label': label});
    final bool opaque = parts != null && parts.a == 255;
    return dom.div(classes: 'ui-color-field', <Widget>[
      ColorSwatch(
        label: pickLabel,
        rgb: parts?.rgbHex ?? '#000000',
        fill: parts?.css ?? 'transparent',
        clear: !opaque,
        disabled: disabled,
        onPicked: (String rgb) =>
            onChanged(huiApplyPickedColor(rgb, value, format)),
      ),
      dom.div(classes: 'ui-color-text', <Widget>[
        TextInput(
          id: id,
          value: value,
          size: controlSize(DensityScope.of(context)),
          fullWidth: true,
          disabled: disabled,
          placeholder:
              placeholder ??
              (format == HuiColorFormat.argb ? '#FFFFFFFF' : '#FFFFFF'),
          onChanged: onChanged,
          attributes: const <String, String>{
            'autocomplete': 'off',
            'spellcheck': 'false',
            'autocapitalize': 'off',
          },
        ),
      ]),
    ]);
  }
}
