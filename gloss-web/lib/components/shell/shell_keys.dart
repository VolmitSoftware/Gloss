/// DOM-free keyboard model shared by the global binder, the top-bar tooltips
/// and the command palette.
///
/// Shortcut specs are written once in the platform-neutral `mod+Z` form and
/// rendered per platform, so a label and its binding can never drift apart.
library;

class ShellKey {
  const ShellKey({
    required this.key,
    this.ctrl = false,
    this.meta = false,
    this.shift = false,
    this.alt = false,
    this.editable = false,
    this.overlayOpen = false,
    this.menuOpen = false,
  });

  /// The `KeyboardEvent.key` value, verbatim.
  final String key;
  final bool ctrl;
  final bool meta;
  final bool shift;
  final bool alt;

  /// True when the event came from a text input, textarea, select or a
  /// contenteditable node. Plain-letter shortcuts stand down for these.
  final bool editable;

  /// True when an Arcane overlay (dialog, sheet, drawer) is open. The shell
  /// stands down entirely so Escape and focus stay with the overlay.
  final bool overlayOpen;

  /// True when the open surface is a menu (dropdown or context menu): it
  /// answers its own keys, Escape included.
  final bool menuOpen;

  /// Command on Apple, Control everywhere else — both are accepted so an
  /// external keyboard on either platform behaves.
  bool get mod => meta || ctrl;

  String get lower => key.toLowerCase();
}

typedef ShellKeyHandler = bool Function(ShellKey key);

/// What the shell does with a key while an Arcane surface is open.
enum HuiOverlayKey { ignore, closeOverlay }

/// Escape over a dialog or sheet closes it through its owner's state (the
/// owner keeps `open`, so a runtime-only close would come back on the next
/// build). A menu answers its own keys: claiming its Escape would leave it
/// open. Everything else is the surface's.
HuiOverlayKey huiOverlayKey(ShellKey key) =>
    key.key == 'Escape' && !key.mod && !key.alt && !key.menuOpen
    ? HuiOverlayKey.closeOverlay
    : HuiOverlayKey.ignore;

bool huiClosesMobilePane(ShellKey key, {required bool mobilePaneOpen}) =>
    mobilePaneOpen && key.key == 'Escape' && !key.mod && !key.alt;

const String _apple = 'apple';

/// Renders `mod+Shift+Z` as `['⌘', '⇧', 'Z']` or `['Ctrl', 'Shift', 'Z']`.
List<String> shortcutKeys(String spec, {required bool apple}) {
  final List<String> parts = <String>[];
  for (final String raw in spec.split('+')) {
    final String token = raw.trim();
    if (token.isEmpty) continue;
    parts.add(_renderToken(token, apple));
  }
  return parts;
}

String shortcutLabel(String spec, {required bool apple}) =>
    shortcutKeys(spec, apple: apple).join(apple ? '' : '+');

String _renderToken(String token, bool apple) {
  switch (token.toLowerCase()) {
    case 'mod':
      return apple ? '⌘' : 'Ctrl';
    case 'shift':
      return apple ? '⇧' : 'Shift';
    case 'alt':
      return apple ? '⌥' : 'Alt';
    case 'ctrl':
      return apple ? '⌃' : 'Ctrl';
    case 'enter':
      return apple ? '↩' : 'Enter';
    case 'esc':
    case 'escape':
      return 'Esc';
    case 'delete':
      return apple ? '⌫' : 'Del';
    case 'arrows':
      return '←↑↓→';
    case _apple:
      return '⌘';
    default:
      return token.length == 1 ? token.toUpperCase() : token;
  }
}
