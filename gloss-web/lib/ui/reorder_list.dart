/// A list whose rows are dragged into a new order by a grip, with Move up,
/// Move down and (optionally) Remove buttons on each row for the keyboard.
/// Pointer events with pointer capture on the grip, so a drag keeps
/// tracking outside the list; `touch-action: none` stops the browser taking
/// it as a scroll. Row centres are measured once at pointer-down and the
/// drop gap is recomputed from them, so a drag costs a few rebuilds, not one
/// per frame. The dragged row stays put; a line marks where it will land.
/// With `inlineTools` the row body places the grip and buttons itself
/// ([ReorderRowTools]) and keeps the full width.
library;

import 'dart:js_interop';
import 'dart:js_interop_unsafe';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart'
    show Component, EventCallback, InheritedComponent;
import 'package:web/web.dart' as web;

import '../l10n/hui_localizations.dart';
import 'density.dart';
import 'icon_button.dart';
import 'reorder_slots.dart';

int _nextReorderId = 0;

class ReorderList extends StatefulWidget {
  const ReorderList({
    required this.itemCount,
    required this.itemBuilder,
    required this.onReorder,
    this.onRemove,
    this.tools = true,
    this.inlineTools = false,
    this.label,
    super.key,
  });

  final int itemCount;

  /// The row body; the grip and the row buttons are added around it.
  final Widget Function(int index) itemBuilder;

  /// Called once per move: the arguments to `removeAt(from)` then
  /// `insert(to, row)`, one mutation and so one undo step.
  final void Function(int from, int to) onReorder;

  /// Shows Remove on each row.
  final void Function(int index)? onRemove;

  /// Shows Move up and Move down on each row. Turn off only where the row
  /// body has its own.
  final bool tools;

  /// The row body puts the grip and the buttons where it means them, with
  /// a [ReorderRowTools] (a text line's own row), instead of a grip column
  /// before the body and a button column after it.
  final bool inlineTools;

  /// Names the list ("Actions").
  final String? label;

  @override
  State<ReorderList> createState() => _ReorderListState();
}

class _ReorderListState extends State<ReorderList> {
  late final String _uid = 'ui-reorder-${_nextReorderId++}';
  int? _dragIndex;

  /// The gap, `0 .. itemCount`, the row would drop into.
  int _slot = 0;
  List<double> _midpoints = const <double>[];
  int? _pointerId;

  String _gripId(int index) => '$_uid-grip-$index';

  /// A number property of a pointer event, read without dynamic dispatch
  /// (which throws on `package:web` types under dart2js).
  double _eventNumber(Object? event, String property) {
    final JSObject? object = event as JSObject?;
    final JSAny? value = object?.getProperty<JSAny?>(property.toJS);
    if (value == null || !value.isA<JSNumber>()) return 0;
    return (value as JSNumber).toDartDouble;
  }

  int _eventInt(Object? event, String property) =>
      _eventNumber(event, property).round();

  void _measure() {
    final List<double> midpoints = <double>[];
    for (int index = 0; index < component.itemCount; index++) {
      final web.Element? row = web.document.getElementById('$_uid-row-$index');
      if (row == null) return;
      final web.DOMRect box = row.getBoundingClientRect();
      midpoints.add(box.top + box.height / 2);
    }
    _midpoints = midpoints;
  }

  void _down(int index, Object? event) {
    if (_eventInt(event, 'button') != 0) return;
    _measure();
    if (_midpoints.length != component.itemCount) return;
    final int pointerId = _eventInt(event, 'pointerId');
    try {
      web.document.getElementById(_gripId(index))?.setPointerCapture(pointerId);
    } on Object {
      // Capture is an enhancement; the drag still works inside the list.
    }
    _pointerId = pointerId;
    setState(() {
      _dragIndex = index;
      _slot = index;
    });
    (event as JSObject?)?.callMethod<JSAny?>('preventDefault'.toJS);
  }

  void _move(Object? event) {
    if (_dragIndex == null || _eventInt(event, 'pointerId') != _pointerId) {
      return;
    }
    final int slot = slotAt(_eventNumber(event, 'clientY'), _midpoints);
    if (slot != _slot) setState(() => _slot = slot);
  }

  void _up(Object? event) {
    final int? from = _dragIndex;
    if (from == null || _eventInt(event, 'pointerId') != _pointerId) return;
    _release(from);
    final int to = dropIndex(
      from: from,
      slot: _slot,
      count: component.itemCount,
    );
    setState(() {
      _dragIndex = null;
      _midpoints = const <double>[];
    });
    if (to != from) component.onReorder(from, to);
  }

  void _cancel(Object? _) {
    final int? from = _dragIndex;
    if (from == null) return;
    _release(from);
    setState(() {
      _dragIndex = null;
      _midpoints = const <double>[];
    });
  }

