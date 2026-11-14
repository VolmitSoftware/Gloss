/// What the server found wrong with the workspace, as a section of the Server
/// sheet.
///
/// These are the plugin's own findings, not the editor's: `/gloss check` runs
/// the same rules and reports the same codes, and they travel in the notes
/// section's `warnings`. The editor's own validation stays in the validation
/// panel, because the two answer different questions: this one knows about
/// documents the browser never opened.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import '../../l10n/hui_localizations.dart';
import '../../logic/sync_problems.dart';
import '../../ui/action_row.dart';
import '../../ui/badge.dart';
import '../../ui/empty_state.dart';
import '../../ui/tone.dart';

/// At most this many findings render; the rest are counted.
const int _maxRows = 120;

class ServerProblemsList extends StatelessWidget {
  const ServerProblemsList({
    required this.problems,
    this.onOpenDocument,
    super.key,
  });

  final SyncProblems problems;

  /// Opens the document a finding names, when the workspace has it.
  final void Function(String kind, String id)? onOpenDocument;

  @override
  Widget build(BuildContext context) {
    if (problems.isEmpty) {
      return EmptyState(
        message: huiText('The server found nothing to fix.'),
        icon: ArcaneIcon.check(),
      );
    }
    final List<Widget> rows = <Widget>[];
    int shown = 0;
    for (final SyncProblemGroup group in problems.groups) {
      for (final SyncProblem problem in group.problems) {
        if (shown == _maxRows) break;
        shown++;
        rows.add(_row(group, problem));
      }
    }
    final int hidden = problems.all.length - shown;
    return dom.div(
      attributes: const <String, String>{'data-server-problems': ''},
      <Widget>[
        ...rows,
        if (hidden > 0)
          dom.p(<Widget>[
            Text(
              huiPlural(
                'sync.more_rows',
                hidden,
                oneEnglish: '{count} more',
                otherEnglish: '{count} more',
              ),
            ),
          ]),
      ],
    );
  }

  Widget _row(SyncProblemGroup group, SyncProblem problem) => ActionRow(
    dense: true,
    leading: <Widget>[
      StatusBadge(
        switch (problem.severity) {
          SyncProblemSeverity.error => huiText('Error'),
          SyncProblemSeverity.warning => huiText('Warning'),
          SyncProblemSeverity.info => huiText('Info'),
        },
        tone: switch (problem.severity) {
          SyncProblemSeverity.error => BadgeTone.error,
          SyncProblemSeverity.warning => BadgeTone.warning,
          SyncProblemSeverity.info => BadgeTone.info,
        },
      ),
      dom.span(<Widget>[
        Text(
          group.isWorkspace
              ? problem.message
              : '${group.label}: ${problem.message}',
        ),
      ]),
    ],
    secondary: <RowAction>[
      if (!group.isWorkspace && onOpenDocument != null)
        RowAction(
          huiText('Open'),
          () => onOpenDocument!(group.kind, group.id),
          icon: ArcaneIcon.crosshair(),
          iconOnly: true,
        ),
    ],
  );
}
