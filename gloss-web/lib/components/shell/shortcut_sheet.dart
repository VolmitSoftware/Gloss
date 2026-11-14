/// The one shortcut table, and the sheet that renders it.
///
/// Every global chord is *dispatched* from [huiShortcutGroups] by
/// `keyboard_shortcuts.dart`, *labelled* from the same rows by this sheet, and
/// *chipped* in the command palette by `shell_actions.dart` reading
/// [huiShortcutSpec]. One row is therefore the binding, its description and its
/// palette chip at once — the three cannot drift apart because there is only
/// one of each.
///
/// Rows with no [HuiShortcutRow.matches] are gestures another surface binds on
/// its own element (`canvas_interactions.dart`, `preview_stage.dart`, the
/// palette's own input). They are documented here and dispatched nowhere; each
/// carries a note naming the surface that owns it.
///
/// The sheet is a kit dialog listing the same rows.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import '../../config/editing.dart';
import '../../state/editor_store.dart';
import '../../ui/action_row.dart';
import '../../ui/dialog.dart';
import '../../ui/field.dart';
import '../../ui/field_group.dart';
import 'shell_intents.dart';
import 'shell_keys.dart';

import 'package:gloss_editor/l10n/hui_localizations.dart';

/// What a bound row is handed when it fires.
class HuiShortcutScope {
  const HuiShortcutScope({
    required this.intents,
    required this.key,
    this.togglePalette,
    this.toggleShortcuts,
  });

  final ShellIntents intents;

  /// The event that matched. Arrow rows need to know which arrow, and nothing
  /// else about the key survives the match.
  final ShellKey key;

  /// Shell-owned surfaces the intents layer has no handle on.
  final void Function()? togglePalette;
  final void Function()? toggleShortcuts;
}

class HuiShortcutRow {
  const HuiShortcutRow({
    required this.id,
    required this.spec,
    required this.label,
    this.matches,
    this.run,
    this.alwaysOn = false,
    this.note,
  });

  /// Stable key for [huiShortcutSpec]. Where a row mirrors a palette command
  /// the id is that command's id, so the chip lookup is `huiShortcutSpec(id)`.
  final String id;

  /// Platform-neutral spec, rendered through [shortcutKeys].
  final String spec;
  final String label;

  /// Null when another surface owns the binding.
  final bool Function(ShellKey key)? matches;

  /// Returns whether the event was consumed. A guard that declines — Delete
  /// with an empty selection — has to leave the browser default alone.
  final bool Function(HuiShortcutScope scope)? run;

  /// Fires even inside a text field and behind an open palette. Only for
  /// chords no editor could plausibly want to type.
  final bool alwaysOn;
  final String? note;
}

class HuiShortcutGroup {
  const HuiShortcutGroup({required this.title, required this.rows, this.note});

  final String title;
  final List<HuiShortcutRow> rows;

  /// Where the group's rows are dispatched from, when it is not the document.
  final String? note;
}

/// `?` is Shift+/ on a US layout, but layouts that put `?` elsewhere still
/// report `key == '?'` — both spellings are accepted so the binding survives a
/// non-US keyboard.
bool huiIsShortcutHelpKey(ShellKey key) =>
    !key.mod && !key.alt && (key.key == '?' || (key.shift && key.key == '/'));

bool _isArrow(String key) =>
    key == 'ArrowLeft' ||
    key == 'ArrowRight' ||
    key == 'ArrowUp' ||
    key == 'ArrowDown';

bool _nudge(HuiShortcutScope scope, double step) {
  if (scope.intents.store.selectionIds.isEmpty) return false;
  switch (scope.key.key) {
    case 'ArrowLeft':
      scope.intents.nudgeSelected(-step, 0);
    case 'ArrowRight':
      scope.intents.nudgeSelected(step, 0);
    case 'ArrowUp':
      scope.intents.nudgeSelected(0, step);
    case 'ArrowDown':
      scope.intents.nudgeSelected(0, -step);
    default:
      return false;
  }
  return true;
}

bool _setView(HuiShortcutScope scope, EditorView view) {
  scope.intents.setView(view);
  return true;
}

/// A plain letter or symbol: no modifier of any kind. Shift is excluded because
/// the binder treats a shifted letter as a different key entirely.
bool _plain(ShellKey key, String lower) =>
    !key.mod && !key.alt && !key.shift && key.lower == lower;

