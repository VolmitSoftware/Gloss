/// One paired server's connection (sync-relay/README.md, editor API section
/// 9.3), ported from the Animus editor's `ServerConnection`: the last good
/// path first, the fingerprint pinned on every hello, the event stream kept
/// open, reconnecting with backoff, and a silent move back to the direct
/// path while on the relay. Pure Dart over [ServerTransport].
library;

import 'dart:async';
import 'dart:math' as math;

import 'error_copy.dart';
import 'event_overlap.dart';
import 'local_network.dart';
import 'page_activity.dart';
import 'pairing.dart';
import 'request_pacer.dart';
import 'server_profiles.dart';
import 'server_proof.dart';
import 'transport.dart';

enum ConnectionPhase {
  /// Not connected and not trying.
  offline,

  /// Trying a path.
  connecting,

  /// Requests and the event stream work.
  online,

  /// Lost or never reached; waiting to try again.
  reconnecting,

  /// The token is missing, expired or revoked: pair again.
  needsPairing,

  /// A path answered with another server's fingerprint, or another protocol.
  /// Nothing more is tried until the user acts.
  refused,
}

class ConnectionStatus {
  const ConnectionStatus(
    this.phase, {
    this.path,
    this.attempt = 0,
    this.retryAt,
    this.problem,
    this.hello,
    this.probed = false,
  });

  /// From a reachability check of a saved server (`GET /v1/hello`), not a
  /// connection: online, connecting (checking) or offline (not reachable).
  final bool probed;

  final ConnectionPhase phase;
  final ConnectionPath? path;

  /// Failed attempts since the last time the connection was online.
  final int attempt;
  final DateTime? retryAt;

  /// Why the connection is not online, in plain words.
  final ErrorCopy? problem;

  String? get message => problem?.text;

  /// The last `hello` answer from the server.
  final Map<String, Object?>? hello;

  bool get online => phase == ConnectionPhase.online;
}

/// The request needs a connection that is not online.
class NotConnected implements Exception {
  const NotConnected(this.status);

  final ConnectionStatus status;

  @override
  String toString() => 'Not connected: ${status.message ?? status.phase.name}';
}

typedef Delay = Future<void> Function(Duration duration);

Future<void> realDelay(Duration duration) => Future<void>.delayed(duration);

/// Wait before retry [attempt] (1-based) when the server has been gone for
/// [away]: 1 to 5 s through the first minute, so a restart is caught within
/// seconds, then doubling from 5 s to 30 s. While the page is [visible] the
/// late waits stay at 5 s. Within [fastWindow] of a `server-stopping` close
/// the direct path is asked every 250 ms instead (section 15).
Duration reconnectDelay(
  int attempt, {
  Duration away = Duration.zero,
  bool visible = false,
  bool stopping = false,
}) {
  if (stopping) return fastRetry;
  const List<int> early = <int>[1, 1, 2, 2, 3, 3, 4, 5];
  if (away < const Duration(minutes: 1)) {
    return Duration(seconds: early[math.min(attempt, early.length) - 1]);
  }
  final int late = (away.inSeconds - 60) ~/ 30;
  return Duration(
    seconds: math.min(visible ? 5 : 30, 5 << math.min(late + 1, 3)),
  );
}

/// The retry step right after the server said it is stopping.
const Duration fastRetry = Duration(milliseconds: 250);

/// How long after a `server-stopping` close the retries stay at [fastRetry].
const Duration fastWindow = Duration(seconds: 30);

/// The tab is in front, by [PageActivity].
bool pageVisible() => !PageActivity.hidden;

/// How many times a `rate-limited` or `busy` answer is retried.
const int retryLimit = 5;

/// Authorized requests one connection keeps in flight. The server allows 8
/// per token and answers the ninth `429`; two stay free for the direct-path
/// probe.
const int maxRequestsInFlight = 6;

/// Waits between direct-path probes while on the relay: the last one repeats.
const List<Duration> defaultProbeIntervals = <Duration>[
  Duration(seconds: 15),
  Duration(seconds: 30),
  Duration(seconds: 60),
];

/// One server's connection. Tries every path of the profile (the last good
/// one first, the other first after a loss), pins the fingerprint on every
/// `hello`, keeps the event stream open, and reconnects with backoff. While
/// online over the relay with a direct path paired, it probes the direct path
/// every [probeIntervals] and moves back to it without going offline.
class ServerConnection {
  ServerConnection({
    required this._profile,
    required this.open,
    this.token,
    this.delay = realDelay,
    this.handshakeTimeout = const Duration(seconds: 5),
    this.visible = pageVisible,
    this.probeIntervals = defaultProbeIntervals,
    this.overlap = const Duration(seconds: 2),
    this.pageOrigin,
    DateTime Function()? now,
  }) : assert(probeIntervals.isNotEmpty),
       _now = now ?? DateTime.now;

  final TransportFactory open;
  final Delay delay;

  /// How long one path gets to open, answer hello and open the event stream
  /// (its `open` frame) before it is closed and the next path tried.
  final Duration handshakeTimeout;

  /// Whether the page is in front, read before each reconnect wait.
  final bool Function() visible;

  /// Waits before each direct-path probe while on the relay; the last repeats.
  final List<Duration> probeIntervals;

  /// How long a recovered direct event stream runs beside the relay stream
  /// before the switch commits.
  final Duration overlap;

  /// This page's origin, named when the server refuses it.
  final String? pageOrigin;
  final DateTime Function() _now;
  ServerToken? token;

  ServerProfile _profile;
  ServerTransport? _transport;
  EventSubscription? _subscription;
  ConnectionStatus _status = const ConnectionStatus(ConnectionPhase.offline);
  Map<String, Object?>? _hello;
  Map<String, Object?>? _opened;
  bool _running = false;

