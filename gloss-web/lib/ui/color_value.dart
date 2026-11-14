/// Colour values as Gloss documents store them: `#RRGGBB` or `#AARRGGBB`
/// text, which may also be an expression the picker cannot show. Pure Dart.
library;

/// Whether the stored text carries an alpha byte.
enum HuiColorFormat {
  /// `#RRGGBB`, six digits. The bubble shimmer band.
  rgb,

  /// `#AARRGGBB`, eight digits. Every display-style colour.
  argb,
}

/// One parsed colour. Alpha is 255 for a source that carried none.
class HuiColorParts {
  const HuiColorParts(this.a, this.r, this.g, this.b);

  final int a;
  final int r;
  final int g;
  final int b;

  /// The six digits a native colour input understands.
  String get rgbHex => '#${_hex(r)}${_hex(g)}${_hex(b)}';

  /// CSS, with the alpha applied: the swatch has to show a transparent
  /// colour as transparent rather than as a washed-out solid.
  String get css => 'rgba($r, $g, $b, ${(a / 255).toStringAsFixed(3)})';

  static String _hex(int value) =>
      value.toRadixString(16).padLeft(2, '0').toUpperCase();
}

/// Parses `#RGB`, `#RRGGBB` and `#AARRGGBB`, with or without the leading hash.
/// Anything else is null: an expression, a placeholder, a half-typed value.
HuiColorParts? huiParseHexColor(String? raw) {
  if (raw == null) return null;
  final String text = raw.trim().replaceFirst('#', '');
  if (!RegExp(r'^[0-9A-Fa-f]+$').hasMatch(text)) return null;
  switch (text.length) {
    case 3:
      final int r = int.parse(text[0], radix: 16);
      final int g = int.parse(text[1], radix: 16);
      final int b = int.parse(text[2], radix: 16);
      return HuiColorParts(255, r * 17, g * 17, b * 17);
    case 6:
      return HuiColorParts(
        255,
        int.parse(text.substring(0, 2), radix: 16),
        int.parse(text.substring(2, 4), radix: 16),
        int.parse(text.substring(4, 6), radix: 16),
      );
    case 8:
      return HuiColorParts(
        int.parse(text.substring(0, 2), radix: 16),
        int.parse(text.substring(2, 4), radix: 16),
        int.parse(text.substring(4, 6), radix: 16),
        int.parse(text.substring(6, 8), radix: 16),
      );
    default:
      return null;
  }
}

/// The picked RGB re-joined to the alpha the field already had, so choosing a
/// colour never quietly makes a transparent background opaque.
String huiApplyPickedColor(
  String picked,
  String? previous,
  HuiColorFormat format,
) {
  final HuiColorParts? next = huiParseHexColor(picked);
  if (next == null) return previous ?? picked;
  if (format == HuiColorFormat.rgb) return next.rgbHex;
  final HuiColorParts? current = huiParseHexColor(previous);
  final int alpha = current?.a ?? 255;
  return '#${HuiColorParts._hex(alpha)}'
      '${HuiColorParts._hex(next.r)}'
      '${HuiColorParts._hex(next.g)}'
      '${HuiColorParts._hex(next.b)}';
}
