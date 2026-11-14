import 'dart:async';
import 'dart:convert';

import 'package:gloss_editor/components/server/connection_words.dart';
import 'package:gloss_editor/services/server/error_copy.dart';
import 'package:gloss_editor/services/server/page_activity.dart';
import 'package:gloss_editor/services/server/pairing.dart';
import 'package:gloss_editor/services/server/server_connection.dart';
import 'package:gloss_editor/services/server/server_hub.dart';
import 'package:gloss_editor/services/server/server_profiles.dart';
import 'package:gloss_editor/services/server/transport.dart';
import 'package:test/test.dart';

import 'support/fake_transport.dart';

void main() {
  late FakeNetwork network;
  late Waits waits;
  late DateTime clock;
  late MemoryKeyValueStore local;
  late MemoryKeyValueStore session;
  final List<ServerHub> made = <ServerHub>[];

  ServerHub hub(ServerProfileStore profiles, {List<String>? forgotten}) {
    final ServerHub servers = ServerHub(
      profiles: profiles,
      open: (ConnectionPath path, String url, String fingerprint) => network.open(path, url),
      onForget: (String id) async => forgotten?.add(id),
      delay: waits.call,
      pageOrigin: () => 'https://gloss.volmitsoftware.com',
      handshakeTimeout: handshakeBudget,
      now: () => clock,
    );
    made.add(servers);
    return servers;
  }

  ServerProfileStore store() => ServerProfileStore(local: local, session: session);

  /// A store holding [bothPaths] and, when [withToken], its token.
  ServerProfileStore saved({bool withToken = true, bool remember = false, ServerToken? token}) {
    final ServerProfileStore profiles = store();
    final ServerProfile profile = bothPaths.copyWith(remember: remember);
    profiles.save(<ServerProfile>[profile]);
    if (withToken) profiles.saveToken(profile, token ?? liveServerToken);
    return profiles;
  }

  final String id = bothPaths.id;
  final ServerToken oldToken = ServerToken(
    token: 'gls_tok_old.c2VjcmV0',
    tokenId: 'tok_old',
    scopes: const <String>['read'],
    expiresAt: DateTime.utc(2030),
  );

  setUp(() {
    network = FakeNetwork(pairingServer());
    waits = Waits();
    clock = DateTime.utc(2026, 11, 4, 12);
    local = MemoryKeyValueStore();
    session = MemoryKeyValueStore();
  });

  tearDown(() {
    for (final ServerHub servers in made) {
      servers.dispose();
    }
    made.clear();
    PageActivity.hidden = false;
  });

  group('pairing', () {
    test('should keep a remembered token in local storage for every tab', () async {
      final ServerHub servers = hub(store());
      final String paired = await servers.pair(fakeCode(), remember: true, client: 'Chrome on macOS');
      expect(paired, id);
      expect(local.values[ServerProfileStore.tokenKey(id)], contains(liveToken));
      expect(session.values[ServerProfileStore.tokenKey(id)], contains(liveToken));
      final ServerProfile profile = servers.server(id)!;
      expect(profile.remember, isTrue);
      expect(profile.name, 'Twin Peaks');
      expect(profile.lastPath, ConnectionPath.direct);
      final ServerProfileStore otherTab = ServerProfileStore(local: local, session: MemoryKeyValueStore());
      expect(otherTab.token(otherTab.load().single)!.token, liveToken);
      expect(servers.storageRefused, isFalse);
    });

    test('should keep a token this tab only when not remembered', () async {
      final ServerHub servers = hub(store());
      await servers.pair(fakeCode(), remember: false);
      expect(local.values.containsKey(ServerProfileStore.tokenKey(id)), isFalse);
      expect(session.values[ServerProfileStore.tokenKey(id)], contains(liveToken));
      expect(servers.hasToken(id), isTrue);
      final ServerProfileStore otherTab = ServerProfileStore(local: local, session: MemoryKeyValueStore());
      expect(otherTab.load().single.id, id, reason: 'the server is listed in every tab');
      expect(otherTab.token(otherTab.load().single), isNull);
    });

    test('should say when the browser refused to store the pairing', () async {
      local = MemoryKeyValueStore(refuseWrites: true);
      final ServerHub servers = hub(store());
      await servers.pair(fakeCode(), remember: true);
      expect(servers.storageRefused, isTrue);
    });

    test('should refuse an expired link before sending anything', () async {
      final ServerHub servers = hub(store());
      await expectLater(
        servers.pair(fakeCode(expiresAt: clock.subtract(const Duration(minutes: 1))), remember: true),
        throwsA(isA<PairingRefused>().having((PairingRefused refused) => refused.failure, 'failure', PairingFailure.expired)),
      );
      expect(network.opened, isEmpty);
      expect(servers.servers, isEmpty);
    });

    test('should tell whether the relay answers before pairing', () async {
      final ServerHub servers = hub(store());
      network.down.add(ConnectionPath.relay);
      expect(await servers.relayAnswers(fakeCode()), isFalse);
      network.down.remove(ConnectionPath.relay);
      expect(await servers.relayAnswers(fakeCode()), isTrue);
      expect(network.transportOn(ConnectionPath.relay).closed, isTrue);
      expect(network.sent, isEmpty, reason: 'nothing is sent, the secret least of all');
      expect(servers.servers, isEmpty);
    });

    test('should tell whether the direct address refuses this page before pairing', () async {
      final ServerHub servers = hub(store());
      expect(await servers.directRefusesOrigin(fakeCode()), isFalse);
      network.down.add(ConnectionPath.direct);
      network.failWith[ConnectionPath.direct] = const TransportFailure(
        ConnectionPath.direct,
        'origin-not-allowed',
        'probe answered',
        'https://editor.example',
      );
      expect(await servers.directRefusesOrigin(fakeCode()), isTrue);
      network.failWith.clear();
      expect(await servers.directRefusesOrigin(fakeCode()), isFalse, reason: 'a silent server is no refusal');
      expect(await servers.directRefusesOrigin(fakeCode(directUrl: null)), isFalse);
      expect(
        network.sent.where(((ConnectionPath, ApiRequest) sent) => sent.$2.path == '/v1/pair'),
        isEmpty,
        reason: 'the secret is never sent',
      );
      expect(servers.servers, isEmpty);
    });

    test('should refuse text that is not a pairing link', () async {
      final ServerHub servers = hub(store());
      await expectLater(servers.pair('https://example.net/', remember: true), throwsA(isA<PairingCodeError>()));
      expect(network.opened, isEmpty);
    });

    test('should keep an open connection when paired again and reconnect it with the new token', () async {
      const String freshToken = 'gls_tok_0002EfGh.bmV3';
      final ServerProfileStore profiles = saved(remember: true);
      final ServerHub servers = hub(profiles);
      await servers.connect(id);
      final ServerConnection before = servers.connection(id);
      final FakeSubscription oldStream = network.lastStream;
      expect(before.status.phase, ConnectionPhase.online);
      network.handler = pairingServer(answer: () => pairAnswer(token: freshToken, tokenId: 'tok_0002EfGh'));
      network.tokens.add(freshToken);
      final List<ConnectionPhase> phases = <ConnectionPhase>[];
      before.statuses.listen((ConnectionStatus status) => phases.add(status.phase));
      expect(await servers.pair(fakeCode(tokenId: 'tok_0002EfGh'), remember: true), id);
      await settle();
      expect(servers.connection(id), same(before));
      expect(before.token!.token, freshToken);
      expect(before.status.phase, ConnectionPhase.online);
      expect(oldStream.isOpen, isFalse, reason: 'the old token\'s stream is closed');
      expect(network.lastStream.token, freshToken);
      expect(phases, isNot(contains(ConnectionPhase.offline)));
      expect(profiles.token(servers.server(id)!)!.token, freshToken);
    });

    test('should connect a connection whose pairing ended once paired again', () async {
      final ServerProfileStore profiles = saved(token: oldToken);
      final ServerHub servers = hub(profiles);
      await servers.connect(id);
      expect(servers.status(id).phase, ConnectionPhase.needsPairing);
      await servers.pair(fakeCode(), remember: false);
      await settle();
      expect(servers.status(id).phase, ConnectionPhase.online);
      expect(network.lastStream.token, liveToken);
    });
  });

  group('tokens', () {
    test('should save the expiry the open frame moved', () async {
      network.openExpiresAt = DateTime.utc(2026, 11, 11, 12);
      final ServerProfileStore profiles = saved();
      final ServerHub servers = hub(profiles);
      await servers.connect(id);
      expect(profiles.token(servers.server(id)!)!.expiresAt, DateTime.utc(2026, 11, 11, 12));
      expect(servers.token(id)!.expiresAt, DateTime.utc(2026, 11, 11, 12));
    });

    test('should forget a token the server refused', () async {
      final ServerProfileStore profiles = saved(token: oldToken);
      final ServerHub servers = hub(profiles);
      await servers.connect(id);
      expect(servers.status(id).phase, ConnectionPhase.needsPairing);
      expect(profiles.token(servers.server(id)!), isNull);
      expect(servers.hasToken(id), isFalse);
    });

    test('should take a token another tab paired and connect with it', () async {
      final ServerProfileStore profiles = saved(token: oldToken, remember: true);
      final ServerHub servers = hub(profiles);
      await servers.connect(id);
      expect(servers.status(id).phase, ConnectionPhase.needsPairing);
      local.writeFromOtherTab(ServerProfileStore.tokenKey(id), jsonEncode(liveServerToken.toJson()));
      await settle();
      expect(servers.status(id).phase, ConnectionPhase.online);
      expect(network.lastStream.token, liveToken);
    });
  });

  group('forgetting a server', () {
    test('should remove its profile and token, call onForget and return the token the server still accepts', () async {
      final ServerProfileStore profiles = saved(remember: true);
      final List<String> forgotten = <String>[];
      final ServerHub servers = hub(profiles, forgotten: forgotten);
      await servers.connect(id);
      final FakeSubscription stream = network.lastStream;
      expect(servers.forgetPrompt(id), contains('/gloss editor revoke $liveTokenId'));
      expect(await servers.forget(id), liveTokenId);
      expect(servers.servers, isEmpty);
      expect(profiles.load(), isEmpty);
      expect(profiles.token(bothPaths), isNull);
      expect(local.values.containsKey(ServerProfileStore.tokenKey(id)), isFalse);
      expect(session.values.containsKey(ServerProfileStore.tokenKey(id)), isFalse);
      expect(forgotten, <String>[id]);
      expect(stream.isOpen, isFalse);
      expect(network.sent.where(((ConnectionPath, ApiRequest) sent) => sent.$2.method == 'DELETE'), isEmpty);
    });

    test('should return no token id and offer no revoke once the token expired', () async {
      final ServerHub servers = hub(saved());
      clock = DateTime.utc(2031);
      expect(servers.forgetPrompt(id), isNot(contains('revoke')));
      expect(await servers.forget(id), isNull);
      expect(servers.servers, isEmpty);
    });
  });

  group('other tabs', () {
    test('should reload the list when another tab changes it and close a removed server', () async {
      final ServerHub servers = hub(saved());
      await servers.connect(id);
      final FakeSubscription stream = network.lastStream;
      int changes = 0;
      servers.addListener(() => changes++);
      local.writeFromOtherTab(
        ServerProfileStore.profilesKey,
        jsonEncode(<Object?>[bothPaths.copyWith(name: 'Lobby').toJson()]),
      );
      expect(servers.server(id)!.name, 'Lobby');
      expect(changes, greaterThan(0));
      local.writeFromOtherTab(ServerProfileStore.profilesKey, '[]');
      await settle();
      expect(servers.servers, isEmpty);
      expect(stream.isOpen, isFalse);
    });
  });

  group('reachability', () {
    test('should check a saved server with one hello and no stream', () async {
      final ServerHub servers = hub(saved());
      servers.probeServers();
      expect(servers.status(id).phase, ConnectionPhase.connecting);
      expect(servers.status(id).probed, isTrue);
      await settle();
      expect(servers.status(id).phase, ConnectionPhase.online);
      expect(servers.status(id).path, ConnectionPath.direct);
      expect(network.sent.map(((ConnectionPath, ApiRequest) sent) => sent.$2.path), <String>['/v1/hello']);
      expect(network.streams, isEmpty);
      expect(network.transports.every((FakeTransport transport) => transport.closed), isTrue);
    });

    test('should say a saved server is not reachable when no path answers', () async {
      network.down.addAll(ConnectionPath.values);
      final ServerHub servers = hub(saved(withToken: false));
      servers.probeServers();
      await settle();
      expect(servers.status(id).phase, ConnectionPhase.offline);
      expect(servers.status(id).probed, isTrue);
      expect(servers.status(id).problem, transportCopy('no-answer'));
    });

    test('should check again only the servers that were not reachable', () async {
      network.down.addAll(ConnectionPath.values);
      final ServerHub servers = hub(saved(withToken: false));
      servers.probeServers();
      await settle();
      expect(servers.status(id).phase, ConnectionPhase.offline);
      network.down.clear();
      servers.probeUnreachable();
      await settle();
      expect(servers.status(id).phase, ConnectionPhase.online);
      final int sent = network.sent.length;
      servers.probeUnreachable();
      await settle();
      expect(network.sent.length, sent, reason: 'an online server is not asked again');
    });

    test('should not count a relay without a proof as reachable', () async {
      network.down.add(ConnectionPath.direct);
      network.handler = serverHandler(prove: false);
      final ServerHub servers = hub(saved());
      servers.probeServers();
      await settle();
      expect(servers.status(id).phase, ConnectionPhase.offline);
    });

    test('should not check a server whose live connection is up, and show that instead', () async {
      final ServerHub servers = hub(saved());
      await servers.connect(id);
      final int sent = network.sent.length;
      servers.probeServers();
      await settle();
      expect(network.sent.length, sent);
      expect(servers.status(id).phase, ConnectionPhase.online);
      expect(servers.status(id).probed, isFalse);
    });

    test('should keep one word while a lost server is retried', () async {
      final ServerHub servers = hub(saved());
      await servers.connect(id);
      final List<String> words = <String>[serverState(servers.status(id), paired: true).$1];
      servers.addListener(() {
        final String word = serverState(servers.status(id), paired: true).$1;
        if (words.last != word) words.add(word);
      });
      await network.serverLeaves();
      await settle();
      for (int round = 0; round < 4; round++) {
        waits.releaseAll();
        await settle();
      }
      expect(words, <String>['Online', 'Not reachable']);
      network.serverReturns();
      waits.releaseAll();
      await settle();
      expect(words, <String>['Online', 'Not reachable', 'Online']);
    });

    test('should keep Online while a dropped path moves to the other one', () async {
      final ServerHub servers = hub(saved());
      await servers.connect(id);
      expect(servers.status(id).path, ConnectionPath.direct);
      final List<String> words = <String>[serverState(servers.status(id), paired: true).$1];
      servers.addListener(() {
        final String word = serverState(servers.status(id), paired: true).$1;
        if (words.last != word) words.add(word);
      });
      network.down.add(ConnectionPath.direct);
      await network.lastStream.end();
      await settle();
      expect(servers.status(id).path, ConnectionPath.relay);
      expect(words, <String>['Online']);
    });

    test('should keep Not reachable while it checks an unreachable server again', () async {
      network.down.addAll(ConnectionPath.values);
      final ServerHub servers = hub(saved(withToken: false));
      servers.probeServers();
      await settle();
      expect(serverState(servers.status(id), paired: true).$1, 'Not reachable');
      servers.probeUnreachable();
      expect(serverState(servers.status(id), paired: true).$1, 'Not reachable');
      await settle();
      expect(serverState(servers.status(id), paired: true).$1, 'Not reachable');
    });

    test(
      'should share what one tab learns with every other tab',
      () async {
        final ServerHub owner = hub(saved(remember: true));
        final ServerHub other = hub(store());
        other.probeServers();
        await settle();
        expect(serverState(other.status(id), paired: true).$1, 'Online');
        await owner.connect(id);
        await network.serverLeaves();
        await settle();
        waits.releaseAll();
        await settle();
        final String key = ServerProfileStore.reachKey(id);
        expect(local.values[key], isNotNull, reason: 'the owner tab says so');
        local.writeFromOtherTab(key, local.values[key]);
        expect(serverState(other.status(id), paired: true).$1, 'Not reachable');
        network.serverReturns();
        waits.releaseAll();
        await settle();
        expect(owner.status(id).online, isTrue);
        local.writeFromOtherTab(key, local.values[key]);
        expect(serverState(other.status(id), paired: true).$1, 'Online');
      },
    );

    test('should take the name the server reports', () async {
      network.handler = serverHandler(serverName: 'Harbor Town');
      final ServerHub servers = hub(saved());
      servers.probeServers();
      await settle();
      expect(servers.server(id)!.name, 'Harbor Town');
    });

    test('should give up on a path that never opens', () async {
      final Stopwatch watch = Stopwatch()..start();
      final (ConnectionPath, String?)? found = await ServerConnection.probe(
        bothPaths,
        (ConnectionPath path, String url) => Completer<ServerTransport>().future,
        timeout: const Duration(milliseconds: 30),
      );
      expect(found, isNull);
      expect(watch.elapsed, lessThan(const Duration(seconds: 2)));
    });

    test('should reconnect at once on a browser wake instead of waiting out the long backoff', () async {
      PageActivity.hidden = true;
      network.down.addAll(ConnectionPath.values);
      final ServerHub servers = hub(saved());
      await servers.connect(id);
      clock = clock.add(const Duration(minutes: 2));
      expect(waits.release(const Duration(seconds: 1)), 1);
      await settle();
      expect(servers.status(id).phase, ConnectionPhase.reconnecting);
      expect(waits.durations, contains(const Duration(seconds: 30)), reason: 'two minutes down in a hidden tab');
      network.down.clear();
      PageActivity.hidden = false;
      servers.wake();
      await settle();
      expect(servers.status(id).phase, ConnectionPhase.online);
    });
  });
}
