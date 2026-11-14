/// The Server sheet: the current pairing in one right sheet.
///
/// In a server's workspace: the state, the server's name and counts, when
/// the pairing ends and what it allows (fingerprint and addresses in
/// Advanced), Review changes with the choice for each document that moved on
/// both sides, the server's Problems and History, and Sync, Disconnect,
/// Forget server and Publish in the footer. A pairing that ended shows
/// "Pairing ended." with a New link field. The browser workspace has no
/// sheet: its Server button opens Pair a server.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import '../../config/kind_groups.dart';
import '../../doctype/doctype.dart';
import '../../l10n/hui_localizations.dart';
import '../../services/editor_sync.dart';
import '../../services/editor_sync_controller.dart';
import '../../services/server/scope_words.dart';
import '../../services/server/server_connection.dart';
import '../../services/server/server_profiles.dart';
import '../../ui/action_row.dart';
import '../../ui/alert.dart';
import '../../ui/badge.dart';
import '../../ui/field.dart';
import '../../ui/field_group.dart';
import '../../ui/mode.dart';
import '../../ui/save_state.dart';
import '../../ui/section.dart';
import '../../ui/sheet.dart';
import '../../ui/tone.dart';
import '../panels/history_panel.dart';
import '../panels/problems_panel.dart';
import '../shell/editor_sync_bar.dart';
import 'connection_words.dart';
import 'pair_link_form.dart';
import 'start_parts.dart';

/// At most this many changes render in Review changes; the rest are counted.
const int _maxChangeRows = 200;

String _bytes(int bytes) {
  if (bytes < 1024) return '$bytes B';
  if (bytes < 1024 * 1024) return '${(bytes / 1024).toStringAsFixed(1)} KB';
  return '${(bytes / (1024 * 1024)).toStringAsFixed(1)} MB';
}

BadgeTone statusTone(EditorSyncStatus status) =>
    switch (editorSyncStatusTone(status)) {
      SaveTone.quiet => BadgeTone.neutral,
      SaveTone.busy => BadgeTone.info,
      SaveTone.done => BadgeTone.ok,
      SaveTone.attention => BadgeTone.warning,
      SaveTone.failed => BadgeTone.error,
    };

String _when(DateTime time) {
  final DateTime local = time.toLocal();
  String two(int value) => value.toString().padLeft(2, '0');
  return '${local.year}-${two(local.month)}-${two(local.day)} '
      '${two(local.hour)}:${two(local.minute)}';
}

class ServerSheet extends StatelessWidget {
  const ServerSheet({
    required this.open,
    required this.onClose,
    required this.controller,
    required this.onPairCode,
    required this.onForget,
    super.key,
  });

  final bool open;
  final void Function() onClose;

  /// The paired server's workspace.
  final EditorSyncController controller;

  /// A pasted pairing code: the pairing card takes it.
  final void Function(String code) onPairCode;

  /// Forget server, after asking.
  final void Function() onForget;

  static const String sheetId = 'gloss-server-sheet';

  @override
  Widget build(BuildContext context) {
    final EditorSyncController sync = controller;
    // Closed, the sheet renders nothing: Review changes walks every
    // document, which the shell must not pay for on each rebuild. Built
    // before the footer, so Publish counts what Review just listed.
    final Widget body = !open ? const dom.div(<Widget>[]) : _paired(sync);
    return Sheet(
      id: sheetId,
      open: open,
      title: huiText('Server'),
      onClose: onClose,
      // A pairing that ended offers only the New link field until a new
      // link is paired: no Publish to press in vain.
      footer: sync.status == EditorSyncStatus.ended ? null : _footer(sync),
      child: body,
    );
  }

  DialogFooter _footer(EditorSyncController sync) {
    final EditorSyncStatus status = sync.status;
    final ConnectionPhase phase = sync.connectionStatus.phase;
    final bool connected =
        phase == ConnectionPhase.online ||
        phase == ConnectionPhase.connecting ||
        phase == ConnectionPhase.reconnecting;
    final int changes = sync.pendingChangeCount;
    return DialogFooter(
      secondary: <RowAction>[
        if (status != EditorSyncStatus.ended &&
            status != EditorSyncStatus.elsewhere)
          connected
              ? RowAction(
                  huiText('Disconnect'),
                  sync.disconnect,
                  attributes: const <String, String>{
                    'data-sync-action': 'disconnect',
                  },
                )
              : RowAction(
                  huiText('Reconnect'),
                  sync.reconnect,
                  attributes: const <String, String>{
                    'data-sync-action': 'reconnect',
                  },
                ),
        if (sync.connectionStatus.online)
          RowAction(
            huiText('Sync'),
            sync.sync,
            attributes: const <String, String>{'data-sync-action': 'sync'},
          ),
      ],
      primary: RowAction(
        huiText('Publish'),
        canPublishEditorSyncStatus(status) && changes > 0 ? sync.publish : null,
        loading: sync.publishing,
        attributes: const <String, String>{'data-sync-action': 'publish'},
      ),
    );
  }

