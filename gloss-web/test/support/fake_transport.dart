/// In-memory paths to one fake Gloss server for the connection, pairing and
/// hub tests: the direct path and the relay (which stays open while the
/// server is away from it), event streams that start with the `open` frame,
/// and waits a test releases by hand.
library;

import 'dart:async';
import 'dart:convert';

import 'package:cryptography/cryptography.dart';
import 'package:gloss_editor/services/server/pairing.dart';
import 'package:gloss_editor/services/server/server_profiles.dart';
import 'package:gloss_editor/services/server/server_proof.dart';
import 'package:gloss_editor/services/server/transport.dart';

import '../../tool/fake_server/identity.dart'
    show fakeFingerprint, fakeSeed, fakeServerKey, pairingCode, proofHeaders;

/// Seed of a key that is not the paired server's.
final List<int> otherSeed = List<int>.generate(32, (int index) => index);

/// The bearer token the fake server accepts unless a test says otherwise.
const String liveToken = 'gls_tok_0001AbCd.ZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXp7fH1-f4CBgoN';
const String liveTokenId = 'tok_0001AbCd';

/// A pairing code's one-time secret: 43 base64url characters.
const String pairSecret = 'ZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXp7fH1-f4CBgoM';

final ServerToken liveServerToken = ServerToken(
  token: liveToken,
  tokenId: liveTokenId,
  scopes: const <String>['read'],
  expiresAt: DateTime.utc(2030),
);

/// A server paired with both paths, never connected.
const ServerProfile bothPaths = ServerProfile(
  fingerprint: fakeFingerprint,
  serverKey: fakeServerKey,
  name: 'Twin Peaks',
  directUrl: 'http://127.0.0.1:9799',
  relayUrl: 'ws://127.0.0.1:9797',
);

/// A server paired with the relay only.
const ServerProfile relayOnly = ServerProfile(
  fingerprint: fakeFingerprint,
  serverKey: fakeServerKey,
  name: 'Twin Peaks',
  relayUrl: 'ws://127.0.0.1:9797',
);

/// The handshake budget in these tests, apart from every backoff, probe and
/// overlap wait.
const Duration handshakeBudget = Duration(seconds: 7);

/// Waits held by the connection until a test releases them. Handshake
/// budgets are held apart in [budgets], so [pending] holds only the backoff,
/// probe and overlap waits.
class Waits {
  final List<(Duration, Completer<void>)> pending =
      <(Duration, Completer<void>)>[];
  final List<Completer<void>> budgets = <Completer<void>>[];

  Future<void> call(Duration duration) {
    final Completer<void> completer = Completer<void>();
    if (duration == handshakeBudget) {
      budgets.add(completer);
    } else {
      pending.add((duration, completer));
    }
    return completer.future;
  }

  /// Runs out every handshake budget started so far.
  void expireBudgets() {
    for (final Completer<void> budget in budgets) {
      if (!budget.isCompleted) budget.complete();
    }
    budgets.clear();
  }

  /// Releases the held waits of [duration]; returns how many.
  int release(Duration duration) {
    final List<(Duration, Completer<void>)> matching =
        <(Duration, Completer<void>)>[
          for (final (Duration, Completer<void>) wait in pending)
            if (wait.$1 == duration) wait,
        ];
    for (final (Duration, Completer<void>) wait in matching) {
      pending.remove(wait);
      if (!wait.$2.isCompleted) wait.$2.complete();
    }
    return matching.length;
  }

  List<Duration> get durations => <Duration>[
    for (final (Duration duration, Completer<void> _) in pending) duration,
  ];

  void releaseAll() {
    for (final (Duration _, Completer<void> completer) in pending) {
      if (!completer.isCompleted) completer.complete();
    }
    pending.clear();
  }
}

/// Lets every queued microtask and zero timer run.
Future<void> settle() async {
  for (int index = 0; index < 20; index++) {
    await Future<void>.delayed(Duration.zero);
  }
}

typedef FakeHandler = FutureOr<ApiResponse> Function(ApiRequest request);

/// An in-memory path: answers with the network's handler, or fails while
/// the path is down.
class FakeTransport implements ServerTransport {
  FakeTransport(this.path, this.network);

  @override
  final ConnectionPath path;
  final FakeNetwork network;
  final List<FakeSubscription> subscriptions = <FakeSubscription>[];
  bool closed = false;

  TransportFailure get _failure =>
      network.failWith[path] ??
      TransportFailure(
        path,
        path == ConnectionPath.relay ? 'dropped' : 'unreachable',
      );

