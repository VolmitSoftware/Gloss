/// Paired servers and their connections, kept apart from document state
/// (ported from the Animus editor's `ServerHub`). The saved list is shared by
/// every tab: each change re-reads it before writing, and another tab's
/// change reloads it here. Pure Dart.
library;

import 'dart:async';

import '../../l10n/hui_localizations.dart';
import 'error_copy.dart';
import 'pairing.dart';
import 'server_connection.dart';
import 'server_profiles.dart';
import 'transport.dart';

typedef HubListener = void Function();

/// Opens a path for a server fingerprint.
typedef HubTransportFactory = Future<ServerTransport> Function(
  ConnectionPath path,
  String url,
  String fingerprint,
);

class ServerHub {
  ServerHub({
    required this.profiles,
    required this.open,
    this.onForget,
    this.delay = realDelay,
    this.pageOrigin = _noOrigin,
    this.handshakeTimeout = const Duration(seconds: 5),
    DateTime Function()? now,
  }) : _now = now ?? DateTime.now {
    _servers = profiles.load();
    _listening.add(profiles.changes.listen((void _) => _reload()));
    _listening.add(profiles.tokenChanges.listen((String? _) => _tokensMoved()));
    _listening.add(profiles.reachChanges.listen(_reachHeard));
  }

  /// How long a server that was online keeps that word while its dropped
  /// connection tries the other path: a switch shorter than this changes
  /// nothing on screen; a failed attempt says Not reachable at once, and
  /// that word stays through every retry. The save bar keeps its
  /// word for the same time.
  static const Duration settle = Duration(seconds: 3);

  static String? _noOrigin() => null;

  final ServerProfileStore profiles;

  /// Called with a forgotten server's id, to remove its browser workspace.
  final Future<void> Function(String serverId)? onForget;

  final HubTransportFactory open;
  final Delay delay;
  final Duration handshakeTimeout;

  /// The page's origin, read when pairing: an https page may be kept from a
  /// plain http server on the local network.
  final String? Function() pageOrigin;
  final DateTime Function() _now;
  final List<StreamSubscription<Object?>> _listening =
      <StreamSubscription<Object?>>[];

  List<ServerProfile> _servers = const <ServerProfile>[];

  /// Set when the browser refused to store the last pairing or server list.
  bool storageRefused = false;
  final Map<String, ServerConnection> _connections =
      <String, ServerConnection>{};
  final List<HubListener> _listeners = <HubListener>[];

  /// Reachability of saved servers without a live connection, by id.
  final Map<String, ConnectionStatus> _probes = <String, ConnectionStatus>{};

  /// The online status a dropped connection keeps showing until [settle]
  /// passed, by id.
  final Map<String, ConnectionStatus> _held = <String, ConnectionStatus>{};

  /// What this tab last told the others, by id.
  final Map<String, bool> _told = <String, bool>{};

  List<ServerProfile> get servers => _servers;

  void addListener(HubListener listener) => _listeners.add(listener);

  void removeListener(HubListener listener) => _listeners.remove(listener);

  void _changed() {
    for (final HubListener listener in List<HubListener>.of(_listeners)) {
      listener();
    }
  }

  ServerProfile? server(String id) {
    for (final ServerProfile profile in _servers) {
      if (profile.id == id) return profile;
    }
    return null;
  }

  /// The connection for a saved server, made on first use.
  ServerConnection connection(String id) {
    final ServerConnection? existing = _connections[id];
    if (existing != null) return existing;
    final ServerProfile profile = server(id)!;
    final ServerConnection connection = ServerConnection(
      profile: profile,
      token: profiles.token(profile),
      open: (ConnectionPath path, String url) =>
          open(path, url, profile.fingerprint),
      delay: delay,
      handshakeTimeout: handshakeTimeout,
      pageOrigin: pageOrigin(),
      now: _now,
    );
    ConnectionStatus? before;
    connection.statuses.listen((ConnectionStatus status) {
      final ConnectionStatus? previous = before;
      before = status;
      if (status.phase == ConnectionPhase.needsPairing) {
        profiles.forgetToken(connection.profile);
      }
      if (status.online) {
        _held.remove(id);
        _probes.remove(id);
        _tell(id, online: true);
        final Object? name = status.hello?['serverName'];
        if (name is String) _rename(id, name);
      } else if (_firstRound(status) &&
          (_held.containsKey(id) || (previous?.online ?? false))) {
        // A drop's first round tries the other path at once: the word stays
        // until it fails or [settle] passes.
        if (!_held.containsKey(id)) {
          _held[id] = previous!;
          unawaited(delay(settle).then((void _) => _settled(id)));
        }
      } else {
        _held.remove(id);
        if (_unreachable(status)) _tell(id, online: false);
      }
      _changed();
    });
    connection.profileChanges.listen((ServerProfile next) {
      final ServerProfile? saved = server(id);
      _store(saved == null ? next : saved.copyWith(lastPath: next.lastPath));
    });
    connection.tokenChanges.listen((ServerToken token) {
      final ServerProfile? saved = server(id);
      if (saved != null && !profiles.saveToken(saved, token)) {
        storageRefused = true;
      }
      _changed();
    });
    _connections[id] = connection;
    return connection;
  }

