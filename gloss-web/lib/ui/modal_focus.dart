/// Keyboard for the kit's dialog and sheet while open: focus moves in and
/// back to the opener on close, Tab stays inside, Escape closes. On a phone
/// or tablet focus lands on the panel itself, so opening never raises the
/// on-screen keyboard ([modalStart]). The arcane
/// runtime does this for surfaces it opened itself; these open from Dart
/// state, which the runtime does not track.
library;

import 'dart:js_interop';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:web/web.dart' as web;

import 'modal_start.dart';

/// The open modals, the last one on top: only it takes Escape and Tab, so a
/// confirmation over the Problems sheet closes alone.
final List<Object> _openModals = <Object>[];

/// A dropdown or context menu the runtime has open.
const String _openMenu =
    '[data-arcane-surface="menu"][data-arcane-state="open"], '
    '[data-arcane-surface="context-menu"][data-arcane-state="open"]';

/// Whether the last input before a modal opened was a key, not a pointer:
/// set by two listeners installed with the first modal.
bool _keyboardLast = false;
bool _watchingInput = false;

void _watchInput() {
  if (_watchingInput) return;
  _watchingInput = true;
  final web.AddEventListenerOptions capture = web.AddEventListenerOptions(
    capture: true,
    passive: true,
  );
  web.window.addEventListener(
    'keydown',
    ((web.Event _) => _keyboardLast = true).toJS,
    capture,
  );
  web.window.addEventListener(
    'pointerdown',
    ((web.Event _) => _keyboardLast = false).toJS,
    capture,
  );
}

/// The dialog or sheet panel the library draws.
const String _panel = '[role="dialog"], [role="alertdialog"]';

const String _focusable =
    'a[href], button:not([disabled]), input:not([disabled]):not([type="hidden"]), select:not([disabled]), '
    'textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

mixin ModalFocus<T extends StatefulWidget> on State<T> {
  /// The id of the element that holds the modal.
  String get modalId;
  bool get modalOpen;
  void modalClose();

  web.HTMLElement? _opener;
  JSFunction? _keys;

  @override
  void initState() {
    super.initState();
    _watchInput();
    if (modalOpen) _opened();
  }

  /// Call from didUpdateWidget with the previous open state.
  void followOpen(bool wasOpen) {
    if (modalOpen && !wasOpen) _opened();
    if (!modalOpen && wasOpen) _closed();
  }

  @override
  void dispose() {
    if (modalOpen) _closed();
    super.dispose();
  }

  List<web.HTMLElement> _inside() {
    final web.NodeList? found = web.document
        .getElementById(modalId)
        ?.querySelectorAll(_focusable);
    if (found == null) return const <web.HTMLElement>[];
    return <web.HTMLElement>[
      for (int index = 0; index < found.length; index++)
        if ((found.item(index)! as web.HTMLElement).offsetParent != null)
          found.item(index)! as web.HTMLElement,
    ];
  }

  void _opened() {
    final web.Element? active = web.document.activeElement;
    _opener = active != null && active.isA<web.HTMLElement>()
        ? active as web.HTMLElement
        : null;
    _openModals.remove(this);
    _openModals.add(this);
    _keys = ((web.KeyboardEvent event) {
      if (!modalOpen || !identical(_openModals.last, this)) return;
      if (event.key == 'Escape') {
        // A menu open inside the modal takes this Escape for itself.
        if (web.document.querySelector(_openMenu) != null) return;
        event.preventDefault();
        event.stopPropagation();
        modalClose();
      } else if (event.key == 'Tab') {
        final List<web.HTMLElement> items = _inside();
        if (items.isEmpty) return;
        final web.Element? now = web.document.activeElement;
        final int at = items.indexWhere(
          (web.HTMLElement item) => identical(item, now),
        );
        if (at < 0 ||
            (event.shiftKey && at == 0) ||
            (!event.shiftKey && at == items.length - 1)) {
          event.preventDefault();
          (event.shiftKey ? items.last : items.first).focus();
        }
      }
    }).toJS;
    web.window.addEventListener(
      'keydown',
      _keys,
      web.AddEventListenerOptions(capture: true),
    );
    // Once now and once after the runtime has focused the panel itself.
    web.window.requestAnimationFrame(((JSNumber _) => _focusFirst()).toJS);
    web.window.setTimeout((() => _focusFirst()).toJS, 50.toJS);
  }

  /// Focus on the first field, else the first control, unless a control
  /// inside already has it. A coarse pointer, or a modal opened with a
  /// click, focuses the panel.
  void _focusFirst() {
    if (!modalOpen) return;
    final web.Element? holder = web.document.getElementById(modalId);
    final web.Element? active = web.document.activeElement;
    if (holder == null ||
        (active != null &&
            holder.contains(active) &&
            active.matches(_focusable))) {
      return;
    }
    final bool coarse = web.window.matchMedia('(pointer: coarse)').matches;
    if (modalStart(coarsePointer: coarse, keyboard: _keyboardLast) ==
        ModalStart.panel) {
      final web.HTMLElement? panel =
          holder.querySelector(_panel) as web.HTMLElement?;
      if (panel != null) {
        if (!panel.hasAttribute('tabindex')) {
          panel.setAttribute('tabindex', '-1');
        }
        if (!identical(active, panel)) panel.focus();
        return;
      }
    }
    // An (i) button would pop its tip open on arrival: never the first stop,
    // nor the corner Close (a direct child of the panel, last in the page).
    final List<web.HTMLElement> items = <web.HTMLElement>[
      for (final web.HTMLElement item in _inside())
        if (!item.matches('[data-ui-info]') &&
            !(item.parentElement?.matches(_panel) ?? false))
          item,
    ];
    final web.HTMLElement? field = items
        .where(
          (web.HTMLElement item) =>
              item.matches('input, textarea, select, [role="listbox"]'),
        )
        .firstOrNull;
    (field ?? items.firstOrNull)?.focus();
  }

  void _closed() {
    _openModals.remove(this);
    if (_keys != null) {
      web.window.removeEventListener(
        'keydown',
        _keys,
        web.EventListenerOptions(capture: true),
      );
    }
    _keys = null;
    final web.HTMLElement? back = _opener;
    _opener = null;
    if (back != null && back.isConnected) {
      web.window.setTimeout(
        (() {
          // Only when focus is lost (on the page or still in this modal): a
          // surface opened meanwhile (the command palette right after Escape) keeps it.
          final web.Element? now = web.document.activeElement;
          final bool lost =
              now == null ||
              identical(now, web.document.body) ||
              (web.document.getElementById(modalId)?.contains(now) ?? false);
          if (back.isConnected && lost) back.focus();
        }).toJS,
        0.toJS,
      );
    }
  }
}
