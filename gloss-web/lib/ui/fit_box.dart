/// Scales its child to fit the room it is given, with no scrollbar: one CSS
/// zoom on the child, measured from its natural size and the box's width
/// (and height, in [FitBox.fill]), never above the natural size ([fitZoom]).
/// Zoom scales the layout box too, so the frame around it follows the
/// scaled size: no gap under it, no overlap. Marked `data-window-fit` for
/// the preview-fit check.
library;

import 'dart:js_interop';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:web/web.dart' as web;

import 'fit_zoom.dart';

int _nextFitId = 0;

class FitBox extends StatefulWidget {
  const FitBox({
    required this.child,
    this.fill = false,
    this.hug = false,
    this.pixel = 0,
    this.align = FitAlign.center,
    super.key,
  });

  /// The content, laid out at its natural size.
  final Widget child;

  /// Fits the height as well: the box takes the height its parent gives it
  /// (a [StageFrame] body). Otherwise only the width is fitted and the box
  /// is as tall as the scaled content.
  final bool fill;

  /// Fits a window placed inside a region: the room (width and height) is
  /// the parent element, and the box keeps only the scaled size of the
  /// content, never the room's. For a fixed-size window anchored in a
  /// game screen.
  final bool hug;

  /// CSS pixels per art pixel for pixel art, so the zoom steps in whole
  /// device pixels; 0 for anything else.
  final int pixel;
  final FitAlign align;

  @override
  State<FitBox> createState() => _FitBoxState();
}

/// Where scaled content sits when it is smaller than the box.
enum FitAlign { center, end }

class _FitBoxState extends State<FitBox> {
  final String _id = 'ui-fit-${_nextFitId++}';
  web.ResizeObserver? _observer;
  bool _queued = false;
  double _zoom = 1;

  @override
  void dispose() {
    _observer?.disconnect();
    super.dispose();
  }

  /// Fits on the next frame: a zoom set inside a resize callback would
  /// resize the observed boxes again within the same frame.
  void _queue() {
    if (_queued) return;
    _queued = true;
    web.window.requestAnimationFrame(
      ((JSNumber _) {
        _queued = false;
        _fit();
      }).toJS,
    );
  }

  void _fit() {
    if (!mounted) return;
    final web.Element? host = web.document.getElementById(_id);
    final web.Element? box = host?.firstElementChild;
    final web.Element? room = component.hug ? host?.parentElement : host;
    if (host == null || box == null || room == null) return;
    if (_observer == null) {
      _observer = web.ResizeObserver(
        ((
          JSArray<web.ResizeObserverEntry> _,
          web.ResizeObserver _,
        ) => _queue()).toJS,
      );
      _observer!.observe(room);
      _observer!.observe(box);
    }
    final web.DOMRect natural = box.getBoundingClientRect();
    final double zoom = fitZoom(
      naturalWidth: natural.width / _zoom,
      naturalHeight: natural.height / _zoom,
      roomWidth: room.clientWidth.toDouble(),
      roomHeight: component.fill || component.hug
          ? room.clientHeight.toDouble()
          : null,
      dpr: web.window.devicePixelRatio,
      pixel: component.pixel,
    );
    if ((zoom - _zoom).abs() > 1e-6) setState(() => _zoom = zoom);
  }

  @override
  Widget build(BuildContext context) {
    // The content may have changed its natural size (a line, a column).
    _queue();
    return dom.div(
      id: _id,
      classes:
          'ui-fit is-${component.align.name}${component.fill ? ' is-fill' : ''}',
      attributes: <String, String>{
        'data-window-fit': '',
        'data-fit-zoom': _zoom.toStringAsFixed(2),
      },
      <Widget>[
        dom.div(
          classes: 'ui-fit-content',
          styles: _zoom == 1
              ? null
              : dom.Styles(raw: <String, String>{'zoom': '$_zoom'}),
          <Widget>[component.child],
        ),
      ],
    );
  }
}
