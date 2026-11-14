// The editor's stylesheets lay out its own panes and draw the game screens;
// they never restyle an arcane_jaspr component, editor chrome takes its
// colours from shadcn tokens, and the total stays under a ceiling so custom
// CSS cannot creep back.
import 'dart:io';

import 'package:test/test.dart';

/// Lines of app CSS in web/styles. Lower it when CSS goes, never raise it:
/// a new surface uses the kit and the library, not new rules.
const int cssLineCeiling = 2782;

/// Stylesheets that draw Minecraft itself (its palette, chat colours and
/// screen chrome) and may write colour literals. Everything else uses
/// tokens.
const Set<String> gamePaletteFiles = <String>{
  '07-preview.css', // menu 3D preview: chat colours, item and text palette
  '08-preview-card.css', // container preview: the chest window
  '10-gloss.css', // game-screen kinds: MOTD, scoreboard, bubbles, drops
  '12-gloss-fidelity.css', // game HUD chrome: tablist, bossbar, hotbar
  '13-mc-stage.css', // WebGL stage
};

/// Stylesheets that are already clean (the kit and the foundations): no
/// library selectors and no colour literals, with or without the switch.
const Set<String> cleanFiles = <String>{'00-kit.css', '01-foundations.css'};

/// Library class names: `arcane-*` (except the theme class on the root) and
/// the shadcn sidebar's own, plus the library's `data-arcane-*` attributes.
final RegExp libraryClass = RegExp(
  r'\.(arcane-(?!theme-)[a-z0-9-]+|sidebar-[a-z0-9-]+)|\[data-arcane-',
);

final RegExp _comments = RegExp(r'/\*[\s\S]*?\*/');
final RegExp _hex = RegExp(
  r'(?<![\w-])#(?:[0-9a-f]{8}|[0-9a-f]{6}|[0-9a-f]{4}|[0-9a-f]{3})(?![\w-])',
  caseSensitive: false,
);
final RegExp _colorFunction = RegExp(
  r'\b(rgba?|hsla?|hwb|lab|lch|oklab|oklch)\(([^()]*(?:\([^()]*\)[^()]*)*)\)',
  caseSensitive: false,
);
final RegExp _named = RegExp(
  r'(?<![\w-])(white|black|red|green|blue|yellow|orange|purple|pink|gray|grey|gold|silver|cyan|magenta|lime|navy|teal|maroon)(?![\w-])',
  caseSensitive: false,
);

/// The app's stylesheets, in load order. Add CSS the stylesheet class
/// writes from Dart here if the redesign adds any.
List<File> stylesheets() => <File>[
  for (final FileSystemEntity entity in Directory('web/styles').listSync())
    if (entity is File && entity.path.endsWith('.css')) entity,
]..sort((File a, File b) => a.path.compareTo(b.path));

String fileName(File file) => file.uri.pathSegments.last;

/// The selectors of [css], comments dropped, one per rule head.
Iterable<String> selectors(String css) sync* {
  final String text = css.replaceAll(_comments, '');
  for (final RegExpMatch match in RegExp(r'([^{}]+)\{').allMatches(text)) {
    final String head = match[1]!.trim();
    if (head.isEmpty || head.startsWith('@')) continue;
    yield head;
  }
}

/// Every colour literal in the declaration values of [css].
Iterable<String> colorLiterals(String css) sync* {
  final String text = css.replaceAll(_comments, '');
  for (final RegExpMatch block in RegExp(r'\{([^{}]*)\}').allMatches(text)) {
    for (final String declaration in block[1]!.split(';')) {
      final int colon = declaration.indexOf(':');
      if (colon < 0) continue;
      final String value = declaration
          .substring(colon + 1)
          .replaceAll(RegExp(r'url\([^)]*\)'), '')
          .replaceAll(
            RegExp(
              r'"[^"]*"'
              "|'[^']*'",
            ),
            '',
          );
      for (final RegExpMatch match in _hex.allMatches(value)) {
        yield match[0]!;
      }
      for (final RegExpMatch match in _colorFunction.allMatches(value)) {
        if (!match[2]!.contains('var(')) yield match[0]!;
      }
      for (final RegExpMatch match in _named.allMatches(value)) {
        yield match[0]!;
      }
    }
  }
}

