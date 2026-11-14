/// The plain-Dart parts of the start page, the pairing card and the Server
/// sheet: the Continue list, the server state words, counts, the expiry line
/// and what a pairing allows in words.
library;

import 'package:gloss_editor/components/server/connection_words.dart';
import 'package:gloss_editor/components/server/start_rows.dart';
import 'package:gloss_editor/services/server/scope_words.dart';
import 'package:gloss_editor/services/server/server_connection.dart';
import 'package:gloss_editor/services/server/server_profiles.dart';
import 'package:gloss_editor/ui/tone.dart';
import 'package:test/test.dart';

import '../tool/fake_server/identity.dart';
import '../tool/fake_server/kinds.dart' show defaultScopes;

ServerProfile server(String name, {int? documents, int? images}) =>
    ServerProfile(
      fingerprint: fakeFingerprint,
      serverKey: fakeServerKey,
      name: name,
      directUrl: 'http://127.0.0.1:9806',
      documents: documents,
      images: images,
    );

ServerToken token(DateTime expires) => ServerToken(
  token: 'gls_tok_a.b',
  tokenId: 'tok_aaaaaaaa',
  scopes: const <String>['read'],
  expiresAt: expires,
);

void main() {
  final DateTime now = DateTime.utc(2026, 11, 13, 12);

  group('continueRows', () {
    test(
      'should list each paired server, then the browser workspace with work',
      () {
        final List<ContinueRow> rows = continueRows(
          servers: <ServerProfile>[
            server('Twin Peaks', documents: 1575, images: 200),
          ],
          local: const LocalSummary(documents: 4, hasWork: true),
          tokenOf: (String _) => null,
          now: now,
        );
        expect(rows.map((ContinueRow row) => row.name), <String>[
          'Twin Peaks',
          'Browser workspace',
        ]);
        expect(rows.first.line, '1575 documents, 200 images');
        expect(rows.last.line, '4 documents, in this browser');
        expect(rows.first.isServer, isTrue);
      },
    );

    test('should leave out a browser workspace with only the sample', () {
      final List<ContinueRow> rows = continueRows(
        servers: const <ServerProfile>[],
        local: const LocalSummary(documents: 1, hasWork: false),
        tokenOf: (String _) => null,
        now: now,
      );
      expect(rows, isEmpty);
    });

    test('should say a server was never opened', () {
      expect(countsLine(), 'Not opened yet');
      expect(countsLine(documents: 1), '1 document');
    });

    test('should warn within three days of the pairing ending', () {
      expect(endsSoon(token(now.add(const Duration(days: 7))), now), isNull);
      expect(
        endsSoon(token(now.add(const Duration(days: 2, hours: 3))), now),
        'Pairing ends in 3 days unless used.',
      );
      expect(
        endsSoon(token(now.add(const Duration(hours: 5))), now),
        'Pairing ends in 1 day unless used.',
      );
      final List<ContinueRow> rows = continueRows(
        servers: <ServerProfile>[server('Twin Peaks')],
        local: const LocalSummary(documents: 0, hasWork: false),
        tokenOf: (String _) => token(now.add(const Duration(days: 1))),
        now: now,
      );
      expect(rows.single.warning, 'Pairing ends in 1 day unless used.');
    });
  });

  group('serverState', () {
    test('should read Online, Checking or Not reachable', () {
      expect(
        serverState(
          const ConnectionStatus(ConnectionPhase.online, probed: true),
          paired: true,
        ),
        ('Online', BadgeTone.ok),
      );
      expect(
        serverState(
          const ConnectionStatus(ConnectionPhase.connecting, probed: true),
          paired: true,
        ).$1,
        'Checking',
      );
      expect(
        serverState(
          const ConnectionStatus(ConnectionPhase.offline, probed: true),
          paired: true,
        ).$1,
        'Not reachable',
      );
      expect(
        serverState(
          const ConnectionStatus(ConnectionPhase.reconnecting, attempt: 2),
          paired: true,
        ),
        ('Not reachable', BadgeTone.warning),
        reason: 'a live connection waiting to retry is not reachable now',
      );
      expect(
        serverState(
          const ConnectionStatus(ConnectionPhase.connecting),
          paired: true,
        ).$1,
        'Checking',
        reason: 'the first attempt is checking',
      );
      expect(
        serverState(
          const ConnectionStatus(ConnectionPhase.connecting, attempt: 2),
          paired: true,
        ).$1,
        'Not reachable',
        reason: 'a retry after a failed attempt keeps saying so',
      );
      expect(
        serverState(
          const ConnectionStatus(ConnectionPhase.needsPairing),
          paired: false,
        ).$1,
        'Pairing ended',
      );
      expect(
        serverState(
          const ConnectionStatus(ConnectionPhase.offline),
          paired: false,
        ).$1,
        'Not paired here',
      );
    });
  });

  group('scopeLines', () {
    test('should say the default pairing in three lines', () {
      expect(scopeLines(defaultScopes), <String>[
        'See documents and images',
        'See stored versions',
        'Publish every kind and images, except documents that run console commands',
      ]);
    });

    test(
      'should name each publish group of a narrow pairing and its folders',
      () {
        expect(
          scopeLines(
            const <String>['read', 'publish:world'],
            folders: <String>['shop', 'lobby'],
          ),
          <String>[
            'See documents and images',
            'Publish world displays',
            'Only folders shop, lobby',
          ],
        );
        expect(
          scopeLines(const <String>['read', 'publish:commands', 'custom']),
          <String>[
            'See documents and images',
            'Publish documents that run console commands',
            'custom',
          ],
        );
      },
    );
  });
}