  /// Bumped by every connect and disconnect: a loop or handshake started
  /// under an older value never goes online, opens a stream or speaks.
  int _generation = 0;
  Completer<void>? _lost;
  Completer<void>? _wake;
  ConnectionPath? _lostPath;
  int _counter = 0;

  /// When the server last said it was stopping; the direct path is then
  /// asked every [fastRetry] for [fastWindow].
  DateTime? _stoppingAt;

  /// The relay path kept open while the server is away from it, so the
  /// reconnect starts the moment the relay says it is back.
  ServerTransport? _parked;

  /// When the connection was last online; null before it ever was.
  DateTime? _lastOnline;

  /// Paces authorized requests to the server's per-token rate.
  final RequestPacer _pacer = RequestPacer();

  /// Keeps at most [maxRequestsInFlight] authorized requests in flight.
  final RequestSlots _slots = RequestSlots(maxRequestsInFlight);

  /// The server closed the stream it had just opened (4000 + status): a
  /// refusal, so the next attempt waits instead of going at once.
  bool _streamRefused = false;

  /// Set by [dispose]: nothing more is said on the streams.
  bool _silent = false;

  /// The server's wait from a refused event stream, used for the next retry.
  Duration? _refusedWait;

  /// Bumped whenever the online path changes or is lost; a probe that sees
  /// another value than it started with gives up.
  int _session = 0;
  Completer<void>? _probeWake;

  /// Ends a probe's overlap early; only the session ending completes it.
  Completer<void>? _overlapEnds;
  EventOverlap? _overlap;

  /// Requests still waiting for an answer, per transport.
  final Map<ServerTransport, int> _inFlight = <ServerTransport, int>{};

  /// Transports replaced by a switch, closed once their requests answered.
  final Set<ServerTransport> _retired = <ServerTransport>{};

  final StreamController<ConnectionStatus> _statuses =
      StreamController<ConnectionStatus>.broadcast(sync: true);
  final StreamController<Map<String, Object?>> _events =
      StreamController<Map<String, Object?>>.broadcast(sync: true);
  final StreamController<ServerProfile> _profiles =
      StreamController<ServerProfile>.broadcast(sync: true);
  final StreamController<ServerToken> _tokens =
      StreamController<ServerToken>.broadcast(sync: true);

  ServerProfile get profile => _profile;

  /// The saved server after pairing again: new addresses or name.
  void updateProfile(ServerProfile next) {
    if (next.fingerprint == _profile.fingerprint) _profile = next;
  }

  ConnectionStatus get status => _status;
  Stream<ConnectionStatus> get statuses => _statuses.stream;

  /// Server events while online, the stream's `open` frame first.
  Stream<Map<String, Object?>> get events => _events.stream;

  /// The profile after a path worked (its [ServerProfile.lastPath]).
  Stream<ServerProfile> get profileChanges => _profiles.stream;

  /// The token after the server moved its expiry or scopes (`open` frame).
  Stream<ServerToken> get tokenChanges => _tokens.stream;

  /// Time left on the token, checked against this computer's clock; null
  /// without a token.
  Duration? get tokenLeft => token?.expiresAt.difference(_now().toUtc());

  Map<String, Object?>? get hello => _hello;

  /// The last `open` frame of the event stream.
  Map<String, Object?>? get opened => _opened;

  void _emit(ConnectionStatus status) {
    _status = status;
    if (!_silent) _statuses.add(status);
  }

  /// Starts connecting; returns once the first attempt round ended.
  Future<void> connect() {
    if (_running) {
      retryNow();
      return Future<void>.value();
    }
    if (token == null) {
      _emit(
        ConnectionStatus(
          ConnectionPhase.needsPairing,
          problem: transportCopy('no-token'),
        ),
      );
      return Future<void>.value();
    }
    if (!token!.expiresAt.isAfter(_now().toUtc())) {
      _needsPairing(serverCopy('token-expired'));
      return Future<void>.value();
    }
    _running = true;
    final int generation = ++_generation;
    final Completer<void> firstRound = Completer<void>();
    unawaited(
      _loop(generation, firstRound).catchError((Object error) {
        if (!firstRound.isCompleted) firstRound.complete();
        if (generation != _generation) return;
        // The loop itself failed: say so, and start it again after a wait.
        _running = false;
        final Duration wait = reconnectDelay(1);
        _emit(
          ConnectionStatus(
            ConnectionPhase.reconnecting,
            retryAt: _now().add(wait),
            problem: transportCopy('dropped'),
            hello: _hello,
          ),
        );
        unawaited(
          delay(wait).then((void _) {
            if (!_running &&
                token != null &&
                _status.phase == ConnectionPhase.reconnecting) {
              unawaited(connect());
            }
          }),
        );
      }),
    );
    return firstRound.future;
  }

  /// Connects again with the current [token] and [profile], after pairing
  /// again: an online session ends and the next one opens at once, the last
  /// good path first, without going offline; a connection waiting to retry
  /// tries now, and a stopped one connects.
  Future<void> reconnect() {
    if (!_running) return connect();
    final ServerTransport? transport = _transport;
    final Completer<void>? lost = _lost;
    if (transport == null || lost == null || lost.isCompleted) {
      retryNow();
      return Future<void>.value();
    }
    _lastOnline = _now();
    unawaited(_drop());
    _emit(
      ConnectionStatus(
        ConnectionPhase.reconnecting,
        path: transport.path,
        hello: _hello,
      ),
    );
    lost.complete();
    return Future<void>.value();
  }

  /// Skips the wait before the next attempt.
  void retryNow() {
    final Completer<void>? wake = _wake;
    if (wake != null && !wake.isCompleted) wake.complete();
  }

  /// Skips the wait before the next direct-path probe (the browser came back
  /// online or the tab became visible). Does nothing unless on the relay.
  void probeNow() {
    final Completer<void>? wake = _probeWake;
    if (wake != null && !wake.isCompleted) wake.complete();
  }