List<HuiShortcutGroup> get huiShortcutGroups => <HuiShortcutGroup>[
  HuiShortcutGroup(
    title: huiText('Global'),
    note: huiText('All but the first two pause while you type in a field.'),
    rows: <HuiShortcutRow>[
      HuiShortcutRow(
        id: 'shell.palette',
        spec: 'mod+K',
        label: huiText('Search commands and documents'),
        alwaysOn: true,
        matches: (ShellKey key) => key.mod && !key.alt && key.lower == 'k',
        run: (HuiShortcutScope scope) {
          scope.togglePalette?.call();
          return true;
        },
      ),
      HuiShortcutRow(
        id: 'file.export',
        spec: 'mod+S',
        label: huiText('Export the active document JSON'),
        alwaysOn: true,
        matches: (ShellKey key) =>
            key.mod && !key.alt && !key.shift && key.lower == 's',
        run: (HuiShortcutScope scope) {
          scope.intents.exportMenu();
          return true;
        },
      ),
      HuiShortcutRow(
        id: 'help.shortcuts',
        spec: '?',
        label: huiText('Keyboard shortcuts'),
        matches: huiIsShortcutHelpKey,
        run: (HuiShortcutScope scope) {
          scope.toggleShortcuts?.call();
          return true;
        },
      ),
      HuiShortcutRow(
        id: 'edit.undo',
        spec: 'mod+Z',
        label: huiText('Undo'),
        matches: (ShellKey key) =>
            key.mod && !key.alt && !key.shift && key.lower == 'z',
        run: (HuiShortcutScope scope) {
          scope.intents.undo();
          return true;
        },
      ),
      HuiShortcutRow(
        id: 'edit.redo',
        spec: 'mod+Shift+Z',
        label: huiText('Redo'),
        note: huiText('Ctrl+Y works too.'),
        matches: (ShellKey key) =>
            key.mod &&
            !key.alt &&
            ((key.shift && key.lower == 'z') || key.lower == 'y'),
        run: (HuiShortcutScope scope) {
          scope.intents.redo();
          return true;
        },
      ),
      HuiShortcutRow(
        id: 'edit.duplicate',
        spec: 'mod+D',
        label: huiText('Duplicate the selection'),
        matches: (ShellKey key) => key.mod && !key.alt && key.lower == 'd',
        run: (HuiShortcutScope scope) {
          scope.intents.duplicateSelected();
          return true;
        },
      ),
      HuiShortcutRow(
        id: 'selection.all',
        spec: 'mod+A',
        label: huiText('Select every component'),
        matches: (ShellKey key) =>
            key.mod && !key.alt && !key.shift && key.lower == 'a',
        run: (HuiShortcutScope scope) {
          scope.intents.selectAll();
          return true;
        },
      ),
      HuiShortcutRow(
        id: 'edit.delete',
        spec: 'Delete',
        label: huiText('Delete the selection'),
        note: huiText('Backspace too. Undo brings it back.'),
        matches: (ShellKey key) =>
            !key.mod &&
            !key.alt &&
            (key.key == 'Delete' || key.key == 'Backspace'),
        run: (HuiShortcutScope scope) {
          if (scope.intents.store.selectionIds.isEmpty) return false;
          scope.intents.deleteSelected();
          return true;
        },
      ),
      HuiShortcutRow(
        id: 'edit.deselect',
        spec: 'Escape',
        label: huiText('Deselect everything'),
        matches: (ShellKey key) => !key.mod && !key.alt && key.key == 'Escape',
        run: (HuiShortcutScope scope) {
          if (scope.intents.store.selectionIds.isEmpty) return false;
          scope.intents.deselect();
          return true;
        },
      ),
      HuiShortcutRow(
        id: 'edit.nudge',
        spec: 'arrows',
        label: huiText(
          "Nudge the selection by {huiNudgeStep} blocks",
          <String, Object?>{'huiNudgeStep': huiNudgeStep},
        ),
        matches: (ShellKey key) =>
            !key.mod && !key.alt && !key.shift && _isArrow(key.key),
        run: (HuiShortcutScope scope) => _nudge(scope, huiNudgeStep),
      ),
      HuiShortcutRow(
        id: 'edit.nudge.large',
        spec: 'Shift+arrows',
        label: huiText("Nudge by {huiNudgeStepLarge} blocks", <String, Object?>{
          'huiNudgeStepLarge': huiNudgeStepLarge,
        }),
        matches: (ShellKey key) =>
            !key.mod && !key.alt && key.shift && _isArrow(key.key),
        run: (HuiShortcutScope scope) => _nudge(scope, huiNudgeStepLarge),
      ),
      HuiShortcutRow(
        id: 'view.visual',
        spec: 'V',
        label: huiText('Edit'),
        matches: (ShellKey key) => _plain(key, 'v'),
        run: (HuiShortcutScope scope) => _setView(scope, EditorView.visual),
      ),
      HuiShortcutRow(
        id: 'view.preview',
        spec: 'P',
        label: huiText('Preview'),
        note: huiText('Menus and container previews.'),
        matches: (ShellKey key) => _plain(key, 'p'),
        run: (HuiShortcutScope scope) => _setView(scope, EditorView.preview),
      ),
      HuiShortcutRow(
        id: 'view.code',
        spec: 'C',
        label: huiText('Code'),
        matches: (ShellKey key) => _plain(key, 'c'),
        run: (HuiShortcutScope scope) {
          scope.intents.toggleCode();
          return true;
        },
      ),
      HuiShortcutRow(
        id: 'view.split',
        spec: 'S',
        label: huiText('Split view'),
        note: huiText('Advanced mode, wide screens.'),
        matches: (ShellKey key) => _plain(key, 's'),
        run: (HuiShortcutScope scope) {
          scope.intents.toggleSplit();
          return true;
        },
      ),
    ],
  ),
  HuiShortcutGroup(
    title: huiText('Artboard'),
    note: huiText('Click the artboard once first.'),
    rows: <HuiShortcutRow>[
      HuiShortcutRow(
        id: 'canvas.marquee',
        spec: 'Drag',
        label: huiText('Marquee-select from empty space'),
      ),
      HuiShortcutRow(
        id: 'canvas.add',
        spec: 'Shift+Click',
        label: huiText('Add or remove one component'),
      ),
      HuiShortcutRow(
        id: 'canvas.duplicate',
        spec: 'Alt+Drag',
        label: huiText('Duplicate the selection and drag the copies'),
      ),
      HuiShortcutRow(
        id: 'canvas.pan',
        spec: 'Space+Drag',
        label: huiText('Pan'),
        note: huiText('The middle mouse button pans without Space.'),
      ),
      HuiShortcutRow(
        id: 'canvas.zoom',
        spec: 'Scroll',
        label: huiText('Zoom around the pointer'),
      ),
      HuiShortcutRow(
        id: 'canvas.reset',
        spec: '0',
        label: huiText('Reset view: fit the menu to the viewport'),
        note: huiText('F does the same.'),
      ),
      HuiShortcutRow(
        id: 'canvas.delete',
        spec: 'Delete',
        label: huiText('Delete the selection immediately'),
      ),
    ],
  ),
  HuiShortcutGroup(
    title: huiText('Preview stage'),
    note: huiText('Click the stage once first.'),
    rows: <HuiShortcutRow>[
      HuiShortcutRow(
        id: 'preview.orbit',
        spec: 'Drag',
        label: huiText('Orbit the camera'),
      ),
      HuiShortcutRow(
        id: 'preview.pan',
        spec: 'Space+Drag',
        label: huiText('Pan the camera target'),
        note: huiText('Middle-drag does the same.'),
      ),
      HuiShortcutRow(
        id: 'preview.dolly',
        spec: 'Scroll',
        label: huiText('Dolly in and out'),
      ),
      HuiShortcutRow(
        id: 'preview.reset',
        spec: '0',
        label: huiText('Reset the camera to the open position'),
      ),
    ],
  ),
  HuiShortcutGroup(
    title: huiText('Search'),
    note: huiText('Search lists only what can run on this document now.'),
    rows: <HuiShortcutRow>[
      HuiShortcutRow(
        id: 'palette.move',
        spec: '↑↓',
        label: huiText('Browse the results'),
      ),
      HuiShortcutRow(
        id: 'palette.run',
        spec: 'Enter',
        label: huiText('Run the highlighted command'),
      ),
      HuiShortcutRow(id: 'palette.close', spec: 'Esc', label: huiText('Close')),
    ],
  ),
];

