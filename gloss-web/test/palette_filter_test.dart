import 'package:gloss_editor/components/shell/palette_filter.dart';
import 'package:test/test.dart';

void main() {
  test('terms are the lowercased words typed', () {
    expect(paletteTerms('  Keyboard   SHORTCUTS '), <String>[
      'keyboard',
      'shortcuts',
    ]);
    expect(paletteTerms('   '), isEmpty);
  });

  test('every term must appear, in any order', () {
    const String haystack = 'Keyboard shortcuts keys chord binding Help';
    expect(
      paletteMatches(haystack, paletteTerms('shortcuts keyboard')),
      isTrue,
    );
    expect(paletteMatches(haystack, paletteTerms('help keys')), isTrue);
    expect(paletteMatches(haystack, paletteTerms('keyboard grid')), isFalse);
    expect(paletteMatches(haystack, const <String>[]), isTrue);
  });
}