  void _endSession() {
    _session++;
    _overlap = null;
    probeNow();
    final Completer<void>? overlapEnds = _overlapEnds;
    if (overlapEnds != null && !overlapEnds.isCompleted) overlapEnds.complete();
  }

  Future<void> disconnect() async {
    _running = false;
    _generation++;
    _lastOnline = null;
    retryNow();
    final Completer<void>? lost = _lost;
    if (lost != null && !lost.isCompleted) lost.complete();
    await _drop();
    await _unpark();
    _emit(const ConnectionStatus(ConnectionPhase.offline));
  }

  /// Ends the online session. A relay path whose server just left stays
  /// open, parked for the reconnect.
  Future<void> _drop() async {
    final EventSubscription? subscription = _subscription;
    final ServerTransport? transport = _transport;
    _subscription = null;
    _transport = null;
    _endSession();
    await subscription?.cancel();
    if (transport is RelayLink &&
        !(transport as RelayLink).isClosed &&
        !(transport as RelayLink).serverOnline &&
        _running) {
      await _unpark();
      _parked = transport;
      return;
    }
    await transport?.close();
  }

  Future<void> _unpark() async {
    final ServerTransport? parked = _parked;
    _parked = null;
    await parked?.close();
  }

  /// The parked relay path when it is still open, taken for an attempt.
  ServerTransport? _takeParked(ConnectionPath path) {
    final ServerTransport? parked = _parked;
    if (parked == null || parked.path != path) return null;
    _parked = null;
    if ((parked as RelayLink).isClosed) {
      unawaited(parked.close());
      return null;
    }
    return parked;
  }

  /// The words for a path no server answered on: once the connection has
  /// been online, the first minute of silence is a restart.
  ErrorCopy _silence(ErrorCopy problem) {
    final DateTime? online = _lastOnline;
    // A relay that does not open is the relay's fault, whatever the server
    // does (a relay-only pairing).
    if (problem.code == 'relay-unreachable') return problem;
    if (online == null ||
        _now().difference(online) >= const Duration(minutes: 1)) {
      return problem;
    }
    return transportCopy('restarting');
  }

  bool get _stopping {
    final DateTime? at = _stoppingAt;
    return at != null && _now().difference(at) < fastWindow;
  }

  bool _live(int generation) => _running && generation == _generation;

  Future<void> _loop(int generation, Completer<void> firstRound) async {
    int attempt = 0;
    final DateTime started = _now();
    // Why the last round failed: a retry in flight keeps saying it, so the
    // words do not alternate on every attempt.
    ErrorCopy? lastProblem;
    while (_live(generation)) {
      ErrorCopy? problem;
      bool wasOnline = false;
      int tried = 0;
      int unanswered = 0;
      // A relay session probes the direct path at once unless this round
      // saw it fail.
      bool directFailed = _lostPath == ConnectionPath.direct;
      for (final (ConnectionPath path, String url) in _order()) {
        if (!_live(generation)) break;
        _emit(
          ConnectionStatus(
            ConnectionPhase.connecting,
            path: path,
            attempt: attempt,
            problem: attempt > 0 ? lastProblem : null,
            hello: _hello,
          ),
        );
        final ErrorCopy? result = await _attempt(
          path,
          url,
          generation,
          probeAtOnce: !directFailed,
        );
        if (result == null) {
          if (!firstRound.isCompleted) firstRound.complete();
          await _lost!.future;
          if (_streamRefused) {
            // Refused right after opening: count it as a failed attempt.
            _streamRefused = false;
            problem = _status.problem;
            tried++;
          } else {
            wasOnline = true;
            attempt = 0;
            lastProblem = null;
          }
          break;
        }
        tried++;
        if (path == ConnectionPath.direct) directFailed = true;
        if (_noAnswer.contains(result.code)) unanswered++;
        // A server that answered and refused this page's origin outranks a
        // path that was silent: it names the config key to change.
        if (problem?.code != 'origin-not-allowed') problem = result;
      }
      // Both paths silent points at the server, not at either path.
      if (tried > 1 && unanswered == tried) {
        problem = transportCopy('no-answer');
      }
      if (problem != null && unanswered == tried) problem = _silence(problem);
      if (!firstRound.isCompleted) firstRound.complete();
      if (!_live(generation)) break;
      // Lost while online: fail over at once, the other path first.
      if (wasOnline) continue;
      attempt++;
      final Duration backoff = reconnectDelay(
        attempt,
        away: _now().difference(_lastOnline ?? started),
        visible: visible(),
        stopping: _stopping,
      );
      final Duration? hinted = _refusedWait;
      _refusedWait = null;
      final Duration wait = hinted != null && hinted > backoff
          ? hinted
          : backoff;
      lastProblem = problem;
      _emit(
        ConnectionStatus(
          ConnectionPhase.reconnecting,
          attempt: attempt,
          retryAt: _now().add(wait),
          problem: problem,
          hello: _hello,
        ),
      );
      final Completer<void> wake = Completer<void>();
      _wake = wake;
      final ServerTransport? parked = _parked;
      await Future.any(<Future<void>>[
        delay(wait),
        wake.future,
        if (parked != null) (parked as RelayLink).serverBack,
      ]);
      _wake = null;
    }
    if (!firstRound.isCompleted) firstRound.complete();
    if (generation != _generation) return;
    _running = false;
    await _unpark();
  }

  /// Path failures that mean nothing answered at all.
  static const Set<String> _noAnswer = <String>{
    'unreachable',
    'timeout',
    'relay-unreachable',
    'server-offline',
    'server-left',
    'restarting',
  };

