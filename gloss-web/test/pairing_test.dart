import 'dart:async';
import 'dart:convert';

import 'package:gloss_editor/components/server/pair_copy.dart';
import 'package:gloss_editor/services/server/error_copy.dart';
import 'package:gloss_editor/services/server/pairing.dart';
import 'package:gloss_editor/services/server/scope_words.dart';
import 'package:gloss_editor/services/server/server_connection.dart';
import 'package:gloss_editor/services/server/server_profiles.dart';
import 'package:gloss_editor/services/server/server_proof.dart';
import 'package:gloss_editor/services/server/transport.dart';
import 'package:test/test.dart';

import '../tool/fake_server/identity.dart' show fakeFingerprint, fakeServerKey;
import 'support/fake_transport.dart';

const String httpsPage = 'https://gloss.volmitsoftware.com';

/// A code for [payload], however wrong it is.
String codeOf(Map<String, Object?> payload) =>
    'GLS1.${base64UrlNoPadding(utf8.encode(jsonEncode(payload)))}';

Map<String, Object?> validPayload() => <String, Object?>{
  'v': 1,
  'directUrl': 'http://127.0.0.1:9799',
  'relayUrl': 'wss://gloss-relay-437930007071.us-central1.run.app',
  'fingerprint': fakeFingerprint,
  'serverKey': fakeServerKey,
  'tokenId': 'tok_Ab3dEf6hIj9kLm2n',
  'secret': pairSecret,
  'expiresAt': '2026-11-13T10:10:00Z',
  'label': 'laptop',
  'serverName': 'Twin Peaks',
  'scopes': <Object?>['read', 'history', 'publish:screens'],
  'folders': <Object?>['shop'],
};