  /// Checks every saved server that has no live connection with one
  /// `GET /v1/hello` (no token, no event stream), so the start page shows
  /// Online, Not reachable or Checking without opening a workspace. A
  /// server whose hello carries a name takes it.
  void probeServers() => _probeWhere((ServerProfile _) => true);

  /// Checks again only the saved servers the last check found not reachable,
  /// so a start page left open sees a server come back.
  void probeUnreachable() => _probeWhere(
    (ServerProfile profile) =>
        _probes[profile.id]?.phase == ConnectionPhase.offline,
  );

  void _probeWhere(bool Function(ServerProfile profile) wanted) {
    for (final ServerProfile profile in _servers) {
      if (!wanted(profile)) continue;
      final ServerConnection? live = _connections[profile.id];
      if (live != null && live.status.phase != ConnectionPhase.offline) {
        continue;
      }
      final ConnectionStatus? last = _probes[profile.id];
      if (last?.phase == ConnectionPhase.connecting) continue;
      // Checking a server found not reachable again keeps that word until
      // the answer (attempt 1); only a first check says Checking.
      _probes[profile.id] = ConnectionStatus(
        ConnectionPhase.connecting,
        probed: true,
        attempt: last?.phase == ConnectionPhase.offline ? 1 : 0,
        problem: last?.problem,
      );
      unawaited(
        ServerConnection.probe(
          profile,
          (ConnectionPath path, String url) =>
              open(path, url, profile.fingerprint),
        ).then(((ConnectionPath, String?)? found) {
          _probes[profile.id] = found == null
              ? ConnectionStatus(
                  ConnectionPhase.offline,
                  probed: true,
                  problem: transportCopy('no-answer'),
                )
              : ConnectionStatus(
                  ConnectionPhase.online,
                  path: found.$1,
                  probed: true,
                );
          if (found?.$2 != null) _rename(profile.id, found!.$2!);
          _tell(profile.id, online: found != null);
          _changed();
        }),
      );
    }
    _changed();
  }

  /// Gives a saved server the name it reports, keeping the one it has when
  /// the server sends none.
  void _rename(String id, String name) {
    final ServerProfile? profile = server(id);
    final String trimmed = name.trim();
    if (profile == null || trimmed.isEmpty || profile.name == trimmed) return;
    _store(profile.copyWith(name: trimmed));
  }

  /// Keeps the server's document and image counts for the start page.
  void noteCounts(String id, {required int documents, required int images}) {
    final ServerProfile? profile = server(id);
    if (profile == null ||
        (profile.documents == documents && profile.images == images)) {
      return;
    }
    _store(profile.copyWith(documents: documents, images: images));
  }

  /// The browser came back online or the tab became visible: a connection
  /// on the relay probes the direct path now, and one waiting to reconnect
  /// tries again now instead of waiting out its backoff.
  void wake() {
    for (final ServerConnection connection in _connections.values) {
      connection.probeNow();
      if (connection.status.phase == ConnectionPhase.reconnecting) {
        connection.retryNow();
      }
    }
  }

  /// Where a saved server stands: its live connection (still online for
  /// [settle] while a drop's first round runs), or else the last check, by
  /// this tab or told by another.
  ConnectionStatus status(String id) {
    final ConnectionStatus? live = _connections[id]?.status;
    if (live != null && live.phase != ConnectionPhase.offline) {
      return live.online ? live : (_held[id] ?? live);
    }
    return _probes[id] ??
        live ??
        const ConnectionStatus(ConnectionPhase.offline);
  }