  /// Paths to try: after a loss, the other path first.
  List<(ConnectionPath, String)> _order() {
    final List<(ConnectionPath, String)> paths = _profile.paths;
    final ConnectionPath? lost = _lostPath;
    _lostPath = null;
    if (lost == null || paths.length < 2 || paths.first.$1 != lost) {
      return paths;
    }
    return paths.reversed.toList();
  }

  /// One attempt on one path. Returns null once online, else the problem.
  /// Opening the path, hello and the event stream's `open` frame share
  /// [handshakeTimeout]; a path still busy after it is closed and reported
  /// as silent, so the other path gets its turn within seconds.
  Future<ErrorCopy?> _attempt(
    ConnectionPath path,
    String url,
    int generation, {
    required bool probeAtOnce,
  }) async {
    final _Handshake shake = _Handshake(path);
    final ErrorCopy? result = await Future.any(<Future<ErrorCopy?>>[
      _handshake(shake, url, generation, probeAtOnce: probeAtOnce),
      delay(handshakeTimeout).then((void _) => shake.expire()),
    ]);
    return shake.expired ?? result;
  }

  /// Opens the event stream on [transport] and waits for its `open` frame.
  /// Every later frame goes to [deliver]. Returns the open frame, or throws
  /// [StreamRefused] when the stream closed first.
  Future<(EventSubscription, Map<String, Object?>)> _openStream(
    ServerTransport transport,
    void Function(Map<String, Object?> event) deliver,
    void Function(EventSubscription subscription) ended,
  ) async {
    final EventSubscription subscription = await transport.subscribe(
      token!.token,
    );
    final Completer<Map<String, Object?>> first =
        Completer<Map<String, Object?>>();
    subscription.events.listen(
      (Map<String, Object?> event) {
        if (!first.isCompleted) {
          if (event['type'] == 'open') {
            first.complete(event);
            return;
          }
          if (event['type'] == 'closing') return;
        }
        deliver(event);
      },
      onError: (Object _) {},
      onDone: () {
        if (!first.isCompleted) {
          final String reason = subscription.closeReason ?? 'stream-closed';
          first.completeError(
            StreamRefused(_statusOf(reason), _codeOf(reason)),
          );
          return;
        }
        ended(subscription);
      },
    );
    try {
      return (subscription, await first.future);
    } on Object {
      await subscription.cancel();
      rethrow;
    }
  }

  static int _statusOf(String reason) {
    final int? status = int.tryParse(reason.split(' ').first);
    if (status != null && status >= 4000) return status - 4000;
    return status ?? 0;
  }

  static String _codeOf(String reason) {
    final List<String> words = reason.split(' ');
    return words.length > 1 && int.tryParse(words.first) != null
        ? words.sublist(1).join(' ')
        : reason;
  }

  /// [_attempt] without its budget. Once [shake] expired, a path that opens
  /// late is closed and nothing heard after is acted on. Any failure, not
  /// only a [TransportFailure], ends as a problem.
  Future<ErrorCopy?> _handshake(
    _Handshake shake,
    String url,
    int generation, {
    required bool probeAtOnce,
  }) async {
    final ConnectionPath path = shake.path;
    ServerTransport? transport;
    try {
      final ServerTransport opened = _takeParked(path) ?? await open(path, url);
      if (shake.late) {
        await opened.close();
        return shake.expired;
      }
      if (!_live(generation)) {
        return await _failed(shake, opened, transportCopy('closed'));
      }
      transport = opened;
      shake.transport = opened;
      if (opened is RelayLink && !(opened as RelayLink).serverOnline) {
        // The relay is up and the server is not on it: keep the relay open
        // and hear the moment it comes back.
        shake.settled = true;
        transport = null;
        await _unpark();
        _parked = opened;
        return transportCopy('server-offline');
      }
      final (ApiResponse hello, bool proven) = await _greet(
        opened,
        _profile.fingerprint,
      );
      if (shake.late) return shake.expired;
      if (!_live(generation)) {
        return await _failed(shake, opened, transportCopy('closed'));
      }
      final Object? body = hello.body;
      if (!hello.ok || body is! Map<String, Object?>) {
        return await _failed(shake, opened, hello.problem(origin: pageOrigin));
      }
      if (body['fingerprint'] != _profile.fingerprint || !proven) {
        _running = false;
        final ErrorCopy wrong = transportCopy('wrong-server');
        _emit(
          ConnectionStatus(ConnectionPhase.refused, path: path, problem: wrong),
        );
        return await _failed(shake, opened, wrong);
      }
      if (!speaksProtocol(body)) {
        _running = false;
        final ErrorCopy protocol = serverCopy('unsupported-protocol');
        _emit(
          ConnectionStatus(
            ConnectionPhase.refused,
            path: path,
            problem: protocol,
          ),
        );
        return await _failed(shake, opened, protocol);
      }
      _hello = body;
      // Disconnected or forgotten meanwhile: open no stream.
      if (!_live(generation)) {
        return await _failed(shake, opened, transportCopy('closed'));
      }
      final EventSubscription subscription;
      final Map<String, Object?> openFrame;
      try {
        (subscription, openFrame) = await _openStream(
          opened,
          (Map<String, Object?> event) => _deliver(path, event),
          (EventSubscription ended) => _streamEnded(opened, ended),
        );
      } on StreamRefused catch (refused) {
        if (shake.late || !_live(generation)) {
          return await _failed(
            shake,
            opened,
            shake.expired ?? transportCopy('closed'),
          );
        }
        // A stream lost before `open` carries the path's own code (a relay
        // reason, `stream-closed`), which no server error copy words.
        final ErrorCopy problem =
            knownServerCopy(refused.code, origin: pageOrigin) ??
            knownTransportCopy(refused.code) ??
            knownRelayCopy(refused.code) ??
            serverCopy(
              refused.code,
              status: refused.status == 0 ? null : refused.status,
            );
        if (tokenErrorCodes.contains(refused.code)) _needsPairing(problem);
        _refusedWait = refused.retryAfter;
        return await _failed(shake, opened, problem);
      }
      if (shake.late || !_live(generation)) {
        await subscription.cancel();
        return await _failed(shake, opened, transportCopy('closed'));
      }
      shake.settled = true;
      _transport = opened;
      _subscription = subscription;
      _lost = Completer<void>();
      _session++;
      _stoppingAt = null;
      _rememberPath(path);
      _lastOnline = _now();
      await _unpark();
      _takeOpen(openFrame);
      _emit(
        ConnectionStatus(ConnectionPhase.online, path: path, hello: _hello),
      );
      if (!_silent) _events.add(openFrame);
      if (path == ConnectionPath.relay && _profile.directUrl != null) {
        unawaited(
          _probeLoop(_session, _profile.directUrl!, atOnce: probeAtOnce),
        );
      }
      return null;
    } on TransportFailure catch (failure) {
      return await _failed(shake, transport, failure.copy);
    } on Object {
      // A malformed answer or a bug: reported like a dropped path, then retried.
      return await _failed(shake, transport, transportCopy('dropped'));
    }
  }

