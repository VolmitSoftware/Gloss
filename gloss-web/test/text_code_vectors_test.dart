import 'dart:convert';
import 'dart:io';

import 'package:gloss_editor/logic/bubble_motion.dart';
import 'package:gloss_editor/logic/bubble_preview.dart';
import 'package:gloss_editor/logic/canvas_scene.dart';
import 'package:gloss_editor/logic/gloss_hologram_scene.dart';
import 'package:gloss_editor/logic/gloss_text.dart';
import 'package:gloss_editor/logic/mc_text.dart';
import 'package:gloss_editor/logic/player_identity_preview.dart';
import 'package:gloss_editor/model/model.dart';
import 'package:test/test.dart';

/// The cross-repo contract for how colour and format codes combine:
/// `Gloss/src/test/resources/text_code_vectors.json` copied verbatim. The Java
/// suite (`TextCodeVectorsTest`) sends every vector the ways the plugin sends
/// text and reads it back the way the client draws it; this suite renders the
/// same vectors through the editor's previews. `cross_repo_fixture_pins_test`
/// keeps the copy identical.
const String vectorsPath = 'test/fixtures/text_code_vectors.json';

/// `TextCodeVectorsTest.MINIMUM_VECTOR_COUNT`.
const int minimumVectorCount = 16;

typedef Run = ({
  String text,
  int color,
  bool bold,
  bool italic,
  bool underlined,
  bool strikethrough,
  bool obfuscated,
});

typedef Shape = ({
  String name,
  bool display,
  bool miniMessage,
  List<List<Run>> Function(String text) render,
});

Run _run(McSpan span) => (
  text: span.text,
  color: span.color,
  bold: span.bold,
  italic: span.italic,
  underlined: span.underlined,
  strikethrough: span.strikethrough,
  obfuscated: span.obfuscated,
);

bool _sameStyle(Run a, Run b) =>
    a.color == b.color &&
    a.bold == b.bold &&
    a.italic == b.italic &&
    a.underlined == b.underlined &&
    a.strikethrough == b.strikethrough &&
    a.obfuscated == b.obfuscated;

/// Adjacent runs with one style read as one run, as `ClientTextLines` merges
/// them on the Java side.
List<Run> _line(Iterable<McSpan> spans) {
  final List<Run> out = <Run>[];
  for (final McSpan span in spans) {
    if (span.text.isEmpty) continue;
    final Run next = _run(span);
    if (out.isNotEmpty && _sameStyle(out.last, next)) {
      final Run last = out.removeLast();
      out.add((
        text: last.text + next.text,
        color: last.color,
        bold: last.bold,
        italic: last.italic,
        underlined: last.underlined,
        strikethrough: last.strikethrough,
        obfuscated: last.obfuscated,
      ));
    } else {
      out.add(next);
    }
  }
  return out;
}

List<Run> _rendered(GlossLineRender render) => _line(<McSpan>[
  for (final GlossTextPiece piece in render.pieces)
    if (piece case GlossTextRun(:final McSpan span)) span,
]);

List<List<Run>> _expected(Object? lines) => <List<Run>>[
  for (final Object? line in lines! as List<Object?>)
    <Run>[
      for (final Object? raw in line! as List<Object?>)
        (() {
          final Map<String, Object?> run = raw! as Map<String, Object?>;
          bool flag(String name) => run[name] == true;
          return (
            text: run['text']! as String,
            color: int.parse((run['color']! as String).substring(1), radix: 16),
            bold: flag('bold'),
            italic: flag('italic'),
            underlined: flag('underlined'),
            strikethrough: flag('strikethrough'),
            obfuscated: flag('obfuscated'),
          );
        })(),
    ],
];

GlossHologramDoc _hologram(List<String> lines) => GlossHologramDoc(
  anchor: GlossHologramAnchor(world: 'world', positionRaw: <num>[0, 64, 0]),
  lines: lines,
);

final List<Shape> shapes = <Shape>[
  (
    name: 'hologram, one display',
    display: true,
    miniMessage: true,
    render: (String text) => <List<Run>>[
      for (final GlossLineRender row in hologramRenderedLines(
        _hologram(text.split('\n')),
      ))
        _rendered(row),
    ],
  ),
  (
    name: 'tab header and MOTD, one legacy component',
    display: true,
    miniMessage: false,
    render: (String text) => <List<Run>>[
      for (final GlossLineRender row in glossDisplayLines(
        renderGlossLine(text),
      ))
        _rendered(row),
    ],
  ),
  (
    name: 'chat bubble message',
    display: true,
    miniMessage: false,
    render: (String text) => <List<Run>>[
      for (final GlossLineRender row in glossDisplayLines(
        renderGlossLiteralLine(
          glossBubbleTextBlock('', '{message}', glossTranslateColors(text), 1000),
        ),
      ))
        _rendered(row),
    ],
  ),
  (
    name: 'menu text icon, one entity per line',
    display: false,
    miniMessage: true,
    render: (String text) => <List<Run>>[
      for (final List<McSpan> line in McTextCache().parse(text).lines)
        _line(line),
    ],
  ),
  (
    name: 'scoreboard, one row per line',
    display: false,
    miniMessage: true,
    render: (String text) => <List<Run>>[
      for (final String line in text.split('\n'))
        _rendered(renderGlossScoreboardLine(line)),
    ],
  ),
];