  Widget _status(EditorSyncController sync) {
    final EditorSyncStatus status = sync.status;
    final ConnectionStatus connection = sync.connectionStatus;
    return dom.div(
      attributes: <String, String>{
        'data-sync-status': status.name,
        'role': 'status',
        'aria-live': 'polite',
      },
      <Widget>[
        stack(spacing: 4, <Widget>[
          Wrap(
            spacing: 8,
            runSpacing: 4,
            crossAxisAlignment: WrapCrossAlignment.center,
            children: <Widget>[
              StatusBadge(
                editorSyncStatusLabel(status),
                tone: statusTone(status),
                dot: !isTerminalEditorSyncStatus(status),
              ),
              if (connection.online && connection.path != null)
                AdvancedOnly(
                  child: StatusBadge(
                    pathLabel(connection.path!),
                    attributes: const <String, String>{
                      'data-testid': 'server-path',
                    },
                  ),
                ),
            ],
          ),
          if (status != EditorSyncStatus.ended)
            Text(sync.statusDetail, size: FontSize.sm),
        ]),
      ],
    );
  }

  Widget _paired(EditorSyncController sync) {
    final EditorSyncStatus status = sync.status;
    final ServerProfile? profile = sync.profile;
    final ServerToken? token = sync.token;
    final List<SyncChange> changes = sync.changes;
    final ({List<SyncChange> writes, List<SyncChange> deletes}) review =
        reviewGroups(changes);
    final Map<String, int> readOnly = sync.readOnlyKinds;
    final SyncImageWeight? weight = sync.serverImageWeight;
    final bool ended = status == EditorSyncStatus.ended;
    return stack(spacing: 16, <Widget>[
      // Ended says it once, in the alert below.
      if (!ended) _status(sync),
      if (ended)
        stack(spacing: 8, <Widget>[
          AppAlert(
            tone: AlertTone.error,
            title: huiText('Pairing ended.'),
            message: sync.statusDetail,
          ),
          PairLinkForm(
            id: 'server-new-link',
            label: huiText('New link'),
            onCode: onPairCode,
          ),
        ]),
      if (sync.choiceCount > 0)
        AppAlert(
          tone: AlertTone.warning,
          message: huiText(
            'Pick a copy for each change marked below. Publish skips them until then.',
          ),
        ),
      // Nothing to review: the status line already says so.
      if (!ended && changes.isNotEmpty)
        AppSection(
          title: huiText('Review changes'),
          count: changes.length,
          memoryKey: sync.reviewFirst ? null : 'server.changes',
          open: true,
          children: <Widget>[
            ..._changeRows(sync, review.writes),
            // Deletions always get their own heading, so Publish never
            // removes server files the owner did not see listed.
            if (review.deletes.isNotEmpty)
              dom.div(
                attributes: const <String, String>{
                  'data-review-group': 'deleted',
                },
                <Widget>[
                  FieldGroup(
                    title: SyncDocState.deleted.label,
                    count: review.deletes.length,
                    children: _changeRows(sync, review.deletes),
                  ),
                ],
              ),
          ],
        ),
      AppSection(
        title: huiText('Pairing'),
        memoryKey: 'server.pairing',
        children: <Widget>[
          Field(label: huiText('Server'), child: Text(sync.serverName)),
          Field(
            label: huiText('Documents'),
            child: Text('${sync.documentCount}'),
          ),
          Field(
            label: huiText('Images'),
            child: Text(
              weight == null
                  ? '${sync.imageCount}'
                  : '${weight.count} (${_bytes(weight.bytes)})',
            ),
          ),
          if (readOnly.isNotEmpty)
            Field(
              label: huiText('Read only here'),
              tip: huiText(
                'This editor has no form for these kinds. They stay on the server unchanged.',
              ),
              child: Text(
                readOnly.entries
                    .map((MapEntry<String, int> e) => '${e.key} ${e.value}')
                    .join(', '),
              ),
            ),
          if (token != null)
            Field(
              label: huiText('Pairing ends'),
              tip: huiText(
                'Each use restarts the timer; a pairing not used for that long ends.',
              ),
              child: Text(
                huiText('{time} unless used', <String, Object?>{
                  'time': _when(token.expiresAt),
                }),
              ),
            ),
          if (token != null)
            Field(
              label: huiText('Allows'),
              child: lineList(
                scopeLines(token.scopes, folders: token.folders),
                attributes: const <String, String>{
                  'data-testid': 'server-scopes',
                },
              ),
            ),
          if (profile != null)
            AdvancedOnly(
              child: stack(spacing: 8, <Widget>[
                Field(
                  label: huiText('Fingerprint'),
                  tip: huiText('Must match what /gloss editor status prints.'),
                  child: codeText(profile.fingerprint),
                ),
                if (profile.directUrl != null)
                  Field(
                    label: huiText('Direct'),
                    child: codeText(profile.directUrl!),
                  ),
                if (profile.relayUrl != null)
                  Field(
                    label: huiText('Relay'),
                    child: codeText(profile.relayUrl!),
                  ),
                if (token != null)
                  Field(
                    label: huiText('Token'),
                    child: codeText(token.tokenId),
                  ),
              ]),
            ),
          ActionRow(
            label: huiText('Pairing'),
            secondary: <RowAction>[
              RowAction(
                huiText('Forget server'),
                onForget,
                destructive: true,
                attributes: const <String, String>{
                  'data-sync-action': 'forget',
                },
              ),
            ],
          ),
        ],
      ),
      AppSection(
        title: huiText('Problems'),
        count: sync.problems.all.length,
        memoryKey: 'server.problems',
        open: false,
        children: <Widget>[
          ServerProblemsList(
            problems: sync.problems,
            onOpenDocument: (String kind, String id) {
              if (sync.openDocument(kind, id)) onClose();
            },
          ),
        ],
      ),
      AppSection(
        title: huiText('History'),
        count: sync.history.documents.length,
        memoryKey: 'server.history',
        open: false,
        children: <Widget>[
          ServerHistoryList(
            history: sync.history,
            load: sync.historyVersion,
            restore: sync.restoreHistoryVersion,
          ),
        ],
      ),
    ]);
  }