  static bool _retrying(ConnectionStatus status) =>
      status.phase == ConnectionPhase.connecting ||
      status.phase == ConnectionPhase.reconnecting;

  /// The round right after a loss while online: no attempt failed yet and
  /// no retry is scheduled.
  static bool _firstRound(ConnectionStatus status) =>
      _retrying(status) && status.attempt == 0 && status.retryAt == null;

  /// A live connection that failed an attempt and is retrying.
  static bool _unreachable(ConnectionStatus status) =>
      status.phase == ConnectionPhase.reconnecting ||
      (status.phase == ConnectionPhase.connecting && status.attempt > 0);

  /// The settle time after a drop ran out: the live state shows, and the
  /// other tabs hear it when the server still does not answer.
  void _settled(String id) {
    if (_held.remove(id) == null) return;
    final ConnectionStatus? live = _connections[id]?.status;
    if (live != null && _retrying(live)) _tell(id, online: false);
    _changed();
  }

  /// Tells the other tabs, once per change, whether [id] answers.
  void _tell(String id, {required bool online}) {
    if (_told[id] == online) return;
    _told[id] = online;
    profiles.saveReach(id, online: online, at: _now());
  }

  /// Another tab learned whether [id] answers: a tab without a live
  /// connection to it says the same.
  void _reachHeard(String id) {
    final ConnectionStatus? live = _connections[id]?.status;
    if (live != null && live.phase != ConnectionPhase.offline) return;
    final bool? online = profiles.reach(id);
    if (online == null || server(id) == null) return;
    _told[id] = online;
    _probes[id] = online
        ? const ConnectionStatus(ConnectionPhase.online, probed: true)
        : ConnectionStatus(
            ConnectionPhase.offline,
            probed: true,
            problem: transportCopy('no-answer'),
          );
    _changed();
  }

  /// The token this tab holds for [id], if any.
  ServerToken? token(String id) {
    final ServerProfile? profile = server(id);
    if (profile == null) return null;
    return _connections[id]?.token ?? profiles.token(profile);
  }

  bool hasToken(String id) => token(id) != null;

  /// Another tab saved or forgot a server.
  void _reload() {
    _servers = profiles.load();
    for (final String id in _connections.keys.toList()) {
      if (server(id) == null) unawaited(_connections.remove(id)!.dispose());
    }
    _changed();
  }

  /// Another tab paired a server again: a connection waiting for a token
  /// takes the new one.
  void _tokensMoved() {
    for (final ServerConnection connection in _connections.values) {
      if (connection.token != null) continue;
      final ServerToken? next = profiles.token(connection.profile);
      if (next == null) continue;
      connection.token = next;
      unawaited(connection.connect());
    }
    _changed();
  }

  void _store(ServerProfile profile) {
    _servers = <ServerProfile>[
      for (final ServerProfile existing in profiles.load())
        if (existing.id != profile.id) existing,
      profile,
    ]..sort((ServerProfile a, ServerProfile b) => a.name.compareTo(b.name));
    if (!profiles.save(_servers)) storageRefused = true;
    _changed();
  }

  /// Pairs with a code, saves the server and its token and connects.
  /// Returns the server id. Throws [PairingRefused] or [PairingCodeError].
  Future<String> pair(
    String code, {
    required bool remember,
    String? client,
  }) async {
    final PairingPayload payload = decodePairingCode(extractPairingCode(code));
    if (!payload.expiresAt.isAfter(_now().toUtc())) {
      throw PairingRefused.because(PairingFailure.expired);
    }
    final (
      ServerToken token,
      ConnectionPath path,
      String? named,
    ) = await ServerConnection.pair(
      payload,
      (ConnectionPath path, String url) => open(path, url, payload.fingerprint),
      client: client,
      pageOrigin: pageOrigin(),
    );
    final ServerProfile? known = server(payload.serverId);
    final ServerProfile profile = ServerProfile(
      fingerprint: payload.fingerprint,
      serverKey: payload.serverKey,
      name:
          named ??
          payload.serverName ??
          known?.name ??
          payload.label ??
          huiText('Server {id}', <String, Object?>{
            'id': payload.serverId.substring(0, 8),
          }),
      directUrl: payload.directUrl,
      relayUrl: payload.relayUrl,
      label: payload.label,
      remember: remember,
      lastPath: path,
      documents: known?.documents,
      images: known?.images,
    );
    storageRefused = false;
    if (!profiles.saveToken(profile, token)) storageRefused = true;
    _store(profile);
    // A workspace open on this server keeps its connection: it takes the new
    // token and addresses and connects again, so its stream no longer runs
    // on the old token.
    final ServerConnection? existing = _connections[profile.id];
    if (existing != null) {
      existing.updateProfile(profile);
      existing.token = token;
      unawaited(existing.reconnect());
    }
    return profile.id;
  }

