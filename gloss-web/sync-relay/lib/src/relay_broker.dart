import 'dart:async';
import 'dart:collection';
import 'dart:convert';

import 'package:gloss_sync_relay/src/relay_address.dart';
import 'package:gloss_sync_relay/src/relay_frame.dart';
import 'package:gloss_sync_relay/src/relay_limits.dart';
import 'package:gloss_sync_relay/src/relay_sink.dart';

/// Headers a browser may not set; the relay writes its own [_originHeader] and
/// [_addressHeader] so the server can apply its origin list and audit log.
const String _originHeader = 'Origin';
const String _addressHeader = 'X-Forwarded-For';
const Set<String> _strippedHeaders = <String>{
  'origin',
  'x-forwarded-for',
  'forwarded',
  'host',
};

/// Routes frames between browsers and servers. Everything is in memory; a
/// restart drops every registration, subscription and pending request.
///
/// Each request gets a relay id unique on its server connection. The answer is
/// delivered only to the browser that asked, with its own id restored.
final class RelayBroker {
  final RelayLimits limits;
  final Map<String, _Agent> _servers = <String, _Agent>{};
  final HashMap<RelaySink, _App> _apps = HashMap<RelaySink, _App>.identity();
  final Map<String, Set<_App>> _appsByServer = <String, Set<_App>>{};
  int _pending = 0;

  RelayBroker({this.limits = const RelayLimits()}) {
    limits.validate();
  }

  int get onlineCount => _servers.length;
  int get appCount => _apps.length;
  int get pendingCount => _pending;
  int get streamCount =>
      _apps.values.fold(0, (int total, _App app) => total + app.streams);

  bool isOnline(String fingerprint) => _servers.containsKey(fingerprint);

  /// Registers [sink] as the server [fingerprint], replacing an earlier
  /// connection with the same key. Returns false at the server limit.
  bool registerServer(String fingerprint, RelaySink sink) {
    final _Agent? existing = _servers[fingerprint];
    if (existing != null && identical(existing.sink, sink)) return true;
    if (existing == null && _servers.length >= limits.maxAgents) return false;
    _servers[fingerprint] = _Agent(fingerprint, sink);
    if (existing != null) {
      _endRoutes(existing, 'the server connection was replaced');
      existing.sink.closeWithError(
        RelayErrorCode.replaced,
        'replaced by a newer connection with the same key',
      );
      return true;
    }
    _notify(fingerprint, true);
    return true;
  }

  void unregisterServer(String fingerprint, RelaySink sink) {
    final _Agent? agent = _servers[fingerprint];
    if (agent == null || !identical(agent.sink, sink)) return;
    _servers.remove(fingerprint);
    _endRoutes(agent, 'the server disconnected');
    _notify(fingerprint, false);
  }

  /// Subscribes the browser [sink] to [fingerprint]. Returns null, or the
  /// `relay-error` code to close the connection with.
  String? subscribeApp(
    RelaySink sink,
    String fingerprint, {
    String? origin,
    String? address,
  }) {
    if (_apps.containsKey(sink)) return RelayErrorCode.invalidFrame;
    final Set<_App> subscribers = _appsByServer[fingerprint] ?? <_App>{};
    final String? group = address == null ? null : addressKey(address);
    int fromGroup = 0;
    int signingIn = 0;
    int signingInFromGroup = 0;
    for (final _App other in subscribers) {
      final bool sameGroup = group != null && other.group == group;
      if (sameGroup) fromGroup++;
      if (!other.signedIn) {
        signingIn++;
        if (sameGroup) signingInFromGroup++;
      }
    }
    if (_apps.length >= limits.maxApps ||
        subscribers.length >= limits.maxAppsPerServer ||
        fromGroup >= limits.maxAppsPerAddressPerServer ||
        signingIn >= limits.maxAppsSigningInPerServer ||
        signingInFromGroup >= limits.maxAppsSigningInPerAddressPerServer) {
      return RelayErrorCode.capacity;
    }
    final _App app = _App(sink, fingerprint, origin, address, group);
    _apps[sink] = app;
    _appsByServer[fingerprint] = subscribers..add(app);
    sink.send(
      jsonEncode(
        RelayFrames.subscribed(fingerprint, _servers.containsKey(fingerprint)),
      ),
    );
    return null;
  }