/// Every row the document-level binder dispatches, flattened once.
final List<HuiShortcutRow> huiBoundShortcuts = <HuiShortcutRow>[
  for (final HuiShortcutGroup group in huiShortcutGroups)
    for (final HuiShortcutRow row in group.rows)
      if (row.matches != null) row,
];

final Map<String, HuiShortcutRow> _byId = <String, HuiShortcutRow>{
  for (final HuiShortcutGroup group in huiShortcutGroups)
    for (final HuiShortcutRow row in group.rows) row.id: row,
};

/// The platform-neutral spec for a row id, or null when the id is unknown —
/// a mistyped id costs a missing chip, never a wrong one.
String? huiShortcutSpec(String id) => _byId[id]?.spec;

class ShortcutSheet extends StatelessWidget {
  const ShortcutSheet({
    required this.open,
    required this.onClose,
    this.apple = false,
    super.key,
  });

  final bool open;
  final void Function() onClose;

  /// Renders the keys with Apple glyphs.
  final bool apple;

  @override
  Widget build(BuildContext context) => AppDialog(
    id: 'hui-shortcuts',
    open: open,
    wide: true,
    title: huiText('Keyboard shortcuts'),
    onClose: onClose,
    // Closes like Help does.
    footer: DialogFooter(cancel: RowAction(huiText('Close'), onClose)),
    children: <Widget>[
      for (final HuiShortcutGroup group in huiShortcutGroups) _group(group),
    ],
  );

  /// A group heading over one row per shortcut: what it does, then its
  /// keys.
  Widget _group(HuiShortcutGroup group) => FieldGroup(
    title: group.title,
    tip: group.note,
    children: <Widget>[
      for (final HuiShortcutRow row in group.rows)
        Field(
          label: row.label,
          hint: row.note,
          // Centred on the label's line, as a control of that height would be.
          child: dom.div(
            styles: const dom.Styles(
              raw: <String, String>{
                'display': 'flex',
                'align-items': 'center',
                'min-height': 'var(--ui-control-h)',
              },
            ),
            <Widget>[
              ArcaneKbd.combo(
                shortcutKeys(row.spec, apple: apple),
                size: ComponentSize.md,
              ),
            ],
          ),
        ),
    ],
  );
}
