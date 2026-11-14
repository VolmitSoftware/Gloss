/// Word wrap for text a canvas draws itself, measured by the canvas font.
/// Pure Dart.
library;

/// [text] in lines no wider than [room] by [measure], broken between words.
/// A word wider than [room] stands on a line of its own.
List<String> wrapCanvasText(
  String text,
  double room,
  double Function(String text) measure,
) {
  final List<String> lines = <String>[];
  String line = '';
  for (final String word in text.split(' ')) {
    if (word.isEmpty) continue;
    final String joined = line.isEmpty ? word : '$line $word';
    if (line.isEmpty || measure(joined) <= room) {
      line = joined;
      continue;
    }
    lines.add(line);
    line = word;
  }
  if (line.isNotEmpty) lines.add(line);
  return lines;
}
