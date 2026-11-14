/// The connection-message surface: the join and leave lines as chat rows.
///
/// Fidelity notes, all from `ConnectionsService.java`: a section broadcasts
/// only when it is present and enabled (`Section.active`), the document and
/// section `show` conditions both gate it (`announce`), and the text is
/// rendered once per recipient rather than once per event
/// (`ConnectionsService.render`), so the scope carries `subject.*` for the
/// player who connected and `viewer.*` for whoever is reading. The preview
/// picks one sampled reader and shows the line they would see, with the
/// highest-priority matching variant already applied.
///
/// The chat window itself is a mock: colours, text functions and expressions
/// are real, but the client's own wrapping, history and opacity are not.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import '../../logic/connections_selection.dart';
import '../../logic/connections_validation.dart';
import '../../logic/gloss_text.dart';
import '../../model/model.dart';
import '../../state/editor_store.dart';
import '../../ui/action_row.dart';
import '../gloss/gloss_game_screen.dart';
import '../gloss/gloss_text_line.dart';

import 'package:gloss_editor/l10n/hui_localizations.dart';

class ConnectionsView extends StatefulWidget {
  const ConnectionsView({required this.store, super.key});

  final EditorStore store;

  @override
  State<ConnectionsView> createState() => _ConnectionsViewState();
}

class _ConnectionsViewState extends State<ConnectionsView> {
  // The shell hands every surface the one long-lived store, so the
  // subscription taken in initState is the only one this state ever needs.
  int _sampledAtMs = DateTime.now().millisecondsSinceEpoch;

  EditorStore get _store => component.store;

  @override
  void initState() {
    super.initState();
    _store.addListener(_onStoreChanged);
  }

  @override
  void dispose() {
    _store.removeListener(_onStoreChanged);
    super.dispose();
  }

  void _onStoreChanged() {
    if (mounted) setState(() {});
  }

  void _refreshSample() {
    setState(() => _sampledAtMs = DateTime.now().millisecondsSinceEpoch);
  }

  @override
  Widget build(BuildContext context) {
    final GlossConnectionsDoc? doc = _store.connectionsDoc;
    if (doc == null) {
      return glossGameEmpty(
        anchor: GlossGameAnchor.chat,
        label: huiText('Connection messages in chat'),
      );
    }
    return GlossGameScreen(
      anchor: GlossGameAnchor.chat,
      label: huiText('Connection messages in chat'),
      status: _readout(doc),
      statusTip: huiText('Read by {name}', <String, Object?>{
        'name': glossConnectionsSampleViewerName,
      }),
      actions: <RowAction>[
        RowAction(
          huiText('Refresh'),
          _refreshSample,
          icon: ArcaneIcon.refreshCcw(),
          iconOnly: true,
        ),
      ],
      child: dom.div(classes: 'hui-connections-chat', <Widget>[
        for (final String key in glossConnectionsSectionKeys) _row(doc, key),
      ]),
    );
  }

  /// One section as the sampled reader would see it; nothing when it stays
  /// silent.
  Widget _row(GlossConnectionsDoc doc, String key) {
    if (!glossConnectionsSectionBroadcasts(doc, key, nowMs: _sampledAtMs)) {
      return const dom.span(<Widget>[]);
    }
    final GlossConnectionsPresentation resolved =
        glossResolveConnectionsPresentation(doc, key);
    if (resolved.text.trim().isEmpty) return const dom.span(<Widget>[]);
    return dom.div(classes: 'hui-connections-line', <Widget>[
      GlossTextLine(
        render: renderGlossLine(
          resolved.text,
          animations: _store.workspaceAnimations,
          emoji: _store.workspaceEmoji,
          nowMs: _sampledAtMs,
          expressionSamples: glossConnectionsSamples,
        ),
      ),
    ]);
  }

  String _readout(GlossConnectionsDoc doc) {
    final int live = <String>[
      for (final String key in glossConnectionsSectionKeys)
        if (glossConnectionsSectionBroadcasts(doc, key, nowMs: _sampledAtMs))
          key,
    ].length;
    return huiText('{live} of {total} messages broadcast', <String, Object?>{
      'live': live,
      'total': glossConnectionsSectionKeys.length,
    });
  }
}
