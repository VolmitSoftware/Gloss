/// One form row on shadcn's Field: label (with its (i) tip), the control, a
/// short hint and the problems at this field. The label sits above the
/// control; once the enclosing field group is at least 28rem wide (a wide
/// dialog, a catalog page) it moves to the left. The library gives the
/// control the field's id, links the hint and problems to it and marks it
/// invalid. A field with a runtime default shows it as a chip with a reset
/// button beside the label. Problems wait while the control is being typed
/// in ([TypingPause]), so a half-typed id does not flash red at every key.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component, InheritedComponent;

import '../l10n/hui_localizations.dart';
import '../logic/validation.dart' show HuiIssue, HuiSeverity;
import 'icon_button.dart';
import 'info_tip.dart';
import 'typing_pause.dart';

/// Names the item a repeated block of fields belongs to ("Link 2"), so each
/// control's accessible name says which one it is: "Address (Link 2)". The
/// label on screen stays "Address". Wrap one list item's section in it.
class FieldOwner extends InheritedComponent {
  const FieldOwner({required this.name, required super.child, super.key});

  final String name;

  static String? of(BuildContext context) =>
      context.dependOnInheritedComponentOfExactType<FieldOwner>()?.name;

  /// [label] with the owner's name, for an `aria-label`.
  static String accessibleName(BuildContext context, String label) {
    final String? owner = of(context);
    return owner == null
        ? label
        : huiText('{field} ({item})', <String, Object?>{
            'field': label,
            'item': owner,
          });
  }

  @override
  bool updateShouldNotify(FieldOwner oldComponent) => oldComponent.name != name;
}

/// [issues] without repeats of the same message at the same severity.
List<HuiIssue> distinctIssues(List<HuiIssue> issues) {
  final Set<String> seen = <String>{};
  return <HuiIssue>[
    for (final HuiIssue issue in issues)
      if (seen.add('${issue.severity.name}|${issue.message}')) issue,
  ];
}

/// One issue as the line under a field: the message, then its fix.
String issueLine(HuiIssue issue) {
  final String? fix = issue.fix;
  return fix == null || fix.trim().isEmpty
      ? issue.message
      : '${issue.message} $fix';
}

/// The label column beside the control once the field group is at least
/// 28rem wide (a wide dialog, a catalog page), so long forms line up.
const String fieldLabelWidth = '11.25rem';

/// The space between a label above its control and the control: the
/// inspector's dense rhythm keeps a field together and 1rem between fields
/// ([FieldStack]). Beside the control the library's column gap stays.
const ArcaneStyleData fieldRowGap = ArcaneStyleData(rowGap: Gap.sm);

/// One issue as a line under a control: an error in the error style (an
/// alert), a warning or a note in its tone. Put it straight into a field (or
/// its content) so the field links it to the control. [child] replaces the
/// text ([issueLine]) when the line carries more than words.
Widget issueNote(HuiIssue issue, {Widget? child}) {
  if (issue.severity == HuiSeverity.error) {
    return child == null
        ? ArcaneFieldError(issueLine(issue))
        : ArcaneFieldError.child(child);
  }
  final List<String> tone = <String>[
    'ui-field-problem',
    'is-${issue.severity.name}',
  ];
  return child == null
      ? ArcaneFieldDescription(issueLine(issue), classes: tone)
      : ArcaneFieldDescription.child(child, classes: tone);
}

class Field extends StatefulWidget {
  const Field({
    required this.label,
    required this.child,
    this.hint,
    this.tip,
    this.docKey,
    this.problems = const <HuiIssue>[],
    this.error,
    this.forId,
    this.required = false,
    this.defaultValue,
    this.onReset,
    this.trailing,
    super.key,
  });

  /// One to three words.
  final String label;
  final Widget child;

  /// A few words under the control. Anything longer belongs in [tip].
  final String? hint;

  /// One or two sentences behind the (i) button.
  final String? tip;

  /// A field-doc key whose body is the tip when [tip] is null.
  final String? docKey;

  /// The validation issues at this field.
  final List<HuiIssue> problems;

  /// A problem the form found itself (a taken id), shown like an error.
  final String? error;