  /// Whether the relay a link names answers, checked when the pairing card
  /// opens so it can say the direct address carries the pairing instead.
  /// Only the relay socket is opened; nothing is sent to the server. True
  /// for a link without a relay.
  Future<bool> relayAnswers(String code) async {
    final PairingPayload payload = decodePairingCode(extractPairingCode(code));
    final String? url = payload.relayUrl;
    if (url == null) return true;
    ServerTransport? transport;
    try {
      transport = await open(
        ConnectionPath.relay,
        url,
        payload.fingerprint,
      ).timeout(handshakeTimeout);
      return true;
    } on Object {
      return false;
    } finally {
      await transport?.close();
    }
  }

  /// Whether the link's direct address answers but refuses this page's
  /// origin, so the pairing card can name the setting before Pair. Only
  /// `hello` is sent; a silent or missing address is no refusal.
  Future<bool> directRefusesOrigin(String code) async {
    final PairingPayload payload = decodePairingCode(extractPairingCode(code));
    final String? url = payload.directUrl;
    if (url == null) return false;
    ServerTransport? transport;
    try {
      transport = await open(
        ConnectionPath.direct,
        url,
        payload.fingerprint,
      ).timeout(handshakeTimeout);
      final ApiResponse hello = await transport
          .send(const ApiRequest('GET', '/v1/hello'))
          .timeout(handshakeTimeout);
      return hello.errorCode == 'origin-not-allowed';
    } on TransportFailure catch (failure) {
      return failure.code == 'origin-not-allowed';
    } on Object {
      return false;
    } finally {
      await transport?.close();
    }
  }

  /// Connects [id] with the token this tab holds.
  Future<void> connect(String id) async {
    final ServerConnection connection = this.connection(id);
    connection.token ??= profiles.token(connection.profile);
    await connection.connect();
  }

  Future<void> disconnect(String id) async => _connections[id]?.disconnect();

  /// Closes [id]'s connection and forgets it until it is used again.
  Future<void> release(String id) async {
    final ServerConnection? connection = _connections.remove(id);
    await connection?.dispose();
    _changed();
  }

  /// The id of [id]'s token while it has not expired.
  String? _tokenId(String id) {
    final ServerToken? held = token(id);
    return held != null && held.expiresAt.isAfter(_now().toUtc())
        ? held.tokenId
        : null;
  }

  /// The confirm text for forgetting [id]. The server keeps accepting the
  /// pairing until it expires unless it is revoked there.
  String forgetPrompt(String id) {
    final String here = huiText(
      'Removes its pairing and unpublished changes from this browser. Published changes stay on the server.',
    );
    final String? tokenId = _tokenId(id);
    if (tokenId == null) return here;
    return '$here ${huiText('To end the pairing on the server too, run {command}.', <String, Object?>{'command': '/gloss editor revoke $tokenId'})}';
  }

  /// Forgets a server in this browser: its token, its saved entry and its
  /// workspace with any drafts in it. Returns the id of a token the server
  /// still accepts, or null when there was none.
  Future<String?> forget(String id) async {
    final String? tokenId = _tokenId(id);
    final ServerConnection? connection = _connections.remove(id);
    await connection?.dispose();
    final ServerProfile? profile = server(id);
    if (profile != null) profiles.forgetToken(profile);
    _servers = <ServerProfile>[
      for (final ServerProfile existing in profiles.load())
        if (existing.id != id) existing,
    ];
    _probes.remove(id);
    _held.remove(id);
    _told.remove(id);
    if (!profiles.save(_servers)) storageRefused = true;
    await onForget?.call(id);
    _changed();
    return tokenId;
  }

  void dispose() {
    for (final StreamSubscription<Object?> subscription in _listening) {
      unawaited(subscription.cancel());
    }
    for (final ServerConnection connection in _connections.values) {
      unawaited(connection.dispose());
    }
    _connections.clear();
    _listeners.clear();
  }
}
