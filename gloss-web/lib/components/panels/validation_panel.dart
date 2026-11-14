/// Side sheet listing every semantic issue in the open document, grouped by
/// severity.
///
/// Issues come from the document's validator, which encodes runtime behavior
/// directly rather than treating the shipped JSON schema as executable truth.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component, ListenableBuilder;

import '../../logic/validation.dart';
import '../../state/editor_store.dart';
import '../../ui/density.dart';
import '../../ui/empty_state.dart';
import '../../ui/sheet.dart';

import 'package:gloss_editor/l10n/hui_localizations.dart';

class ValidationPanel extends StatelessWidget {
  const ValidationPanel({
    required this.store,
    required this.isOpen,
    required this.onClose,
    super.key,
  });

  final EditorStore store;
  final bool isOpen;
  final VoidCallback onClose;

  @override
  Widget build(BuildContext context) => Sheet(
    id: 'hui-validation',
    open: isOpen,
    onClose: onClose,
    side: SheetSide.end,
    dense: true,
    title: huiText('Validation'),
    child: ListenableBuilder(
      listenable: store,
      builder: (BuildContext inner) =>
          dom.div(isOpen ? _body(DensityScope.of(inner)) : const <Widget>[]),
    ),
  );

  List<Widget> _body(Density density) {
    final List<HuiIssue> issues = store.issues;
    if (issues.isEmpty) {
      return <Widget>[
        EmptyState(
          message: huiText('No validation issues'),
          icon: ArcaneIcon.check(),
        ),
      ];
    }
    return <Widget>[
      for (final HuiSeverity severity in HuiSeverity.values)
        ..._group(severity, issues, density),
    ];
  }

  List<Widget> _group(
    HuiSeverity severity,
    List<HuiIssue> all,
    Density density,
  ) {
    final List<HuiIssue> group = all
        .where((HuiIssue issue) => issue.severity == severity)
        .toList();
    if (group.isEmpty) return const <Widget>[];
    return <Widget>[
      dom.h3(<Widget>[
        Component.text(
          huiText("{severityLabel} ({length})", <String, Object?>{
            'severityLabel': _severityLabel(severity),
            'length': group.length,
          }),
        ),
      ]),
      dom.div(
        attributes: const <String, String>{'role': 'list'},
        <Widget>[for (final HuiIssue issue in group) _row(issue, density)],
      ),
    ];
  }

  Widget _row(HuiIssue issue, Density density) {
    final String? componentId = issue.componentId;
    return dom.div(
      attributes: const <String, String>{'role': 'listitem'},
      <Widget>[
        // The demo recorder reads messages through this hook.
        dom.p(
          attributes: const <String, String>{'data-validation-message': ''},
          <Widget>[Component.text(issue.message)],
        ),
        if (issue.fix != null)
          dom.p(<Widget>[
            Component.text(
              huiText("Fix: {value}", <String, Object?>{'value': issue.fix!}),
            ),
          ]),
        dom.p(<Widget>[
          dom.code(
            attributes: const <String, String>{'dir': 'ltr'},
            <Widget>[Component.text(issue.path)],
          ),
        ]),
        if (componentId != null)
          Button.ghost(
            size: buttonSize(density),
            onPressed: () {
              store.select(componentId);
              onClose();
            },
            icon: ArcaneIcon.crosshair().sized(controlIconSize),
            label: componentId,
            attributes: <String, String>{
              'aria-label': huiText(
                "Select component {componentId}",
                <String, Object?>{'componentId': componentId},
              ),
            },
          ),
      ],
    );
  }

  String _severityLabel(HuiSeverity severity) => switch (severity) {
    HuiSeverity.error => huiText('Errors'),
    HuiSeverity.warning => huiText('Warnings'),
    HuiSeverity.info => huiText('Notes'),
  };
}
