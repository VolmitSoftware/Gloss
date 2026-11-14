/// Review changes lists deletions as their own group: before
/// Publish, what it deletes on the server never hides among edits.
library;

import 'package:gloss_editor/services/editor_sync_controller.dart';
import 'package:test/test.dart';

SyncChange _change(String id, SyncDocState state, {bool image = false}) =>
    SyncChange(
      key: id,
      kind: image ? 'image' : 'hologram',
      id: id,
      name: id,
      state: state,
      image: image,
    );

void main() {
  test('should put every deletion in its own group after the rest', () {
    final ({List<SyncChange> writes, List<SyncChange> deletes}) groups =
        reviewGroups(<SyncChange>[
          _change('a', SyncDocState.deleted),
          _change('b', SyncDocState.changedHere),
          _change('c.png', SyncDocState.deleted, image: true),
          _change('d', SyncDocState.added),
          _change('e', SyncDocState.changedOnServer),
        ]);
    expect(
      groups.writes.map((SyncChange change) => change.id),
      <String>['b', 'd', 'e'],
    );
    expect(
      groups.deletes.map((SyncChange change) => change.id),
      <String>['a', 'c.png'],
    );
  });

  test('should keep a group empty when nothing is deleted', () {
    final ({List<SyncChange> writes, List<SyncChange> deletes}) groups =
        reviewGroups(<SyncChange>[_change('b', SyncDocState.changedHere)]);
    expect(groups.deletes, isEmpty);
    expect(groups.writes, hasLength(1));
  });
}
