import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'api.dart';
import 'direct.dart';
import 'events.dart';
import 'protocol.dart';
import 'server.dart';

/// Close code the relay uses after an `error` frame.
const int relayCloseCode = 4008;

/// The browser side of the relay at `ws://127.0.0.1:<port>/app`, as if the
/// relay and this server's agent connection were one process. Any origin is
/// accepted (the real relay checks its own ALLOWED_APP_ORIGINS).
class RelayPath {
  RelayPath(this.server);

  final FakeGlossServer server;
  final Set<_RelayConnection> _connections = <_RelayConnection>{};

  int get connections => _connections.length;

  Future<void> serve(HttpRequest request) async {
    if (server.relayDown) return destroyConnection(request);
    if (!WebSocketTransformer.isUpgradeRequest(request)) {
      return writeResponse(request.response, invalid('The relay at /app takes WebSocket connections only.').toResponse());
    }
    final String origin = request.headers.value('origin') ?? 'null';
    final String address = peerAddress(request);
    final WebSocket socket = await WebSocketTransformer.upgrade(request);
    final _RelayConnection connection = _RelayConnection(this, socket, origin, address);
    _connections.add(connection);
    server.log('relay connect from $origin');
    connection.start();
  }

  void closeSockets() {
    for (final _RelayConnection connection in _connections.toList()) {
      connection.socket.close(1001, 'gone');
    }
    _connections.clear();
  }

  /// Pushes `status` to every browser subscribed to this server.
  void announce(bool online) {
    for (final _RelayConnection connection in _connections) {
      if (connection.matched) connection.send(<String, Object?>{'type': 'status', 'online': online});
    }
  }
}

class _RelayConnection {
  _RelayConnection(this.path, this.socket, this.origin, this.address);

  final RelayPath path;
  final WebSocket socket;
  final String origin;
  final String address;
  final Map<int, void Function()> _streams = <int, void Function()>{};
  bool subscribed = false;

  /// Subscribed with this server's fingerprint.
  bool matched = false;
  Timer? _subscribeDeadline;

  FakeGlossServer get server => path.server;

  bool get online => matched && server.onRelay;

  void start() {
    _subscribeDeadline = Timer(const Duration(seconds: 10), () {
      if (!subscribed) _fail('timeout', 'Send a subscribe frame within 10 seconds.');
    });
    socket.listen(_message, onDone: _done, onError: (Object error) {}, cancelOnError: true);
  }

  void send(Map<String, Object?> frame) {
    try {
      socket.add(jsonEncode(frame));
    } on Object {
      // The browser left; onDone cleans up.
    }
  }

  void _fail(String code, String message) {
    send(<String, Object?>{'type': 'error', 'code': code, 'message': message});
    socket.close(relayCloseCode, code);
  }

  void _done() {
    _subscribeDeadline?.cancel();
    for (final void Function() remove in _streams.values.toList()) {
      remove();
    }
    _streams.clear();
    path._connections.remove(this);
  }

  void _message(Object? data) {
    if (data is! String) return _fail('invalid-frame', 'Frames are JSON text.');
    if (data.length > relayFrameBytes || utf8.encode(data).length > relayFrameBytes) return _fail('too-large', 'A frame is at most $relayFrameBytes bytes.');
    final Object? frame;
    try {
      frame = jsonDecode(data);
    } on FormatException {
      return _fail('invalid-frame', 'A frame is not JSON.');
    }
    if (frame is! Map<String, Object?>) return _fail('invalid-frame', 'A frame is one JSON object.');
    final Object? type = frame['type'];
    if (!subscribed) {
      if (type != 'subscribe' || frame['fingerprint'] is! String) return _fail('invalid-frame', 'The first frame is {"type":"subscribe","protocol":1,"fingerprint":...}.');
      if (frame['protocol'] != 1) return _fail('unsupported-protocol', 'This relay speaks protocol 1.');
      subscribed = true;
      _subscribeDeadline?.cancel();
      final String fingerprint = frame['fingerprint']! as String;
      matched = fingerprint == fakeFingerprint;
      send(<String, Object?>{'type': 'subscribed', 'fingerprint': fingerprint, 'online': online});
      return;
    }
    if (type == 'close' && frame['id'] is int) {
      _streams.remove(frame['id'])?.call();
      return;
    }
    if (type != 'request') return _fail('invalid-frame', 'Browsers send request and close frames after subscribing.');
    final Object? id = frame['id'];
    final Object? method = frame['method'];
    final Object? target = frame['path'];
    final Object given = frame['headers'] ?? const <String, Object?>{};
    final bool known = frame.keys.every(const <String>{'type', 'id', 'method', 'path', 'headers', 'body'}.contains);
    if (!known || id is! int || id < 0 || !const <String>{'GET', 'POST', 'DELETE'}.contains(method) || target is! String || !target.startsWith('/') ||
        given is! Map<String, Object?> || given.values.any((Object? value) => value is! String)) {
      return _fail('invalid-frame', 'A request is {"type":"request","id":N,"method":"GET|POST|DELETE","path":"/v1/...","headers":{...},"body":...}.');
    }
    if (_streams.containsKey(id)) return _respond(id, invalid('Request id $id is already in use on this connection.').toResponse());
    final int? limit = server.relayRequestBytes;
    if (limit != null && utf8.encode(data).length > limit) return _respond(id, ApiError('too-large', 'the request is too large').toResponse());
    if (!online) return _respond(id, ApiError('server-offline', 'The server is not connected to the relay.').toResponse());
    final Map<String, String> headers = <String, String>{
      for (final MapEntry<String, Object?> entry in given.entries)
        if (!const <String>{'host', 'origin', 'forwarded', 'x-forwarded-for'}.contains(entry.key.toLowerCase())) entry.key.toLowerCase(): entry.value! as String,
      'origin': origin,
      'x-forwarded-for': address,
    };
    final FakeRequest call = FakeRequest(transport: Transport.relay, method: method! as String, target: target, headers: headers, body: frame['body'], address: address);
    if (call.method == 'GET' && call.route == '/v1/events') return _stream(id, call);
    unawaited(_answer(id, call));
  }

  void _respond(int id, FakeResponse response) {
    final Map<String, Object?> frame = <String, Object?>{
      'type': 'response',
      'id': id,
      'status': response.status,
      if (response.headers.isNotEmpty) 'headers': response.headers,
      if (response.body != null) 'body': response.body,
    };
    if (jsonBytes(frame) > relayFrameBytes) {
      return _respond(id, ApiError('too-large', 'The answer does not fit one relay frame; use the direct path or a smaller request.').toResponse());
    }
    send(frame);
  }

  Future<void> _answer(int id, FakeRequest call) async {
    final Stopwatch clock = Stopwatch()..start();
    final FakeResponse response = await server.api.handle(call);
    _respond(id, response);
    server.log('relay ${call.method} ${call.route} ${response.status} ${clock.elapsedMilliseconds}ms');
  }

  void _stream(int id, FakeRequest call) {
    bool ended = false;
    final EventsOpened opened = server.api.openEvents(
      call,
      StreamPath.relay,
      (Map<String, Object?> event) => send(<String, Object?>{'type': 'event', 'id': id, 'event': event}),
      () {
        if (ended) return;
        ended = true;
        _streams.remove(id);
        send(<String, Object?>{'type': 'close', 'id': id});
      },
    );
    _respond(id, opened.response);
    server.log('relay events ${opened.response.status}');
    if (opened.open == null) return;
    send(<String, Object?>{'type': 'event', 'id': id, 'event': opened.open});
    _streams[id] = () {
      ended = true;
      opened.remove!();
    };
  }
}
