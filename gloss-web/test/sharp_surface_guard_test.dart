import 'dart:io';

import 'package:test/test.dart';

List<File> _sourceFiles(String root, String extension) =>
    Directory(root)
        .listSync(recursive: true)
        .whereType<File>()
        .where((File file) => file.path.endsWith(extension))
        .toList(growable: false);

String _block(String source, String marker) {
  final int markerIndex = source.indexOf(marker);
  expect(markerIndex, isNonNegative, reason: 'missing $marker');
  final int openIndex = source.indexOf('{', markerIndex);
  expect(
    openIndex,
    greaterThan(markerIndex),
    reason: 'missing block for $marker',
  );
  int depth = 0;
  for (int index = openIndex; index < source.length; index++) {
    if (source[index] == '{') depth++;
    if (source[index] == '}') {
      depth--;
      if (depth == 0) return source.substring(openIndex + 1, index);
    }
  }
  fail('unterminated block for $marker');
}

void main() {
  test('controls keep the kit sizes on coarse pointers', () {
    // Two heights only (32 px dense, 36 px regular) at every width: no
    // stylesheet adds a coarse-pointer size scale for buttons.
    for (final File file in _sourceFiles('web/styles', '.css')) {
      final String css = file.readAsStringSync();
      for (final RegExpMatch query in RegExp(
        r'@media[^{]*pointer:\s*coarse[^{]*\{',
      ).allMatches(css)) {
        final String block = _block(css.substring(query.start), '@media');
        expect(
          block,
          isNot(matches(RegExp(r'(min-)?height:\s*4[04]px'))),
          reason: '${file.path}: ${query[0]}',
        );
      }
    }
  });

  test('shell chrome leaves control sizes to the kit', () {
    // The frame, the library and the dialogs size nothing: the kit's density
    // sets every control (32 px dense, 36 px in dialogs) and the theme draws
    // it, so the shell never selects a library control or forces a height.
    final String shell = File('web/styles/02-shell.css').readAsStringSync();
    expect(shell, isNot(contains('.arcane-')));
    expect(shell, isNot(contains('[data-arcane-')));
    expect(shell, isNot(matches(RegExp(r'height:\s*\d+px\s*!important'))));
    final String library = File(
      'web/styles/09-workspace.css',
    ).readAsStringSync().split('/* ---- Flow-map board').first;
    expect(library, contains('.hui-library-row'));
    expect(library, isNot(contains('.arcane-')));
    expect(library, isNot(contains('!important')));
  });

  test('artboard frames fold their header instead of a CSS ladder', () {
    for (final String path in <String>[
      'web/styles/03-canvas.css',
      'web/styles/08-preview-card.css',
    ]) {
      final String css = File(path).readAsStringSync();
      expect(css, isNot(contains('@container')), reason: path);
      expect(
        css,
        isNot(matches(RegExp(r'overflow-x\s*:\s*(auto|scroll)'))),
        reason: path,
      );
      expect(css, isNot(contains('.arcane-')), reason: path);
      expect(css, isNot(contains('!important')), reason: path);
    }
    for (final String path in <String>[
      'lib/components/canvas/canvas_toolbar.dart',
      'lib/components/preview_card/preview_card_toolbar.dart',
    ]) {
      final String toolbar = File(path).readAsStringSync();
      expect(toolbar, contains('RowAction('), reason: path);
      expect(toolbar, contains("huiText('Zoom in')"), reason: path);
      expect(toolbar, isNot(contains('Button(')), reason: path);
    }
  });

  test('the shell has one compact rule and one phone rule', () {
    final String css = File('web/styles/02-shell.css').readAsStringSync();
    final Set<String> queries = <String>{
      for (final RegExpMatch match in RegExp(
        r'@media\s*([^{]+)\{',
      ).allMatches(css))
        match.group(1)!.trim(),
    };
    expect(queries, <String>{'(max-width: 760px)'});
    final String shell = File('lib/components/shell/editor_shell.dart')
        .readAsStringSync();
    expect(shell, contains("compactQuery = '(max-width: 1024px)'"));
    expect(shell, contains("phoneQuery = '(max-width: 760px)'"));
    expect(css, isNot(contains('--hui-context-height')));
  });

  test(
    'true circular controls stay circular',
    () {
      final String preview = File('web/styles/07-preview.css')
          .readAsStringSync();
      expect(
        _block(preview, '.hui-preview-avatar-disc,'),
        contains('border-radius: 50%;'),
      );
    },
  );
}
