/// `text` icon editor: the multiline field with its formatting row, and the
/// live in-game preview.
///
/// The formatting row splices tags around the current selection. Because
/// jaspr renders a textarea's value as a child text node, which the browser
/// stops mirroring once the field is user-dirty, every splice writes the DOM
/// `value` property directly and restores the caret before notifying the
/// store.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/gloss_text.dart' show GlossEmojiResolver, GlossNoEmoji;
import '../../logic/validation.dart';
import '../../ui/action_row.dart';
import '../../ui/color_swatch.dart';
import '../../ui/field.dart';
import '../../ui/menu.dart';
import 'dom_bridge.dart';
import 'mc_text_preview.dart';

import 'package:gloss_editor/l10n/hui_localizations.dart';

/// One formatting tag. [close] null means "insert at the caret" rather than
/// "wrap the selection".
class _TagAction {
  const _TagAction({
    required this.label,
    required this.open,
    required this.icon,
    this.hint,
    this.close,
  });

  final String label;
  final String open;
  final String? close;
  final ArcaneGlyph Function() icon;

  /// What the tag does, shown under its menu entry.
  final String? hint;
}

class TextIconEditor extends StatefulWidget {
  const TextIconEditor({
    required this.fieldId,
    required this.text,
    required this.onChanged,
    this.emoji = const GlossNoEmoji(),
    this.issues = const <HuiIssue>[],
    this.label,
    super.key,
  });

  /// DOM id of the textarea. Must be unique per slot.
  final String fieldId;
  final String text;

  /// Emoji the menu text stage substitutes in the live preview.
  final GlossEmojiResolver emoji;

  /// Called with a mutation label and the new text.
  final void Function(String label, String text) onChanged;
  final List<HuiIssue> issues;
  final String? label;

  @override
  State<TextIconEditor> createState() => _TextIconEditorState();
}

class _TextIconEditorState extends State<TextIconEditor> {
  /// The last value this editor is known to have pushed into the DOM. When the
  /// document moves for another reason (undo, redo, the code view) the textarea
  /// is force-synced, because jaspr can only render the value as a child text
  /// node and the browser ignores that once the field is user-dirty.
  late String _known;

  /// The colour the swatch shows: the last one inserted.
  String _swatch = '#ffaa00';

  @override
  void initState() {
    super.initState();
    _known = component.text;
  }

  @override
  void didUpdateWidget(TextIconEditor oldComponent) {
    super.didUpdateWidget(oldComponent);
    if (oldComponent.fieldId != component.fieldId) {
      // Callers key this editor by field id, so the id only moves under a
      // reused state when one of them forgets. This runs before the DOM is
      // patched, so the new id does not exist yet and a write would be dropped:
      // adopt the value instead and let the field render it.
      _known = component.text;
      return;
    }
    if (component.text != _known) {
      _known = component.text;
      writeTextValue(component.fieldId, component.text, focus: false);
    }
  }

  String get _text => component.text;

  String get _fieldId => component.fieldId;

  void _push(String label, String next) {
    _known = next;
    component.onChanged(label, next);
  }

  /// Shown as icon buttons; the legacy codes for each are in the field tip.
  static const List<_TagAction> _styles = <_TagAction>[
    _TagAction(
      label: 'Bold',
      open: '<bold>',
      close: '</bold>',
      icon: ArcaneIcon.bold,
    ),
    _TagAction(
      label: 'Italic',
      open: '<italic>',
      close: '</italic>',
      icon: ArcaneIcon.italic,
    ),
    _TagAction(
      label: 'Underlined',
      open: '<underlined>',
      close: '</underlined>',
      icon: ArcaneIcon.underline,
    ),
    _TagAction(
      label: 'Strikethrough',
      open: '<strikethrough>',
      close: '</strikethrough>',
      icon: ArcaneIcon.strikethrough,
    ),
  ];

  /// Rarer tags, in the row's `...` menu.
  static const List<_TagAction> _extras = <_TagAction>[
    _TagAction(
      label: 'Obfuscated',
      open: '<obfuscated>',
      close: '</obfuscated>',
      icon: ArcaneIcon.shuffle,
      hint: 'Scrambling text. Legacy &k.',
    ),
    _TagAction(
      label: 'Gradient',
      open: '<gradient:#ffffff:#5599ff>',
      close: '</gradient>',
      icon: ArcaneIcon.blend,
      hint:
          'Interpolates colour across the wrapped characters. Add more '
          'colours by appending :#rrggbb.',
    ),
    _TagAction(
      label: 'Reset',
      open: '<reset>',
      icon: ArcaneIcon.removeFormatting,
      hint: 'Drops all colour and formatting from this point on. Legacy &r.',
    ),
  ];

