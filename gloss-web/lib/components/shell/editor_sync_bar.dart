/// The save bar in a paired server's workspace (or in a workspace another
/// tab holds, or after a local save failed), and the library badge for a
/// document's server state. The shell mounts the bar in its bottom slot and
/// passes [syncDocBadge] to the library rows.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';

import '../../services/editor_sync_controller.dart';
import '../../state/editor_store.dart';
import '../../ui/badge.dart';
import '../../ui/save_bar.dart';
import '../../ui/save_state.dart';
import 'store_selector.dart';
import '../../ui/tone.dart';

class EditorSyncBar extends StatelessWidget {
  const EditorSyncBar({
    required this.store,
    required this.onAction,
    this.controller,
    this.waiting = false,
    super.key,
  });

  /// The paired server's workspace; null in the browser workspace.
  final EditorSyncController? controller;
  final EditorStore store;

  /// Another tab holds this browser workspace.
  final bool waiting;
  final void Function(SaveAction action) onAction;

  SaveBarState get _state {
    final String? failure = store.workspace.lastError;
    if (failure != null && failure.trim().isNotEmpty) {
      return localSaveState(
        saving: false,
        failure: failure,
        requiresReload: store.workspace.requiresReload,
      );
    }
    final EditorSyncController? sync = controller;
    if (sync != null) return sync.saveBarState;
    if (waiting) return elsewhereSaveState();
    return localSaveState(saving: false);
  }

  @override
  Widget build(BuildContext context) {
    final EditorSyncController? sync = controller;
    if (sync == null) return SaveBar(state: _state, onAction: onAction);
    // The fill's count redraws the bar alone.
    return StoreSelector<SaveBarState>(
      listenable: sync.progressChanges,
      selector: () => _state,
      builder: (BuildContext context, SaveBarState state) =>
          SaveBar(state: state, onAction: onAction),
    );
  }
}

/// Whether the shell shows the bar: in a server's workspace, in a workspace
/// another tab holds, or after the local autosave failed.
bool showEditorSyncBar({
  required EditorStore store,
  required bool server,
  required bool waiting,
}) => showSaveBar(
  synced: server || waiting,
  failure: store.workspace.lastError,
);

BadgeTone _tone(SyncDocState state) => switch (state) {
  // A local edit is the normal case: no colour of its own.
  SyncDocState.changedHere => BadgeTone.neutral,
  SyncDocState.added => BadgeTone.ok,
  SyncDocState.deleted => BadgeTone.neutral,
  SyncDocState.changedOnServer => BadgeTone.warning,
  SyncDocState.conflict => BadgeTone.error,
};

/// The badge for [state], or null when the document matches the server.
Widget? syncDocBadge(SyncDocState? state) => state == null
    ? null
    : StatusBadge(
        state.label,
        tone: _tone(state),
        attributes: <String, String>{'data-sync-doc': state.name},
      );

/// The library row badge for one workspace document id, for
/// `EditorRail(serverBadge:)`.
Widget? Function(String documentId) syncDocBadgeFor(
  EditorSyncController controller,
) =>
    (String documentId) => syncDocBadge(controller.stateOf(documentId));