  /// The control's id: the label becomes `<label for>`, and a library text
  /// box, text area, select or switch inside takes it when it has none.
  /// Without it the label is a title, for groups and controls that name
  /// themselves.
  final String? forId;

  /// Marks the label as required.
  final bool required;

  /// The value Gloss uses when the key is omitted, formatted for reading
  /// (`1.7`, `ease_out_cubic`). Shows as a chip beside the label.
  final String? defaultValue;

  /// Puts the field back to [defaultValue]. Null with a [defaultValue]
  /// draws the reset button disabled, so the row never shifts.
  final void Function()? onReset;

  /// A small widget at the end of the label line (a unit).
  final Widget? trailing;

  @override
  State<Field> createState() => _FieldState();
}

class _FieldState extends State<Field> with TypingPause<Field> {
  /// The chip truncates when the row is short (a three-axis default beside
  /// a long label); the reset button's name carries the whole value.
  List<Widget> _defaultTools(String value) => <Widget>[
    dom.span(classes: 'ui-field-default', <Widget>[
      Component.text(
        huiText('default {defaultValue}', <String, Object?>{
          'defaultValue': value,
        }),
      ),
    ]),
    AppIconButton(
      icon: ArcaneIcon.rotateCcw(),
      label: huiText('Reset {label} to {defaultValue}', <String, Object?>{
        'label': component.label,
        'defaultValue': value,
      }),
      tipAtEnd: true,
      onPressed: component.onReset,
    ),
  ];

  @override
  Widget build(BuildContext context) {
    final Field field = component;
    final List<HuiIssue> problems = typing
        ? const <HuiIssue>[]
        : distinctIssues(field.problems);
    final List<String> errors = <String>[
      if (!typing && field.error != null) field.error!,
      for (final HuiIssue problem in problems)
        if (problem.severity == HuiSeverity.error) issueLine(problem),
    ];
    // In a named item the label reads "Event (Subscription 2)" to assistive
    // tech and to `<label for>` lookups; the eye sees "Event".
    final String named = FieldOwner.accessibleName(context, field.label);
    final Widget text = Component.fragment(<Widget>[
      if (named == field.label)
        Component.text(field.label)
      else if (named.startsWith(field.label)) ...<Widget>[
        Component.text(field.label),
        dom.span(classes: 'ui-sr-only', <Widget>[
          Component.text(named.substring(field.label.length)),
        ]),
      ] else ...<Widget>[
        dom.span(
          attributes: const <String, String>{'aria-hidden': 'true'},
          <Widget>[Component.text(field.label)],
        ),
        dom.span(classes: 'ui-sr-only', <Widget>[Component.text(named)]),
      ],
    ]);
    final List<Widget> trailing = <Widget>[
      InfoButton(tip: field.tip, docKey: field.docKey),
      if (field.defaultValue != null || field.trailing != null)
        dom.span(classes: 'ui-field-tools', <Widget>[
          if (field.defaultValue != null) ..._defaultTools(field.defaultValue!),
          ?field.trailing,
        ]),
    ];
    // Without a control id the label names no single control (a choice
    // group, three axes): a title, not a `<label for>`.
    return Component.wrapElement(
      events: typingEvents,
      child: ArcaneFormField(
        controlId: field.forId,
        orientation: FieldOrientation.responsive,
        labelWidth: fieldLabelWidth,
        invalid: errors.isNotEmpty,
        styles: fieldRowGap,
        children: <Widget>[
          if (field.forId != null)
            ArcaneFieldLabel.child(
              text,
              required: field.required,
              trailing: trailing,
            )
          else
            ArcaneFieldTitle.child(
              text,
              required: field.required,
              trailing: trailing,
            ),
          ArcaneFieldContent(
            children: <Widget>[
              field.child,
              if (field.hint != null && field.hint!.trim().isNotEmpty)
                ArcaneFieldDescription(field.hint!),
              if (errors.isNotEmpty) ArcaneFieldError.list(errors),
              for (final HuiIssue problem in problems)
                if (problem.severity != HuiSeverity.error) issueNote(problem),
            ],
          ),
        ],
      ),
    );
  }
}
