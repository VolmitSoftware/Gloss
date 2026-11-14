/// The Mode plumbing: [ModeScope] hands the current [EditorMode] down the
/// tree, [AdvancedOnly] shows its child in Advanced only, and [MoreFields]
/// keeps rare fields behind a "More" disclosure in both modes. Hidden never
/// means lost: editors write single keys, so keys they do not show
/// round-trip.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/jaspr.dart' show Component, InheritedComponent;
import 'package:web/web.dart' as web;

import '../l10n/hui_localizations.dart';
import 'editor_mode.dart';
import 'section.dart';

/// The mode this browser starts in (see [initialMode]). A first visit
/// stores its answer, so the documents made afterwards do not flip it.
EditorMode loadMode({required bool hasWorkspace}) {
  String? stored;
  try {
    stored = web.window.localStorage.getItem(modeStorageKey);
  } on Object {
    stored = null;
  }
  final EditorMode mode = initialMode(
    stored: stored,
    hasWorkspace: hasWorkspace,
  );
  if (parseMode(stored) == null) saveMode(mode);
  return mode;
}

/// Keeps [mode] for the next visit; blocked storage keeps it for this page.
void saveMode(EditorMode mode) {
  try {
    web.window.localStorage.setItem(modeStorageKey, mode.name);
  } on Object {
    // Private mode or storage switched off.
  }
}

class ModeScope extends InheritedComponent {
  const ModeScope({
    required this.mode,
    required this.onChanged,
    required super.child,
    super.key,
  });

  final EditorMode mode;

  /// Switches the whole editor (the Mode toggle calls it).
  final void Function(EditorMode mode) onChanged;

  static ModeScope? maybeOf(BuildContext context) =>
      context.dependOnInheritedComponentOfExactType<ModeScope>();

  /// The current mode; Advanced outside a scope, so a part built without
  /// the shell (a test, a dialog) shows every field.
  static EditorMode of(BuildContext context) =>
      maybeOf(context)?.mode ?? EditorMode.advanced;

  static bool advanced(BuildContext context) =>
      of(context) == EditorMode.advanced;

  @override
  bool updateShouldNotify(ModeScope oldComponent) => oldComponent.mode != mode;
}

/// [child] in Advanced, [simple] (or nothing) in Simple.
class AdvancedOnly extends StatelessWidget {
  const AdvancedOnly({required this.child, this.simple, super.key});

  final Widget child;
  final Widget? simple;

  @override
  Widget build(BuildContext context) => ModeScope.advanced(context)
      ? child
      : (simple ?? const Component.fragment(<Widget>[]));
}

/// Rare fields behind one "More" disclosure at the end of a pane, in both
/// modes. Its open state is its own, so a rebuild keeps it as left; give a
/// [memoryKey] to keep it across documents too.
class MoreFields extends StatelessWidget {
  const MoreFields({
    required this.children,
    this.label,
    this.open = false,
    this.memoryKey,
    super.key,
  });

  final List<Widget> children;

  /// Defaults to "More".
  final String? label;
  final bool open;
  final String? memoryKey;

  @override
  Widget build(BuildContext context) => AppSection(
    title: label ?? huiText('More'),
    open: open,
    memoryKey: memoryKey,
    children: children,
  );
}
