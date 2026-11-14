/// The empty container preview's message wraps to the canvas instead of
/// running past both sides on a phone.
library;

import 'package:gloss_editor/logic/canvas_text_wrap.dart';
import 'package:test/test.dart';

double _width(String text) => text.length * 7.0;

void main() {
  test('should keep a line that fits whole', () {
    expect(wrapCanvasText('Nothing drawn yet', 400, _width), <String>[
      'Nothing drawn yet',
    ]);
  });

  test('should break between words so every line fits', () {
    const String message =
        'Nothing drawn yet. Add an element, or check the simulated state.';
    final List<String> lines = wrapCanvasText(message, 200, _width);
    expect(lines.length, greaterThan(1));
    for (final String line in lines) {
      expect(_width(line), lessThanOrEqualTo(200), reason: line);
    }
    expect(lines.join(' '), message);
  });

  test('should keep a word longer than the room on a line of its own', () {
    expect(wrapCanvasText('a supercalifragilistic b', 70, _width), <String>[
      'a',
      'supercalifragilistic',
      'b',
    ]);
  });
}
