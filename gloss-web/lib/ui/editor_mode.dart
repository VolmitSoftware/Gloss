/// Simple and Advanced. Simple hides the technical rows (extra keys, raw
/// ids, revisions, source citations, Split view); Advanced shows everything.
/// Pure Dart, so the parsing is unit tested.
library;

import '../l10n/hui_localizations.dart';

enum EditorMode {
  simple,
  advanced;

  /// The word the Mode switch shows.
  String get label => switch (this) {
    EditorMode.simple => huiText('Simple'),
    EditorMode.advanced => huiText('Advanced'),
  };
}

/// The localStorage key the mode is kept under, per browser.
const String modeStorageKey = 'gloss.mode';

/// A stored value as a mode, or null when nothing valid is stored.
EditorMode? parseMode(String? stored) {
  for (final EditorMode mode in EditorMode.values) {
    if (mode.name == stored) return mode;
  }
  return null;
}

/// The mode a browser starts in: the stored one; otherwise Advanced when
/// this browser already has a Gloss workspace (it was made before the
/// switch existed, so nobody loses a field they used), else Simple.
EditorMode initialMode({String? stored, required bool hasWorkspace}) =>
    parseMode(stored) ??
    (hasWorkspace ? EditorMode.advanced : EditorMode.simple);
