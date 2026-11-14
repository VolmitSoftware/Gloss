import 'dart:io';

import 'package:test/test.dart';

/// Every top-bar action has exactly one home (plus the command palette), the
/// bar is one row, and it folds through the kit's ActionRow rather than a
/// CSS breakpoint ladder. These are properties of the source, so they are
/// checked here; the browser checks measure the result.
void main() {
  final String topBar = File('lib/components/shell/top_bar.dart')
      .readAsStringSync();
  final String rail = File('lib/components/panels/editor_rail.dart')
      .readAsStringSync();
  final String settings = File('lib/components/dialogs/settings_dialog.dart')
      .readAsStringSync();
  final String foundationsCss = File('web/styles/01-foundations.css')
      .readAsStringSync();
  final String shellCss = File('web/styles/02-shell.css').readAsStringSync();

  int count(String source, String needle) => needle.allMatches(source).length;

  test('the top bar is one row with one File menu and one Help menu', () {
    expect(count(topBar, "id: 'hui-file-menu'"), 1);
    expect(count(topBar, "id: 'hui-help-menu'"), 1);
    for (final String gone in <String>[
      'hui-bar-primary',
      'hui-bar-context',
      '_overflowTiers',
      'BarMenu',
      'ViewSwitcher',
      'hui-kind-picker',
    ]) {
      expect(topBar, isNot(contains(gone)), reason: gone);
      expect(shellCss, isNot(contains(gone)), reason: gone);
    }
    expect(File('lib/components/shell/bar_menu.dart').existsSync(), isFalse);
    expect(
      File('lib/components/shell/view_switcher.dart').existsSync(),
      isFalse,
    );
  });

  test('File holds the document and workspace actions, each once', () {
    final String file = _between(
      topBar,
      'List<AppMenuEntry> _fileEntries()',
      'List<AppMenuEntry> _helpEntries()',
    );
    for (final String label in <String>[
      "huiText('New document')",
      "huiText('Templates')",
      "huiText('Import JSON')",
      "huiText('Export {document} JSON'",
      "huiText('Copy {document} JSON'",
      "huiText('Images')",
      "huiText('Import workspace bundle')",
      "huiText('Export workspace bundle')",
      "huiText('Erase all local data')",
    ]) {
      expect(count(file, label), 1, reason: label);
      expect(count(topBar, label), 1, reason: '$label has a second route');
    }
  });

  test('Help holds help, shortcuts, the tour, settings and the theme', () {
    final String help = _between(
      topBar,
      'List<AppMenuEntry> _helpEntries()',
      'Widget _brand()',
    );
    for (final String label in <String>[
      "huiText('Help')",
      "huiText('Keyboard shortcuts')",
      "huiText('Guided tour')",
      "huiText('Settings')",
      "huiText('Dark theme')",
    ]) {
      expect(count(help, label), 1, reason: label);
    }
    expect(topBar, isNot(contains("huiText('Light theme')")));
  });

  test('workspace actions and the theme are not repeated elsewhere', () {
    for (final String label in <String>[
      'Import workspace bundle',
      'Export workspace bundle',
      'Erase all local data',
    ]) {
      expect(rail, isNot(contains(label)), reason: 'rail repeats $label');
      expect(settings, isNot(contains(label)), reason: 'settings: $label');
    }
    expect(settings, isNot(contains('onToggleTheme')));
    expect(settings, isNot(contains('Reset local data')));
  });

  test('the bar folds through ActionRow, never a breakpoint ladder', () {
    expect(topBar, contains('ActionRow('));
    expect(topBar, contains("moreId: 'hui-bar-fold'"));
    expect(topBar, contains("id: 'hui-bar-more'"), reason: 'one `...` for what the bar folds, before Help');
    for (final String width in <String>['1400px', '1150px']) {
      expect(shellCss, isNot(contains(width)), reason: width);
    }
    final String barRules = _between(
      shellCss,
      '/* ---- Top bar',
      '/* ---- Status bar',
    );
    expect(barRules, isNot(contains('overflow-x')));
    expect(barRules, isNot(contains('scrollbar-width')));
    expect(topBar, isNot(contains('scrollLeft')));
    expect(topBar, isNot(contains('querySelector')));
  });

  test('the Server button carries the sync hooks', () {
    final String server = _between(
      topBar,
      "status == null ? huiText('Server')",
      ');',
    );
    expect(topBar, contains("id: 'gloss-server-button'"));
    expect(topBar, contains("'data-sync-action': 'open'"));
    expect(server, isNotEmpty);
  });

  test('the retired colour, shadow and radius tokens stay gone', () {
    // Colours come from the shadcn tokens (--destructive, --border, ...);
    // the --hui-* names that once aliased them must not come back.
    final RegExp retired = RegExp(
      r'--hui-(text|muted|accent|accent-foreground|ring|danger|danger-fill|'
      r'warning|info|success|bg|panel|panel-soft|surface|surface-raised|'
      r'border|border-soft|shadow-[a-z]+|radius[a-z-]*)(?![\w-])',
    );
    final List<String> found = <String>[
      for (final String dir in <String>['lib', 'web'])
        for (final FileSystemEntity entity in Directory(
          dir,
        ).listSync(recursive: true))
          if (entity is File &&
              (entity.path.endsWith('.dart') ||
                  entity.path.endsWith('.css') ||
                  entity.path.endsWith('.js') ||
                  entity.path.endsWith('.html')) &&
              retired.hasMatch(entity.readAsStringSync()))
            entity.path,
    ];
    expect(found, isEmpty);
    expect(foundationsCss, isNot(contains('TEMPORARY ALIASES')));
  });
}

String _between(String source, String start, String end) {
  final int startIndex = source.indexOf(start);
  final int endIndex = source.indexOf(end, startIndex + start.length);
  expect(startIndex, isNonNegative, reason: 'missing $start');
  expect(endIndex, greaterThan(startIndex), reason: 'missing $end');
  return source.substring(startIndex, endIndex);
}
