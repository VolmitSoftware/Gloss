/// An in-memory store that keeps workspaces as records, as the IndexedDB
/// repository does, and logs what each write touched.
library;

import 'dart:async';

import 'package:gloss_editor/state/workspace_records.dart';
import 'package:gloss_editor/state/workspace_repository.dart';

final class LayeredMemoryStore
    implements WorkspaceRepository, IncrementalWorkspaceRepository {
  LayeredMemoryStore([Map<String, String>? records])
    : records = records ?? <String, String>{};

  /// Every record by key.
  final Map<String, String> records;

  /// The records each write put (a value) or deleted (null), in order.
  final List<Map<String, String?>> commits = <Map<String, String?>>[];

  /// Refuses every write while set.
  bool refuse = false;

  /// Refuses only [writeDelta] while set.
  bool refuseChanges = false;

  /// Characters written by the commits so far.
  int get written => commits.fold(
    0,
    (int sum, Map<String, String?> commit) =>
        sum +
        commit.values.fold(
          0,
          (int part, String? value) => part + (value?.length ?? 0),
        ),
  );

  @override
  Future<String?> read(String key) async {
    final String? raw = records[key];
    if (raw == null) return null;
    if (isWorkspaceHeader(raw)) {
      final Map<String, String> documents = <String, String>{
        for (final MapEntry<String, String> entry in records.entries)
          if (workspaceDocumentIdOf(key, entry.key) case final String id)
            id: entry.value,
      };
      return assembleWorkspaceRecord(
        header: raw,
        layout: records[workspaceLayoutKey(key)],
        documents: documents,
      );
    }
    if (wholeWorkspaceOf(raw) != null) await write(key, raw);
    return raw;
  }

  @override
  Future<bool> write(String key, String value) async {
    await Future<void>.delayed(Duration.zero);
    if (refuse) return false;
    final Map<String, Object?>? whole = wholeWorkspaceOf(value);
    if (whole == null) {
      _commit(<String, String?>{key: value});
      return true;
    }
    final Map<String, String> next = workspaceRecordsOf(
      key,
      splitWorkspaceRecord(whole),
    );
    _commit(<String, String?>{
      for (final String recordKey in records.keys)
        if (workspaceDocumentIdOf(key, recordKey) != null &&
            !next.containsKey(recordKey))
          recordKey: null,
      ...next,
    });
    return true;
  }

  @override
  Future<bool> writeDelta(String key, WorkspaceDelta delta) async {
    await Future<void>.delayed(Duration.zero);
    final String? current = records[key];
    if (refuse ||
        refuseChanges ||
        current == null ||
        !isWorkspaceHeader(current)) {
      return false;
    }
    _commit(workspaceDeltaRecords(key, delta));
    return true;
  }

  void _commit(Map<String, String?> commit) {
    commits.add(commit);
    commit.forEach((String key, String? value) {
      if (value == null) {
        records.remove(key);
      } else {
        records[key] = value;
      }
    });
  }
}