  @override
  Future<ApiResponse> send(ApiRequest request) async {
    network.sent.add((path, request));
    if (closed || network.down.contains(path)) throw _failure;
    if (path == ConnectionPath.relay && !network.serverOnRelay) {
      return const ApiResponse(503, <String, Object?>{
        'error': <String, Object?>{'code': 'server-offline', 'message': 'away'},
      }, path: ConnectionPath.relay);
    }
    final Future<void> Function(ConnectionPath path, ApiRequest request)?
    gate = network.gate;
    if (gate != null) await gate(path, request);
    if (closed || network.down.contains(path)) throw _failure;
    final ApiResponse answer = await (network.pathHandlers[path] ??
        network.handler)(request);
    return ApiResponse(
      answer.status,
      answer.body,
      headers: answer.headers,
      path: path,
    );
  }

  @override
  Future<EventSubscription> subscribe(String token) async {
    network.subscribed.add((path, token));
    if (closed || network.down.contains(path)) throw _failure;
    if (path == ConnectionPath.relay && !network.serverOnRelay) {
      throw const StreamRefused(503, 'server-offline');
    }
    final StreamRefused? refusal = network.refuseStreamWith;
    if (refusal != null) throw refusal;
    final bool accepted = network.tokens.contains(token);
    // The relay answers the events request like any other request.
    if (!accepted && path == ConnectionPath.relay) {
      throw const StreamRefused(401, 'token-revoked');
    }
    final String? closes = accepted
        ? network.closeBeforeOpen[path]
        : '4401 token-revoked';
    final FakeSubscription subscription = FakeSubscription(
      path,
      token,
      first: closes == null && !network.withholdOpen
          ? network.openFrame()
          : null,
      refusal: closes,
    );
    subscriptions.add(subscription);
    network.streams.add((path, subscription));
    return subscription;
  }

  @override
  Future<void> close() async {
    closed = true;
    for (final FakeSubscription subscription in subscriptions) {
      await subscription.cancel();
    }
  }
}

/// The relay path: it stays open while the server is away from the relay.
class FakeRelayTransport extends FakeTransport implements RelayLink {
  FakeRelayTransport(FakeNetwork network)
    : super(ConnectionPath.relay, network);

  @override
  bool get serverOnline => network.serverOnRelay && !closed;

  @override
  bool get isClosed => closed;

  @override
  Future<void> get serverBack => network._back.future;
}

/// One event stream. Once listened to it sends [first] (the `open` frame),
/// or closes with [refusal] before it.
class FakeSubscription implements EventSubscription {
  FakeSubscription(
    this.path,
    this.token, {
    Map<String, Object?>? first,
    String? refusal,
  }) {
    controller = StreamController<Map<String, Object?>>.broadcast(
      sync: true,
      onListen: () {
        if (_started) return;
        _started = true;
        scheduleMicrotask(() {
          if (refusal != null) {
            unawaited(end(refusal));
          } else if (first != null) {
            emit(first);
          }
        });
      },
    );
  }

  final ConnectionPath path;

  /// The bearer token the stream was opened with.
  final String token;
  late final StreamController<Map<String, Object?>> controller;
  bool _started = false;
  String? reason;

  bool get isOpen => !controller.isClosed;

  @override
  Stream<Map<String, Object?>> get events => controller.stream;

  @override
  String? get closeReason => reason;

  void emit(Map<String, Object?> event) {
    if (!controller.isClosed) controller.add(event);
  }

  /// The server ended the stream, with [why] as its close reason.
  Future<void> end([String? why]) async {
    reason = why;
    if (!controller.isClosed) await controller.close();
  }

  /// The server sends `closing` with [why], then ends the stream.
  Future<void> closing(String why) async {
    emit(<String, Object?>{'type': 'closing', 'reason': why});
    await end(why);
  }

  @override
  Future<void> cancel() async {
    if (!controller.isClosed) await controller.close();
  }
}

/// Both paths of one fake server.
class FakeNetwork {
  FakeNetwork(this.handler);

  FakeHandler handler;

  /// Answers on one path instead of [handler].
  final Map<ConnectionPath, FakeHandler> pathHandlers =
      <ConnectionPath, FakeHandler>{};

  /// Awaited before a request is answered, to hold it in flight.
  Future<void> Function(ConnectionPath path, ApiRequest request)? gate;
  final Set<ConnectionPath> down = <ConnectionPath>{};

  /// How a path that is [down] fails, when not with its usual code: a
  /// direct path whose server refused this page's origin without CORS
  /// headers fails with `origin-not-allowed`.
  final Map<ConnectionPath, TransportFailure> failWith =
      <ConnectionPath, TransportFailure>{};
  final List<(ConnectionPath, ApiRequest)> sent =
      <(ConnectionPath, ApiRequest)>[];

