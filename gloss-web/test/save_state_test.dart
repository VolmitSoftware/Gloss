/// The save bar in a paired server's workspace: one status line, Publish as
/// the primary only with something to send, Review when a choice waits
/// (never hidden behind "No changes to publish"), Reconnect while the
/// server is away, New link once the pairing ended, Take over in a second
/// tab, and the plain status name for the browser checks.
library;

import 'package:gloss_editor/services/editor_sync.dart';
import 'package:gloss_editor/ui/save_state.dart';
import 'package:test/test.dart';

SaveBarState sync(
  EditorSyncStatus status, {
  bool publishing = false,
  int changes = 2,
  bool review = false,
  String detail = '2 changes to publish',
}) => syncSaveState(
  status: status,
  detail: detail,
  publishing: publishing,
  changes: changes,
  review: review,
);

void main() {
  test('should offer Publish only with changes and a server to take them', () {
    for (final EditorSyncStatus status in <EditorSyncStatus>[
      EditorSyncStatus.connected,
      EditorSyncStatus.loading,
      EditorSyncStatus.applied,
      EditorSyncStatus.rejected,
    ]) {
      final SaveBarState state = sync(status);
      expect(state.primary, SaveAction.publish, reason: status.name);
      expect(state.hint, 'Sends 2 changes to the server.');
    }
    expect(sync(EditorSyncStatus.connected, changes: 0).primary, isNull);
    for (final EditorSyncStatus status in <EditorSyncStatus>[
      EditorSyncStatus.connecting,
      EditorSyncStatus.publishing,
      EditorSyncStatus.refused,
    ]) {
      expect(sync(status).primary, isNull, reason: status.name);
    }
  });

  test('should keep a busy Publish as the primary with a spinner', () {
    final SaveBarState state = sync(
      EditorSyncStatus.publishing,
      publishing: true,
    );
    expect(state.primary, SaveAction.publish);
    expect(state.busy, SaveAction.publish);
    expect(state.tone, SaveTone.busy);
  });

  test('should make Review the primary when only a choice waits', () {
    final SaveBarState choice = sync(
      EditorSyncStatus.conflict,
      changes: 0,
      review: true,
      detail: '1 needs a choice',
    );
    expect(choice.primary, SaveAction.review);
    expect(choice.message, 'Needs a choice');
    expect(choice.detail, '1 needs a choice');
    final SaveBarState both = sync(
      EditorSyncStatus.conflict,
      review: true,
    );
    expect(both.primary, SaveAction.publish);
    expect(both.secondary, <SaveAction>[SaveAction.review]);
  });

  test('should offer Reconnect while the server is away', () {
    expect(sync(EditorSyncStatus.reconnecting).primary, SaveAction.reconnect);
    expect(sync(EditorSyncStatus.offline).primary, SaveAction.reconnect);
    expect(sync(EditorSyncStatus.reconnecting).message, 'Not reachable');
    expect(sync(EditorSyncStatus.connecting).message, 'Checking');
  });

  test('should say Pairing ended with New link', () {
    final SaveBarState ended = sync(
      EditorSyncStatus.ended,
      detail: 'Paste a new link from /gloss editor pair into New link.',
    );
    expect(ended.message, 'Pairing ended', reason: 'a label, not a sentence');
    expect(ended.primary, SaveAction.newLink);
    expect(ended.tone, SaveTone.failed);
    expect(ended.detail, contains('/gloss editor pair'));
  });

  test('should offer Take over in a tab that does not hold the workspace', () {
    final SaveBarState waiting = sync(EditorSyncStatus.elsewhere);
    expect(waiting.message, 'Open in another tab');
    expect(waiting.primary, SaveAction.takeOver);
    final SaveBarState local = elsewhereSaveState();
    expect(local.primary, SaveAction.takeOver);
    expect(local.status, 'elsewhere');
  });

  test('should carry the plain status name for the checks', () {
    for (final EditorSyncStatus status in EditorSyncStatus.values) {
      expect(sync(status).status, status.name);
    }
    expect(localSaveState(saving: false).status, isNull);
  });

  test('should show the bar in a server workspace or after a failed save', () {
    expect(showSaveBar(synced: true), isTrue);
    expect(showSaveBar(synced: false), isFalse);
    expect(showSaveBar(synced: false, failure: 'Storage is full.'), isTrue);
  });
}
