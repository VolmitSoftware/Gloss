// The editor theme: shadcn Midnight with the Gloss purple as primary and
// ring in both modes, Geist as the font, no Ascii stylesheet left.
import 'dart:io';

import 'package:gloss_editor/config/gloss_stylesheet.dart';
import 'package:test/test.dart';

void main() {
  final String css = const GlossStylesheet().baseCss.toLowerCase();

  test('should use the purple as primary and ring in both modes', () {
    expect(css, contains('--primary: #7000ef'));
    expect(css, contains('--primary: #a354ff'));
    expect(css, contains('--ring: #7000ef'));
    expect(css, contains('--ring: #a354ff'));
  });

  test('should be Midnight with Geist', () {
    expect(const GlossStylesheet().bodyClass, 'shadcn-midnight');
    expect(css, contains("--font-sans: 'geist'"));
  });

  test('should not depend on the Ascii stylesheet any more', () {
    final List<String> found = <String>[
      for (final FileSystemEntity entity in Directory(
        'lib',
      ).listSync(recursive: true))
        if (entity is File &&
            entity.path.endsWith('.dart') &&
            entity.readAsStringSync().contains('arcane_jaspr_ascii'))
          entity.path,
    ];
    expect(found, isEmpty);
    expect(File('pubspec.yaml').readAsStringSync(), isNot(contains('ascii')));
  });
}
