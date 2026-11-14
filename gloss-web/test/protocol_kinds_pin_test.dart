import 'dart:io';

import 'package:test/test.dart';

import 'support/gloss_repository.dart';
import 'support/sync_kinds.dart';

/// `sync-relay/README.md` states the document kinds the transport carries.
/// Gloss is the truth repo for that list, so the table is derived from
/// `EditorSyncDocumentKind` in the parent repository rather than maintained by
/// hand: a new kind shows up in the protocol or this test fails.
void main() {
  test('the protocol kind table matches the Gloss repository', () {
    final File source = File(
      glossRepositoryFilePath(
        'src/main/java/art/arcane/gloss/editor/sync/'
        'EditorSyncDocumentKind.java',
      ),
    );
    expect(source.existsSync(), isTrue, reason: 'Missing ${source.path}');

    final String table = protocolKindTable(source.readAsStringSync());
    final String protocol = File('sync-relay/README.md').readAsStringSync();

    expect(
      protocol.contains(table),
      isTrue,
      reason:
          'sync-relay/README.md is missing the generated kind table:\n$table',
    );
  });

  test('every kind in the table is a transport slug', () {
    final File source = File(
      glossRepositoryFilePath(
        'src/main/java/art/arcane/gloss/editor/sync/'
        'EditorSyncDocumentKind.java',
      ),
    );
    expect(source.existsSync(), isTrue, reason: 'Missing ${source.path}');

    final List<ProtocolKind> kinds = protocolKinds(source.readAsStringSync());
    final RegExp slug = RegExp(r'^[a-z][a-z0-9-]{0,31}$');

    expect(kinds, isNotEmpty);
    for (final ProtocolKind kind in kinds) {
      expect(slug.hasMatch(kind.wireName), isTrue, reason: kind.wireName);
    }
  });
}

String protocolKindTable(String java) => <String>[
  '| Wire kind | Storage | Layout | Versioned | Singleton id |',
  '|---|---|---|---|---|',
  for (final ProtocolKind kind in protocolKinds(java)) kind.row,
].join('\n');
