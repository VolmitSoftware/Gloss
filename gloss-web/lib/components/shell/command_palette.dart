/// Search (Mod+K): every command the editor can run now, and every document,
/// on shadcn's command palette. Each keystroke filters here over the
/// command's label, keywords and group ([paletteMatches]); the first row is
/// highlighted on open and after each search, as in cmdk; Enter or a click
/// runs the highlighted row and closes.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:web/web.dart' as web;

import 'shell_actions.dart';
import 'palette_filter.dart';
import 'shell_keys.dart';

import 'package:gloss_editor/l10n/hui_localizations.dart';

class ShellCommandPalette extends StatefulWidget {
  const ShellCommandPalette({
    required this.actions,
    required this.onClose,
    this.apple = false,
    super.key,
  });

  final List<ShellAction> actions;
  final void Function() onClose;

  /// Shortcut hints with Apple glyphs.
  final bool apple;

  @override
  State<ShellCommandPalette> createState() => _ShellCommandPaletteState();
}

class _ShellCommandPaletteState extends State<ShellCommandPalette> {
  static const String _id = 'hui-command-palette';

  String _query = '';

  /// The search the first row was last highlighted for.
  String? _highlighted;

  /// Marks the first row active the way the runtime's arrow keys do, so the
  /// highlight, Enter and the arrows start from it.
  void _highlightFirst() {
    final web.NodeList rows = web.document.querySelectorAll(
      '[data-arcane-command="$_id"] [role="option"]:not([aria-disabled="true"])',
    );
    for (int index = 0; index < rows.length; index++) {
      final web.Element row = rows.item(index)! as web.Element;
      row.setAttribute('data-arcane-state', index == 0 ? 'active' : 'visible');
      row.setAttribute('aria-selected', '${index == 0}');
    }
  }

  @override
  Widget build(BuildContext context) {
    final List<String> terms = paletteTerms(_query);
    final List<CommandGroup> groups = <CommandGroup>[];
    String? group;
    final bool touch = web.window.matchMedia('(pointer: coarse)').matches;
    for (final ShellAction action in component.actions) {
      if (!action.enabled) continue;
      if (!paletteMatches(
        '${action.haystack} ${shellGroupLabel(action.group)}',
        terms,
      )) {
        continue;
      }
      final CommandItem item = CommandItem(
        label: action.label,
        icon: action.icon,
        // A touch screen has no keys to show.
        shortcut: action.shortcut == null || touch
            ? null
            : shortcutLabel(action.shortcut!, apple: component.apple),
        keywords: <String>[...action.keywords, shellGroupLabel(action.group)],
        onSelect: () {
          component.onClose();
          action.run();
        },
      );
      if (action.group != group) {
        group = action.group;
        groups.add(
          CommandGroup(
            heading: shellGroupLabel(action.group),
            items: <CommandItem>[item],
          ),
        );
      } else {
        groups.last.items.add(item);
      }
    }
    if (_highlighted != _query) {
      _highlighted = _query;
      context.binding.addPostFrameCallback(_highlightFirst);
    }
    return ArcaneCommand(
      id: _id,
      isOpen: true,
      animate: false,
      // The search row at the regular control height, like the dialogs.
      compact: true,
      // Stock shadcn: the dialog ends at the list, with no row of key names
      // (which a touch screen has no keys for).
      keyHints: false,
      externalFilter: true,
      onSearch: (String query) => setState(() => _query = query),
      placeholder: huiText('Search commands and documents'),
      emptyMessage: huiText('No matches'),
      groups: groups,
      onClose: component.onClose,
    );
  }
}
