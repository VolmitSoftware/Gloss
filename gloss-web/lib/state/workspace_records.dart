/// How a workspace is laid out in a store that takes many records: a small
/// header under the workspace key, a layout record (folders and document
/// order) and one record per document. A save then writes the documents
/// that changed instead of the whole workspace. Pure Dart: the IndexedDB
/// repository and the tests' in-memory store share it.
library;

import 'dart:convert';

/// The schema of a workspace written as one record, which the workspace
/// decodes; the layered records assemble back into it.
const int wholeWorkspaceSchema = 2;

/// The schema of a header record.
const int layeredWorkspaceSchema = 3;

/// Header records are a few hundred bytes; anything longer is not one.
const int _headerLimit = 64 * 1024;

const Set<String> _headerFields = <String>{
  'workspaceId',
  'revision',
  'activeDocumentId',
};

/// The record holding the folders and the document order.
String workspaceLayoutKey(String key) => '$key#layout';

/// Every document record of [key] starts with this.
String workspaceDocumentPrefix(String key) => '$key#doc:';

String workspaceDocumentKey(String key, String documentId) =>
    '${workspaceDocumentPrefix(key)}$documentId';

/// What one save changes in a layered store.
final class WorkspaceDelta {
  const WorkspaceDelta({
    required this.header,
    this.layout,
    this.documents = const <String, String>{},
    this.removed = const <String>{},
  });

  /// The header record: workspace id, revision and active document.
  final String header;

  /// The layout record, when the folders or the document order changed.
  final String? layout;

  /// Document records that changed, by document id.
  final Map<String, String> documents;

  /// Documents no longer in the workspace.
  final Set<String> removed;
}

/// A whole workspace split into its records.
final class WorkspaceRecordSet {
  const WorkspaceRecordSet({
    required this.header,
    required this.layout,
    required this.documents,
  });

  final String header;
  final String layout;

  /// Document records by document id, in workspace order.
  final Map<String, String> documents;
}

/// Whether [raw] is a header record.
bool isWorkspaceHeader(String raw) {
  if (raw.length > _headerLimit) return false;
  try {
    final Object? decoded = jsonDecode(raw);
    return decoded is Map && decoded['schemaVersion'] == layeredWorkspaceSchema;
  } on FormatException {
    return false;
  }
}

/// [raw] decoded when it is a workspace written as one record, else null.
/// Other records sharing the store (image libraries) are told apart by
/// their first bytes before anything is decoded.
Map<String, Object?>? wholeWorkspaceOf(String raw) {
  if (!raw.startsWith('{"schemaVersion":$wholeWorkspaceSchema,')) return null;
  try {
    final Object? decoded = jsonDecode(raw);
    if (decoded is! Map<String, Object?> ||
        decoded['schemaVersion'] != wholeWorkspaceSchema ||
        decoded['documents'] is! List) {
      return null;
    }
    return decoded;
  } on FormatException {
    return null;
  }
}

/// [whole] as header, layout and document records. Document entries
/// without a string id cannot be keyed and are left out, as the workspace
/// would skip them when it reads.
WorkspaceRecordSet splitWorkspaceRecord(Map<String, Object?> whole) {
  final Map<String, Object?> header = <String, Object?>{
    'schemaVersion': layeredWorkspaceSchema,
    for (final String field in _headerFields) field: whole[field],
  };
  header['revision'] ??= 0;
  final Map<String, String> documents = <String, String>{};
  for (final Object? entry in whole['documents']! as List<Object?>) {
    if (entry is! Map || entry['id'] is! String) continue;
    documents.putIfAbsent(entry['id'] as String, () => jsonEncode(entry));
  }
  final Map<String, Object?> layout = <String, Object?>{
    for (final MapEntry<String, Object?> entry in whole.entries)
      if (entry.key != 'schemaVersion' &&
          entry.key != 'documents' &&
          !_headerFields.contains(entry.key))
        entry.key: entry.value,
    'documents': documents.keys.toList(),
  };
  return WorkspaceRecordSet(
    header: jsonEncode(header),
    layout: jsonEncode(layout),
    documents: documents,
  );
}

/// The workspace as one record from its [header], [layout] and document
/// records by id. A document the layout lists without a record comes back
/// as `null`, which the workspace counts as a damaged entry.
String assembleWorkspaceRecord({
  required String header,
  required String? layout,
  required Map<String, String> documents,
}) {
  final Object? head = jsonDecode(header);
  if (head is! Map) {
    throw const FormatException('The workspace header is not an object.');
  }
  final Object? shape = layout == null ? null : jsonDecode(layout);
  final Map<String, Object?> top = <String, Object?>{
    'schemaVersion': wholeWorkspaceSchema,
    for (final MapEntry<Object?, Object?> entry in head.entries)
      if (entry.key is String && entry.key != 'schemaVersion')
        entry.key! as String: entry.value,
    if (shape is Map)
      for (final MapEntry<Object?, Object?> entry in shape.entries)
        if (entry.key is String && entry.key != 'documents')
          entry.key! as String: entry.value,
  };
  top.putIfAbsent('folders', () => const <Object?>[]);
  final Object? order = shape is Map ? shape['documents'] : null;
  final List<String> records = <String>[
    if (order is List)
      for (final Object? id in order)
        id is String ? (documents[id] ?? 'null') : 'null',
  ];
  final String fields = jsonEncode(top);
  return '${fields.substring(0, fields.length - 1)},"documents":[${records.join(',')}]}';
}

/// The records [delta] writes under [key]; null deletes one.
Map<String, String?> workspaceDeltaRecords(
  String key,
  WorkspaceDelta delta,
) => <String, String?>{
  for (final String id in delta.removed) workspaceDocumentKey(key, id): null,
  for (final MapEntry<String, String> entry in delta.documents.entries)
    workspaceDocumentKey(key, entry.key): entry.value,
  if (delta.layout != null) workspaceLayoutKey(key): delta.layout,
  key: delta.header,
};

/// The records [records] writes under [key], the layout and header last.
/// Document records of [key] that are not named are to be deleted first.
Map<String, String> workspaceRecordsOf(
  String key,
  WorkspaceRecordSet records,
) => <String, String>{
  for (final MapEntry<String, String> entry in records.documents.entries)
    workspaceDocumentKey(key, entry.key): entry.value,
  workspaceLayoutKey(key): records.layout,
  key: records.header,
};

/// The document id of a record key under [key], or null for another record.
String? workspaceDocumentIdOf(String key, String recordKey) {
  final String prefix = workspaceDocumentPrefix(key);
  return recordKey.startsWith(prefix)
      ? recordKey.substring(prefix.length)
      : null;
}