  void _release(int index) {
    final int? pointerId = _pointerId;
    _pointerId = null;
    if (pointerId == null) return;
    try {
      final web.Element? grip = web.document.getElementById(_gripId(index));
      if (grip != null && grip.hasPointerCapture(pointerId)) {
        grip.releasePointerCapture(pointerId);
      }
    } on Object {
      // Already released by the browser.
    }
  }

  /// A line only where the row would really move: the gaps either side of
  /// the row itself are no move.
  bool _lineAt(int slot) {
    final int? from = _dragIndex;
    return from != null && _slot == slot && slot != from && slot != from + 1;
  }

  Widget _line(int slot) => dom.div(
    classes: 'ui-reorder-line${_lineAt(slot) ? ' is-active' : ''}',
    attributes: const <String, String>{'aria-hidden': 'true'},
    const <Widget>[],
  );

  /// Pointer only: the row buttons are the keyboard path, so the grip is
  /// hidden from assistive technology.
  Widget _grip(int index) => dom.span(
    id: _gripId(index),
    classes: 'ui-reorder-grip',
    attributes: const <String, String>{'aria-hidden': 'true'},
    events: <String, EventCallback>{
      'pointerdown': (Object? event) => _down(index, event),
      'pointermove': _move,
      'pointerup': _up,
      'pointercancel': _cancel,
    },
    <Widget>[ArcaneIcon.gripVertical(size: IconSize.sm)],
  );

  Widget _row(int index) {
    final bool inline = component.inlineTools;
    return dom.div(
      id: '$_uid-row-$index',
      classes:
          'ui-reorder-row${inline ? ' is-inline' : ''}${_dragIndex == index ? ' is-dragging' : ''}',
      attributes: <String, String>{
        'role': 'listitem',
        'data-reorder-index': '$index',
      },
      <Widget>[
        if (!inline) _grip(index),
        dom.div(classes: 'ui-reorder-body', <Widget>[
          if (inline)
            _ReorderRowScope(
              tools: _tools(index, grip: true),
              child: component.itemBuilder(index),
            )
          else
            component.itemBuilder(index),
        ]),
        if (!inline) _tools(index),
      ],
    );
  }

  /// Move up, Move down and Remove, as the list has them; with [grip] the
  /// grip leads them.
  Widget _tools(int index, {bool grip = false}) {
    final int count = component.itemCount;
    final void Function(int index)? remove = component.onRemove;
    if (!grip && !component.tools && remove == null) {
      return const Component.fragment(<Widget>[]);
    }
    return DensityScope.dense(
      child: dom.div(classes: 'ui-reorder-tools', <Widget>[
        if (grip) _grip(index),
        if (component.tools) ...<Widget>[
          AppIconButton(
            icon: ArcaneIcon.arrowUp(),
            label: huiText('Move up'),
            onPressed: index == 0
                ? null
                : () => component.onReorder(index, index - 1),
          ),
          AppIconButton(
            icon: ArcaneIcon.arrowDown(),
            label: huiText('Move down'),
            onPressed: index >= count - 1
                ? null
                : () => component.onReorder(index, index + 1),
          ),
        ],
        if (remove != null)
          AppIconButton(
            icon: ArcaneIcon.trash(),
            label: huiText('Remove'),
            tipAtEnd: true,
            onPressed: () => remove(index),
          ),
      ]),
    );
  }

  @override
  Widget build(BuildContext context) => dom.div(
    id: _uid,
    classes: 'ui-reorder${_dragIndex != null ? ' is-dragging' : ''}',
    attributes: <String, String>{
      'role': 'list',
      'aria-label': ?component.label,
    },
    <Widget>[
      for (int index = 0; index < component.itemCount; index++) ...<Widget>[
        _line(index),
        _row(index),
      ],
      _line(component.itemCount),
    ],
  );
}

/// Hands a row body its grip and buttons in an `inlineTools` list.
class _ReorderRowScope extends InheritedComponent {
  const _ReorderRowScope({required this.tools, required super.child});

  final Widget tools;

  @override
  bool updateShouldNotify(_ReorderRowScope oldComponent) => true;
}

/// The grip and the Move up / Move down buttons of the [ReorderList] row
/// this sits in, when the list has `inlineTools`; nothing elsewhere, so a
/// row body can place it whether or not it is in such a list.
class ReorderRowTools extends StatelessWidget {
  const ReorderRowTools({super.key});

  @override
  Widget build(BuildContext context) =>
      context
          .dependOnInheritedComponentOfExactType<_ReorderRowScope>()
          ?.tools ??
      const Component.fragment(<Widget>[]);
}