void main() {
  group('pairing code', () {
    test('should decode a code made like the server makes it', () {
      final PairingPayload payload = decodePairingCode(fakeCode(label: 'laptop'));
      expect(payload.fingerprint, fakeFingerprint);
      expect(payload.serverKey, fakeServerKey);
      expect(payload.tokenId, liveTokenId);
      expect(payload.secret, pairSecret);
      expect(payload.directUrl, 'http://127.0.0.1:9799');
      expect(payload.relayUrl, 'ws://127.0.0.1:9797');
      expect(payload.expiresAt, DateTime.utc(2030));
      expect(payload.label, 'laptop');
      expect(payload.serverName, 'Twin Peaks');
      expect(payload.scopes, <String>['read', 'history', 'publish:screens']);
      expect(payload.folders, <String>['shop']);
      expect(payload.serverId, fakeFingerprint.substring(7, 23));
    });

    test('should read a link without scopes or folders as no scopes and every folder', () {
      final PairingPayload payload = decodePairingCode(
        codeOf(<String, Object?>{...validPayload()}
          ..remove('scopes')
          ..remove('folders')
          ..['label'] = '  '),
      );
      expect(payload.scopes, isEmpty);
      expect(payload.folders, isNull);
      expect(payload.label, isNull, reason: 'a blank label is no label');
    });

    test('should refuse every malformed code with its reason', () {
      final Map<String, String> cases = <String, String>{
        'ANM1.${codeOf(validPayload()).substring(5)}': 'prefix',
        'GLS1.${'A' * 4100}': 'prefix',
        'GLS1.': 'base64',
        'GLS1.not+base64': 'base64',
        'GLS1.${base64UrlNoPadding(utf8.encode('not json'))}': 'json',
        'GLS1.${base64UrlNoPadding(utf8.encode('[1]'))}': 'version',
        codeOf(<String, Object?>{...validPayload(), 'v': 2}): 'version',
        codeOf(<String, Object?>{...validPayload(), 'fingerprint': 'sha256:${'0' * 64}'}): 'fingerprint',
        codeOf(<String, Object?>{...validPayload(), 'fingerprint': fakeFingerprint.toUpperCase()}): 'fields',
        codeOf(<String, Object?>{...validPayload(), 'tokenId': 'abc'}): 'fields',
        codeOf(<String, Object?>{...validPayload(), 'secret': pairSecret.substring(1)}): 'fields',
        codeOf(<String, Object?>{...validPayload(), 'expiresAt': 'soon'}): 'expiry',
        codeOf(<String, Object?>{...validPayload()}
          ..remove('directUrl')
          ..remove('relayUrl')): 'no-path',
        codeOf(<String, Object?>{...validPayload(), 'directUrl': 'http://192.168.1.5:9799'}): 'address',
        codeOf(<String, Object?>{...validPayload(), 'relayUrl': 'ws://relay.example.net'}): 'address',
        codeOf(<String, Object?>{...validPayload(), 'directUrl': 'ftp://127.0.0.1'}): 'address',
        codeOf(<String, Object?>{...validPayload(), 'scopes': 'read'}): 'list',
        codeOf(<String, Object?>{...validPayload(), 'folders': <Object?>[1]}): 'list',
      };
      cases.forEach((String code, String reason) {
        expect(
          () => decodePairingCode(code),
          throwsA(isA<PairingCodeError>().having((PairingCodeError error) => error.message, 'message', reason)),
          reason: reason,
        );
      });
    });

    test('should accept plain http and ws only on this computer', () {
      final PairingPayload loopback = decodePairingCode(
        codeOf(<String, Object?>{...validPayload(), 'directUrl': 'http://localhost:9799', 'relayUrl': 'ws://127.0.0.1:9797'}),
      );
      expect(loopback.directUrl, 'http://localhost:9799');
      expect(loopback.relayUrl, 'ws://127.0.0.1:9797');
      final PairingPayload secure = decodePairingCode(
        codeOf(<String, Object?>{...validPayload(), 'directUrl': 'https://play.example.net:9799'}),
      );
      expect(secure.directUrl, 'https://play.example.net:9799');
    });

    test('should take the code out of a pasted link', () {
      expect(extractPairingCode('  https://gloss.volmitsoftware.com/#/pair/GLS1.abc  '), 'GLS1.abc');
      expect(extractPairingCode('https://gloss.volmitsoftware.com/#/pair/GLS1.abc?x=1'), 'GLS1.abc');
      expect(extractPairingCode('GLS1.abc'), 'GLS1.abc');
    });

    test('should word the scopes and folders a link grants, one line each', () {
      expect(scopeLines(<String>['publish:hud', 'read', 'custom']), <String>[
        'See documents and images',
        'Publish HUD and server list',
        'custom',
      ]);
      expect(
        scopeLines(<String>['read', ...publishEverything], folders: <String>['shop', 'lobby']),
        <String>['See documents and images', 'Publish every kind and images, except documents that run console commands', 'Only folders shop, lobby'],
      );
      expect(
        scopeLines(<String>[...publishEverything, 'publish:commands']),
        <String>['Publish every kind and images', 'Publish documents that run console commands'],
      );
    });
  });

  group('pair', () {
    late FakeNetwork network;

    setUp(() => network = FakeNetwork(pairingServer()));

    List<(ConnectionPath, ApiRequest)> pairs() => network.sentTo('/v1/pair');

    Future<PairingRefused> refused(PairingPayload payload, {String? pageOrigin, Duration? pathTimeout}) async {
      try {
        await ServerConnection.pair(
          payload,
          network.open,
          pageOrigin: pageOrigin,
          pathTimeout: pathTimeout ?? const Duration(seconds: 8),
        );
      } on PairingRefused catch (refusal) {
        return refusal;
      }
      fail('paired');
    }

    final PairingPayload both = fakePayload(directUrl: 'http://127.0.0.1:9799', relayUrl: 'ws://127.0.0.1:9797');

    test('should exchange the secret over the direct path first', () async {
      final (ServerToken token, ConnectionPath path, String? name) = await ServerConnection.pair(
        both,
        network.open,
        client: 'Chrome on macOS',
      );
      expect(path, ConnectionPath.direct);
      expect(network.opened, <ConnectionPath>[ConnectionPath.direct]);
      expect(token.token, liveToken);
      expect(token.tokenId, liveTokenId);
      expect(token.scopes, <String>['read', 'history', 'publish:screens']);
      expect(token.folders, <String>['shop']);
      expect(token.lifetime, const Duration(days: 7));
      expect(token.admin, '0f6c0000-0000-4000-8000-0000000000b2');
      expect(name, isNull);
      expect(pairs().single.$2.body, <String, Object?>{
        'tokenId': liveTokenId,
        'secret': pairSecret,
        'client': 'Chrome on macOS',
      });
    });

    test('should pair over the relay with a proven server when the direct path is unreachable', () async {
      network.down.add(ConnectionPath.direct);
      final (ServerToken _, ConnectionPath path, String? _) = await ServerConnection.pair(both, network.open);
      expect(path, ConnectionPath.relay);
      final ApiRequest hello = network.sentTo('/v1/hello').single.$2;
      expect(hello.headers[challengeHeader], hasLength(43));
      expect(pairs().single.$1, ConnectionPath.relay);
    });

    test('should send at most 128 characters of the browser name and none when it is empty', () async {
      await ServerConnection.pair(both, network.open, client: 'x' * 200);
      expect((pairs().last.$2.body! as Map<String, Object?>)['client'], 'x' * 128);
      await ServerConnection.pair(both, network.open, client: '');
      expect((pairs().last.$2.body! as Map<String, Object?>).containsKey('client'), isFalse);
    });

    test('should take the server name from the pair answer, else from hello', () async {
      network.handler = pairingServer(serverName: 'Hello Name', answer: () => pairAnswer(serverName: '  Pair Name  '));
      expect((await ServerConnection.pair(both, network.open)).$3, 'Pair Name');
      network.handler = pairingServer(serverName: 'Hello Name');
      expect((await ServerConnection.pair(both, network.open)).$3, 'Hello Name');
    });

    test('should never send the secret to a direct path that answers as another server', () async {
      network.pathHandlers[ConnectionPath.direct] = serverHandler(fingerprint: 'sha256:${'0' * 64}');
      final (ServerToken _, ConnectionPath path, String? _) = await ServerConnection.pair(both, network.open);
      expect(path, ConnectionPath.relay);
      expect(pairs().map(((ConnectionPath, ApiRequest) sent) => sent.$1), <ConnectionPath>[ConnectionPath.relay]);
    });

    test('should refuse a relay without a valid proof and send no secret anywhere', () async {
      for (final FakeHandler relay in <FakeHandler>[
        serverHandler(prove: false),
        serverHandler(seed: otherSeed),
        tamperedProof(pairingServer()),
        serverHandler(prefix: 'gloss-relay/1 '),
      ]) {
        network = FakeNetwork(relay);
        final PairingRefused refusal = await refused(fakePayload(relayUrl: 'ws://127.0.0.1:9797'));
        expect(refusal.failure, PairingFailure.wrongServer);
        expect(pairs(), isEmpty);
        expect(
          network.sent.any(((ConnectionPath, ApiRequest) sent) => jsonEncode(sent.$2.body).contains(pairSecret)),
          isFalse,
        );
      }
    });

    test('should refuse a token answered for another fingerprint', () async {
      network.handler = pairingServer(answer: () => pairAnswer(fingerprint: 'sha256:${'0' * 64}'));
      final PairingRefused refusal = await refused(fakePayload(directUrl: 'http://127.0.0.1:9799'));
      expect(refusal.failure, PairingFailure.wrongServer);
    });

    group('which refusal is reported', () {
      test('should put the server refusing the secret first', () async {
        network.pathHandlers[ConnectionPath.direct] = serverHandler(fingerprint: 'sha256:${'0' * 64}');
        network.pathHandlers[ConnectionPath.relay] = serverHandler(
          rest: (ApiRequest request) => refusal(401, 'pairing-invalid'),
        );
        final PairingRefused outcome = await refused(both);
        expect(outcome.code, 'pairing-invalid');
        expect(outcome.failure, PairingFailure.used);
        expect(outcome.copy, serverCopy('pairing-invalid'));
      });

      test('should put a path that answered without pairing ahead of a blocked direct path', () async {
        network.down.add(ConnectionPath.direct);
        network.pathHandlers[ConnectionPath.relay] = (ApiRequest request) => refusal(503, 'busy');
        final PairingRefused outcome = await refused(both, pageOrigin: httpsPage);
        expect(outcome.code, 'busy');
        expect(outcome.copy, serverCopy('busy'));
      });

      test('should carry the wait the pairing limit names', () async {
        network.handler = pairingServer(
          answer: () => refusal(
            429,
            'rate-limited',
            extra: <String, Object?>{'retryAfterMillis': 40500},
          ),
        );
        final PairingRefused outcome = await refused(fakePayload(directUrl: 'http://127.0.0.1:9799'));
        expect(outcome.code, 'rate-limited');
        expect(outcome.retryAfter, const Duration(milliseconds: 40500));
      });

      test('should word the origin a direct path refuses', () async {
        network.pathHandlers[ConnectionPath.direct] = (ApiRequest request) => refusal(403, 'origin-not-allowed');
        network.down.add(ConnectionPath.relay);
        final PairingRefused outcome = await refused(both, pageOrigin: httpsPage);
        expect(outcome.failure, PairingFailure.originNotAllowed);
        expect(outcome.copy.next, contains(httpsPage));
      });

      test('should word the origin when the server refused it without CORS headers', () async {
        // The browser cannot read the refusal; a no-cors probe of the same
        // address got an answer, so the transport says origin-not-allowed.
        network.down.add(ConnectionPath.direct);
        network.failWith[ConnectionPath.direct] = const TransportFailure(
          ConnectionPath.direct,
          'origin-not-allowed',
          'probe answered',
          httpsPage,
        );
        network.serverOnRelay = false;
        final PairingRefused outcome = await refused(both, pageOrigin: httpsPage);
        expect(outcome.failure, PairingFailure.originNotAllowed);
        final PairProblem problem = pairProblemFor(outcome, pageOrigin: httpsPage, directUrl: both.directUrl);
        expect(problem.title, 'The server does not accept this editor\'s address.');
        expect(problem.next, contains('allowedOrigins under [editor] in gloss.toml'));
        expect(problem.next, contains(httpsPage));
        network.down.add(ConnectionPath.relay);
        expect(
          (await refused(both, pageOrigin: httpsPage)).failure,
          PairingFailure.originNotAllowed,
          reason: 'a dead relay does not hide a server that answered',
        );
      });

      test('should put the browser blocking a local address ahead of a relay without the server', () async {
        network.down.add(ConnectionPath.direct);
        network.serverOnRelay = false;
        expect((await refused(both, pageOrigin: httpsPage)).failure, PairingFailure.localNetworkBlocked);
      });

      test('should say the server did not answer when neither path reached it', () async {
        network.down.add(ConnectionPath.direct);
        network.serverOnRelay = false;
        expect((await refused(both)).failure, PairingFailure.unreachable);
        expect(
          (await refused(fakePayload(directUrl: null, relayUrl: 'ws://127.0.0.1:9797'))).failure,
          PairingFailure.relayUnavailable,
          reason: 'a link with only the relay names the relay',
        );
        network.serverOnRelay = true;
        network.down.add(ConnectionPath.relay);
        expect((await refused(both)).failure, PairingFailure.relayUnreachable);
      });

      test('should say nothing answered when no path did', () async {
        network.down.add(ConnectionPath.direct);
        expect((await refused(fakePayload(directUrl: 'http://127.0.0.1:9799'))).failure, PairingFailure.unreachable);
        network.gate = (ConnectionPath path, ApiRequest request) => Completer<void>().future;
        network.down.clear();
        final PairingRefused silent = await refused(
          fakePayload(directUrl: 'https://play.example.net:9799', relayUrl: 'ws://127.0.0.1:9797'),
          pageOrigin: httpsPage,
          pathTimeout: const Duration(milliseconds: 30),
        );
        expect(silent.failure, PairingFailure.unreachable, reason: 'an https address is not blocked as local');
        expect(silent.copy.cause, isEmpty, reason: 'the card words it');
      });
    });

    test('should give the relay its turn when the direct hello never answers', () async {
      network.gate = (ConnectionPath path, ApiRequest request) =>
          path == ConnectionPath.direct ? Completer<void>().future : Future<void>.value();
      final (ServerToken _, ConnectionPath path, String? _) = await ServerConnection.pair(
        both,
        network.open,
        pathTimeout: const Duration(milliseconds: 30),
      );
      expect(path, ConnectionPath.relay);
      expect(network.transportOn(ConnectionPath.direct).closed, isTrue);
      expect(pairs().map(((ConnectionPath, ApiRequest) sent) => sent.$1), <ConnectionPath>[ConnectionPath.relay]);
    });

    test('should close a direct path that opens after its turn ran out', () async {
      final Completer<ServerTransport> opening = Completer<ServerTransport>();
      final (ServerToken _, ConnectionPath path, String? _) = await ServerConnection.pair(
        both,
        (ConnectionPath path, String url) =>
            path == ConnectionPath.direct ? opening.future : network.open(path, url),
        pathTimeout: const Duration(milliseconds: 30),
      );
      expect(path, ConnectionPath.relay);
      final FakeTransport late = FakeTransport(ConnectionPath.direct, network);
      opening.complete(late);
      await settle();
      expect(late.closed, isTrue);
      expect(network.sent.where(((ConnectionPath, ApiRequest) sent) => sent.$1 == ConnectionPath.direct), isEmpty);
    });

    test('should not cut the exchange once the secret is on its way', () async {
      network.gate = (ConnectionPath path, ApiRequest request) => request.path == '/v1/pair'
          ? Future<void>.delayed(const Duration(milliseconds: 80))
          : Future<void>.value();
      final (ServerToken token, ConnectionPath path, String? _) = await ServerConnection.pair(
        both,
        network.open,
        pathTimeout: const Duration(milliseconds: 20),
      );
      expect(path, ConnectionPath.direct);
      expect(token.token, liveToken);
      expect(pairs(), hasLength(1));
    });
  });

  group('ServerProfileStore', () {
    const ServerProfile remembered = ServerProfile(
      fingerprint: fakeFingerprint,
      serverKey: fakeServerKey,
      name: 'Twin Peaks',
      relayUrl: 'wss://relay',
      remember: true,
    );

    test('should keep a remembered token in local storage and a session token only in the session', () {
      final MemoryKeyValueStore local = MemoryKeyValueStore();
      final ServerProfileStore store = ServerProfileStore(local: local, session: MemoryKeyValueStore());
      store.save(<ServerProfile>[remembered]);
      expect(store.saveToken(remembered, liveServerToken), isTrue);
      final ServerProfileStore reopened = ServerProfileStore(local: local, session: MemoryKeyValueStore());
      expect(reopened.token(reopened.load().single)!.tokenId, liveTokenId);
      final ServerProfile forgetful = remembered.copyWith(remember: false);
      store.save(<ServerProfile>[forgetful]);
      store.saveToken(forgetful, liveServerToken);
      expect(local.values.containsKey(ServerProfileStore.tokenKey(forgetful.id)), isFalse);
      final ServerProfileStore later = ServerProfileStore(local: local, session: MemoryKeyValueStore());
      expect(later.token(later.load().single), isNull);
      expect(store.token(forgetful), isNotNull);
    });

    test('should say when the browser refused to keep the token', () {
      final ServerProfileStore store = ServerProfileStore(
        local: MemoryKeyValueStore(refuseWrites: true),
        session: MemoryKeyValueStore(),
      );
      expect(store.saveToken(remembered, liveServerToken), isFalse);
      expect(store.saveToken(remembered.copyWith(remember: false), liveServerToken), isTrue);
    });

    test('should drop a stored profile whose key does not hash to its fingerprint', () {
      final MemoryKeyValueStore local = MemoryKeyValueStore()
        ..write(
          ServerProfileStore.profilesKey,
          '[{"fingerprint":"sha256:${'0' * 64}","serverKey":"$fakeServerKey","name":"Evil"}]',
        );
      expect(ServerProfileStore(local: local, session: MemoryKeyValueStore()).load(), isEmpty);
    });

    test('should keep a profile and token through storage', () {
      final ServerProfile full = bothPaths.copyWith(lastPath: ConnectionPath.relay, documents: 1575, images: 200);
      expect(ServerProfile.fromJson(jsonDecode(jsonEncode(full.toJson())))!.toJson(), full.toJson());
      final ServerToken token = ServerToken.fromJson(pairAnswer().body)!.copyWith(counter: 42);
      final ServerToken back = ServerToken.fromJson(jsonDecode(jsonEncode(token.toJson())))!;
      expect(back.toJson(), token.toJson());
      expect(back.folders, <String>['shop']);
      expect(back.lifetime, const Duration(days: 7));
      expect(back.counter, 42);
    });

    test('should try the last good path first', () {
      expect(bothPaths.paths.first.$1, ConnectionPath.direct);
      expect(bothPaths.copyWith(lastPath: ConnectionPath.relay).paths.first.$1, ConnectionPath.relay);
      expect(relayOnly.copyWith(lastPath: ConnectionPath.direct).paths.single.$1, ConnectionPath.relay);
    });
  });
}
