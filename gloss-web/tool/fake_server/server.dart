/// An in-memory Gloss editor server (the editor API in sync-relay/README.md)
/// for tests, browser checks and local development: the direct API at
/// `/gloss/v1/`, a relay emulation at `/app` and test controls at
/// `/__fake/`, all on one loopback port. Nothing is written to disk.
library;

import 'dart:async';
import 'dart:io';

import 'api.dart';
import 'control.dart';
import 'direct.dart';
import 'events.dart';
import 'identity.dart';
import 'kinds.dart';
import 'protocol.dart';
import 'relay.dart';
import 'tokens.dart';

export 'identity.dart'
    show
        challengeHeader,
        decodePairingCode,
        editorProofPrefix,
        fakeFingerprint,
        fakeSeed,
        fakeServerKey,
        pairingPrefix,
        proofHeader,
        serverKeyHeader;
export 'kinds.dart' show allScopes, defaultScopes;
export 'store.dart' show solidPng;

/// Which paths a pairing code carries.
enum PairPaths { both, direct, relay }

/// A minted pairing link.
class PairLink {
  const PairLink(this.link, this.code, this.tokenId, this.expiresAt);

  /// `<editorUrl>/#/pair/<code>`.
  final String link;
  final String code;
  final String tokenId;
  final DateTime expiresAt;

  Map<String, Object?> get json => <String, Object?>{'link': link, 'code': code, 'tokenId': tokenId, 'expiresAt': instant(expiresAt)};
}

class FakeGlossServer {
  FakeGlossServer._(this._http, this.api, this.editorUrl, this.verbose) {
    direct = DirectPath(this);
    relay = RelayPath(this);
    control = ControlPath(this);
    _housekeeping = Timer.periodic(const Duration(seconds: 1), (Timer timer) => api.housekeeping());
    _http.listen(_serve);
  }

  /// Binds 127.0.0.1:[port] (0 picks a free port). [dataDirectory] is a Gloss
  /// data folder laid out by storage names; without it a small built-in set
  /// loads. [allowedOrigins] defaults to [editorUrl]'s origin.
  static Future<FakeGlossServer> start({
    int port = 0,
    String? dataDirectory,
    String editorUrl = 'http://127.0.0.1:8808',
    List<String>? allowedOrigins,
    String serverName = 'Twin Peaks',
    int catalogPageSize = 1000,
    int requestsPerSecond = 20,
    int pairAttemptsPerMinute = 10,
    bool verbose = false,
  }) async {
    final String editor = editorUrl.endsWith('/') ? editorUrl.substring(0, editorUrl.length - 1) : editorUrl;
    final FakeApi api = FakeApi(
      dataDirectory: dataDirectory,
      allowedOrigins: allowedOrigins ?? <String>[Uri.parse(editor).origin],
      serverName: serverName,
      catalogPageSize: catalogPageSize,
      requestsPerSecond: requestsPerSecond,
      pairAttemptsPerMinute: pairAttemptsPerMinute,
    );
    final HttpServer http = await HttpServer.bind(InternetAddress.loopbackIPv4, port);
    http.autoCompress = false;
    return FakeGlossServer._(http, api, editor, verbose);
  }

  final HttpServer _http;
  final FakeApi api;
  final String editorUrl;
  final bool verbose;
  late final DirectPath direct;
  late final RelayPath relay;
  late final ControlPath control;
  late final Timer _housekeeping;
  Timer? _restartTimer;
  bool _directDown = false;
  bool _relayDown = false;
  bool _serverOnRelay = true;

  /// `/__fake/relay-limit`: relayed requests over this many bytes are
  /// answered `413 too-large`, as a relay with a smaller frame does once it
  /// adds its headers. Null: only the frame limit applies.
  int? relayRequestBytes;

  int get port => _http.port;

  String get directUrl => 'http://127.0.0.1:$port';

  /// Loopback `ws://`; the contract's relays are `wss://` elsewhere.
  String get relayUrl => 'ws://127.0.0.1:$port';

  /// `<directUrl>/gloss/v1`.
  String get apiUrl => '$directUrl/gloss/v1';

  bool get restarting => _restartTimer != null;

  bool get directDown => _directDown;

  bool get directRefuses => _directDown || restarting;

  bool get relayDown => _relayDown;

  bool get serverOnRelay => _serverOnRelay;

  /// Whether the relay reports this server online.
  bool get onRelay => _serverOnRelay && !restarting;

