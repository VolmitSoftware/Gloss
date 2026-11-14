/// Which views the top bar offers for the open kind, which one the centre
/// shows, and what the bar keeps on itself at each width. Pure, so the rules
/// are unit tested.
///
/// - One View switch: Edit, then Preview for kinds whose preview differs
///   (the menu and the container preview), Code for kinds with runtime JSON,
///   and Split in Advanced at desktop width.
/// - A choice the kind cannot serve is not offered at all.
/// - The bar's controls depend on the width only, never on the kind.
library;

import '../../config/kind_groups.dart';
import '../../doctype/doctype.dart';
import '../../state/editor_store.dart';
import '../../state/workspace.dart';

/// What the view controls show for one kind in one mode and width.
class ViewChoices {
  const ViewChoices({
    required this.editPreview,
    required this.code,
    required this.split,
  });

  /// The Edit | Preview switch.
  final bool editPreview;

  /// The Code toggle.
  final bool code;

  /// The Split toggle.
  final bool split;

  /// The View switch's options, in order.
  List<EditorView> get options => <EditorView>[
    EditorView.visual,
    if (editPreview) EditorView.preview,
    if (code) EditorView.code,
    if (split) EditorView.split,
  ];

  /// A switch needs two options; a kind with one view shows none.
  bool get any => options.length > 1;
}

ViewChoices viewChoices(
  DocumentTypeAdapter type, {
  required bool advanced,
  required bool compact,
}) => ViewChoices(
  editPreview:
      hasDistinctPreview(type.kind) && type.supportsView(EditorView.preview),
  code: type.supportsView(EditorView.code),
  split: advanced && !compact && type.supportsView(EditorView.split),
);

/// The view the centre draws for a stored [view]: a Preview that would draw
/// the same stage, or a Split the bar does not offer, shows the stage.
EditorView shownView(
  EditorView view,
  WorkspaceDocKind kind, {
  required bool advanced,
  required bool compact,
}) {
  if (view == EditorView.preview && !hasDistinctPreview(kind)) {
    return EditorView.visual;
  }
  if (view == EditorView.split && (!advanced || compact)) {
    return EditorView.visual;
  }
  return view;
}

/// Where the Code toggle goes from [view]: into code, or back to [stage].
EditorView afterCodeToggle(EditorView view, EditorView stage) =>
    view == EditorView.code ? stage : EditorView.code;

/// Where the Split toggle goes from [view].
EditorView afterSplitToggle(EditorView view) =>
    view == EditorView.split ? EditorView.visual : EditorView.split;

/// The widths the top bar lays out for: the shell's phone rule (760 px and
/// below), its compact rule (1024 px and below) and wider.
enum BarWidth { wide, compact, phone }

BarWidth barWidth({required bool compact, required bool phone}) => phone
    ? BarWidth.phone
    : compact
    ? BarWidth.compact
    : BarWidth.wide;

/// What the top bar shows on itself; the rest waits in its "..." menu.
class BarLayout {
  const BarLayout({
    required this.viewLabels,
    required this.actionLabels,
    required this.history,
    required this.search,
    required this.server,
    required this.mode,
  });

  /// The View options show their names beside their icons.
  final bool viewLabels;

  /// Search and Server show their names.
  final bool actionLabels;

  /// Undo and Redo.
  final bool history;
  final bool search;
  final bool server;

  /// The Simple | Advanced switch.
  final bool mode;
}

/// Measured fold steps past a width's layout: the mode switch, then the
/// View labels. The action row then folds its buttons on its own.
const int barFoldSteps = 2;

/// The bar at [width], the same for every kind. [fold] counts the steps a
/// measurement took because a long title or a translation still left no
/// room.
BarLayout barLayout(BarWidth width, {int fold = 0}) {
  final int steps = switch (width) {
    BarWidth.wide => fold,
    BarWidth.compact || BarWidth.phone => barFoldSteps,
  };
  final bool phone = width == BarWidth.phone;
  return BarLayout(
    viewLabels: steps < 2,
    actionLabels: width == BarWidth.wide,
    history: !phone,
    search: !phone,
    server: !phone,
    mode: steps < 1,
  );
}