void main() {
  final File file = File(vectorsPath);
  if (!file.existsSync()) {
    throw StateError('missing vector fixture: $vectorsPath');
  }
  final Map<String, Object?> root =
      jsonDecode(file.readAsStringSync()) as Map<String, Object?>;
  final List<Object?> vectors = root['vectors']! as List<Object?>;

  test('the fixture carries the full contract', () {
    expect(vectors.length, greaterThanOrEqualTo(minimumVectorCount));
  });

  for (final Object? raw in vectors) {
    final Map<String, Object?> vector = raw! as Map<String, Object?>;
    final String id = vector['id']! as String;
    final String text = vector['text']! as String;
    final bool miniMessage = vector['syntax'] == 'minimessage';
    group(id, () {
      for (final Shape shape in shapes) {
        if (miniMessage && !shape.miniMessage) continue;
        test(shape.name, () {
          expect(
            shape.render(text),
            _expected(vector[shape.display ? 'display' : 'perLine']),
          );
        });
      }
    });
  }

  group('nametags', () {
    for (final Object? raw in root['nametags']! as List<Object?>) {
      final Map<String, Object?> vector = raw! as Map<String, Object?>;
      final String id = vector['id']! as String;
      final String name = vector['name']! as String;
      final GlossNametagPresentation presentation = GlossNametagPresentation(
        prefix: vector['prefix']! as String,
        suffix: vector['suffix']! as String,
        color: vector['color']! as String,
      );
      test('$id above the head', () {
        expect(
          <List<Run>>[
            _rendered(
              renderGlossLine(
                nametagPreviewText(presentation),
                richText: true,
                expressionSamples: GlossTextExpressionSamples(
                  values: <String, Object>{'subject.name': name},
                ),
              ),
            ),
          ],
          _expected(vector['team']),
        );
      });
      test('$id as a nameplate name', () {
        final String identity = resolveIdentityPreviewName(
          <({String id, GlossNametagDoc doc})>[
            (id: id, doc: GlossNametagDoc(presentation: presentation)),
          ],
          identityPreviewContext(name, <String>{}),
        );
        expect(
          <List<Run>>[_rendered(renderGlossLine(identity))],
          _expected(vector['nameplate']),
        );
      });
    }
  });

  group('bubbles', () {
    for (final Object? raw in root['bubbles']! as List<Object?>) {
      final Map<String, Object?> vector = raw! as Map<String, Object?>;
      test(vector['id']! as String, () {
        final GlossBubbleStyleDoc style = GlossBubbleStyleDoc(
          prefix: vector['prefix']! as String,
          format: vector['format']! as String,
          wordWrapChars: vector['wrap']! as int,
        );
        final String message = vector['message']! as String;
        final GlossBubblePreviewBubble bubble = GlossBubblePreviewBubble(
          message: message,
          text: glossBubbleTextBlock(
            style.effectivePrefix,
            style.format,
            message,
            style.effectiveWordWrapChars,
          ),
          lineCount: 1,
          stackY: 0,
          remainingMs: 1000,
          motion: const GlossBubbleMotionFrame.identity(),
          shimmerBandIndex: null,
        );
        expect(
          <List<Run>>[
            for (final GlossLineRender row in glossDisplayLines(
              renderGlossBubblePreviewText(style, bubble, nowMs: 0),
            ))
              _rendered(row),
          ],
          _expected(vector['display']),
        );
      });
    }
  });

  group('a hologram line in the inspector reads as it does in game', () {
    for (final Object? raw in vectors) {
      final Map<String, Object?> vector = raw! as Map<String, Object?>;
      test(vector['id']! as String, () {
        final GlossHologramDoc doc = _hologram(
          (vector['text']! as String).split('\n'),
        );
        final List<GlossLineRender> rows = hologramRenderedLines(doc);
        for (int index = 0; index < doc.lines.length; index++) {
          expect(
            _rendered(hologramLinePreview(doc, index)),
            _rendered(rows[index]),
            reason: 'line ${index + 1}',
          );
        }
      });
    }
  });
}