  /// The listener refuses connections while [down]; open direct streams drop.
  set directDown(bool down) {
    _directDown = down;
    if (!down) return;
    api.events.dropWhere((EventStream stream) => stream.path == StreamPath.direct);
    direct.closeSockets();
  }

  /// The relay refuses or closes browser connections while [down].
  set relayDown(bool down) {
    _relayDown = down;
    if (!down) return;
    api.events.dropWhere((EventStream stream) => stream.path == StreamPath.relay);
    relay.closeSockets();
  }

  /// Whether the server is connected to the relay: subscribed browsers get
  /// `status`, relayed requests answer `server-offline` while it is not.
  set serverOnRelay(bool online) {
    final bool before = onRelay;
    _serverOnRelay = online;
    if (!online) api.events.dropWhere((EventStream stream) => stream.path == StreamPath.relay);
    if (onRelay != before) relay.announce(onRelay);
  }

  /// A server restart: streams get `closing` `server-stopping`, both paths
  /// refuse for [down], the relay reports the server offline then online.
  /// Tokens survive; waiting links, staged uploads and idempotency answers
  /// do not.
  void restart(Duration down) {
    final bool wasOnRelay = onRelay;
    api.events.closeAll('server-stopping');
    direct.closeSockets();
    api.tokens.forgetLinks();
    api.changes.restart();
    _restartTimer?.cancel();
    _restartTimer = Timer(down, () {
      _restartTimer = null;
      if (onRelay) relay.announce(true);
    });
    if (wasOnRelay) relay.announce(false);
  }

  /// Mints a one-time pairing link (10 minutes), as `/gloss editor pair`.
  PairLink pair({
    PairPaths paths = PairPaths.both,
    List<String>? scopes,
    List<String> folders = const <String>[],
    String? label,
    Duration lifetime = const Duration(days: 7),
    String? admin,
  }) {
    final List<String> granted = orderedScopes(scopes ?? const <String>[]);
    final (WaitingLink link, String secret) = api.tokens.pair(granted, folders, label, admin, lifetime);
    final String code = pairingCode(
      directUrl: paths == PairPaths.relay ? null : directUrl,
      relayUrl: paths == PairPaths.direct ? null : relayUrl,
      tokenId: link.tokenId,
      secret: secret,
      expiresAt: link.expires,
      label: label,
      serverName: api.serverName,
      scopes: granted,
      folders: folders,
    );
    return PairLink('$editorUrl/#/pair/$code', code, link.tokenId, link.expires);
  }

  Map<String, Object?> state() => <String, Object?>{
        'port': port,
        'fingerprint': fakeFingerprint,
        'serverName': api.serverName,
        'documents': api.store.documents.length,
        'images': api.store.images.length,
        'stagedImages': api.changes.stagedImages,
        'tokens': <Object?>[for (final FakeToken token in api.tokens.tokens) token.state],
        'waitingLinks': api.tokens.waitingLinks,
        'lastPublish': <String, Object?>{'request': api.lastPublishRequest, 'answer': api.lastPublishAnswer},
        'requests': api.requestCounts,
        'streams': <String, Object?>{'direct': api.events.openOn(StreamPath.direct), 'relay': api.events.openOn(StreamPath.relay)},
        'relayConnections': relay.connections,
        'paths': <String, Object?>{'directDown': _directDown, 'relayDown': _relayDown, 'serverOnRelay': _serverOnRelay, 'restarting': restarting},
        'latencyMillis': api.latencyMillis,
        'allowedOrigins': api.allowedOrigins,
      };

  /// One line to stderr when [verbose].
  void log(String line) {
    if (verbose) stderr.writeln(line);
  }

  Future<void> _serve(HttpRequest request) async {
    try {
      final String path = request.uri.path;
      if (path == '/__fake' || path.startsWith('/__fake/')) {
        await control.serve(request);
      } else if (path == '/app') {
        await relay.serve(request);
      } else {
        await direct.serve(request);
      }
    } on Object catch (failure) {
      log('internal error: $failure');
      try {
        await writeResponse(request.response, ApiError('internal', 'The fake server failed: $failure').toResponse());
      } on Object {
        // The client already left.
      }
    }
  }

  Future<void> close() async {
    _housekeeping.cancel();
    _restartTimer?.cancel();
    api.events.closeAll('server-stopping');
    direct.closeSockets();
    relay.closeSockets();
    await _http.close(force: true);
  }
}
