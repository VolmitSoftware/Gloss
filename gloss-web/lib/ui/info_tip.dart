/// Help beside a label: a square (i) button named "More info" and described
/// by the tip. Hover or focus shows the tip after a short rest; a click or
/// tap pins it open until Escape, a second click or a click elsewhere. Built
/// on shadcn's hover card, so the tip is a neutral popover surface. The tip
/// is text the caller wrote or a field doc (`huiFieldDoc`); a doc's source
/// citation shows in Advanced only.
library;

import 'dart:js_interop';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;
import 'package:web/web.dart' as web;

import '../config/field_docs.dart';
import '../l10n/hui_localizations.dart';
import 'mode.dart';

int _nextTipId = 0;

/// How long a resting pointer waits before the tip opens.
const int tipOpenDelayMs = 300;

/// The tip text and citation for [tip] or [docKey]; null when neither has
/// any (an unknown doc key draws no button, never a broken row).
({String text, String? citation})? resolveTip({
  String? tip,
  String? docKey,
  String? citation,
}) {
  if (tip != null && tip.trim().isNotEmpty) {
    return (text: tip.trim(), citation: citation);
  }
  if (docKey == null) return null;
  final HuiFieldDoc? doc = huiFieldDoc(docKey);
  if (doc == null || doc.body.trim().isEmpty) return null;
  return (text: doc.body.trim(), citation: citation ?? doc.citation);
}

class InfoButton extends StatefulWidget {
  const InfoButton({this.tip, this.docKey, this.citation, super.key});

  /// One or two short sentences, already localized.
  final String? tip;

  /// A key into the field docs; its body is the tip when [tip] is null.
  final String? docKey;

  /// The source line that proves the tip (`File.java:12`); Advanced only.
  final String? citation;

  @override
  State<InfoButton> createState() => _InfoButtonState();
}

class _InfoButtonState extends State<InfoButton> {
  final String _id = 'ui-tip-${_nextTipId++}';
  bool _open = false;

  /// Opened by a click or tap: stays until dismissed, not until the
  /// pointer leaves.
  bool _pinned = false;

  void _changed(bool open) {
    if (!open && _pinned) {
      // The hover card closes on leave and blur; a pinned tip waits for an
      // explicit close (Escape and outside clicks clear the pin first).
      final web.Element? active = web.document.activeElement;
      final bool inside =
          active != null &&
          (web.document.getElementById('$_id-wrap')?.contains(active) ?? false);
      if (inside) return;
    }
    if (!open && _pinned) {
      _pinned = false;
      _listen(false);
    }
    if (mounted && open != _open) setState(() => _open = open);
  }

  void _click() {
    setState(() {
      _pinned = !_pinned;
      _open = _pinned;
    });
    _listen(_pinned);
  }

  /// Escape while pinned. The hover card swallows Escape on its trigger and
  /// asks to close, which [_changed] ignores while pinned, so the key is
  /// caught on the window before it gets there.
  JSFunction? _escape;

  void _listen(bool on) {
    final JSFunction? current = _escape;
    if (current != null) {
      web.window.removeEventListener(
        'keydown',
        current,
        web.EventListenerOptions(capture: true),
      );
      _escape = null;
    }
    if (!on) return;
    final JSFunction escape = ((web.KeyboardEvent event) {
      if (event.key != 'Escape' || !_pinned) return;
      event.preventDefault();
      event.stopPropagation();
      _pinned = false;
      _listen(false);
      if (mounted) setState(() => _open = false);
    }).toJS;
    _escape = escape;
    web.window.addEventListener(
      'keydown',
      escape,
      web.AddEventListenerOptions(capture: true),
    );
  }

  @override
  void dispose() {
    _listen(false);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final ({String text, String? citation})? tip = resolveTip(
      tip: component.tip,
      docKey: component.docKey,
      citation: component.citation,
    );
    if (tip == null) return const Component.fragment(<Widget>[]);
    final String? citation = ModeScope.advanced(context) ? tip.citation : null;
    return dom.span(id: '$_id-wrap', <Widget>[
      ArcaneHoverCard(
        id: _id,
        isOpen: _open,
        onOpenChange: _changed,
        openDelay: tipOpenDelayMs,
        closeDelay: 150,
        position: FloatingPosition.bottomStart,
        showArrow: false,
        offset: 6,
        maxWidth: 320,
        trigger: Button.ghost(
          icon: ArcaneIcon.info(size: IconSize.sm),
          size: ButtonSize.iconSm,
          attributes: <String, String>{
            'data-ui-info': '',
            'aria-label': huiText('More info'),
            'aria-describedby': '$_id-text',
            'aria-expanded': '$_open',
          },
          onPressed: _click,
        ),
        content: dom.span(classes: 'ui-info-tip', <Widget>[
          dom.span(id: '$_id-text', <Widget>[Component.text(tip.text)]),
          if (citation != null)
            dom.code(classes: 'ui-info-citation', <Widget>[
              Component.text(citation),
            ]),
        ]),
      ),
    ]);
  }
}

/// [label] followed by its (i) button, or [label] alone without a tip.
class TipLabel extends StatelessWidget {
  const TipLabel({
    required this.label,
    this.tip,
    this.docKey,
    this.citation,
    super.key,
  });

  final Widget label;
  final String? tip;
  final String? docKey;
  final String? citation;

  @override
  Widget build(BuildContext context) {
    if (resolveTip(tip: tip, docKey: docKey, citation: citation) == null) {
      return label;
    }
    return dom.span(classes: 'ui-tip-label', <Widget>[
      label,
      InfoButton(tip: tip, docKey: docKey, citation: citation),
    ]);
  }
}
