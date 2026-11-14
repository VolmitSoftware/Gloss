import 'package:arcane_jaspr/theme/index.dart';
import 'package:arcane_jaspr_shadcn/arcane_jaspr_shadcn.dart';

/// shadcn Midnight as shipped, with the purple of the Gloss gem as primary
/// and ring and Geist as the font. `ShadcnTheme` is an enum, so the brand
/// goes in through the seeds and the ring after the library's own CSS
/// (Midnight sets its own ring on the root). Keep it const: the base CSS is
/// cached per instance.
class GlossStylesheet extends ShadcnStylesheet {
  const GlossStylesheet() : super(theme: ShadcnTheme.midnight);

  /// The deep gem purple, for the light theme (7.2:1 under white text).
  static const int purpleLight = 0xFF7000EF;

  /// The light gem purple, for the dark theme (5.3:1 under black text).
  static const int purpleDark = 0xFFA354FF;

  static String _css(int argb) =>
      '#${(argb & 0xFFFFFF).toRadixString(16).padLeft(6, '0')}';

  @override
  ThemeSeed get lightSeed => super.lightSeed.copyWith(
    primary: purpleLight,
    // Neutral seeds stop the palette tinting secondary, accent and border.
    secondary: 0xFFF5F5F5,
    accent: 0xFFF5F5F5,
    border: 0xFFE5E5E5,
  );

  @override
  ThemeSeed get darkSeed => super.darkSeed.copyWith(primary: purpleDark);

  @override
  FontConfig get fonts => const FontConfig(
    sans:
        "'Geist', ui-sans-serif, system-ui, -apple-system, 'Segoe UI', sans-serif",
    heading:
        "'Geist', ui-sans-serif, system-ui, -apple-system, 'Segoe UI', sans-serif",
    mono: "'Geist Mono', ui-monospace, SFMono-Regular, Menlo, monospace",
  );

  @override
  String get componentCss =>
      '''
${super.componentCss}
#arcane-root.arcane-theme-shadcn {
  --ring: ${_css(purpleLight)};
  --shadcn-focus-ring: 0 0 0 2px var(--ring);
}
html.dark #arcane-root.arcane-theme-shadcn,
#arcane-root.dark.arcane-theme-shadcn {
  --ring: ${_css(purpleDark)};
}
''';
}
