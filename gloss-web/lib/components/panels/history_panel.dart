/// The copies the server kept of each document, as a section of the Server
/// sheet: pick a version to read it, then restore it into the open document.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import '../../l10n/hui_localizations.dart';
import '../../logic/sync_history.dart';
import '../../services/editor_sync.dart';
import '../../ui/action_row.dart';
import '../../ui/alert.dart';
import '../../ui/empty_state.dart';

class ServerHistoryList extends StatefulWidget {
  const ServerHistoryList({
    required this.history,
    required this.load,
    required this.restore,
    super.key,
  });

  final SyncHistory history;

  /// Fetches one version's JSON; null when the server no longer keeps it.
  final Future<String?> Function(SyncHistoryVersion version) load;

  /// Restores a loaded version; returns why it could not, or null.
  final String? Function(SyncHistoryVersion version, String json) restore;

  @override
  State<ServerHistoryList> createState() => _ServerHistoryListState();
}

class _ServerHistoryListState extends State<ServerHistoryList> {
  SyncHistoryVersion? _loading;
  SyncHistoryVersion? _loaded;
  String? _json;
  String? _failure;

  bool _same(SyncHistoryVersion? a, SyncHistoryVersion b) =>
      a != null && a.kind == b.kind && a.id == b.id && a.version == b.version;

  Future<void> _view(SyncHistoryVersion version) async {
    setState(() {
      _loading = version;
      _failure = null;
    });
    try {
      final String? json = await component.load(version);
      if (!mounted) return;
      setState(() {
        _loading = null;
        _loaded = json == null ? null : version;
        _json = json;
        _failure = json == null
            ? huiText('The server no longer keeps that version.')
            : null;
      });
    } on EditorSyncFailure catch (error) {
      if (!mounted) return;
      setState(() {
        _loading = null;
        _failure = error.message;
      });
    }
  }

  void _restore() {
    final SyncHistoryVersion? version = _loaded;
    final String? json = _json;
    if (version == null || json == null) return;
    final String? problem = component.restore(version, json);
    setState(() => _failure = problem);
  }

  @override
  Widget build(BuildContext context) {
    final List<SyncHistoryDocument> documents = component.history.documents;
    if (documents.isEmpty) {
      return EmptyState(
        message: huiText('No stored versions yet.'),
        icon: ArcaneIcon.history(),
      );
    }
    return dom.div(
      attributes: const <String, String>{'data-server-history': ''},
      <Widget>[
        if (_failure != null)
          AppAlert(message: _failure!, tone: AlertTone.warning, live: true),
        if (_loaded != null && _json != null) ...<Widget>[
          ActionRow(
            dense: true,
            leading: <Widget>[
              dom.strong(<Widget>[Text('${_loaded!.kind} ${_loaded!.id}')]),
            ],
            primary: RowAction(huiText('Restore'), _restore),
          ),
          dom.pre(<Widget>[Text(_json!)]),
        ],
        for (final SyncHistoryDocument document in documents)
          for (final SyncHistoryVersion version in document.versions)
            ActionRow(
              dense: true,
              leading: <Widget>[
                dom.span(<Widget>[
                  Text(
                    '${document.kind} ${document.id}: ${version.sourceLabel}',
                  ),
                ]),
              ],
              secondary: <RowAction>[
                RowAction(
                  _same(_loading, version)
                      ? huiText('Loading')
                      : huiText('View'),
                  _loading == null ? () => _view(version) : null,
                  loading: _same(_loading, version),
                ),
              ],
            ),
      ],
    );
  }
}