/// Rule heads that select a library class or attribute, per file.
List<String> libraryHeads() => <String>[
  for (final File file in stylesheets())
    for (final String selector in selectors(file.readAsStringSync()))
      if (libraryClass.hasMatch(selector))
        '${fileName(file)}: ${selector.replaceAll(RegExp(r'\s+'), ' ')}',
];

/// Colour literals outside [gamePaletteFiles], per file.
List<String> chromeLiterals() => <String>[
  for (final File file in stylesheets())
    if (!gamePaletteFiles.contains(fileName(file)))
      for (final String literal in colorLiterals(file.readAsStringSync()))
        '${fileName(file)}: $literal',
];

void main() {
  test('the kit and foundation styles are already clean', () {
    final List<String> found = <String>[
      for (final String head in libraryHeads())
        if (cleanFiles.contains(head.split(':').first)) head,
      for (final File file in stylesheets())
        if (cleanFiles.contains(fileName(file)))
          for (final String literal in colorLiterals(file.readAsStringSync()))
            '${fileName(file)}: $literal',
    ];
    expect(found, isEmpty);
  });

  test('no app stylesheet selects an arcane_jaspr class or attribute', () {
    final List<String> found = libraryHeads();
    stdout.writeln(
      'css_scan: ${found.length} rule heads select a library class',
    );
    expect(
      found,
      isEmpty,
      reason:
          '${found.length} rule heads restyle a library component. Use the '
          'component as it renders; fix a defect in arcane_jaspr.',
    );
  });

  test('colour literals only in the game palette files', () {
    final List<String> found = chromeLiterals();
    stdout.writeln(
      'css_scan: ${found.length} colour literals outside the game palette files',
    );
    expect(
      found,
      isEmpty,
      reason:
          '${found.length} colour literals in editor chrome. Use a shadcn '
          'token (var(--primary), var(--border), ...).',
    );
  });

  test('app CSS stays under its line ceiling', () {
    final int lines = <int>[
      for (final File file in stylesheets()) file.readAsLinesSync().length,
    ].fold(0, (int sum, int each) => sum + each);
    stdout.writeln(
      'css_scan: $lines lines of app CSS, ceiling $cssLineCeiling',
    );
    expect(
      lines,
      lessThanOrEqualTo(cssLineCeiling),
      reason: '$lines lines of app CSS',
    );
  });

  test('the scan catches what it looks for', () {
    expect(
      selectors(
        '/* .arcane-button */ .hui-bar > .arcane-button { color: red; }',
      ).single,
      '.hui-bar > .arcane-button',
    );
    expect(libraryClass.hasMatch('#arcane-root.arcane-theme-shadcn'), isFalse);
    expect(libraryClass.hasMatch('.ui-nav .sidebar-link'), isTrue);
    expect(libraryClass.hasMatch(':is(.arcane-toggle-group-item, .x)'), isTrue);
    expect(libraryClass.hasMatch('.hui-tip [data-arcane-surface="x"]'), isTrue);
    expect(libraryClass.hasMatch('.hui-sidebar-row'), isFalse);
    expect(
      selectors('@media (max-width: 760px) { .a { b: c; } }').toList(),
      <String>['.a'],
    );
    expect(
      colorLiterals(
        '#add-row, .x { color: #fff; border: 1px solid rgb(0 0 0 / 0.4); '
        'background: rgb(var(--primary) / 0.2); white-space: nowrap; '
        'outline-color: white; font-family: "Black"; '
        'fill: color-mix(in srgb, var(--primary), #00000080); '
        'mask: url(#mark); }',
      ).toList(),
      <String>['#fff', 'rgb(0 0 0 / 0.4)', 'white', '#00000080'],
    );
  });
}
