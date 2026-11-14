/// Everything the code pane paints that has no state of its own.
///
/// Split off [CodeEditorView] so the pane's file stays about the buffer: sync,
/// commit, keyboard, hover hit-testing. Each widget here takes the values it
/// draws and hands its interactions straight back; none reads the store or the
/// buffer. The toolbar, notices and issue rows are kit pieces; the completion
/// list and the key card float at measured points, which no library surface
/// does, so they are drawn here on popover tokens.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:arcane_jaspr/core/dom_value.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component, EventCallback;

import '../../logic/validation.dart';
import '../../ui/action_row.dart';
import '../../ui/alert.dart';
import '../../ui/badge.dart';
import '../../ui/field.dart' show issueNote;
import '../../ui/mode.dart';
import '../../ui/tone.dart';
import 'code_completion.dart';
import 'code_hover.dart';

import 'package:gloss_editor/l10n/hui_localizations.dart';

/// The one place the pane says, in words, whether the document has the text.
///
/// [saved] is true only just after a save, so "Saved" confirms something that
/// happened rather than labelling every clean buffer.
StatusBadge huiJsonStateBadge({required bool dirty, required bool saved}) =>
    StatusBadge(
      dirty
          ? huiText('Unsaved changes')
          : saved
          ? huiText('Saved')
          : huiText('Matches the document'),
      tone: dirty
          ? BadgeTone.warning
          : saved
          ? BadgeTone.ok
          : BadgeTone.neutral,
      attributes: const <String, String>{'aria-live': 'polite'},
    );

/// A refused save or a format that could not parse. Raised only by something
/// the user did; the text stays as typed.
class HuiJsonErrorAlert extends StatelessWidget {
  const HuiJsonErrorAlert(this.message, {super.key});

  final String message;

  @override
  Widget build(BuildContext context) => AppAlert(
    tone: AlertTone.error,
    live: true,
    message: huiText('{message} Your text is untouched.', <String, Object?>{
      'message': message,
    }),
  );
}

/// A standing statement about the buffer, with the choices that answer it.
class HuiJsonNotice extends StatelessWidget {
  const HuiJsonNotice({
    required this.message,
    this.secondary = const <RowAction>[],
    this.cancel,
    this.primary,
    this.warning = false,
    super.key,
  });

  final String message;
  final List<RowAction> secondary;
  final RowAction? cancel;
  final RowAction? primary;
  final bool warning;

  @override
  Widget build(BuildContext context) => AppAlert(
    tone: warning ? AlertTone.warning : AlertTone.info,
    live: true,
    message: message,
    action: ActionRow(
      dense: true,
      secondary: secondary,
      cancel: cancel,
      primary: primary,
    ),
  );
}

/// The Ctrl+Space list, floated at the caret.
///
/// Rows accept on `mousedown` rather than `click`: a click would move focus off
/// the textarea first, and the caret the insertion is measured against would be
/// gone by the time the handler ran.
class HuiJsonCompletionPopup extends StatelessWidget {
  const HuiJsonCompletionPopup({
    required this.items,
    required this.index,
    required this.x,
    required this.y,
    required this.onAccept,
    required this.onHover,
    super.key,
  });

  final List<HuiCompletion> items;
  final int index;

  /// Viewport coordinates of the caret the popup hangs from.
  final double x;
  final double y;

  final void Function(int index) onAccept;
  final void Function(int index) onHover;

  @override
  Widget build(BuildContext context) => dom.div(
    classes: 'hui-json-complete',
    attributes: <String, String>{
      'role': 'listbox',
      'aria-label': huiText('JSON completion'),
    },
    styles: dom.Styles(
      raw: <String, String>{
        'left': 'clamp(8px, ${x.round()}px, calc(100vw - 348px))',
        'top': '${y.round()}px',
      },
    ),
    <Widget>[for (int i = 0; i < items.length; i++) _row(items[i], i)],
  );