  bool isSubscribed(RelaySink sink) => _apps.containsKey(sink);

  /// True once the server accepted an event stream from the browser [sink].
  /// The server opens a stream only for a valid token, so this is the relay's
  /// sign that the browser holds one; the relay never sees token checks.
  bool isSignedIn(RelaySink sink) => _apps[sink]?.signedIn ?? false;

  /// Drops the browser [sink] and tells its server to stop each of its
  /// requests and streams.
  void unregisterApp(RelaySink sink) {
    final _App? app = _apps.remove(sink);
    if (app == null) return;
    final Set<_App>? subscribers = _appsByServer[app.fingerprint];
    subscribers?.remove(app);
    if (subscribers != null && subscribers.isEmpty) {
      _appsByServer.remove(app.fingerprint);
    }
    for (final _Route route in List<_Route>.of(app.routes.values)) {
      _remove(route);
      _cancelAtServer(route);
    }
  }

  /// Forwards a request or close from a subscribed browser.
  void routeFromApp(RelaySink sink, AppFrame frame) {
    final _App? app = _apps[sink];
    if (app == null) return;
    switch (frame) {
      case RequestFrame():
        _request(app, frame);
      case CloseFrame():
        final _Route? route = app.routes[frame.id];
        if (route == null) return;
        _remove(route);
        _cancelAtServer(route);
      case SubscribeFrame():
        return;
    }
  }

  void _request(_App app, RequestFrame request) {
    if (app.routes.containsKey(request.id)) {
      return _fail(app, request.id, 400, 'invalid-request', 'id is in use');
    }
    if (request.opensStream && app.streams >= limits.maxStreamsPerApp) {
      return _fail(app, request.id, 429, 'rate-limited', 'too many streams');
    }
    if (app.pending >= limits.maxPendingPerApp) {
      return _fail(app, request.id, 429, 'rate-limited', 'too many requests');
    }
    if (_pending >= limits.maxPendingGlobal) {
      return _fail(app, request.id, 503, 'busy', 'the relay is busy');
    }
    final _Agent? agent = _servers[app.fingerprint];
    if (agent == null) {
      return _fail(
        app,
        request.id,
        503,
        'server-offline',
        'the server is not connected to the relay',
      );
    }
    final Map<String, String> headers = <String, String>{
      for (final MapEntry<String, String> entry in request.headers.entries)
        if (!_strippedHeaders.contains(entry.key.toLowerCase()))
          entry.key: entry.value,
      if (app.origin != null) _originHeader: app.origin!,
      if (app.address != null) _addressHeader: app.address!,
    };
    final int relayId = agent.nextId++;
    final String text = jsonEncode(
      request.withRoute(relayId, headers).toJson(),
    );
    if (utf8.encode(text).length > limits.maxFrameBytes) {
      return _fail(
        app,
        request.id,
        413,
        'too-large',
        'the request is too large',
      );
    }
    final _Route route = _Route(
      app,
      agent,
      request.id,
      relayId,
      request.opensStream,
    );
    route.timer = Timer(limits.requestTimeout, () => _expire(route));
    app.routes[request.id] = route;
    agent.routes[relayId] = route;
    app.pending++;
    _pending++;
    if (route.stream) app.streams++;
    agent.sink.send(text);
  }

  /// True when the relay id [id] on the server connection [sink] is a request
  /// still waiting for its response.
  bool awaitsResponse(String fingerprint, RelaySink sink, int id) {
    final _Agent? agent = _servers[fingerprint];
    if (agent == null || !identical(agent.sink, sink)) return false;
    return agent.routes[id]?.pending ?? false;
  }

