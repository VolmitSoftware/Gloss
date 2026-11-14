/// Pins the editor's half of editor API 1 to the plugin's own record of it:
/// `schema/editor/editor-v1.endpoints.json` (routes, headers, scopes, limits,
/// pairing and relay constants) and the golden messages the plugin's tests
/// write from real answers (`schema/editor/fixtures/api`). Gloss is the truth
/// repo: when a value drifts, the editor follows the plugin.
library;

import 'dart:convert';
import 'dart:io';

import 'package:gloss_editor/services/editor_sync.dart';
import 'package:gloss_editor/services/server/error_copy.dart';
import 'package:gloss_editor/services/server/pairing.dart';
import 'package:gloss_editor/services/server/scope_words.dart';
import 'package:gloss_editor/services/server/server_profiles.dart';
import 'package:gloss_editor/services/server/server_proof.dart';
import 'package:test/test.dart';

import 'support/gloss_repository.dart';

Map<String, Object?> _json(String relative) {
  final File file = File(glossRepositoryFilePath('schema/editor/$relative'));
  expect(file.existsSync(), isTrue, reason: 'Missing ${file.path}');
  return jsonDecode(file.readAsStringSync()) as Map<String, Object?>;
}

void main() {
  late final Map<String, Object?> table = _json('editor-v1.endpoints.json');

  test('should name the headers and prefixes the plugin uses', () {
    final Map<String, Object?> headers =
        table['headers']! as Map<String, Object?>;
    expect(headers['challenge'], challengeHeader);
    expect(headers['serverKey'], serverKeyHeader);
    expect(headers['proof'], proofHeader);
    expect(headers['counter'], 'X-Gloss-Counter');
    final Map<String, Object?> relay = table['relay']! as Map<String, Object?>;
    expect(relay['proofPrefix'], editorProofPrefix);
    expect(relay['appPath'], '/app');
    expect(table['basePath'], '/gloss');
    expect(table['protocol'], 1);
    final Map<String, Object?> pairing =
        table['pairing']! as Map<String, Object?>;
    expect(pairing['prefix'], pairingPrefix);
  });

  test('should word every scope in the plugin order', () {
    expect(table['scopes'], scopeOrder);
    final Map<String, Object?> pairing =
        table['pairing']! as Map<String, Object?>;
    final List<String> defaults = <String>[
      for (final Object? scope in pairing['defaultScopes']! as List<Object?>)
        '$scope',
    ];
    expect(scopeLines(defaults), contains('Publish every kind and images, except documents that run console commands'));
    for (final Object? scope in table['scopes']! as List<Object?>) {
      expect(scopeWord('$scope'), isNot('$scope'), reason: '$scope has words');
    }
  });

  test('should keep within the plugin limits', () {
    final Map<String, Object?> limits =
        table['limits']! as Map<String, Object?>;
    final Map<String, Object?> relay = table['relay']! as Map<String, Object?>;
    expect(limits['documentsReadRefs'], huiEditorSyncMaxReadRefs);
    expect(limits['imagesReadHashes'], huiEditorSyncMaxReadImages);
    expect(limits['publishDocumentEntries'], huiEditorSyncMaxPublishDocuments);
    expect(limits['publishImageEntries'], huiEditorSyncMaxPublishImages);
    expect(limits['maximumAnswerBytes'], huiEditorSyncDirectPageBytes);
    expect(limits['confirmDeletesAbove'], huiEditorSyncConfirmDeletesAbove);
    expect(
      limits['confirmDeletesPercent'],
      huiEditorSyncConfirmDeletesPercent,
    );
    expect(
      (relay['frameBytes']! as int) - (limits['relayMarginBytes']! as int),
      huiEditorSyncRelayPageBytes,
    );
  });

  test('should word every error code the plugin answers', () {
    final Map<String, Object?> statuses =
        table['errorStatus']! as Map<String, Object?>;
    for (final MapEntry<String, Object?> entry in statuses.entries) {
      expect(knownServerCopy(entry.key), isNotNull, reason: entry.key);
    }
  });

  test('should decode the plugin pairing code and pair answer', () {
    final Map<String, Object?> fixture = _json(
      'fixtures/api/pairing-code.json',
    );
    final PairingPayload payload = decodePairingCode(
      fixture['code']! as String,
    );
    final Map<String, Object?> expected =
        fixture['payload']! as Map<String, Object?>;
    expect(payload.fingerprint, expected['fingerprint']);
    expect(payload.tokenId, expected['tokenId']);
    expect(payload.scopes, expected['scopes']);
    expect(payload.folders, expected['folders']);
    expect(payload.serverName, expected['serverName']);
    expect(extractPairingCode(fixture['link']! as String), fixture['code']);
    final ServerToken token = ServerToken.fromJson(
      _json('fixtures/api/pair-response.json'),
    )!;
    expect(token.folders, <String>['shop']);
    expect(token.lifetime, const Duration(days: 7));
  });

  test('should decode the catalog, bodies, publish answer and events', () {
    final SyncCatalog catalog = SyncCatalog.decode(
      _json('fixtures/api/catalog-response.json'),
    );
    expect(catalog.documents, isNotEmpty);
    expect(catalog.metaHash, startsWith('sha256:'));
    final Map<String, Object?> page = _json(
      'fixtures/api/documents-page-response.json',
    );
    for (final Object? entry in page['documents']! as List<Object?>) {
      final SyncDocumentBody body = SyncDocumentBody.decode(entry);
      expect(editorSyncDocumentHash(body.json), body.hash, reason: body.id);
    }
    final SyncPublishResult result = SyncPublishResult.decode(
      _json('fixtures/api/changes-response.json'),
    );
    expect(result.applied, isTrue);
    expect(result.conflicts.single.reason, 'changed');
    expect(result.delta.documents.single.revision, 2);
    final SyncMeta meta = SyncMeta.decode(
      _json('fixtures/api/meta-response.json')['meta'],
    );
    expect(meta.scopeOf('animation'), 'publish:shared');
    final SyncCatalog event = SyncCatalog.decode(
      _json('fixtures/api/event-catalog.json'),
    );
    expect(event.documents.single.source, 'server');
    expect(
      DateTime.tryParse(
        '${_json('fixtures/api/event-open.json')['expiresAt']}',
      ),
      isNotNull,
    );
  });
}
