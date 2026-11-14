/// An on/off setting on shadcn's horizontal Field: the label (and its tip)
/// with a hint or a warning under it on the left, the switch at the end. The
/// label points at the switch, which takes the field's id, description
/// links and disabled state from the library; its accessible name keeps the
/// owner's name ([FieldOwner]).
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';

import '../logic/validation.dart' show HuiIssue, HuiSeverity;
import 'density.dart';
import 'field.dart' show FieldOwner, distinctIssues, issueNote;
import 'info_tip.dart';

class SwitchField extends StatelessWidget {
  const SwitchField({
    required this.label,
    required this.value,
    required this.onChanged,
    this.hint,
    this.tip,
    this.docKey,
    this.warning,
    this.problems = const <HuiIssue>[],
    this.id,
    super.key,
  });

  final String label;
  final bool value;

  /// Null shows the switch disabled.
  final void Function(bool value)? onChanged;
  final String? hint;
  final String? tip;
  final String? docKey;

  /// A line in the warning colour (a setting another one overrides).
  final String? warning;
  final List<HuiIssue> problems;
  final String? id;

  @override
  Widget build(BuildContext context) {
    final List<HuiIssue> issues = distinctIssues(problems);
    final Widget name = ArcaneFieldLabel(
      label,
      trailing: <Widget>[InfoButton(tip: tip, docKey: docKey)],
    );
    final List<Widget> below = <Widget>[
      if (hint != null && hint!.trim().isNotEmpty)
        ArcaneFieldDescription(hint!),
      if (warning != null)
        ArcaneFieldDescription(
          warning!,
          classes: const <String>['ui-field-problem', 'is-warning'],
        ),
      for (final HuiIssue issue in issues) issueNote(issue),
    ];
    // A bare row centres the switch on the label; with lines under the
    // label the switch stays level with its first line.
    return ArcaneFormField(
      controlId: id,
      orientation: FieldOrientation.horizontal,
      disabled: onChanged == null,
      invalid: issues.any(
        (HuiIssue issue) => issue.severity == HuiSeverity.error,
      ),
      children: <Widget>[
        if (below.isEmpty)
          name
        else
          ArcaneFieldContent(children: <Widget>[name, ...below]),
        ArcaneToggleSwitch(
          ariaLabel: FieldOwner.accessibleName(context, label),
          value: value,
          size: controlSize(DensityScope.of(context)),
          onChanged: onChanged,
        ),
      ],
    );
  }
}
