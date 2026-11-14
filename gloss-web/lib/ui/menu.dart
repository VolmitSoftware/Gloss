/// Short lists of actions on shadcn's dropdown and context menus. The arcane
/// runtime opens them, moves focus with the arrow keys and types ahead,
/// closes them on Escape, Tab, a pick or an outside click, and returns focus
/// to the button. Entries are actions, checkboxes, radio choices, headings
/// and separators. Destructive entries are red and confirm with
/// `askToConfirm` when Undo cannot bring the thing back.
library;

import 'dart:js_interop';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:web/web.dart' as web;

import '../l10n/hui_localizations.dart';
import 'density.dart';
import 'icon_button.dart';

enum AppMenuEntryKind { action, checkbox, radio, heading, separator }

/// One menu row. A null callback shows it disabled.
class AppMenuEntry {
  const AppMenuEntry(
    this.label,
    this.onSelect, {
    this.hint,
    this.description,
    this.destructive = false,
    this.icon,
  }) : kind = AppMenuEntryKind.action,
       checked = false,
       onChecked = null,
       group = null,
       value = null,
       onPicked = null;

  /// A row with a check mark that turns something on or off ("Show grid").
  const AppMenuEntry.checkbox(
    this.label, {
    required this.checked,
    required void Function(bool checked)? onChanged,
    this.hint,
    this.icon,
  }) : kind = AppMenuEntryKind.checkbox,
       onSelect = null,
       description = null,
       destructive = false,
       onChecked = onChanged,
       group = null,
       value = null,
       onPicked = null;

  /// One of a set of choices, the picked one marked ("Language: English").
  const AppMenuEntry.radio(
    this.label, {
    required String this.group,
    required String this.value,
    required this.checked,
    required this.onPicked,
    this.icon,
  }) : kind = AppMenuEntryKind.radio,
       onSelect = null,
       hint = null,
       description = null,
       destructive = false,
       onChecked = null;

  /// A muted heading over the entries after it.
  const AppMenuEntry.heading(this.label)
    : kind = AppMenuEntryKind.heading,
      onSelect = null,
      hint = null,
      description = null,
      destructive = false,
      icon = null,
      checked = false,
      onChecked = null,
      group = null,
      value = null,
      onPicked = null;

  /// A line between groups of entries.
  const AppMenuEntry.separator()
    : kind = AppMenuEntryKind.separator,
      label = '',
      onSelect = null,
      hint = null,
      description = null,
      destructive = false,
      icon = null,
      checked = false,
      onChecked = null,
      group = null,
      value = null,
      onPicked = null;

  final AppMenuEntryKind kind;
  final String label;
  final void Function()? onSelect;

  /// A short note at the end of the row, such as a shortcut.
  final String? hint;

  /// A muted line under the label: what this entry makes or does.
  final String? description;
  final bool destructive;
  final ArcaneGlyph? icon;

  /// A checkbox's state, or whether a radio entry is the picked one.
  final bool checked;
  final void Function(bool checked)? onChecked;

  /// A radio entry's set and its value in that set.
  final String? group;
  final String? value;
  final void Function(String value)? onPicked;

  bool get separator => kind == AppMenuEntryKind.separator;

  bool get enabled => switch (kind) {
    AppMenuEntryKind.action => onSelect != null,
    AppMenuEntryKind.checkbox => onChecked != null,
    AppMenuEntryKind.radio => onPicked != null,
    AppMenuEntryKind.heading || AppMenuEntryKind.separator => false,
  };

  /// Whether a menu showing [other] in this row's place looks the same.
  bool looksLike(AppMenuEntry other) =>
      kind == other.kind &&
      label == other.label &&
      hint == other.hint &&
      description == other.description &&
      destructive == other.destructive &&
      checked == other.checked &&
      group == other.group &&
      value == other.value &&
      enabled == other.enabled;
}

