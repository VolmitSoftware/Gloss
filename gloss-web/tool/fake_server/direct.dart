import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'api.dart';
import 'events.dart';
import 'protocol.dart';
import 'server.dart';

/// The request's headers, lower-cased, repeated values joined.
Map<String, String> requestHeaders(HttpRequest request) {
  final Map<String, String> headers = <String, String>{};
  request.headers.forEach((String name, List<String> values) => headers[name.toLowerCase()] = values.join(', '));
  return headers;
}

String peerAddress(HttpRequest request) => request.connectionInfo?.remoteAddress.address ?? 'unknown';

/// Writes [result] as a JSON response.
Future<void> writeResponse(HttpResponse response, FakeResponse result) async {
  response.statusCode = result.status;
  response.headers.set('Cache-Control', 'no-store');
  result.headers.forEach(response.headers.set);
  if (result.body != null) {
    response.headers.contentType = ContentType('application', 'json', charset: 'utf-8');
    response.add(utf8.encode(jsonEncode(result.body)));
  }
  await response.close();
}

/// Ends the connection without an answer, as a stopped listener does.
Future<void> destroyConnection(HttpRequest request) async {
  try {
    final Socket socket = await request.response.detachSocket(writeHeaders: false);
    socket.destroy();
  } on Object {
    // The connection is gone either way.
  }
}

/// The direct path: `http://127.0.0.1:<port>/gloss/v1/...` and the
/// `/gloss/v1/events` WebSocket with its `auth` first frame.
class DirectPath {
  DirectPath(this.server);

  final FakeGlossServer server;
  final Set<WebSocket> _sockets = <WebSocket>{};

  /// Closes every direct event socket, authenticated or not.
  void closeSockets() {
    for (final WebSocket socket in _sockets.toList()) {
      socket.close(1001, 'gone');
    }
    _sockets.clear();
  }

  Future<void> serve(HttpRequest request) async {
    if (server.directRefuses) return destroyConnection(request);
    final Stopwatch clock = Stopwatch()..start();
    final String path = request.uri.path;
    final String target = path.startsWith('/gloss/') ? path.substring('/gloss'.length) : '';
    final Map<String, String> headers = requestHeaders(request);
    if (WebSocketTransformer.isUpgradeRequest(request)) {
      if (target == '/v1/events') return _events(request, headers);
      return writeResponse(request.response, invalid('Only /gloss/v1/events takes a WebSocket.').toResponse());
    }
    final String query = request.uri.hasQuery ? '?${request.uri.query}' : '';
    final int maximum = target == '/v1/pair' || !headers.containsKey('authorization') ? openBodyBytes : requestBodyBytes;
    final FakeRequest call = FakeRequest(transport: Transport.direct, method: request.method, target: target + query, headers: headers, address: peerAddress(request));
    ApiError? refused;
    if (target.isEmpty) refused = ApiError('not-found', 'The editor API is under /gloss/v1/.');
    if (target == '/v1/events' && request.method != 'OPTIONS') refused = invalid('GET /gloss/v1/events upgrades to a WebSocket.');
    Object? body;
    if (refused == null && request.contentLength > maximum) {
      refused = ApiError('too-large', 'This request body is limited to $maximum bytes.');
      request.response.persistentConnection = false;
    } else {
      final BytesBuilder bytes = BytesBuilder(copy: false);
      await for (final List<int> chunk in request) {
        bytes.add(chunk);
        if (bytes.length > maximum) break;
      }
      if (refused == null && bytes.length > maximum) {
        refused = ApiError('too-large', 'This request body is limited to $maximum bytes.');
        request.response.persistentConnection = false;
      } else if (refused == null && bytes.isNotEmpty) {
        try {
          body = jsonDecode(utf8.decode(bytes.takeBytes()));
        } on FormatException catch (failure) {
          refused = invalid('The body is not JSON: ${failure.message}');
        }
      }
    }
    final FakeResponse result = refused == null
        ? await server.api.handle(FakeRequest(transport: Transport.direct, method: call.method, target: call.target, headers: headers, body: body, address: call.address))
        : refused.toResponse().withHeaders(server.api.cors(call));
    await writeResponse(request.response, result);
    server.log('direct ${request.method} $path ${result.status} ${clock.elapsedMilliseconds}ms');
  }

  Future<void> _events(HttpRequest request, Map<String, String> headers) async {
    final String address = peerAddress(request);
    final WebSocket socket = await WebSocketTransformer.upgrade(request);
    socket.pingInterval = const Duration(seconds: 30);
    _sockets.add(socket);
    final String? origin = headers['origin'];
    if (origin != null && !server.api.allowedOrigins.contains(origin)) {
      server.log('direct events refused origin $origin');
      await socket.close(4403, 'origin-not-allowed');
      _sockets.remove(socket);
      return;
    }
    bool authenticated = false;
    void Function()? remove;
    void send(Map<String, Object?> event) {
      try {
        socket.add(jsonEncode(event));
      } on Object {
        // The browser left; onDone removes the stream.
      }
    }

    final Timer deadline = Timer(const Duration(seconds: 10), () {
      if (!authenticated) socket.close(4401, 'unauthorized');
    });
    socket.listen(
      (Object? data) {
        if (authenticated) return;
        authenticated = true;
        deadline.cancel();
        final String? token = _authToken(data);
        if (token == null) {
          socket.close(4400, 'invalid-request');
          return;
        }
        final FakeRequest call = FakeRequest(
          transport: Transport.direct,
          method: 'GET',
          target: '/v1/events',
          headers: <String, String>{...headers, 'authorization': 'Bearer $token'},
          address: address,
        );
        final EventsOpened opened = server.api.openEvents(call, StreamPath.direct, send, () => socket.close(1000, 'closing'));
        server.log('direct events ${opened.response.status}');
        if (opened.open == null) {
          final Object? error = (opened.response.body! as Map<String, Object?>)['error'];
          socket.close(4000 + opened.response.status, (error! as Map<String, Object?>)['code']! as String);
          return;
        }
        remove = opened.remove;
        send(opened.open!);
      },
      onDone: () {
        deadline.cancel();
        remove?.call();
        _sockets.remove(socket);
      },
      onError: (Object error) {},
      cancelOnError: true,
    );
  }

  /// The token of an `{"type":"auth","token":...}` frame, or null.
  static String? _authToken(Object? data) {
    if (data is! String) return null;
    try {
      final Object? frame = jsonDecode(data);
      if (frame is! Map<String, Object?> || frame['type'] != 'auth') return null;
      final Object? token = frame['token'];
      return token is String && token.isNotEmpty ? token : null;
    } on FormatException {
      return null;
    }
  }
}