  /// The `open` frame moves the token's expiry and scopes.
  void _takeOpen(Map<String, Object?> frame) {
    _opened = frame;
    final ServerToken? current = token;
    if (current == null) return;
    final DateTime? expires = DateTime.tryParse('${frame['expiresAt']}');
    final Object? scopes = frame['scopes'];
    final ServerToken next = current.copyWith(
      expiresAt: expires?.toUtc(),
      scopes: scopes is List<Object?>
          ? <String>[
              for (final Object? scope in scopes)
                if (scope is String) scope,
            ]
          : null,
    );
    token = next;
    if (!_silent) _tokens.add(next);
  }

  /// Ends a handshake that failed: its budget stops, its path closes, and
  /// the budget's word stands when it ran out first.
  Future<ErrorCopy> _failed(
    _Handshake shake,
    ServerTransport? transport,
    ErrorCopy problem,
  ) async {
    final ErrorCopy outcome = shake.expired ?? problem;
    shake.settled = true;
    await transport?.close();
    return outcome;
  }

  void _rememberPath(ConnectionPath path) {
    if (_profile.lastPath == path) return;
    _profile = _profile.copyWith(lastPath: path);
    if (!_silent) _profiles.add(_profile);
  }

  void _deliver(ConnectionPath from, Map<String, Object?> event) {
    if (_silent) return;
    final EventOverlap? overlap = _overlap;
    if (overlap != null && !overlap.accept(from, event)) return;
    if (event['type'] == 'open') _takeOpen(event);
    _events.add(event);
  }

  /// Probes the direct path with backoff for as long as [session] is the
  /// relay session, and switches to it once a probe succeeds. With [atOnce]
  /// the first probe goes without a wait and does not count as a failure.
  Future<void> _probeLoop(
    int session,
    String url, {
    bool atOnce = false,
  }) async {
    int failures = 0;
    bool wait = !atOnce;
    while (_running && _session == session) {
      final bool counted = wait;
      if (wait) {
        final Completer<void> wake = Completer<void>();
        _probeWake = wake;
        await Future.any(<Future<void>>[
          delay(probeIntervals[math.min(failures, probeIntervals.length - 1)]),
          wake.future,
        ]);
        if (identical(_probeWake, wake)) _probeWake = null;
        if (!_running || _session != session) return;
      }
      wait = true;
      if (await _probe(session, url)) return;
      if (counted) failures++;
    }
  }

  /// One probe of the direct path while the relay session [session] carries
  /// everything. Nothing it does touches the relay session: a failure closes
  /// only the probe's own transport. Returns whether the switch committed.
  ///
  /// hello must carry the pinned fingerprint, then the direct event stream
  /// opens (its `open` frame proves the token) beside the relay stream for
  /// [overlap]. Only when it is still open after that do new requests go
  /// direct and the relay stream close. Requests already sent over the relay
  /// keep their transport until they answer.
  Future<bool> _probe(int session, String url) async {
    ServerTransport? direct;
    EventSubscription? stream;
    bool committed = false;
    final Completer<void> ended = Completer<void>();
    bool stale() => !_running || _session != session || token == null;
    try {
      direct = await open(ConnectionPath.direct, url);
      if (stale()) return false;
      final ApiResponse hello = await direct.send(
        const ApiRequest('GET', '/v1/hello'),
      );
      final Object? body = hello.body;
      if (stale() ||
          !hello.ok ||
          body is! Map<String, Object?> ||
          body['fingerprint'] != _profile.fingerprint ||
          !speaksProtocol(body)) {
        return false;
      }
      final ServerTransport opened = direct;
      // A stream that never sends its `open` frame would hold the probe, and
      // every later one, for as long as it stays open: it gets the handshake
      // budget, and the path closes when that runs out.
      final (EventSubscription, Map<String, Object?>)? streamed =
          await Future.any(<Future<(EventSubscription, Map<String, Object?>)?>>[
            _openStream(
              opened,
              (Map<String, Object?> event) =>
                  _deliver(ConnectionPath.direct, event),
              (EventSubscription subscription) {
                if (committed) {
                  _streamEnded(opened, subscription);
                } else if (!ended.isCompleted) {
                  ended.complete();
                }
              },
            ),
            delay(handshakeTimeout).then((void _) => null),
          ]);
      if (streamed == null) return false;
      final (EventSubscription subscription, Map<String, Object?> openFrame) =
          streamed;
      stream = subscription;
      if (stale()) return false;
      final EventOverlap overlapping = EventOverlap();
      _overlap = overlapping;
      final Completer<void> sessionEnded = Completer<void>();
      _overlapEnds = sessionEnded;
      await Future.any(<Future<void>>[
        delay(overlap),
        ended.future,
        sessionEnded.future,
      ]);
      if (identical(_overlapEnds, sessionEnded)) _overlapEnds = null;
      if (ended.isCompleted || stale()) {
        if (identical(_overlap, overlapping)) _overlap = null;
        return false;
      }
      committed = true;
      _commit(opened, subscription, body, openFrame, overlapping);
      return true;
    } on TransportFailure {
      return false;
    } on StreamRefused {
      return false;
    } on Object {
      return false;
    } finally {
      if (!committed) {
        await stream?.cancel();
        await direct?.close();
      }
    }
  }