  /// Splices [open]/[close] around the current selection, or inserts [open] at
  /// the caret when [close] is null.
  void _apply(_TagAction tag) {
    final String text = _text;
    final (int, int)? selection = readTextSelection(_fieldId);
    final int length = text.length;
    final int start = (selection?.$1 ?? length).clamp(0, length);
    final int end = (selection?.$2 ?? start).clamp(start, length);
    final String selected = text.substring(start, end);
    final String close = tag.close ?? '';
    final String replacement = '${tag.open}$selected$close';
    final String next = text.replaceRange(start, end, replacement);
    // Caret lands inside the wrap so the selection survives, or right after the
    // tag when nothing was selected.
    final int caretStart = start + tag.open.length;
    final int caretEnd = caretStart + selected.length;
    writeTextValue(
      _fieldId,
      next,
      selectionStart: caretStart,
      selectionEnd: caretEnd,
    );
    _push('text ${tag.label.toLowerCase()}', next);
  }

  /// Colour tags are inserted, never wrapped: MiniMessage colours run until the
  /// next colour tag or a reset, which is how authors expect them to behave.
  void _insertColor(String hex) {
    final String normalized = _normalizeHex(hex);
    if (normalized.isEmpty) return;
    final String text = _text;
    final (int, int)? selection = readTextSelection(_fieldId);
    final int length = text.length;
    final int start = (selection?.$1 ?? length).clamp(0, length);
    final String tag = '<$normalized>';
    final String next = text.replaceRange(start, start, tag);
    final int caret = start + tag.length;
    writeTextValue(_fieldId, next, selectionStart: caret, selectionEnd: caret);
    setState(() => _swatch = normalized);
    _push('text colour', next);
  }

  static String _normalizeHex(String raw) {
    final String trimmed = raw.trim().toLowerCase();
    final String body = trimmed.startsWith('#')
        ? trimmed.substring(1)
        : trimmed;
    if (body.length != 6) return '';
    for (int i = 0; i < body.length; i++) {
      if (!'0123456789abcdef'.contains(body[i])) return '';
    }
    return '#$body';
  }

  @override
  Widget build(BuildContext context) => Component.fragment(<Widget>[
    Field(
      label: component.label ?? huiText('Text'),
      forId: _fieldId,
      tip:
          '${huiText('One stacked line per line break; each is parsed on its own.')} '
          '${huiText('Legacy codes work too: &0-&f for colour, &k obfuscated, &l bold, &m strikethrough, &n underlined, &o italic, &r reset.')}',
      problems: component.issues,
      child: Component.fragment(<Widget>[
        _toolbar(),
        TextArea(
          id: _fieldId,
          value: _text,
          rows: 4,
          fullWidth: true,
          resize: TextAreaResize.vertical,
          placeholder: huiText('&6&lShop\n&7Click to browse'),
          onChanged: (String raw) => _push('text', raw),
        ),
      ]),
    ),
    Field(
      label: huiText('In-game preview'),
      child: McTextPreview(raw: _text, emoji: component.emoji),
    ),
  ]);

  Widget _toolbar() => ActionRow(
    dense: true,
    label: huiText('Text formatting'),
    leading: <Widget>[_colorControl()],
    more: <AppMenuEntry>[
      for (final _TagAction tag in _extras)
        AppMenuEntry(
          huiText(tag.label),
          () => _apply(tag),
          icon: tag.icon(),
          description: tag.hint == null ? null : huiText(tag.hint!),
        ),
    ],
    secondary: <RowAction>[
      for (final _TagAction tag in _styles)
        RowAction(
          huiText(tag.label),
          () => _apply(tag),
          icon: tag.icon(),
          iconOnly: true,
        ),
    ],
  );

  /// The kit swatch: picking inserts a `<#rrggbb>` tag at the caret.
  Widget _colorControl() => ColorSwatch(
    label: huiText('Insert a colour tag'),
    rgb: _swatch,
    commit: true,
    onPicked: _insertColor,
  );
}