  Widget _row(HuiCompletion item, int at) => dom.div(
    classes: <String>[
      'hui-json-complete-row',
      if (at == index) 'is-active',
      if (item.isDefault) 'is-default',
    ].join(' '),
    attributes: <String, String>{
      'role': 'option',
      'aria-selected': at == index ? 'true' : 'false',
    },
    events: <String, EventCallback>{
      'mousedown': (Object? event) {
        domPreventDefault(event);
        onAccept(at);
      },
      'mousemove': (Object? event) => onHover(at),
    },
    <Widget>[
      dom.div(classes: 'hui-json-complete-head', <Widget>[
        dom.code(classes: 'hui-json-complete-label', <Widget>[
          Text(item.label),
        ]),
        dom.span(classes: 'hui-json-complete-detail', <Widget>[
          Text(item.detail),
        ]),
      ]),
      if (item.description.isNotEmpty)
        dom.span(classes: 'hui-json-complete-desc', <Widget>[
          Text(item.description),
        ]),
    ],
  );
}

/// The key explanation, floated under the hovered key.
///
/// Not the library tooltip: that one opens from a trigger element, and the
/// keys here are spans under a transparent textarea that never receive a
/// pointer event. The view hit-tests them and places this card itself.
class HuiJsonHoverCard extends StatelessWidget {
  const HuiJsonHoverCard({
    required this.doc,
    required this.x,
    required this.y,
    super.key,
  });

  final HuiCodeKeyDoc doc;
  final double x;
  final double y;

  @override
  Widget build(BuildContext context) => dom.div(
    classes: 'hui-json-hovercard',
    attributes: const <String, String>{'role': 'tooltip'},
    styles: dom.Styles(
      raw: <String, String>{
        'left': 'clamp(8px, ${x.round()}px, calc(100vw - 336px))',
        'top': '${y.round()}px',
      },
    ),
    <Widget>[
      dom.strong(classes: 'hui-json-hovercard-title', <Widget>[
        Text(doc.title),
      ]),
      dom.code(
        classes: 'hui-json-hovercard-path',
        attributes: const <String, String>{'dir': 'ltr'},
        <Widget>[Text(doc.path)],
      ),
      dom.span(<Widget>[Text(doc.body)]),
      if (doc.detail.isNotEmpty)
        dom.span(classes: 'hui-json-hovercard-detail', <Widget>[
          Text(doc.detail),
        ]),
      if (doc.citation != null)
        AdvancedOnly(
          child: dom.code(
            classes: 'hui-json-hovercard-path',
            attributes: const <String, String>{'dir': 'ltr'},
            <Widget>[Text(doc.citation!)],
          ),
        ),
    ],
  );
}

/// The heading of an issue group, as the validation panel words it.
String huiJsonSeverityLabel(HuiSeverity severity) => switch (severity) {
  HuiSeverity.error => huiText('Errors'),
  HuiSeverity.warning => huiText('Warnings'),
  HuiSeverity.info => huiText('Notes'),
};

/// The validation strip under the editor: the document's issues, not the
/// buffer's, so nothing here moves until a save lands. Grouped by severity
/// under plain headings, one kit problem line per issue.
class HuiJsonIssueList extends StatelessWidget {
  const HuiJsonIssueList(this.issues, {super.key});

  final List<HuiIssue> issues;

  @override
  Widget build(BuildContext context) => dom.section(
    classes: 'hui-json-issues',
    attributes: <String, String>{'aria-label': huiText('Validation')},
    <Widget>[
      if (issues.isEmpty)
        ArcaneFieldDescription(huiText('No issues'))
      else
        for (final HuiSeverity severity in HuiSeverity.values)
          ..._group(severity, <HuiIssue>[
            for (final HuiIssue issue in issues)
              if (issue.severity == severity) issue,
          ]),
    ],
  );

  List<Widget> _group(HuiSeverity severity, List<HuiIssue> group) {
    if (group.isEmpty) return const <Widget>[];
    return <Widget>[
      dom.h3(classes: 'ui-section-title', <Widget>[
        Text(
          huiText('{severityLabel} ({length})', <String, Object?>{
            'severityLabel': huiJsonSeverityLabel(severity),
            'length': group.length,
          }),
        ),
      ]),
      for (final HuiIssue issue in group)
        issueNote(
          issue,
          child: Component.fragment(<Widget>[
            dom.code(
              attributes: const <String, String>{'dir': 'ltr'},
              <Widget>[Text(issue.path)],
            ),
            Text(' ${issue.message}'),
          ]),
        ),
    ];
  }
}