  void _commit(
    ServerTransport direct,
    EventSubscription stream,
    Map<String, Object?> hello,
    Map<String, Object?> openFrame,
    EventOverlap overlapping,
  ) {
    final ServerTransport? relay = _transport;
    final EventSubscription? relayStream = _subscription;
    _transport = direct;
    _subscription = stream;
    _hello = hello;
    _takeOpen(openFrame);
    _session++;
    _rememberPath(ConnectionPath.direct);
    _emit(
      ConnectionStatus(
        ConnectionPhase.online,
        path: ConnectionPath.direct,
        hello: _hello,
      ),
    );
    unawaited(relayStream?.cancel());
    if (relay != null) {
      _retired.add(relay);
      _closeIfIdle(relay);
    }
    // Relay frames can trail the direct ones; keep matching them for one
    // more overlap so a late direct copy of a relay event is still dropped.
    unawaited(
      delay(overlap).then((void _) {
        if (identical(_overlap, overlapping)) _overlap = null;
      }),
    );
  }

  void _closeIfIdle(ServerTransport transport) {
    if ((_inFlight[transport] ?? 0) > 0 || !_retired.remove(transport)) return;
    unawaited(transport.close());
  }

  void _streamEnded(ServerTransport transport, EventSubscription subscription) {
    if (!identical(_subscription, subscription)) return;
    final String? reason = subscription.closeReason;
    // A reason is a code, or a status and a code ("4429 rate-limited"):
    // match whole words, so "rate-limited" is not read as "limit".
    final List<String> words = reason == null
        ? const <String>[]
        : reason.split(' ');
    final String? tokenCode = tokenErrorCodes.where(words.contains).firstOrNull;
    if (tokenCode != null) {
      _needsPairing(serverCopy(tokenCode));
      return;
    }
    final String? closing = closingReasons.where(words.contains).firstOrNull;
    if (closing == 'server-stopping') _stoppingAt = _now();
    // A direct stream closed with 4000 + status carries a server error code;
    // the relay closes with its own codes.
    final String code = words.isEmpty ? '' : words.last;
    final ErrorCopy? refused =
        closing == null && transport.path == ConnectionPath.direct
        ? knownServerCopy(code, origin: pageOrigin)
        : null;
    final ErrorCopy? pathCopy =
        knownTransportCopy(code) ?? knownRelayCopy(code);
    if (refused != null || (closing == null && _relayRefusals.contains(code))) {
      _streamRefused = true;
    }
    _connectionLost(
      transport.path,
      closing != null
          ? closingCopyOf(closing)
          : refused ?? pathCopy ?? transportCopy('stream-closed'),
    );
  }

  /// Relay close reasons that refuse the stream rather than lose the path.
  static const Set<String> _relayRefusals = <String>{
    'rate-limited',
    'capacity',
  };

  void _needsPairing(ErrorCopy problem) {
    _running = false;
    token = null;
    unawaited(_drop());
    final Completer<void>? lost = _lost;
    if (lost != null && !lost.isCompleted) lost.complete();
    _emit(
      ConnectionStatus(
        ConnectionPhase.needsPairing,
        problem: problem,
        hello: _hello,
      ),
    );
  }

  void _connectionLost(ConnectionPath path, ErrorCopy problem) {
    if (_transport == null || _transport!.path != path) return;
    _lostPath = path;
    _lastOnline = _now();
    unawaited(_drop());
    _emit(
      ConnectionStatus(
        ConnectionPhase.reconnecting,
        path: path,
        problem: problem,
        hello: _hello,
      ),
    );
    final Completer<void>? lost = _lost;
    if (lost != null && !lost.isCompleted) lost.complete();
  }

  bool _tokenRefused(ApiResponse response) {
    if (response.status != 401 ||
        !tokenErrorCodes.contains(response.errorCode)) {
      return false;
    }
    _needsPairing(response.problem());
    return true;
  }

  ApiRequest _authorized(ApiRequest request) {
    final Map<String, String> headers = <String, String>{
      'Authorization': 'Bearer ${token!.token}',
    };
    if (request.counted) {
      _counter = math.max(
        math.max(_counter, token!.counter) + 1,
        _now().millisecondsSinceEpoch,
      );
      headers['X-Gloss-Counter'] = '$_counter';
    }
    return request.withHeaders(headers);
  }

  /// Sends an authorized request on the open path. A path failure starts a
  /// reconnect (to the other path first) and is rethrown. A counted request
  /// refused as replayed takes the server's counter and goes again once.
  Future<ApiResponse> request(ApiRequest request) async {
    if (_transport == null ||
        _status.phase != ConnectionPhase.online ||
        token == null) {
      throw NotConnected(_status);
    }
    await _slots.take();
    try {
      await _pacer.take();
      final ApiResponse response = await _send(request);
      if (request.counted && response.errorCode == 'counter-replayed') {
        final Object? seen = response.error?['counter'];
        if (seen is num) _counter = math.max(_counter, seen.toInt());
        return await _send(request);
      }
      return response;
    } finally {
      _slots.release();
    }
  }

