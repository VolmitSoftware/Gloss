import 'dart:async';

import 'package:gloss_editor/services/server/error_copy.dart';
import 'package:gloss_editor/services/server/request_pacer.dart';
import 'package:gloss_editor/services/server/server_connection.dart';
import 'package:gloss_editor/services/server/server_profiles.dart';
import 'package:gloss_editor/services/server/server_proof.dart';
import 'package:gloss_editor/services/server/transport.dart';
import 'package:test/test.dart';

import '../tool/fake_server/identity.dart' show fakeFingerprint;
import 'support/fake_transport.dart';

void main() {
  late FakeNetwork network;
  late Waits waits;
  late ServerConnection connection;
  late List<ConnectionPhase> phases;
  late DateTime clock;

  ServerConnection make({
    ServerProfile serverProfile = bothPaths,
    ServerToken? withToken,
    TransportFactory? open,
    bool Function()? visible,
  }) {
    final ServerConnection made = ServerConnection(
      profile: serverProfile,
      token: withToken ?? liveServerToken,
      open: open ?? network.open,
      delay: waits.call,
      handshakeTimeout: handshakeBudget,
      visible: visible ?? () => true,
      now: () => clock,
    );
    made.statuses.listen(
      (ConnectionStatus status) => phases.add(status.phase),
    );
    return made;
  }

  /// The `X-Gloss-Counter` values sent, with their paths.
  List<(ConnectionPath, int)> counters() => <(ConnectionPath, int)>[
    for (final (ConnectionPath path, ApiRequest request) in network.sent)
      if (request.headers['X-Gloss-Counter'] case final String counter)
        (path, int.parse(counter)),
  ];

  ApiResponse ok(ApiRequest request) =>
      const ApiResponse(200, <String, Object?>{});

  setUp(() {
    network = FakeNetwork(serverHandler());
    waits = Waits();
    phases = <ConnectionPhase>[];
    clock = DateTime.utc(2026, 11, 4, 12);
    connection = make();
  });

  tearDown(() async => connection.dispose());

  group('paths', () {
    test('should go online over the direct path first when both are paired', () async {
      final List<Map<String, Object?>> events = <Map<String, Object?>>[];
      connection.events.listen(events.add);
      await connection.connect();
      expect(connection.status.phase, ConnectionPhase.online);
      expect(connection.status.path, ConnectionPath.direct);
      expect(connection.hello?['platform'], 'paper');
      expect(network.opened, <ConnectionPath>[ConnectionPath.direct]);
      expect(network.streams.single.$1, ConnectionPath.direct);
      expect(events.first['type'], 'open', reason: 'the open frame first');
      expect(connection.opened?['tokenId'], liveTokenId);
    });

    test('should fail over to the relay when the direct path is unreachable', () async {
      final List<ServerProfile> remembered = <ServerProfile>[];
      connection.profileChanges.listen(remembered.add);
      network.down.add(ConnectionPath.direct);
      await connection.connect();
      expect(connection.status.phase, ConnectionPhase.online);
      expect(connection.status.path, ConnectionPath.relay);
      expect(connection.profile.lastPath, ConnectionPath.relay);
      expect(remembered.single.lastPath, ConnectionPath.relay);
    });

    test('should try the last good path first', () async {
      connection = make(
        serverProfile: bothPaths.copyWith(lastPath: ConnectionPath.relay),
      );
      await connection.connect();
      expect(network.opened.first, ConnectionPath.relay);
      expect(connection.status.path, ConnectionPath.relay);
    });

    test('should forward server events while online', () async {
      await connection.connect();
      final List<Map<String, Object?>> events = <Map<String, Object?>>[];
      connection.events.listen(events.add);
      network.lastStream.emit(<String, Object?>{
        'type': 'catalog',
        'documents': <Object?>[],
      });
      expect(events.single['type'], 'catalog');
    });

    test('should fail over to the other path at once when the event stream drops', () async {
      await connection.connect();
      network.down.add(ConnectionPath.direct);
      await network.lastStream.end();
      await settle();
      expect(connection.status.phase, ConnectionPhase.online);
      expect(connection.status.path, ConnectionPath.relay);
      expect(
        phases,
        containsAllInOrder(<ConnectionPhase>[
          ConnectionPhase.online,
          ConnectionPhase.reconnecting,
          ConnectionPhase.connecting,
          ConnectionPhase.online,
        ]),
      );
      expect(
        waits.durations,
        <Duration>[const Duration(seconds: 15)],
        reason: 'only the direct probe waits; no backoff before the relay',
      );
      expect(
        network.opened,
        <ConnectionPath>[ConnectionPath.direct, ConnectionPath.relay],
        reason: 'the relay first after losing the direct path',
      );
    });

    test('should fail over at once when a request fails on the path', () async {
      await expectLater(
        connection.request(const ApiRequest('GET', '/v1/catalog')),
        throwsA(isA<NotConnected>()),
      );
      await connection.connect();
      network.down.add(ConnectionPath.direct);
      await expectLater(
        connection.request(const ApiRequest('GET', '/v1/catalog')),
        throwsA(isA<TransportFailure>()),
      );
      await settle();
      expect(connection.status.path, ConnectionPath.relay);
      expect(connection.status.phase, ConnectionPhase.online);
    });

    test('should reconnect over the relay when the server closes a slow stream at its limit', () async {
      network.down.add(ConnectionPath.direct);
      await connection.connect();
      final List<String?> messages = <String?>[];
      connection.statuses.listen(
        (ConnectionStatus status) => messages.add(status.message),
      );
      await network.lastStream.closing('limit');
      await settle();
      expect(messages, contains('Fell behind on server events. Reconnecting.'));
      expect(connection.status.phase, ConnectionPhase.online);
      expect(connection.status.path, ConnectionPath.relay);
      expect(network.streams, hasLength(2));
      expect(network.lastStream.isOpen, isTrue);
    });
  });

  group('server identity', () {
    test('should refuse a direct path that answers with another fingerprint and send no token', () async {
      network.handler = serverHandler(fingerprint: 'sha256:${'0' * 64}');
      await connection.connect();
      expect(connection.status.phase, ConnectionPhase.refused);
      expect(connection.status.problem, transportCopy('wrong-server'));
      expect(
        network.sent.where(
          ((ConnectionPath, ApiRequest) sent) => sent.$2.path != '/v1/hello',
        ),
        isEmpty,
      );
      expect(network.subscribed, isEmpty);
      expect(
        network.opened,
        <ConnectionPath>[ConnectionPath.direct],
        reason: 'nothing more is tried',
      );
      expect(waits.pending, isEmpty);
    });

    test('should refuse a server that speaks another protocol', () async {
      network.handler = serverHandler(protocol: 2);
      await connection.connect();
      expect(connection.status.phase, ConnectionPhase.refused);
      expect(connection.status.problem!.code, 'unsupported-protocol');
      expect(network.subscribed, isEmpty);
      expect(waits.pending, isEmpty);
    });

    test('should send no challenge and need no proof on the direct path', () async {
      network.handler = serverHandler(prove: false);
      await connection.connect();
      expect(connection.status.phase, ConnectionPhase.online);
      expect(connection.status.path, ConnectionPath.direct);
      expect(
        network.sent.every(
          ((ConnectionPath, ApiRequest) sent) =>
              !sent.$2.headers.containsKey(challengeHeader),
        ),
        isTrue,
      );
    });

    group('relay proof', () {
      setUp(() => network.down.add(ConnectionPath.direct));

      Future<void> expectRefused(FakeHandler relay) async {
        network.handler = relay;
        await connection.connect();
        expect(connection.status.phase, ConnectionPhase.refused);
        expect(connection.status.problem, transportCopy('wrong-server'));
        expect(
          network.sent.where(
            ((ConnectionPath, ApiRequest) sent) => sent.$2.path != '/v1/hello',
          ),
          isEmpty,
        );
        expect(network.subscribed, isEmpty, reason: 'the token never left');
      }

      test('should refuse a relay hello without a proof', () => expectRefused(serverHandler(prove: false)));

      test('should refuse a proof from a key that is not the pinned one', () => expectRefused(serverHandler(seed: otherSeed)));

      test('should refuse a proof whose signature does not verify', () => expectRefused(tamperedProof(serverHandler())));

      test('should refuse a proof signed over the relay registration prefix', () => expectRefused(serverHandler(prefix: 'gloss-relay/1 ')));

      test('should refuse a relay that echoes the fingerprint with a valid proof of another key', () async {
        await expectRefused(serverHandler(seed: otherSeed, fingerprint: fakeFingerprint));
      });

      test('should go online over the relay with a valid proof of a fresh challenge', () async {
        await connection.connect();
        expect(connection.status.phase, ConnectionPhase.online);
        expect(connection.status.path, ConnectionPath.relay);
        await network.lastStream.end();
        await settle();
        final List<String?> challenges = <String?>[
          for (final (ConnectionPath path, ApiRequest request) in network.sent)
            if (path == ConnectionPath.relay && request.path == '/v1/hello')
              request.headers[challengeHeader],
        ];
        expect(challenges, hasLength(2));
        expect(
          challenges.every(
            (String? challenge) => challenge != null && challenge.length == 43,
          ),
          isTrue,
        );
        expect(challenges.first, isNot(challenges.last));
      });
    });
  });

  group('event stream', () {
    test('should not count a path as online before the open frame', () async {
      network.withholdOpen = true;
      final Future<void> firstRound = connection.connect();
      await settle();
      expect(connection.status.phase, ConnectionPhase.connecting);
      expect(connection.status.path, ConnectionPath.direct);
      expect(network.streams, hasLength(1));
      await expectLater(
        connection.request(const ApiRequest('GET', '/v1/catalog')),
        throwsA(isA<NotConnected>()),
      );
      network.lastStream.emit(network.openFrame());
      await firstRound;
      expect(connection.status.phase, ConnectionPhase.online);
    });

    test('should give up on a stream that never opens and try the next path after the budget', () async {
      network.withholdOpen = true;
      final Future<void> firstRound = connection.connect();
      await settle();
      network.withholdOpen = false;
      waits.expireBudgets();
      await firstRound;
      expect(connection.status.phase, ConnectionPhase.online);
      expect(connection.status.path, ConnectionPath.relay);
      expect(network.transportOn(ConnectionPath.direct).closed, isTrue);
      expect(network.streamOn(ConnectionPath.direct).isOpen, isFalse);
    });

    test('should move the token expiry and scopes from the open frame', () async {
      final List<ServerToken> moved = <ServerToken>[];
      connection.tokenChanges.listen(moved.add);
      network.openExpiresAt = DateTime.utc(2026, 11, 11, 12);
      network.openScopes = <String>['read', 'history'];
      await connection.connect();
      expect(moved, hasLength(1));
      expect(moved.single.expiresAt, DateTime.utc(2026, 11, 11, 12));
      expect(moved.single.scopes, <String>['read', 'history']);
      expect(moved.single.token, liveToken);
      expect(connection.token!.expiresAt, DateTime.utc(2026, 11, 11, 12));
      expect(connection.tokenLeft, const Duration(days: 7));
    });

    test('should ask to pair again when the direct stream closes 4401 token-revoked before open', () async {
      connection = make(
        withToken: ServerToken(
          token: 'gls_tok_old.c2VjcmV0',
          tokenId: 'tok_old',
          scopes: const <String>['read'],
          expiresAt: DateTime.utc(2030),
        ),
      );
      await connection.connect();
      expect(connection.status.phase, ConnectionPhase.needsPairing);
      expect(connection.status.problem!.code, 'token-revoked');
      expect(connection.token, isNull);
      expect(network.opened, <ConnectionPath>[ConnectionPath.direct]);
      expect(waits.pending, isEmpty, reason: 'no retry with a dead token');
    });

    test('should ask to pair again when the relay refuses the stream for an expired token', () async {
      network.down.add(ConnectionPath.direct);
      network.refuseStreamWith = const StreamRefused(401, 'token-expired');
      await connection.connect();
      expect(connection.status.phase, ConnectionPhase.needsPairing);
      expect(connection.status.problem!.code, 'token-expired');
      expect(connection.token, isNull);
      expect(waits.pending, isEmpty);
    });

    test('should take a stream closed before open for another reason as a refusal and try the next path', () async {
      network.closeBeforeOpen[ConnectionPath.direct] = '4503 busy';
      await connection.connect();
      expect(connection.status.phase, ConnectionPhase.online);
      expect(connection.status.path, ConnectionPath.relay);
      expect(connection.token, isNotNull);
    });

    test('should word a stream the relay ended before open by the relay\'s reason', () async {
      connection = make(serverProfile: relayOnly);
      network.closeBeforeOpen[ConnectionPath.relay] = 'server-left';
      await connection.connect();
      expect(connection.status.phase, ConnectionPhase.reconnecting);
      expect(connection.status.problem, transportCopy('server-left'));
      network.closeBeforeOpen[ConnectionPath.relay] = 'capacity';
      waits.releaseAll();
      await settle();
      expect(connection.status.problem, transportCopy('capacity'));
    });

    test('should ask to pair again when the server ends the stream because the token was revoked', () async {
      await connection.connect();
      await network.lastStream.closing('token-revoked');
      await settle();
      expect(connection.status.phase, ConnectionPhase.needsPairing);
      expect(connection.token, isNull);
      expect(waits.pending, isEmpty);
    });

    test('should ask to pair again without a token or with one this clock says expired', () async {
      connection = ServerConnection(
        profile: bothPaths,
        open: network.open,
        delay: waits.call,
        now: () => clock,
      );
      await connection.connect();
      expect(connection.status.phase, ConnectionPhase.needsPairing);
      expect(connection.status.problem!.code, 'no-token');
      connection = make(
        withToken: liveServerToken.copyWith(expiresAt: clock),
      );
      await connection.connect();
      expect(connection.status.phase, ConnectionPhase.needsPairing);
      expect(connection.status.problem!.code, 'token-expired');
      expect(network.opened, isEmpty);
    });

    test('should back off instead of reopening at once when the server refuses a stream it opened', () async {
      await connection.connect();
      expect(connection.status.path, ConnectionPath.direct);
      await network.lastStream.end('4429 rate-limited');
      await settle();
      expect(connection.status.phase, ConnectionPhase.reconnecting);
      expect(connection.status.attempt, 1);
      expect(waits.durations, <Duration>[const Duration(seconds: 1)]);
      expect(network.streams, hasLength(1), reason: 'no new stream before the wait');
      waits.releaseAll();
      await settle();
      expect(connection.status.phase, ConnectionPhase.online);
      await network.lastStream.end('rate-limited');
      await settle();
      expect(
        connection.status.attempt,
        2,
        reason: 'a refusal right after going online does not reset the backoff',
      );
      expect(network.streams, hasLength(2));
    });

    test('should wait out the server hint when the relay refuses the event stream', () async {
      network.down.add(ConnectionPath.direct);
      network.refuseStreamWith = const StreamRefused(
        429,
        'rate-limited',
        retryAfter: Duration(seconds: 60),
      );
      await connection.connect();
      expect(connection.status.phase, ConnectionPhase.reconnecting);
      expect(waits.durations, <Duration>[const Duration(seconds: 60)]);
    });
  });

  group('reconnecting', () {
    test('should retry within 1 to 5 seconds for the first minute, then back off to 30 seconds', () {
      expect(
        <int>[
          for (int attempt = 1; attempt <= 12; attempt++)
            reconnectDelay(attempt, away: const Duration(seconds: 20)).inSeconds,
        ],
        <int>[1, 1, 2, 2, 3, 3, 4, 5, 5, 5, 5, 5],
      );
      expect(
        <int>[
          for (final int seconds in <int>[61, 95, 125, 300])
            reconnectDelay(20, away: Duration(seconds: seconds)).inSeconds,
        ],
        <int>[10, 20, 30, 30],
      );
      expect(
        <int>[
          for (final int seconds in <int>[61, 95, 125, 300])
            reconnectDelay(
              20,
              away: Duration(seconds: seconds),
              visible: true,
            ).inSeconds,
        ],
        <int>[5, 5, 5, 5],
      );
      expect(reconnectDelay(7, stopping: true), fastRetry);
      expect(fastRetry, const Duration(milliseconds: 250));
    });

    test('should walk the 1, 1, 2, 2, 3, 3, 4, 5 second ladder while both paths are down', () async {
      network.down.addAll(ConnectionPath.values);
      await connection.connect();
      final List<int> ladder = <int>[];
      for (int round = 0; round < 9; round++) {
        expect(connection.status.phase, ConnectionPhase.reconnecting);
        expect(connection.status.attempt, round + 1);
        ladder.add(waits.durations.single.inSeconds);
        waits.releaseAll();
        await settle();
      }
      expect(ladder, <int>[1, 1, 2, 2, 3, 3, 4, 5, 5]);
      network.down.clear();
      connection.retryNow();
      await settle();
      expect(connection.status.phase, ConnectionPhase.online);
    });

    test('should say the server did not answer when both paths are down', () async {
      network.down.addAll(ConnectionPath.values);
      await connection.connect();
      expect(connection.status.problem, transportCopy('no-answer'));
    });

    test('should say the server refuses this page\'s origin rather than that it is down', () async {
      const String page = 'https://gloss.volmitsoftware.com';
      network.down.add(ConnectionPath.direct);
      network.failWith[ConnectionPath.direct] = const TransportFailure(
        ConnectionPath.direct,
        'origin-not-allowed',
        'probe answered',
        page,
      );
      network.serverOnRelay = false;
      await connection.connect();
      final ErrorCopy problem = connection.status.problem!;
      expect(problem.code, 'origin-not-allowed');
      expect(problem.cause, 'The server does not accept this editor\'s address.');
      expect(problem.next, contains('allowedOrigins under [editor] in gloss.toml'));
      expect(problem.next, contains(page));
      network.down.add(ConnectionPath.relay);
      connection.retryNow();
      await settle();
      expect(connection.status.problem?.code, 'origin-not-allowed', reason: 'a dead relay does not hide it');
    });

    test('should word a request the server refused for this page\'s origin', () async {
      await connection.connect();
      network.down.add(ConnectionPath.direct);
      network.failWith[ConnectionPath.direct] = const TransportFailure(
        ConnectionPath.direct,
        'origin-not-allowed',
        null,
        'http://127.0.0.1:8807',
      );
      try {
        await connection.request(const ApiRequest('GET', '/v1/documents'));
        fail('answered');
      } on TransportFailure catch (failure) {
        expect(
          failure.message,
          'The server does not accept this editor\'s address. '
          'Add http://127.0.0.1:8807 to allowedOrigins under [editor] in gloss.toml.',
        );
      }
    });

    test('should name the failure of the one path a server was paired with', () async {
      connection = make(serverProfile: relayOnly);
      network.down.add(ConnectionPath.relay);
      await connection.connect();
      expect(connection.status.problem, transportCopy('relay-unreachable'));
    });

    test('should say the server is restarting when both paths go quiet after being online', () async {
      await connection.connect();
      network.down.addAll(ConnectionPath.values);
      await network.lastStream.end();
      await settle();
      expect(connection.status.phase, ConnectionPhase.reconnecting);
      expect(connection.status.message, 'Server restarting. Reconnecting.');
    });

    test('should keep its words through every retry while the server is away', () async {
      await connection.connect();
      final List<ConnectionStatus> seen = <ConnectionStatus>[];
      connection.statuses.listen(seen.add);
      network.down.addAll(ConnectionPath.values);
      await network.lastStream.end();
      await settle();
      for (int round = 0; round < 3; round++) {
        waits.releaseAll();
        await settle();
      }
      // From the first failed round on, a retry in flight keeps that round's
      // words instead of dropping them until it fails again.
      final List<ConnectionStatus> retries = seen
          .skipWhile((ConnectionStatus status) => status.attempt == 0)
          .toList();
      expect(retries.length, greaterThan(3));
      expect(
        retries.map((ConnectionStatus status) => status.message).toSet(),
        <String?>{'Server restarting. Reconnecting.'},
      );
    });

    test('should blame the relay, not a restart, when a relay-only pairing loses it', () async {
      connection = make(serverProfile: relayOnly);
      await connection.connect();
      expect(connection.status.online, isTrue);
      network.down.add(ConnectionPath.relay);
      for (final FakeTransport transport in network.transports.toList()) {
        await transport.close();
      }
      await network.lastStream.end();
      await settle();
      expect(connection.status.phase, ConnectionPhase.reconnecting);
      expect(connection.status.problem, transportCopy('relay-unreachable'));
    });

    test('should retry every 250 ms for 30 seconds after server-stopping, then use the ladder', () async {
      await connection.connect();
      expect(connection.status.path, ConnectionPath.direct);
      await network.serverStops();
      await settle();
      expect(connection.status.phase, ConnectionPhase.reconnecting);
      expect(connection.status.message, 'Server restarting. Reconnecting.');
      expect(waits.durations, <Duration>[fastRetry]);
      for (int round = 0; round < 3; round++) {
        clock = clock.add(fastRetry);
        expect(waits.release(fastRetry), 1);
        await settle();
        expect(waits.durations, <Duration>[fastRetry]);
      }
      final int directTries = network.opened
          .where((ConnectionPath path) => path == ConnectionPath.direct)
          .length;
      expect(directTries, 5, reason: 'the direct path is asked on every retry');
      clock = clock.add(fastWindow);
      waits.releaseAll();
      await settle();
      expect(connection.status.attempt, 5);
      expect(waits.durations, <Duration>[reconnectDelay(5)]);
      network.serverReturns();
      await settle();
      expect(connection.status.phase, ConnectionPhase.online);
    });

    test('should forget the fast retry once online again', () async {
      await connection.connect();
      await network.serverStops();
      await settle();
      network.serverReturns();
      await settle();
      expect(connection.status.phase, ConnectionPhase.online);
      waits.releaseAll();
      await settle();
      network.down.addAll(ConnectionPath.values);
      await network.lastStream.end();
      await settle();
      expect(waits.durations, <Duration>[const Duration(seconds: 1)]);
    });

    test('should stay subscribed on the relay while the server is away and reconnect the moment it is back', () async {
      network.down.add(ConnectionPath.direct);
      await connection.connect();
      expect(connection.status.path, ConnectionPath.relay);
      final int relays = network.opened
          .where((ConnectionPath path) => path == ConnectionPath.relay)
          .length;
      await network.serverLeaves();
      await settle();
      expect(connection.status.phase, ConnectionPhase.reconnecting);
      expect(connection.status.message, 'Server restarting. Reconnecting.');
      expect(
        network.transports.where(
          (FakeTransport transport) =>
              transport.path == ConnectionPath.relay && !transport.closed,
        ),
        hasLength(1),
        reason: 'the relay socket stays open',
      );
      waits.releaseAll();
      await settle();
      expect(connection.status.phase, ConnectionPhase.reconnecting);
      expect(
        network.opened
            .where((ConnectionPath path) => path == ConnectionPath.relay)
            .length,
        relays,
        reason: 'no new relay socket per retry',
      );
      network.serverReturns();
      await settle();
      expect(
        connection.status.phase,
        ConnectionPhase.online,
        reason: 'online without waiting out the backoff',
      );
      expect(connection.status.path, ConnectionPath.relay);
      expect(network.lastStream.isOpen, isTrue);
    });

    test('should read the page each time it waits', () async {
      bool visible = true;
      connection = make(visible: () => visible);
      network.down.addAll(ConnectionPath.values);
      await connection.connect();
      clock = clock.add(const Duration(minutes: 2));
      waits.releaseAll();
      await settle();
      expect(waits.durations, <Duration>[const Duration(seconds: 5)]);
      visible = false;
      waits.releaseAll();
      await settle();
      expect(waits.durations, <Duration>[const Duration(seconds: 30)]);
    });

    test('should take connect while waiting as retry now, even right after retryNow', () async {
      network.down.addAll(ConnectionPath.values);
      await connection.connect();
      network.down.clear();
      connection.retryNow();
      await connection.connect();
      await settle();
      expect(connection.status.phase, ConnectionPhase.online);
    });

    test('should go offline and stop retrying on disconnect', () async {
      await connection.connect();
      await connection.disconnect();
      expect(connection.status.phase, ConnectionPhase.offline);
      expect(network.lastStream.isOpen, isFalse);
      await settle();
      expect(connection.status.phase, ConnectionPhase.offline);
    });
  });

  group('reconnect after pairing again', () {
    const String freshToken = 'gls_tok_0002EfGh.bmV3';

    ServerToken fresh() => ServerToken(
      token: freshToken,
      tokenId: 'tok_0002EfGh',
      scopes: const <String>['read'],
      expiresAt: DateTime.utc(2030),
    );

    test('should reopen an online session with the new token without going offline', () async {
      await connection.connect();
      final FakeSubscription old = network.lastStream;
      network.tokens.add(freshToken);
      phases.clear();
      connection.token = fresh();
      await connection.reconnect();
      await settle();
      expect(connection.status.phase, ConnectionPhase.online);
      expect(old.isOpen, isFalse);
      expect(network.lastStream.token, freshToken);
      expect(phases, isNot(contains(ConnectionPhase.offline)));
      expect(waits.pending, isEmpty, reason: 'no backoff before the new session');
    });

    test('should connect a stopped connection', () async {
      network.tokens.add(freshToken);
      connection = make(
        withToken: ServerToken(
          token: 'gls_tok_old.c2VjcmV0',
          tokenId: 'tok_old',
          scopes: const <String>['read'],
          expiresAt: DateTime.utc(2030),
        ),
      );
      await connection.connect();
      expect(connection.status.phase, ConnectionPhase.needsPairing);
      connection.token = fresh();
      await connection.reconnect();
      expect(connection.status.phase, ConnectionPhase.online);
    });
  });

  group('requests', () {
    test('should send the bearer token and a strictly increasing counter on counted requests only', () async {
      network.handler = serverHandler(rest: ok);
      await connection.connect();
      for (int index = 0; index < 2; index++) {
        await connection.request(
          const ApiRequest('POST', '/v1/changes', body: <String, Object?>{}, counted: true),
        );
      }
      await connection.request(const ApiRequest('GET', '/v1/catalog'));
      await connection.request(
        const ApiRequest('POST', '/v1/documents/read', body: <String, Object?>{'refs': <Object?>[]}),
      );
      final List<(ConnectionPath, int)> sent = counters();
      expect(sent, hasLength(2));
      expect(sent.last.$2, greaterThan(sent.first.$2));
      expect(
        network.sent
            .where(((ConnectionPath, ApiRequest) sent) => sent.$2.path != '/v1/hello')
            .every(((ConnectionPath, ApiRequest) sent) => sent.$2.headers['Authorization'] == 'Bearer $liveToken'),
        isTrue,
      );
    });

    test('should start the counter above the one the token was paired with', () async {
      final int stored = clock.millisecondsSinceEpoch + 100000;
      connection = make(withToken: liveServerToken.copyWith(counter: stored));
      network.handler = serverHandler(rest: ok);
      await connection.connect();
      await connection.request(
        const ApiRequest('POST', '/v1/changes', body: <String, Object?>{}, counted: true),
      );
      expect(counters().single.$2, stored + 1);
    });

    test('should send a replayed counted request once more above the server counter', () async {
      final int seen = clock.millisecondsSinceEpoch + 50000;
      int replays = 1;
      network.handler = serverHandler(
        rest: (ApiRequest request) => replays-- > 0
            ? refusal(409, 'counter-replayed', extra: <String, Object?>{'counter': seen})
            : const ApiResponse(200, <String, Object?>{'applied': true}),
      );
      await connection.connect();
      final ApiResponse answer = await connection.request(
        const ApiRequest('POST', '/v1/changes', body: <String, Object?>{}, counted: true),
      );
      expect(answer.ok, isTrue);
      final List<(ConnectionPath, int)> sent = counters();
      expect(sent, hasLength(2));
      expect(sent.last.$2, greaterThan(seen));
      await connection.request(
        const ApiRequest('POST', '/v1/changes', body: <String, Object?>{}, counted: true),
      );
      expect(counters().last.$2, greaterThan(sent.last.$2));
    });

    test('should retry a replayed counted request only once', () async {
      network.handler = serverHandler(
        rest: (ApiRequest request) => refusal(409, 'counter-replayed', extra: <String, Object?>{'counter': 1}),
      );
      await connection.connect();
      final ApiResponse answer = await connection.request(
        const ApiRequest('POST', '/v1/changes', body: <String, Object?>{}, counted: true),
      );
      expect(answer.errorCode, 'counter-replayed');
      expect(network.sentTo('/v1/changes'), hasLength(2));
    });

    test('should not resend an uncounted request answered counter-replayed', () async {
      network.handler = serverHandler(
        rest: (ApiRequest request) => refusal(409, 'counter-replayed', extra: <String, Object?>{'counter': 1}),
      );
      await connection.connect();
      await connection.request(const ApiRequest('GET', '/v1/catalog'));
      expect(network.sentTo('/v1/catalog'), hasLength(1));
    });

    test('should ask to pair again when a request is refused for the token', () async {
      network.handler = serverHandler(rest: (ApiRequest request) => refusal(401, 'token-revoked'));
      await connection.connect();
      final ApiResponse answer = await connection.request(const ApiRequest('GET', '/v1/catalog'));
      expect(answer.status, 401);
      expect(connection.status.phase, ConnectionPhase.needsPairing);
      expect(connection.token, isNull);
    });

    test('should keep at most $maxRequestsInFlight requests in flight and send the rest as answers arrive', () async {
      network.handler = serverHandler(rest: ok);
      await connection.connect();
      final List<Completer<void>> held = <Completer<void>>[];
      network.gate = (ConnectionPath path, ApiRequest request) {
        if (request.path != '/v1/catalog') return Future<void>.value();
        final Completer<void> answer = Completer<void>();
        held.add(answer);
        return answer.future;
      };
      final List<Future<ApiResponse>> answers = <Future<ApiResponse>>[
        for (int index = 0; index < maxRequestsInFlight + 4; index++)
          connection.request(const ApiRequest('GET', '/v1/catalog')),
      ];
      await settle();
      expect(held, hasLength(maxRequestsInFlight));
      held.first.complete();
      await settle();
      expect(held, hasLength(maxRequestsInFlight + 1));
      while (held.any((Completer<void> answer) => !answer.isCompleted)) {
        for (final Completer<void> answer in held.toList()) {
          if (!answer.isCompleted) answer.complete();
        }
        await settle();
      }
      expect((await Future.wait(answers)).every((ApiResponse answer) => answer.ok), isTrue);
      expect(held, hasLength(maxRequestsInFlight + 4));
    });

    test('should retry a rate-limited read after the server hint, at most five times, and return the last answer', () async {
      int refusals = 3;
      network.handler = serverHandler(
        rest: (ApiRequest request) => refusals-- > 0
            ? refusal(429, 'rate-limited', extra: <String, Object?>{'retryAfterMillis': 20})
            : const ApiResponse(200, <String, Object?>{'documents': <Object?>[]}),
      );
      final List<Duration> retried = <Duration>[];
      // Retry waits pass at once; the handshake budget never runs out.
      connection = ServerConnection(
        profile: bothPaths,
        token: liveServerToken,
        open: network.open,
        delay: (Duration wait) {
          if (wait == handshakeBudget) return Completer<void>().future;
          retried.add(wait);
          return Future<void>.value();
        },
        handshakeTimeout: handshakeBudget,
      );
      await connection.connect();
      final ApiResponse answer = await connection.requestRetrying(const ApiRequest('GET', '/v1/catalog'));
      expect(answer.ok, isTrue);
      expect(retried, hasLength(3));
      expect(retried.every((Duration wait) => wait >= const Duration(milliseconds: 20)), isTrue);
      refusals = 100;
      final ApiResponse refused = await connection.requestRetrying(const ApiRequest('GET', '/v1/catalog'));
      expect(refused.errorCode, 'rate-limited');
      expect(refused.retryAfter, const Duration(milliseconds: 20));
      expect(network.sentTo('/v1/catalog'), hasLength(4 + 1 + retryLimit));
    });

    test('should not retry a refusal without a hint', () async {
      network.handler = serverHandler(rest: (ApiRequest request) => refusal(403, 'scope-missing'));
      await connection.connect();
      final ApiResponse answer = await connection.requestRetrying(const ApiRequest('GET', '/v1/catalog'));
      expect(answer.errorCode, 'scope-missing');
      expect(network.sentTo('/v1/catalog'), hasLength(1));
    });

    test('should let a burst of 38 requests go at once, pace the rest and wait out a refusal', () async {
      final RequestPacer pacer = RequestPacer();
      final Stopwatch watch = Stopwatch()..start();
      for (int index = 0; index < 38; index++) {
        await pacer.take();
      }
      expect(watch.elapsedMilliseconds, lessThan(40));
      await pacer.take();
      expect(watch.elapsedMilliseconds, greaterThanOrEqualTo(40), reason: 'the 39th waits for a token');
      pacer.hold(const Duration(milliseconds: 150));
      final int held = watch.elapsedMilliseconds;
      await pacer.take();
      expect(watch.elapsedMilliseconds - held, greaterThanOrEqualTo(140));
    });
  });

  group('direct recovery while on the relay', () {
    const Duration overlap = Duration(seconds: 2);

    Future<void> onRelay() async {
      network.down.add(ConnectionPath.direct);
      await connection.connect();
      expect(connection.status.path, ConnectionPath.relay);
    }

    /// Lets the next probe run against a reachable direct path.
    Future<void> probeRecovers() async {
      network.down.remove(ConnectionPath.direct);
      expect(waits.release(const Duration(seconds: 15)), 1);
      await settle();
    }

    Iterable<FakeSubscription> directStreams() => <FakeSubscription>[
      for (final (ConnectionPath path, FakeSubscription stream) in network.streams)
        if (path == ConnectionPath.direct) stream,
    ];

    test('should probe the direct path after 15, 30 and then every 60 seconds', () async {
      await onRelay();
      expect(waits.durations, <Duration>[const Duration(seconds: 15)]);
      final FakeSubscription relayStream = network.streamOn(ConnectionPath.relay);
      for (final Duration wait in <Duration>[
        const Duration(seconds: 15),
        const Duration(seconds: 30),
        const Duration(seconds: 60),
      ]) {
        expect(waits.release(wait), 1);
        await settle();
      }
      expect(waits.durations, <Duration>[const Duration(seconds: 60)]);
      expect(network.opened.where((ConnectionPath path) => path == ConnectionPath.direct).length, 4);
      expect(connection.status.phase, ConnectionPhase.online);
      expect(connection.status.path, ConnectionPath.relay);
      expect(relayStream.isOpen, isTrue);
    });

    test('should probe the direct path at once when a connect comes online over the relay without trying it', () async {
      connection = make(serverProfile: bothPaths.copyWith(lastPath: ConnectionPath.relay));
      await connection.connect();
      await settle();
      expect(connection.status.path, ConnectionPath.relay);
      expect(directStreams(), hasLength(1), reason: 'probed with no 15 second wait');
      expect(waits.durations, <Duration>[overlap]);
      waits.release(overlap);
      await settle();
      expect(connection.status.phase, ConnectionPhase.online);
      expect(connection.status.path, ConnectionPath.direct);
    });

    test('should wait 15 seconds after a failed first probe of a relay-first connect', () async {
      connection = make(serverProfile: bothPaths.copyWith(lastPath: ConnectionPath.relay));
      network.down.add(ConnectionPath.direct);
      await connection.connect();
      await settle();
      expect(connection.status.path, ConnectionPath.relay);
      expect(network.opened, <ConnectionPath>[ConnectionPath.relay, ConnectionPath.direct]);
      expect(waits.durations, <Duration>[const Duration(seconds: 15)]);
    });

    test('should keep the overlap and its relay frames when asked to probe during it', () async {
      await onRelay();
      final List<String> seen = <String>[];
      connection.events.listen((Map<String, Object?> event) {
        if (event['type'] == 'preview') seen.add(event['previewId']! as String);
      });
      final FakeSubscription relayStream = network.streamOn(ConnectionPath.relay);
      await probeRecovers();
      expect(waits.durations, contains(overlap));
      connection.probeNow();
      await settle();
      expect(connection.status.path, ConnectionPath.relay, reason: 'the overlap still runs');
      expect(relayStream.isOpen, isTrue);
      relayStream.emit(<String, Object?>{'type': 'preview', 'previewId': 'in-transit', 'state': 'running'});
      await settle();
      expect(seen, <String>['in-transit']);
      waits.release(overlap);
      await settle();
      expect(connection.status.path, ConnectionPath.direct);
    });

    test('should drop the probe at once when the relay session ends during the overlap', () async {
      await onRelay();
      await probeRecovers();
      final FakeSubscription probeStream = network.streamOn(ConnectionPath.direct);
      expect(waits.durations, contains(overlap));
      await connection.disconnect();
      await settle();
      expect(probeStream.isOpen, isFalse);
      expect(connection.status.phase, ConnectionPhase.offline);
    });

    test('should not probe while online over the direct path', () async {
      await connection.connect();
      expect(connection.status.path, ConnectionPath.direct);
      expect(waits.pending, isEmpty);
    });

    test('should probe at once when asked', () async {
      await onRelay();
      network.down.remove(ConnectionPath.direct);
      connection.probeNow();
      await settle();
      expect(directStreams(), hasLength(1));
      expect(waits.durations, contains(overlap));
    });

    test('should switch back to direct once the direct stream outlived the overlap, never leaving online', () async {
      await onRelay();
      final FakeSubscription relayStream = network.streamOn(ConnectionPath.relay);
      await probeRecovers();
      expect(network.streamOn(ConnectionPath.direct).isOpen, isTrue);
      expect(connection.status.path, ConnectionPath.relay, reason: 'not committed before the overlap');
      expect(relayStream.isOpen, isTrue);
      phases.clear();
      waits.release(overlap);
      await settle();
      expect(connection.status.phase, ConnectionPhase.online);
      expect(connection.status.path, ConnectionPath.direct);
      expect(connection.profile.lastPath, ConnectionPath.direct);
      expect(phases, <ConnectionPhase>[ConnectionPhase.online], reason: 'the switch never leaves online');
      expect(relayStream.isOpen, isFalse);
      network.handler = serverHandler(rest: ok);
      await connection.request(const ApiRequest('GET', '/v1/catalog'));
      expect(network.sent.last.$1, ConnectionPath.direct);
    });

    test('should not switch while the direct stream withholds its open frame', () async {
      await onRelay();
      network.withholdOpen = true;
      await probeRecovers();
      expect(directStreams(), hasLength(1));
      expect(waits.durations, isNot(contains(overlap)));
      expect(connection.status.path, ConnectionPath.relay);
    });

    test('should close a probe whose direct stream never opens within the budget and probe again later', () async {
      await onRelay();
      network.withholdOpen = true;
      await probeRecovers();
      final FakeTransport stuck = network.transportOn(ConnectionPath.direct);
      waits.expireBudgets();
      await settle();
      expect(stuck.closed, isTrue);
      expect(waits.durations, <Duration>[const Duration(seconds: 30)]);
      network.withholdOpen = false;
      waits.release(const Duration(seconds: 30));
      await settle();
      waits.release(overlap);
      await settle();
      expect(connection.status.phase, ConnectionPhase.online);
      expect(connection.status.path, ConnectionPath.direct);
    });

    test('should finish a request sent over the relay after the switch commits', () async {
      network.handler = serverHandler(
        rest: (ApiRequest request) => const ApiResponse(200, <String, Object?>{'applied': true}),
      );
      await onRelay();
      final FakeTransport relay = network.transportOn(ConnectionPath.relay);
      final Completer<void> held = Completer<void>();
      network.gate = (ConnectionPath path, ApiRequest request) async {
        if (path == ConnectionPath.relay && request.path == '/v1/changes') await held.future;
      };
      final Future<ApiResponse> inFlight = connection.request(
        const ApiRequest('POST', '/v1/changes', body: <String, Object?>{}, counted: true),
      );
      await probeRecovers();
      waits.release(overlap);
      await settle();
      expect(connection.status.path, ConnectionPath.direct);
      expect(relay.closed, isFalse, reason: 'kept open for the request in flight');
      held.complete();
      final ApiResponse response = await inFlight;
      expect(response.ok, isTrue);
      expect(response.body, <String, Object?>{'applied': true});
      await settle();
      expect(relay.closed, isTrue);
      expect(connection.status.path, ConnectionPath.direct);
    });

    test('should deliver every event once across the overlap', () async {
      await onRelay();
      final List<String> seen = <String>[];
      connection.events.listen((Map<String, Object?> event) {
        if (event['type'] == 'preview') seen.add(event['previewId']! as String);
      });
      Map<String, Object?> event(String id) => <String, Object?>{
        'type': 'preview',
        'previewId': id,
        'state': 'running',
      };
      final FakeSubscription relayStream = network.streamOn(ConnectionPath.relay);
      relayStream.emit(event('before'));
      await probeRecovers();
      final FakeSubscription directStream = network.streamOn(ConnectionPath.direct);
      relayStream.emit(event('relay-only'));
      directStream.emit(event('direct-first'));
      relayStream.emit(event('direct-first'));
      relayStream.emit(event('relay-first'));
      directStream.emit(event('relay-first'));
      relayStream.emit(event('relay-ahead'));
      waits.release(overlap);
      await settle();
      expect(connection.status.path, ConnectionPath.direct);
      directStream.emit(event('relay-ahead'));
      directStream.emit(event('after'));
      expect(seen, <String>['before', 'relay-only', 'direct-first', 'relay-first', 'relay-ahead', 'after']);
    });

    test('should stay on the relay when the direct path answers with another fingerprint', () async {
      await onRelay();
      final FakeSubscription relayStream = network.streamOn(ConnectionPath.relay);
      network.pathHandlers[ConnectionPath.direct] = serverHandler(fingerprint: 'sha256:${'0' * 64}');
      await probeRecovers();
      expect(connection.status.phase, ConnectionPhase.online);
      expect(connection.status.path, ConnectionPath.relay);
      expect(relayStream.isOpen, isTrue);
      expect(
        network.subscribed.where(((ConnectionPath, String) stream) => stream.$1 == ConnectionPath.direct),
        isEmpty,
        reason: 'no token to the wrong server',
      );
      expect(waits.durations, <Duration>[const Duration(seconds: 30)]);
    });

    test('should stay on the relay and keep the token when the direct stream closes during the overlap', () async {
      await onRelay();
      final FakeSubscription relayStream = network.streamOn(ConnectionPath.relay);
      await probeRecovers();
      await network.streamOn(ConnectionPath.direct).end('4401 unauthorized');
      await settle();
      expect(connection.status.phase, ConnectionPhase.online);
      expect(connection.status.path, ConnectionPath.relay);
      expect(relayStream.isOpen, isTrue);
      expect(connection.token, isNotNull);
      expect(waits.durations, contains(const Duration(seconds: 30)));
    });

    test('should keep X-Gloss-Counter strictly increasing across the switch', () async {
      network.handler = serverHandler(rest: ok);
      await onRelay();
      for (int index = 0; index < 2; index++) {
        await connection.request(
          const ApiRequest('POST', '/v1/changes', body: <String, Object?>{}, counted: true),
        );
      }
      await probeRecovers();
      waits.release(overlap);
      await settle();
      expect(connection.status.path, ConnectionPath.direct);
      await connection.request(
        const ApiRequest('POST', '/v1/changes', body: <String, Object?>{}, counted: true),
      );
      final List<(ConnectionPath, int)> sent = counters();
      expect(
        <ConnectionPath>[for (final (ConnectionPath path, int _) in sent) path],
        <ConnectionPath>[ConnectionPath.relay, ConnectionPath.relay, ConnectionPath.direct],
      );
      for (int index = 1; index < sent.length; index++) {
        expect(sent[index].$2, greaterThan(sent[index - 1].$2));
      }
    });

    test('should fail over to the relay again when the recovered direct path drops', () async {
      await onRelay();
      await probeRecovers();
      waits.release(overlap);
      await settle();
      network.down.add(ConnectionPath.direct);
      await network.streamOn(ConnectionPath.direct).end();
      await settle();
      expect(connection.status.phase, ConnectionPhase.online);
      expect(connection.status.path, ConnectionPath.relay);
      expect(waits.durations, contains(const Duration(seconds: 15)));
    });
  });

  group('handshake budget', () {
    test('should give up on a relay whose hello never answers and go online over the direct path', () async {
      connection = make(serverProfile: bothPaths.copyWith(lastPath: ConnectionPath.relay));
      network.gate = (ConnectionPath path, ApiRequest request) =>
          path == ConnectionPath.relay && request.path == '/v1/hello' ? Completer<void>().future : Future<void>.value();
      final Future<void> firstRound = connection.connect();
      await settle();
      expect(connection.status.phase, ConnectionPhase.connecting);
      expect(connection.status.path, ConnectionPath.relay);
      expect(waits.budgets, hasLength(1));
      waits.expireBudgets();
      await firstRound;
      expect(connection.status.phase, ConnectionPhase.online);
      expect(connection.status.path, ConnectionPath.direct);
      expect(network.transportOn(ConnectionPath.relay).closed, isTrue);
      expect(connection.profile.lastPath, ConnectionPath.direct);
    });

    test('should close a path that opens after its budget ran out and blame the relay', () async {
      final Completer<ServerTransport> opening = Completer<ServerTransport>();
      connection = make(serverProfile: relayOnly, open: (ConnectionPath path, String url) => opening.future);
      await Future.wait(<Future<void>>[
        connection.connect(),
        settle().then((void _) => waits.expireBudgets()),
      ]);
      expect(connection.status.phase, ConnectionPhase.reconnecting);
      expect(connection.status.problem, transportCopy('relay-unreachable'));
      final FakeTransport late = FakeRelayTransport(network);
      opening.complete(late);
      await settle();
      expect(late.closed, isTrue);
      expect(network.streams, isEmpty);
      expect(connection.status.phase, ConnectionPhase.reconnecting);
    });

    test('should not cut a path that finished its handshake inside the budget', () async {
      await connection.connect();
      waits.expireBudgets();
      await settle();
      expect(connection.status.phase, ConnectionPhase.online);
      expect(network.lastStream.isOpen, isTrue);
    });

    test('should not go online when hello answers after a disconnect', () async {
      final Completer<void> held = Completer<void>();
      network.gate = (ConnectionPath path, ApiRequest request) =>
          request.path == '/v1/hello' ? held.future : Future<void>.value();
      unawaited(connection.connect());
      await settle();
      await connection.disconnect();
      held.complete();
      await settle();
      expect(connection.status.phase, ConnectionPhase.offline);
      expect(network.streams, isEmpty);
      expect(network.transports.every((FakeTransport transport) => transport.closed), isTrue);
    });

    test('should keep one stream and deliver each event once when connect follows a disconnect during a hello', () async {
      final Completer<void> held = Completer<void>();
      int hellos = 0;
      network.gate = (ConnectionPath path, ApiRequest request) {
        if (request.path == '/v1/hello' && ++hellos == 1) return held.future;
        return Future<void>.value();
      };
      unawaited(connection.connect());
      await settle();
      await connection.disconnect();
      unawaited(connection.connect());
      await settle();
      held.complete();
      await settle();
      List<FakeSubscription> open() => <FakeSubscription>[
        for (final (ConnectionPath _, FakeSubscription stream) in network.streams)
          if (stream.isOpen) stream,
      ];
      expect(connection.status.phase, ConnectionPhase.online);
      expect(open(), hasLength(1), reason: 'the first loop never opens a stream');
      int catalogs = 0;
      connection.events.listen((Map<String, Object?> event) {
        if (event['type'] == 'catalog') catalogs++;
      });
      for (final FakeSubscription stream in open()) {
        stream.emit(<String, Object?>{'type': 'catalog', 'documents': <Object?>[]});
      }
      await settle();
      expect(catalogs, 1);
      await connection.disconnect();
      await settle();
      expect(open(), isEmpty);
      expect(connection.status.phase, ConnectionPhase.offline);
    });

    test('should not go online when the open frame arrives after a disconnect', () async {
      network.withholdOpen = true;
      unawaited(connection.connect());
      await settle();
      await connection.disconnect();
      network.lastStream.emit(network.openFrame());
      await settle();
      expect(connection.status.phase, ConnectionPhase.offline);
      expect(network.lastStream.isOpen, isFalse);
      expect(network.transports.every((FakeTransport transport) => transport.closed), isTrue);
    });

    test('should keep a parked relay open when the budget runs out after the server left it', () async {
      connection = make(serverProfile: relayOnly);
      network.serverOnRelay = false;
      await connection.connect();
      expect(connection.status.problem, transportCopy('server-offline'));
      waits.expireBudgets();
      await settle();
      expect(network.transports.single.closed, isFalse, reason: 'the parked relay hears the server come back');
    });
  });
}