  /// Every event stream asked for, with the bearer token it carried.
  final List<(ConnectionPath, String)> subscribed =
      <(ConnectionPath, String)>[];
  final List<(ConnectionPath, FakeSubscription)> streams =
      <(ConnectionPath, FakeSubscription)>[];
  final List<ConnectionPath> opened = <ConnectionPath>[];
  final List<FakeTransport> transports = <FakeTransport>[];

  /// Bearer tokens the event stream accepts; any other is refused before
  /// `open` (`4401 token-revoked` direct, a 401 answer over the relay).
  final Set<String> tokens = <String>{liveToken};

  /// Streams open but never send their `open` frame.
  bool withholdOpen = false;

  /// Streams on a path close with this reason before their `open` frame.
  final Map<ConnectionPath, String> closeBeforeOpen =
      <ConnectionPath, String>{};

  /// Refuses every event stream with this answer (status, code and hint).
  StreamRefused? refuseStreamWith;

  /// What the `open` frame says about the token.
  DateTime openExpiresAt = DateTime.utc(2030, 1, 8);
  List<String> openScopes = const <String>['read', 'publish:screens'];

  /// The server is connected to the relay.
  bool serverOnRelay = true;
  Completer<void> _back = Completer<void>();

  Map<String, Object?> openFrame() => <String, Object?>{
    'type': 'open',
    'tokenId': liveTokenId,
    'expiresAt': openExpiresAt.toIso8601String(),
    'scopes': openScopes,
    'metaHash': 'sha256:${'0' * 64}',
    'notesHash': 'sha256:${'1' * 64}',
  };

  /// The server leaves: the direct path stops answering and the relay
  /// streams end with `server-left`, while the relay sockets stay open.
  Future<void> serverLeaves() async {
    serverOnRelay = false;
    down.add(ConnectionPath.direct);
    if (_back.isCompleted) _back = Completer<void>();
    for (final FakeTransport transport in transports.toList()) {
      for (final FakeSubscription subscription in transport.subscriptions.toList()) {
        await subscription.end(
          transport.path == ConnectionPath.relay ? 'server-left' : null,
        );
      }
    }
  }

  /// The server stops for a restart: every stream gets `closing`
  /// `server-stopping`, the direct path stops answering and the relay says
  /// the server left.
  Future<void> serverStops() async {
    serverOnRelay = false;
    down.add(ConnectionPath.direct);
    if (_back.isCompleted) _back = Completer<void>();
    for (final FakeTransport transport in transports.toList()) {
      for (final FakeSubscription subscription in transport.subscriptions.toList()) {
        if (subscription.isOpen) await subscription.closing('server-stopping');
      }
    }
  }

  /// The server is back on both paths.
  void serverReturns() {
    serverOnRelay = true;
    down.remove(ConnectionPath.direct);
    if (!_back.isCompleted) _back.complete();
  }

  Future<ServerTransport> open(ConnectionPath path, String url) async {
    opened.add(path);
    // The browser opens a direct path without a request; only its requests
    // fail.
    if (down.contains(path) && !failWith.containsKey(path)) {
      throw TransportFailure(
        path,
        path == ConnectionPath.relay ? 'relay-unreachable' : 'unreachable',
      );
    }
    final FakeTransport transport = path == ConnectionPath.relay
        ? FakeRelayTransport(this)
        : FakeTransport(path, this);
    transports.add(transport);
    return transport;
  }

  FakeSubscription get lastStream => streams.last.$2;

  /// The last stream opened on [path].
  FakeSubscription streamOn(ConnectionPath path) => streams
      .lastWhere(
        ((ConnectionPath, FakeSubscription) stream) => stream.$1 == path,
      )
      .$2;

  /// The last transport opened on [path].
  FakeTransport transportOn(ConnectionPath path) =>
      transports.lastWhere((FakeTransport transport) => transport.path == path);

  /// The requests sent to [route], in order.
  List<(ConnectionPath, ApiRequest)> sentTo(String route) =>
      <(ConnectionPath, ApiRequest)>[
        for (final (ConnectionPath, ApiRequest) request in sent)
          if (request.$2.path == route) request,
      ];
}

/// `X-Gloss-Server-Key` and `X-Gloss-Proof` from the key of [seed], signed
/// over [prefix] and [challenge].
Future<Map<String, String>> signedProof(
  List<int> seed,
  String challenge, {
  String prefix = editorProofPrefix,
}) async {
  if (identical(seed, fakeSeed) && prefix == editorProofPrefix) {
    return proofHeaders(challenge);
  }
  final Ed25519 ed25519 = Ed25519();
  final SimpleKeyPair pair = await ed25519.newKeyPairFromSeed(seed);
  final SimplePublicKey key = await pair.extractPublicKey();
  final Signature signature = await ed25519.sign(
    utf8.encode('$prefix$challenge'),
    keyPair: pair,
  );
  return <String, String>{
    serverKeyHeader: base64UrlNoPadding(key.bytes),
    proofHeader: base64UrlNoPadding(signature.bytes),
  };
}