  Future<ApiResponse> _send(ApiRequest request) async {
    final ServerTransport? transport = _transport;
    if (transport == null ||
        _status.phase != ConnectionPhase.online ||
        token == null) {
      throw NotConnected(_status);
    }
    _inFlight[transport] = (_inFlight[transport] ?? 0) + 1;
    try {
      final ApiResponse response = await transport.send(_authorized(request));
      _tokenRefused(response);
      if (response.errorCode == 'rate-limited') {
        _pacer.hold(response.retryAfter ?? const Duration(seconds: 1));
      }
      return response;
    } on TransportFailure catch (failure) {
      if (identical(_transport, transport)) {
        _connectionLost(transport.path, failure.copy);
      }
      rethrow;
    } finally {
      final int left = _inFlight[transport]! - 1;
      if (left == 0) {
        _inFlight.remove(transport);
      } else {
        _inFlight[transport] = left;
      }
      _closeIfIdle(transport);
    }
  }

  /// [request], sent again after the server's hint while it answers
  /// `rate-limited` or `busy` with one, at most [retries] times. The last
  /// answer comes back either way, so nothing is dropped unsaid. Only for
  /// requests that are safe to repeat.
  Future<ApiResponse> requestRetrying(
    ApiRequest request, {
    int retries = retryLimit,
    math.Random? jitter,
  }) async {
    final math.Random random = jitter ?? math.Random();
    for (int attempt = 0; ; attempt++) {
      final ApiResponse response = await this.request(request);
      final Duration? wait = response.retryAfter;
      if (wait == null ||
          attempt >= retries ||
          !(response.errorCode == 'rate-limited' ||
              response.errorCode == 'busy')) {
        return response;
      }
      await delay(
        wait + Duration(milliseconds: 10 + random.nextInt(40) + attempt * 25),
      );
      if (_status.phase != ConnectionPhase.online) throw NotConnected(_status);
    }
  }

  /// Asks each path of [profile] for `GET /v1/hello` without a token and
  /// returns the first that answers as the pinned server, with the server's
  /// name; null when none does within [timeout] each. Opens no event stream.
  static Future<(ConnectionPath, String?)?> probe(
    ServerProfile profile,
    TransportFactory open, {
    Duration timeout = const Duration(seconds: 8),
  }) async {
    for (final (ConnectionPath path, String url) in profile.paths) {
      ServerTransport? transport;
      try {
        transport = await open(path, url).timeout(timeout);
        if (transport is RelayLink && !(transport as RelayLink).serverOnline) {
          continue;
        }
        final (ApiResponse hello, bool proven) = await _greet(
          transport,
          profile.fingerprint,
        ).timeout(timeout);
        final Object? body = hello.body;
        if (hello.ok &&
            proven &&
            body is Map<String, Object?> &&
            body['fingerprint'] == profile.fingerprint) {
          return (
            path,
            body['serverName'] is String ? body['serverName']! as String : null,
          );
        }
      } on Object {
        continue;
      } finally {
        await transport?.close();
      }
    }
    return null;
  }

  /// Exchanges a pairing code's secret for a token over the first path that
  /// answers as the pinned server, and returns the server's name when it
  /// sends one. A path that fails or refuses does not stop the next one.
  ///
  /// Each path has [pathTimeout] to open and answer hello, so a direct call
  /// the browser holds (an unanswered local network prompt) still leaves the
  /// relay its turn. The secret goes out only after that and is never cut,
  /// so an abandoned path cannot use up the code.
  ///
  /// When no path pairs, the refusal names the first of: the server refusing
  /// the secret; a path that answered without pairing (another server, or an
  /// error such as `origin-not-allowed`); the browser blocking the direct
  /// path from the https page at [pageOrigin], ahead of a relay that is also
  /// down because the user can lift it; the relay not answering; the server
  /// missing from the relay when the link has no direct address that stayed
  /// silent too; and last, no answer at all.
  static Future<(ServerToken, ConnectionPath, String?)> pair(
    PairingPayload payload,
    TransportFactory open, {
    String? client,
    String? pageOrigin,
    Duration pathTimeout = const Duration(seconds: 8),
  }) async {
    PairingRefused? refusal;
    PairingRefused? answered;
    bool blocked = false;
    bool relayDown = false;
    bool relayGone = false;
    bool directSilent = false;
    for (final (ConnectionPath path, String url) in <(ConnectionPath, String)>[
      if (payload.directUrl != null)
        (ConnectionPath.direct, payload.directUrl!),
      if (payload.relayUrl != null) (ConnectionPath.relay, payload.relayUrl!),
    ]) {
      ServerTransport? transport;
      bool cut = false;
      bool heard = false;
      Future<(ServerTransport, ApiResponse, bool)> reach() async {
        final ServerTransport opened = await open(path, url);
        if (cut) {
          await opened.close();
          throw TransportFailure(
            path,
            'closed',
            'opened after the pairing timeout',
          );
        }
        transport = opened;
        if (opened is RelayLink && !(opened as RelayLink).serverOnline) {
          throw TransportFailure(path, 'server-offline');
        }
        final (ApiResponse hello, bool proven) = await _greet(
          opened,
          payload.fingerprint,
        );
        return (opened, hello, proven);
      }

      try {
        final (
          ServerTransport opened,
          ApiResponse hello,
          bool proven,
        ) = await reach().timeout(
          pathTimeout,
          onTimeout: () {
            cut = true;
            // A relay socket that never opened is the relay's fault; a
            // silent hello is the server's.
            throw TransportFailure(
              path,
              path == ConnectionPath.relay && transport == null
                  ? 'relay-unreachable'
                  : 'timeout',
              'no answer in $pathTimeout',
            );
          },
        );
        heard = true;
        final Object? body = hello.body;
        if (!hello.ok || body is! Map<String, Object?>) {
          answered ??= PairingRefused(
            hello.errorCode ?? codeForStatus(hello.status),
            hello.problem(origin: pageOrigin),
            retryAfter: hello.retryAfter,
          );
          continue;
        }
        if (body['fingerprint'] != payload.fingerprint || !proven) {
          answered = PairingRefused.because(PairingFailure.wrongServer);
          continue;
        }
        final ApiResponse response = await opened.send(
          ApiRequest(
            'POST',
            '/v1/pair',
            body: <String, Object?>{
              'tokenId': payload.tokenId,
              'secret': payload.secret,
              if (client != null && client.isNotEmpty)
                'client': client.length > 128
                    ? client.substring(0, 128)
                    : client,
            },
          ),
        );
        final Object? answer = response.body;
        if (!response.ok) {
          refusal ??= PairingRefused(
            response.errorCode ?? codeForStatus(response.status),
            response.problem(origin: pageOrigin),
            retryAfter: response.retryAfter,
          );
          continue;
        }
        final ServerToken? token =
            answer is Map<String, Object?> &&
                answer['fingerprint'] == payload.fingerprint
            ? ServerToken.fromJson(answer)
            : null;
        if (token == null) {
          answered = PairingRefused.because(PairingFailure.wrongServer);
          continue;
        }
        final Object? named =
            (answer as Map<String, Object?>)['serverName'] ??
            (body['serverName']);
        return (
          token,
          path,
          named is String && named.trim().isNotEmpty ? named.trim() : null,
        );
      } on TransportFailure catch (failure) {
        final String code = failure.code;
        // The server answered and refused this page's origin: an answer,
        // worded by its config key.
        if (code == 'origin-not-allowed') {
          answered ??= PairingRefused.because(PairingFailure.originNotAllowed);
          continue;
        }
        if (path == ConnectionPath.direct &&
            !heard &&
            (code == 'unreachable' || code == 'timeout') &&
            browserMayBlock(pageOrigin, url)) {
          blocked = true;
        }
        if (path == ConnectionPath.direct &&
            !heard &&
            (code == 'unreachable' || code == 'timeout')) {
          directSilent = true;
        }
        // On the relay a timeout is the server's silence; the relay's own
        // errors and a server missing from it are the relay's.
        final bool relayFault =
            code == 'relay-unreachable' ||
            code == 'server-offline' ||
            code == 'server-left' ||
            (code != 'timeout' && knownRelayCopy(code) != null);
        if (path == ConnectionPath.relay && relayFault) relayDown = true;
        if (path == ConnectionPath.relay && code == 'relay-unreachable') {
          relayGone = true;
        }
      } on Object {
        // A malformed answer or a bug counts as no answer.
      } finally {
        await transport?.close();
      }
    }
    throw refusal ??
        answered ??
        PairingRefused.because(
          blocked
              ? PairingFailure.localNetworkBlocked
              : relayGone
              ? PairingFailure.relayUnreachable
              // The server missing from the relay while its own address is
              // silent too is the server, not the relay.
              : (relayDown && !directSilent
                    ? PairingFailure.relayUnavailable
                    : PairingFailure.unreachable),
        );
  }