  /// Delivers a response, event or close from the server [fingerprint].
  /// Frames for unknown, finished or foreign ids are dropped.
  void routeFromAgent(String fingerprint, RelaySink sink, AgentFrame frame) {
    final _Agent? agent = _servers[fingerprint];
    if (agent == null || !identical(agent.sink, sink)) return;
    switch (frame) {
      case ResponseFrame():
        final _Route? route = agent.routes[frame.id];
        if (route == null || !route.pending) return;
        route.timer?.cancel();
        route.pending = false;
        route.app.pending--;
        _pending--;
        final bool opened =
            route.stream && frame.status >= 200 && frame.status < 300;
        if (opened) route.app.signedIn = true;
        if (!opened) _remove(route);
        route.app.sink.send(jsonEncode(frame.withId(route.clientId).toJson()));
      case EventFrame():
        final _Route? route = agent.routes[frame.id];
        if (route == null || route.pending || !route.stream) return;
        route.app.sink.send(
          jsonEncode(EventFrame(route.clientId, frame.event).toJson()),
        );
      case CloseFrame():
        final _Route? route = agent.routes[frame.id];
        if (route == null) return;
        _remove(route);
        route.app.sink.send(jsonEncode(CloseFrame(route.clientId).toJson()));
      case RegisterFrame():
        return;
    }
  }

  void _expire(_Route route) {
    if (!route.pending || !identical(route.app.routes[route.clientId], route)) {
      return;
    }
    _remove(route);
    _cancelAtServer(route);
    _fail(
      route.app,
      route.clientId,
      504,
      'timeout',
      'the server did not answer in time',
    );
  }

  void _remove(_Route route) {
    if (!identical(route.app.routes[route.clientId], route)) return;
    route.app.routes.remove(route.clientId);
    route.agent.routes.remove(route.relayId);
    route.timer?.cancel();
    if (route.pending) {
      route.pending = false;
      route.app.pending--;
      _pending--;
    }
    if (route.stream) route.app.streams--;
  }

  void _cancelAtServer(_Route route) {
    if (!identical(_servers[route.agent.fingerprint], route.agent)) return;
    route.agent.sink.send(jsonEncode(CloseFrame(route.relayId).toJson()));
  }

  void _endRoutes(_Agent agent, String message) {
    for (final _Route route in List<_Route>.of(agent.routes.values)) {
      final bool wasPending = route.pending;
      _remove(route);
      if (wasPending) {
        _fail(route.app, route.clientId, 503, 'server-offline', message);
      } else {
        route.app.sink.send(jsonEncode(CloseFrame(route.clientId).toJson()));
      }
    }
  }

  void _notify(String fingerprint, bool online) {
    final Set<_App>? subscribers = _appsByServer[fingerprint];
    if (subscribers == null) return;
    final String text = jsonEncode(RelayFrames.status(online));
    for (final _App app in List<_App>.of(subscribers)) {
      app.sink.send(text);
    }
  }

  void _fail(_App app, int id, int status, String code, String message) {
    app.sink.send(
      jsonEncode(RelayFrames.failure(id, status, code, message).toJson()),
    );
  }
}

final class _Agent {
  final String fingerprint;
  final RelaySink sink;
  final Map<int, _Route> routes = <int, _Route>{};
  int nextId = 1;

  _Agent(this.fingerprint, this.sink);
}

final class _App {
  final RelaySink sink;
  final String fingerprint;
  final String? origin;
  final String? address;

  /// [address] as counted by the per-address limits, see [addressKey].
  final String? group;
  final Map<int, _Route> routes = <int, _Route>{};
  int pending = 0;
  int streams = 0;
  bool signedIn = false;

  _App(this.sink, this.fingerprint, this.origin, this.address, this.group);
}

final class _Route {
  final _App app;
  final _Agent agent;
  final int clientId;
  final int relayId;
  final bool stream;
  bool pending = true;
  Timer? timer;

  _Route(this.app, this.agent, this.clientId, this.relayId, this.stream);
}
