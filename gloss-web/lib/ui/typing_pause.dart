/// Holds a field's problems back while its box is being typed in, so a
/// half-typed id does not flash red at every key. Problems show once typing
/// pauses for [typingPause] or focus leaves the field.
library;

import 'dart:async';
import 'dart:js_interop';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:web/web.dart' as web;

/// How long typing must pause before a field's problems show.
const Duration typingPause = Duration(milliseconds: 800);

bool _isTypingTarget(web.EventTarget? target) {
  if (target == null || !target.isA<web.Element>()) return false;
  final web.Element element = target as web.Element;
  final String tag = element.tagName.toLowerCase();
  return tag == 'input' ||
      tag == 'textarea' ||
      element.getAttribute('contenteditable') == 'true';
}

/// For a field row's state: put [typingEvents] on the row's element and
/// render its problems only while [typing] is false.
mixin TypingPause<T extends StatefulWidget> on State<T> {
  bool _typing = false;
  Timer? _pause;

  /// True from a key typed in the row until the pause or a blur.
  bool get typing => _typing;

  /// Listeners for the row's element; `input` and `focusout` bubble to it.
  late final Map<String, void Function(web.Event)> typingEvents =
      <String, void Function(web.Event)>{
        'input': (web.Event event) {
          // The field's own input listener can rebuild the page first and
          // drop this row before the event bubbles here.
          if (!mounted || !_isTypingTarget(event.target)) return;
          _pause?.cancel();
          _pause = Timer(typingPause, _settle);
          if (!_typing) setState(() => _typing = true);
        },
        'focusout': (web.Event _) => _settle(),
      };

  void _settle() {
    _pause?.cancel();
    if (mounted && _typing) setState(() => _typing = false);
  }

  @override
  void dispose() {
    _pause?.cancel();
    super.dispose();
  }
}