/// The library rows for [entries]; picks go through [run] with the row's
/// index so callers can return focus first and read the newest entries.
List<ArcaneMenuItem> _menuItems(
  List<AppMenuEntry> entries,
  void Function(int index, Object? value) run,
) => <ArcaneMenuItem>[
  for (final (int index, AppMenuEntry entry) in entries.indexed)
    switch (entry.kind) {
      AppMenuEntryKind.separator => const MenuItemSeparator(),
      AppMenuEntryKind.heading => MenuItemLabel(label: entry.label),
      AppMenuEntryKind.checkbox => MenuItemCheckbox(
        label: entry.label,
        icon: entry.icon?.sized(controlIconSize),
        checked: entry.checked,
        shortcut: entry.hint,
        disabled: !entry.enabled,
        onChanged: entry.enabled ? (bool checked) => run(index, checked) : null,
      ),
      AppMenuEntryKind.radio => MenuItemRadio(
        label: entry.label,
        icon: entry.icon?.sized(controlIconSize),
        group: entry.group!,
        value: entry.value!,
        selected: entry.checked,
        disabled: !entry.enabled,
        onChanged: entry.enabled ? (String value) => run(index, value) : null,
      ),
      AppMenuEntryKind.action => MenuItemAction(
        label: entry.label,
        description: entry.description,
        icon: entry.icon?.sized(controlIconSize),
        shortcut: entry.hint,
        destructive: entry.destructive,
        disabled: !entry.enabled,
        onSelect: entry.enabled ? () => run(index, null) : null,
      ),
    },
];

/// Runs [entry] with [value] (a checkbox's new state, a radio's value).
void _runEntry(AppMenuEntry entry, Object? value) {
  switch (entry.kind) {
    case AppMenuEntryKind.action:
      entry.onSelect?.call();
    case AppMenuEntryKind.checkbox:
      entry.onChecked?.call(value is bool ? value : !entry.checked);
    case AppMenuEntryKind.radio:
      entry.onPicked?.call(value is String ? value : entry.value!);
    case AppMenuEntryKind.heading || AppMenuEntryKind.separator:
      break;
  }
}

bool _sameEntries(List<AppMenuEntry> a, List<AppMenuEntry> b) {
  if (a.length != b.length) return false;
  for (int index = 0; index < a.length; index++) {
    if (!a[index].looksLike(b[index])) return false;
  }
  return true;
}

int _nextMenuId = 0;

class AppMenu extends StatefulWidget {
  const AppMenu({
    required this.entries,
    this.label,
    this.icon,
    this.iconLabel,
    this.id,
    this.alignEnd = false,
    this.outline = false,
    this.primary = false,
    this.disabled = false,
    this.attributes = const <String, String>{},
    super.key,
  }) : assert(
         label != null || icon != null,
         'A menu button needs a label or an icon.',
       );

  /// The button's text. Without it the button is the [icon] alone and
  /// [iconLabel] names it. The trigger button's id is `<id>-button`.
  final String? label;
  final ArcaneGlyph? icon;

  /// The accessible name and tooltip of an icon-only button: what it acts
  /// on ("Actions for Lobby") where a page has many. Defaults to "More
  /// actions".
  final String? iconLabel;
  final List<AppMenuEntry> entries;
  final String? id;

  /// Opens to the left of the button's right edge (menus at the end of a row).
  final bool alignEnd;

  /// A bordered button instead of a ghost one.
  final bool outline;

  /// The row's primary action as a menu ("New document").
  final bool primary;
  final bool disabled;

  /// Extra attributes on the trigger button.
  final Map<String, String> attributes;

  @override
  State<AppMenu> createState() => _AppMenuState();
}

class _AppMenuState extends State<AppMenu> {
  /// Stable across rebuilds: the runtime keeps the open state by this id.
  late final String _id = component.id ?? 'ui-menu-${_nextMenuId++}';

  @override
  void initState() {
    super.initState();
    _catchEscape();
  }

  /// Whether the runtime has this menu open now.
  bool get _open =>
      web.document.getElementById(_id)?.getAttribute('data-arcane-state') ==
      'open';

  /// A rebuild redraws the menu closed (the open state is the runtime's), so
  /// while it is open a rebuild that changes nothing it shows is skipped.
  /// The picks read the newest entries when they run.
  @override
  bool shouldRebuild(AppMenu newComponent) {
    if (!_open) return true;
    final AppMenu old = component;
    return newComponent.label != old.label ||
        newComponent.iconLabel != old.iconLabel ||
        newComponent.disabled != old.disabled ||
        newComponent.outline != old.outline ||
        newComponent.primary != old.primary ||
        newComponent.alignEnd != old.alignEnd ||
        !_sameEntries(old.entries, newComponent.entries);
  }