/// Answers `GET /v1/hello` like the server; [rest] answers the rest. A
/// challenged hello is signed by the key of [seed] over [prefix], unless
/// [prove] is false.
FakeHandler serverHandler({
  String fingerprint = fakeFingerprint,
  List<int>? seed,
  String prefix = editorProofPrefix,
  bool prove = true,
  int protocol = 1,
  String? serverName,
  FutureOr<ApiResponse> Function(ApiRequest request)? rest,
}) {
  return (ApiRequest request) async {
    if (request.method == 'GET' && request.path == '/v1/hello') {
      final String? challenge = headerValue(request.headers, challengeHeader);
      return ApiResponse(
        200,
        <String, Object?>{
          'protocol': protocol,
          'protocols': <Object?>[protocol],
          'fingerprint': fingerprint,
          'gloss': '4.0.1',
          'platform': 'paper',
          'minecraft': '26.3',
          'serverName': ?serverName,
        },
        headers: prove && challenge != null
            ? await signedProof(seed ?? fakeSeed, challenge, prefix: prefix)
            : const <String, String>{},
      );
    }
    if (rest != null) return rest(request);
    return const ApiResponse(404, <String, Object?>{
      'error': <String, Object?>{'code': 'not-found', 'message': 'no'},
    });
  };
}

/// The `POST /v1/pair` answer: a token for [token].
ApiResponse pairAnswer({
  String token = liveToken,
  String tokenId = liveTokenId,
  String fingerprint = fakeFingerprint,
  String? serverName,
}) => ApiResponse(200, <String, Object?>{
  'token': token,
  'tokenId': tokenId,
  'scopes': <Object?>['read', 'history', 'publish:screens'],
  'folders': <Object?>['shop'],
  'expiresAt': '2026-11-20T10:03:12Z',
  'lifetimeSeconds': 604800,
  'fingerprint': fingerprint,
  'counter': 0,
  'admin': '0f6c0000-0000-4000-8000-0000000000b2',
  'serverName': ?serverName,
});

/// A server that answers hello and pairs every `POST /v1/pair` with
/// [answer].
FakeHandler pairingServer({
  ApiResponse Function()? answer,
  String? serverName,
}) => serverHandler(
  serverName: serverName,
  rest: (ApiRequest request) {
    if (request.method == 'POST' && request.path == '/v1/pair') {
      return answer == null ? pairAnswer() : answer();
    }
    return const ApiResponse(404, <String, Object?>{
      'error': <String, Object?>{'code': 'not-found', 'message': 'no'},
    });
  },
);

/// An error answer with [code] and [status].
ApiResponse refusal(int status, String code, {Map<String, Object?>? extra}) =>
    ApiResponse(status, <String, Object?>{
      'error': <String, Object?>{'code': code, 'message': code, ...?extra},
    });

/// [inner] with one bit of the hello proof flipped, so the signature fails.
FakeHandler tamperedProof(FakeHandler inner) => (ApiRequest request) async {
  final ApiResponse response = await inner(request);
  final String? proof = response.headers[proofHeader];
  if (proof == null) return response;
  final List<int> bytes = base64Url.decode(base64Url.normalize(proof))
    ..[0] ^= 1;
  return ApiResponse(
    response.status,
    response.body,
    headers: <String, String>{
      ...response.headers,
      proofHeader: base64UrlNoPadding(bytes),
    },
  );
};

/// A pairing code's payload for the fake server's key.
PairingPayload fakePayload({String? directUrl, String? relayUrl}) =>
    PairingPayload(
      fingerprint: fakeFingerprint,
      serverKey: fakeServerKey,
      tokenId: liveTokenId,
      secret: pairSecret,
      expiresAt: DateTime.utc(2030),
      directUrl: directUrl,
      relayUrl: relayUrl,
    );

/// A `GLS1.` code for the fake server.
String fakeCode({
  String? directUrl = 'http://127.0.0.1:9799',
  String? relayUrl = 'ws://127.0.0.1:9797',
  DateTime? expiresAt,
  String? label,
  String tokenId = liveTokenId,
}) => pairingCode(
  directUrl: directUrl,
  relayUrl: relayUrl,
  tokenId: tokenId,
  secret: pairSecret,
  expiresAt: expiresAt ?? DateTime.utc(2030),
  label: label,
  serverName: 'Twin Peaks',
  scopes: const <String>['read', 'history', 'publish:screens'],
  folders: const <String>['shop'],
);