  /// Sends `GET /v1/hello` and says whether the answer proves the server
  /// pinned by [fingerprint]. A relay can echo any fingerprint, so there the
  /// server must sign a fresh challenge; the direct path needs no proof.
  static Future<(ApiResponse, bool)> _greet(
    ServerTransport transport,
    String fingerprint,
  ) async {
    if (transport.path == ConnectionPath.direct) {
      return (await transport.send(const ApiRequest('GET', '/v1/hello')), true);
    }
    final String challenge = newChallenge();
    final ApiResponse hello = await transport.send(
      ApiRequest(
        'GET',
        '/v1/hello',
        headers: <String, String>{challengeHeader: challenge},
      ),
    );
    return (
      hello,
      hello.ok &&
          await verifyServerProof(
            hello.headers,
            challenge: challenge,
            fingerprint: fingerprint,
          ),
    );
  }

  /// Stops reconnecting and closes everything; says nothing more after.
  Future<void> dispose() async {
    await disconnect();
    _silent = true;
    await _statuses.close();
    await _events.close();
    await _profiles.close();
    await _tokens.close();
  }
}

/// Pairing did not produce a token: [code] is the server's error code, or
/// the [PairingFailure.code] of a failure no server answered with.
class PairingRefused implements Exception {
  const PairingRefused(this.code, this.copy, {this.retryAfter});

  /// Refused for [failure].
  PairingRefused.because(PairingFailure failure)
    : this(failure.code, ErrorCopy(failure.code, '', ''));

  final String code;
  final ErrorCopy copy;

  /// The wait a `rate-limited` answer named (the pairing limit).
  final Duration? retryAfter;

  /// The case [code] falls under, which the pairing card words apart.
  PairingFailure get failure => PairingFailure.of(code);

  @override
  String toString() => 'pairing refused: $code';
}

/// Whether a `hello` answer offers editor protocol 1.
bool speaksProtocol(Map<String, Object?> hello) {
  final Object? protocols = hello['protocols'];
  return hello['protocol'] == 1 ||
      (protocols is List<Object?> && protocols.contains(1));
}

/// One attempt's handshake: the path it opened and whether its budget ran
/// out before it finished.
class _Handshake {
  _Handshake(this.path);

  final ConnectionPath path;
  ServerTransport? transport;

  /// The attempt finished, online or not; the budget no longer applies.
  bool settled = false;

  /// What the attempt reports when the budget ran out first.
  ErrorCopy? expired;

  bool get late => expired != null;

  /// The budget ran out: closes the path and says which side was silent.
  Future<ErrorCopy?> expire() async {
    if (settled) return null;
    // A relay socket that never opened is the relay's fault; a silent hello
    // is the server's.
    final ErrorCopy problem = transportCopy(
      path == ConnectionPath.relay && transport == null
          ? 'relay-unreachable'
          : 'timeout',
    );
    expired = problem;
    await transport?.close();
    return problem;
  }
}