  /// At most [_maxChangeRows] rows of [changes], then how many more.
  List<Widget> _changeRows(
    EditorSyncController sync,
    List<SyncChange> changes,
  ) => <Widget>[
    for (final SyncChange change in changes.take(_maxChangeRows))
      _changeRow(sync, change),
    if (changes.length > _maxChangeRows)
      dom.p(<Widget>[
        Text(
          huiPlural(
            'sync.more_rows',
            changes.length - _maxChangeRows,
            oneEnglish: '{count} more',
            otherEnglish: '{count} more',
          ),
        ),
      ]),
  ];

  /// The kind as the library names it; an unknown wire kind as it is.
  static String _kindName(SyncChange change) {
    if (change.image) return huiText('Image');
    final DocumentTypeAdapter? type = DocumentTypeRegistry.byWireKind(
      change.kind,
    );
    return type == null ? change.kind : kindSingular(type.kind);
  }

  /// A changed document or image: its state and name, then Open; a choice
  /// puts its two buttons on a line of their own, so a narrow sheet never
  /// squeezes them.
  Widget _changeRow(EditorSyncController sync, SyncChange change) {
    final String? docId = change.docId;
    final bool choice = change.state.needsChoice;
    return dom.div(
      attributes: <String, String>{
        'data-sync-change': change.state.name,
        'data-sync-key': change.key,
      },
      <Widget>[
        ActionRow(
          dense: true,
          leading: <Widget>[
            ?syncDocBadge(change.state),
            dom.span(<Widget>[
              Text(change.image ? change.id : change.name, size: FontSize.sm),
              const Text(' '),
              mutedText(_kindName(change)),
            ]),
          ],
          secondary: <RowAction>[
            if (docId != null && !choice)
              RowAction(huiText('Open'), () {
                sync.store.openDocument(docId);
                onClose();
              }),
          ],
        ),
        if (choice)
          ActionRow(
            dense: true,
            secondary: <RowAction>[
              RowAction(
                huiText('Keep mine'),
                () => sync.keepMine(change.key),
                attributes: const <String, String>{'data-sync-choice': 'mine'},
              ),
              RowAction(
                huiText('Use server copy'),
                () => sync.useServer(change.key),
                attributes: const <String, String>{
                  'data-sync-choice': 'server',
                },
              ),
            ],
          ),
      ],
    );
  }
}