  /// Runs entry [index] with focus back on the trigger, so a confirmation it
  /// opens returns focus there instead of to the closed menu.
  void _select(int index, Object? value) {
    final List<AppMenuEntry> entries = component.entries;
    if (index >= entries.length) return;
    final AppMenuEntry entry = entries[index];
    (web.document.getElementById('$_id-button') as web.HTMLElement?)?.focus();
    _runEntry(entry, value);
  }

  @override
  Widget build(BuildContext context) {
    final AppMenu menu = component;
    final String trigger = '$_id-button';
    final Map<String, String> attributes = <String, String>{
      ...menu.attributes,
      'aria-haspopup': 'menu',
    };
    return dom.span(
      classes: 'ui-menu',
      attributes: <String, String>{'data-ui-menu': _id},
      <Widget>[
        ArcaneDropdownMenu(
          id: _id,
          alignment: menu.alignEnd
              ? DropdownAlignment.right
              : DropdownAlignment.left,
          trigger: menu.label == null
              ? AppIconButton(
                  id: trigger,
                  icon: menu.icon!,
                  label: menu.iconLabel ?? huiText('More actions'),
                  disabled: menu.disabled,
                  outline: menu.outline,
                  attributes: attributes,
                  tipAtEnd: menu.alignEnd,
                  onPressed: () {},
                )
              : Button(
                  id: trigger,
                  label: menu.label,
                  icon: menu.icon?.sized(controlIconSize),
                  size: buttonSize(DensityScope.of(context)),
                  variant: menu.primary
                      ? ButtonVariant.primary
                      : menu.outline
                      ? ButtonVariant.outline
                      : ButtonVariant.ghost,
                  disabled: menu.disabled,
                  attributes: attributes,
                  onPressed: () {},
                ),
          items: _menuItems(menu.entries, _select),
        ),
      ],
    );
  }
}

int _nextContextMenuId = 0;

/// Right-click (and the context-menu key) on [child] opens [entries]. Offer
/// the same actions somewhere a pointer-less user reaches them too (a row's
/// `...` [AppMenu]); a context menu is a shortcut, never the only way.
class AppContextMenu extends StatefulWidget {
  const AppContextMenu({
    required this.child,
    required this.entries,
    this.id,
    super.key,
  });

  final Widget child;
  final List<AppMenuEntry> entries;
  final String? id;

  @override
  State<AppContextMenu> createState() => _AppContextMenuState();
}

class _AppContextMenuState extends State<AppContextMenu> {
  /// Stable across rebuilds; the library's own id changes on every build.
  late final String _id = component.id ?? 'ui-context-${_nextContextMenuId++}';

  void _select(int index, Object? value) {
    final List<AppMenuEntry> entries = component.entries;
    if (index < entries.length) _runEntry(entries[index], value);
  }

  @override
  Widget build(BuildContext context) => ArcaneContextMenu(
    id: _id,
    trigger: component.child,
    items: _menuItems(component.entries, _select),
  );
}

bool _escapeCaught = false;

/// Escape on an open menu's button closes the menu. The button's tooltip
/// would otherwise take the key (it swallows Escape to close itself), which
/// left a menu opened by a click open until focus moved into it.
void _catchEscape() {
  if (_escapeCaught) return;
  _escapeCaught = true;
  web.window.addEventListener(
    'keydown',
    ((web.KeyboardEvent event) {
      if (event.key != 'Escape') return;
      final JSAny? target = event.target;
      if (target == null || !target.isA<web.Element>()) return;
      final web.Element element = target as web.Element;
      final web.Element? holder = element.closest('[data-ui-menu]');
      final String? id = holder?.getAttribute('data-ui-menu');
      if (id == null) return;
      final web.Element? surface = web.document.getElementById(id);
      if (surface == null ||
          surface.getAttribute('data-arcane-state') != 'open' ||
          surface.contains(element)) {
        return;
      }
      event.preventDefault();
      event.stopPropagation();
      (web.document.getElementById('$id-button') as web.HTMLElement?)?.click();
    }).toJS,
    web.AddEventListenerOptions(capture: true),
  );
}
