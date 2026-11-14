import 'package:gloss_editor/logic/gloss_text.dart';
import 'package:gloss_editor/logic/mc_text.dart';
import 'package:test/test.dart';

/// Each run as `text#RRGGBB` plus `+b`, `+i`, `+u`, `+s`, `+k` for the formats
/// that are on; adjacent runs with one style merge, as the client draws them.
List<String> _runs(Iterable<McSpan> spans) {
  final List<McSpan> merged = <McSpan>[];
  for (final McSpan span in spans) {
    if (span.text.isEmpty) continue;
    if (merged.isNotEmpty && merged.last.withText('') == span.withText('')) {
      merged.last = merged.last.withText(merged.last.text + span.text);
    } else {
      merged.add(span);
    }
  }
  return <String>[
    for (final McSpan span in merged)
      '${span.text}#${span.color.toRadixString(16).padLeft(6, '0').toUpperCase()}'
          '${span.bold ? '+b' : ''}${span.italic ? '+i' : ''}'
          '${span.underlined ? '+u' : ''}${span.strikethrough ? '+s' : ''}'
          '${span.obfuscated ? '+k' : ''}',
  ];
}

List<McSpan> _spans(GlossLineRender render) => <McSpan>[
  for (final GlossTextPiece piece in render.pieces)
    if (piece case GlossTextRun(:final McSpan span)) span,
];

List<List<String>> _lines(GlossLineRender render) => <List<String>>[
  for (final GlossLineRender line in glossDisplayLines(render))
    _runs(_spans(line)),
];

void main() {
  group('surface default colour', () {
    test('uncoloured text takes the surface default', () {
      expect(_runs(_spans(renderGlossLine('Welcome', defaultColor: 0x808080))), <String>[
        'Welcome#808080',
      ]);
    });

    test('&r returns to the surface default, not white', () {
      expect(
        _runs(_spans(renderGlossLine('&lHi&r there', defaultColor: 0x808080))),
        <String>['Hi#808080+b', ' there#808080'],
      );
      expect(
        _runs(_spans(renderGlossLine('&cA&rB', defaultColor: 0x404040))),
        <String>['A#FF5555', 'B#404040'],
      );
    });

    test('MiniMessage text takes the surface default too', () {
      expect(
        _runs(
          _spans(
            renderGlossLine(
              '<bold>A</bold>B<reset>C',
              richText: true,
              defaultColor: 0x404040,
            ),
          ),
        ),
        <String>['A#404040+b', 'BC#404040'],
      );
    });

    test('without a surface default the text is white', () {
      expect(_runs(_spans(renderGlossLine('A&rB'))), <String>['AB#FFFFFF']);
    });
  });

  group('escapes on legacy-only surfaces', () {
    test(r'\& stays a literal & and is not read again', () {
      expect(_runs(_spans(renderGlossLine(r'\&cA'))), <String>['&cA#FFFFFF']);
    });

    test(r'\[ stays a literal [ and is not read again', () {
      expect(_runs(_spans(renderGlossLine(r'\[FF0000]Hello'))), <String>[
        '[FF0000]Hello#FFFFFF',
      ]);
    });
  });

  group('section signs the client swallows', () {
    test('an unknown code is dropped with its character', () {
      expect(_runs(_spans(renderGlossLine('§zA&cB'))), <String>[
        'A#FFFFFF',
        'B#FF5555',
      ]);
      expect(_runs(_spans(renderGlossLine('§zA&cB', richText: true))), <String>[
        'A#FFFFFF',
        'B#FF5555',
      ]);
    });

    test('a trailing section sign is dropped', () {
      expect(_runs(_spans(renderGlossLine('A§'))), <String>['A#FFFFFF']);
      expect(_runs(_spans(renderGlossLine('A§', richText: true))), <String>[
        'A#FFFFFF',
      ]);
    });

    test('a short hex run drops its x and keeps the colour codes after it', () {
      expect(_runs(_spans(renderGlossLine('§x§1§2A'))), <String>['A#00AA00']);
    });

    test('a section sign before a newline swallows the newline', () {
      expect(_lines(renderGlossLine('A§\nB')), <List<String>>[
        <String>['AB#FFFFFF'],
      ]);
    });
  });

  group('unmatched MiniMessage closers', () {
    test('print as text, as the game does', () {
      expect(
        _runs(parseMcDisplayText('<bold>&cHello</bold> world').lines.single),
        <String>['Hello</bold> world#FF5555'],
      );
      expect(_runs(parseMcDisplayText('A</red>B').lines.single), <String>[
        'A</red>B#FFFFFF',
      ]);
    });

    test('a closer on its own line prints when every line starts fresh', () {
      expect(
        <List<String>>[
          for (final List<McSpan> line in parseMcText('<gold>T\nS</gold>').lines)
            _runs(line),
        ],
        <List<String>>[
          <String>['T#FFAA00'],
          <String>['S</gold>#FFFFFF'],
        ],
      );
    });
  });
}
