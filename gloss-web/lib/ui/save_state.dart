/// What the save bar says and offers, worked out from plain facts: the
/// local autosave (always on) and, in a paired server's workspace, the
/// server. Pure Dart, so every state is unit tested; the shell feeds it from
/// the store and the sync controller.
library;

import '../l10n/hui_localizations.dart';
import '../services/editor_sync.dart'
    show EditorSyncStatus, canPublishEditorSyncStatus;

/// How the line reads: quiet (nothing to do), busy (working), attention
/// (something waits for you), failed, done (just finished).
enum SaveTone { quiet, busy, attention, failed, done }

/// The buttons the bar can show. Each carries `data-sync-action=<name>`.
enum SaveAction {
  publish,
  review,
  newLink,
  reconnect,
  takeOver,
  retrySave,
  reload;

  String get label => switch (this) {
    SaveAction.publish => huiText('Publish'),
    SaveAction.review => huiText('Review'),
    SaveAction.newLink => huiText('New link'),
    SaveAction.reconnect => huiText('Reconnect'),
    SaveAction.takeOver => huiText('Take over'),
    SaveAction.retrySave => huiText('Retry'),
    SaveAction.reload => huiText('Reload'),
  };
}

class SaveBarState {
  const SaveBarState(
    this.message,
    this.tone, {
    this.detail,
    this.hint,
    this.primary,
    this.secondary = const <SaveAction>[],
    this.busy,
    this.status,
  });

  /// The plain sync status name (`connected`, `pending`...) for the bar's
  /// `data-sync-status` hook; null for the local autosave line.
  final String? status;

  /// The state in a few words ("Connected", "Autosaved locally 09:05").
  final String message;
  final SaveTone tone;

  /// The muted rest of the line: the server project, its last message.
  final String? detail;

  /// What the primary button will do, for its tooltip.
  final String? hint;

  /// The one filled button, last in the row.
  final SaveAction? primary;
  final List<SaveAction> secondary;

  /// The action whose work is running; its button shows a spinner.
  final SaveAction? busy;

  @override
  bool operator ==(Object other) =>
      other is SaveBarState &&
      other.message == message &&
      other.tone == tone &&
      other.detail == detail &&
      other.hint == hint &&
      other.primary == primary &&
      other.busy == busy &&
      other.status == status &&
      other.secondary.length == secondary.length &&
      <int>[for (int index = 0; index < secondary.length; index++) index]
          .every((int index) => other.secondary[index] == secondary[index]);

  @override
  int get hashCode => Object.hash(
    message,
    tone,
    detail,
    hint,
    primary,
    busy,
    status,
    Object.hashAll(secondary),
  );

  @override
  String toString() =>
      '$message ($tone, $detail, $primary, $secondary, busy $busy)';
}

String _clock(DateTime time) =>
    '${time.hour.toString().padLeft(2, '0')}:'
    '${time.minute.toString().padLeft(2, '0')}';

/// The browser's own autosave. [failure] is the store's last error; with
/// [requiresReload] only a reload can recover (another tab took over).
SaveBarState localSaveState({
  required bool saving,
  String? failure,
  bool requiresReload = false,
  DateTime? savedAt,
}) {
  if (failure != null && failure.trim().isNotEmpty) {
    return SaveBarState(
      failure.trim(),
      SaveTone.failed,
      primary: requiresReload ? SaveAction.reload : SaveAction.retrySave,
    );
  }
  if (saving) return SaveBarState(huiText('Saving…'), SaveTone.busy);
  if (savedAt == null) {
    return SaveBarState(huiText('Autosaved locally'), SaveTone.quiet);
  }
  return SaveBarState(
    huiText('Autosaved locally {time}', <String, Object?>{
      'time': _clock(savedAt),
    }),
    SaveTone.quiet,
  );
}

/// Whether the bar shows at all: in a server's workspace, in a workspace
/// another tab holds, or after a local save failed. Otherwise the status
/// bar's autosave note is enough.
bool showSaveBar({required bool synced, String? failure}) =>
    synced || (failure != null && failure.trim().isNotEmpty);

/// A browser workspace another tab of this browser holds.
SaveBarState elsewhereSaveState() => SaveBarState(
  huiText('Open in another tab'),
  SaveTone.failed,
  detail: huiText('Take over to edit here.'),
  primary: SaveAction.takeOver,
  status: EditorSyncStatus.elsewhere.name,
);

/// The status name in a few words, as the bar and the Server button say it.
String editorSyncStatusLabel(EditorSyncStatus status) => switch (status) {
  EditorSyncStatus.connecting => huiText('Checking'),
  EditorSyncStatus.loading => huiText('Loading'),
  EditorSyncStatus.connected => huiText('Connected'),
  EditorSyncStatus.publishing => huiText('Publishing'),
  EditorSyncStatus.asking => huiText('Needs an answer'),
  EditorSyncStatus.applied => huiText('Published'),
  EditorSyncStatus.conflict => huiText('Needs a choice'),
  EditorSyncStatus.rejected => huiText('Rejected'),
  EditorSyncStatus.reconnecting => huiText('Not reachable'),
  EditorSyncStatus.offline => huiText('Not connected'),
  EditorSyncStatus.ended => huiText('Pairing ended'),
  EditorSyncStatus.refused => huiText('Refused'),
  EditorSyncStatus.elsewhere => huiText('Open in another tab'),
};

/// How [status] reads.
SaveTone editorSyncStatusTone(EditorSyncStatus status) => switch (status) {
  EditorSyncStatus.connected || EditorSyncStatus.offline => SaveTone.quiet,
  EditorSyncStatus.connecting ||
  EditorSyncStatus.loading ||
  EditorSyncStatus.publishing => SaveTone.busy,
  EditorSyncStatus.applied => SaveTone.done,
  EditorSyncStatus.conflict ||
  EditorSyncStatus.asking ||
  EditorSyncStatus.reconnecting => SaveTone.attention,
  EditorSyncStatus.rejected ||
  EditorSyncStatus.ended ||
  EditorSyncStatus.refused ||
  EditorSyncStatus.elsewhere => SaveTone.failed,
};

/// A paired server's workspace. [detail] is the one sentence after the
/// status (what changed, what the server said, what to do); [changes] is
/// what Publish would send; [review] offers Review (changes or choices to
/// look at).
SaveBarState syncSaveState({
  required EditorSyncStatus status,
  required String detail,
  bool publishing = false,
  int changes = 0,
  bool review = false,
}) {
  final bool canPublish = canPublishEditorSyncStatus(status);
  final bool publish = publishing || (canPublish && changes > 0);
  final SaveAction? primary = switch (status) {
    EditorSyncStatus.elsewhere => SaveAction.takeOver,
    EditorSyncStatus.ended => SaveAction.newLink,
    EditorSyncStatus.reconnecting ||
    EditorSyncStatus.offline => SaveAction.reconnect,
    EditorSyncStatus.conflict when !publish => SaveAction.review,
    _ => publish ? SaveAction.publish : null,
  };
  return SaveBarState(
    editorSyncStatusLabel(status),
    publishing ? SaveTone.busy : editorSyncStatusTone(status),
    detail: detail.trim().isEmpty ? null : detail.trim(),
    hint: canPublish
        ? huiPlural(
            'sync.publish_hint',
            changes,
            oneEnglish: 'Sends 1 change to the server.',
            otherEnglish: 'Sends {count} changes to the server.',
          )
        : null,
    primary: primary,
    secondary: <SaveAction>[
      if (review && primary != SaveAction.review) SaveAction.review,
    ],
    busy: publishing ? SaveAction.publish : null,
    status: status.name,
  );
}
