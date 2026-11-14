/// A panel that slides over the editor on shadcn's sheet: from the right
/// (Problems, History, the inspector drawer), from the bottom (the
/// validation list on a phone) or from the start (the library drawer).
/// Full screen at 760 px and below. Focus moves in, stays in and goes back
/// to the opener ([ModalFocus]); Escape and the scrim close it.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show InheritedComponent;

import 'action_row.dart';
import 'density.dart';
import 'modal_focus.dart';

/// Where a [Sheet] comes from.
enum SheetSide { end, bottom, start }

int _nextSheetId = 0;

class Sheet extends StatefulWidget {
  const Sheet({
    required this.open,
    required this.title,
    required this.onClose,
    required this.child,
    this.footer,
    this.side = SheetSide.end,
    this.dense = false,
    this.id,
    super.key,
  });

  final bool open;
  final String title;
  final void Function() onClose;
  final Widget child;

  /// The buttons at the bottom of the sheet.
  final DialogFooter? footer;
  final SheetSide side;

  /// Dense controls inside (the inspector or library drawer); a sheet is
  /// regular otherwise.
  final bool dense;

  /// On the wrapper element.
  final String? id;

  @override
  State<Sheet> createState() => _SheetState();
}

class _SheetState extends State<Sheet> with ModalFocus<Sheet> {
  late final String _id = component.id ?? 'ui-sheet-${_nextSheetId++}';

  @override
  String get modalId => _id;

  @override
  bool get modalOpen => component.open;

  @override
  void modalClose() => component.onClose();

  @override
  void didUpdateWidget(Sheet oldComponent) {
    super.didUpdateWidget(oldComponent);
    followOpen(oldComponent.open);
  }

  @override
  Widget build(BuildContext context) => dom.div(id: _id, <Widget>[
    DensityScope(
      density: component.dense ? Density.dense : Density.regular,
      child: ArcaneSheet(
        isOpen: component.open,
        position: switch (component.side) {
          SheetSide.end => SheetPosition.end,
          SheetSide.bottom => SheetPosition.bottom,
          SheetSide.start => SheetPosition.start,
        },
        size: SheetSize.md,
        title: component.title,
        onClose: component.onClose,
        footer: component.footer,
        showDragHandle: false,
        child: ArcaneFieldGroup(
          children: <Widget>[SheetScope(child: component.child)],
        ),
      ),
    ),
  ]);
}

/// Marks what a [Sheet] holds: a pane shown in a drawer drops its own
/// heading and inset, since the sheet already titles and pads it.
class SheetScope extends InheritedComponent {
  const SheetScope({required super.child, super.key});

  /// Whether [context] is inside a sheet.
  static bool inside(BuildContext context) =>
      context.dependOnInheritedComponentOfExactType<SheetScope>() != null;

  @override
  bool updateShouldNotify(SheetScope oldComponent) => false;
}
