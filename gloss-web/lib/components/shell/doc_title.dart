/// The open document's title in the top bar, renamed in place: a click
/// turns it into a field; Enter or leaving the field keeps the new title,
/// Escape keeps the old one.
library;

import 'dart:js_interop';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:web/web.dart' as web;

import '../../l10n/hui_localizations.dart';
import '../../ui/density.dart';

class DocTitle extends StatefulWidget {
  const DocTitle({required this.title, required this.onRename, super.key});

  final String title;

  /// Called with a trimmed, non-empty title that differs from [title].
  final void Function(String title) onRename;

  @override
  State<DocTitle> createState() => _DocTitleState();
}

class _DocTitleState extends State<DocTitle> {
  static const String _inputId = 'hui-doc-title-input';
  static const String _buttonId = 'hui-doc-title';

  bool _editing = false;
  String _draft = '';

  void _start() {
    setState(() {
      _editing = true;
      _draft = component.title;
    });
    context.binding.addPostFrameCallback(() {
      final web.Element? input = web.document.getElementById(_inputId);
      if (input != null && input.isA<web.HTMLInputElement>()) {
        (input as web.HTMLInputElement)
          ..focus()
          ..select();
      }
    });
  }

  void _finish({required bool keep}) {
    if (!_editing) return;
    final String title = _draft.trim();
    setState(() => _editing = false);
    if (keep && title.isNotEmpty && title != component.title) {
      component.onRename(title);
    }
    context.binding.addPostFrameCallback(() {
      (web.document.getElementById(_buttonId) as web.HTMLElement?)?.focus();
    });
  }

  /// Enter is taken on key up: the key down that ends the field would
  /// otherwise reach the title button that replaces it and reopen it.
  void _key(web.Event event, {required bool up}) {
    if (!event.isA<web.KeyboardEvent>()) return;
    final String key = (event as web.KeyboardEvent).key;
    if (up && key == 'Enter') _finish(keep: true);
    if (!up && key == 'Escape') {
      event.preventDefault();
      event.stopPropagation();
      _finish(keep: false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final Density density = DensityScope.of(context);
    if (!_editing) {
      // A block box so a long title ends in an ellipsis instead of being cut
      // when the bar narrows; with no top padding its text sits on the line
      // the flex-centred bar buttons use. It
      // never shrinks by flex, so the runtime id beside it gives way first,
      // and is capped at the title group's width.
      return Button.ghost(
        id: _buttonId,
        label: component.title,
        size: buttonSize(density),
        onPressed: _start,
        styles: const ArcaneStyleData(
          display: Display.block,
          overflow: Overflow.hidden,
          textOverflow: TextOverflow.ellipsis,
          minWidth: '0',
          maxWidthCustom: '100%',
          flexShrink: 0,
          paddingTop: '0',
        ),
        attributes: <String, String>{'aria-description': huiText('Rename')},
      );
    }
    return dom.span(
      classes: 'hui-doc-field',
      events: <String, void Function(web.Event)>{
        'keyup': (web.Event event) => _key(event, up: true),
        'keydown': (web.Event event) => _key(event, up: false),
      },
      <Widget>[
        TextInput(
          id: _inputId,
          value: _draft,
          size: controlSize(density),
          placeholder: huiText('Untitled'),
          attributes: <String, String>{'aria-label': huiText('Document title')},
          onChanged: (String value) => _draft = value,
          onBlur: () => _finish(keep: true),
        ),
      ],
    );
  }
}
