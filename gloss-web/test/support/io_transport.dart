/// The editor's two paths over dart:io, for tests that run the editor
/// against the in-process fake server (`tool/fake_server`): requests and the
/// event WebSocket on the direct listener, and one relay socket carrying
/// request, response and event frames. They mirror
/// `lib/services/server/browser_transports.dart`.
library;

import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:gloss_editor/services/server/server_hub.dart';
import 'package:gloss_editor/services/server/transport.dart';

/// Opens [path] at [url] against [origin]'s page.
Future<ServerTransport> openIoTransport(
  ConnectionPath path,
  String url,
  String fingerprint, {
  String origin = 'http://127.0.0.1:8808',
}) async {
  switch (path) {
    case ConnectionPath.direct:
      return IoDirectTransport(url, origin: origin);
    case ConnectionPath.relay:
      final IoRelayTransport relay = IoRelayTransport(
        url,
        fingerprint,
        origin: origin,
      );
      await relay.open();
      return relay;
  }
}

/// [openIoTransport] as the hub takes it.
HubTransportFactory ioTransports({String origin = 'http://127.0.0.1:8808'}) =>
    (ConnectionPath path, String url, String fingerprint) =>
        openIoTransport(path, url, fingerprint, origin: origin);

String _trimSlash(String url) =>
    url.endsWith('/') ? url.substring(0, url.length - 1) : url;

class IoDirectTransport implements ServerTransport {
  IoDirectTransport(String url, {required this.origin})
    : _base = '${_trimSlash(url)}/gloss';

  final String _base;
  final String origin;
  final HttpClient _client = HttpClient();
  final Set<_IoDirectSubscription> _open = <_IoDirectSubscription>{};
  bool _closed = false;

  @override
  ConnectionPath get path => ConnectionPath.direct;

  @override
  Future<ApiResponse> send(ApiRequest request) async {
    if (_closed) throw const TransportFailure(ConnectionPath.direct, 'closed');
    try {
      final HttpClientRequest http = await _client.openUrl(
        request.method,
        Uri.parse('$_base${request.path}'),
      );
      http.headers.set('Origin', origin);
      request.headers.forEach(http.headers.set);
      if (request.body != null) {
        http.headers.contentType = ContentType.json;
        http.add(utf8.encode(jsonEncode(request.body)));
      }
      final HttpClientResponse response = await http.close();
      final String text = await utf8.decodeStream(response);
      Object? body;
      if (text.isNotEmpty) {
        try {
          body = jsonDecode(text);
        } on FormatException {
          body = null;
        }
      }
      final Map<String, String> headers = <String, String>{};
      response.headers.forEach((String name, List<String> values) {
        headers[name] = values.join(',');
      });
      return ApiResponse(response.statusCode, body, headers: headers);
    } on SocketException catch (failure) {
      throw TransportFailure(ConnectionPath.direct, 'unreachable', '$failure');
    } on HttpException catch (failure) {
      throw TransportFailure(ConnectionPath.direct, 'unreachable', '$failure');
    }
  }

  @override
  Future<EventSubscription> subscribe(String token) async {
    if (_closed) throw const TransportFailure(ConnectionPath.direct, 'closed');
    final WebSocket socket;
    try {
      socket = await WebSocket.connect(
        '${_base.replaceFirst(RegExp('^http'), 'ws')}/v1/events',
        headers: <String, Object>{'Origin': origin},
      );
    } on Object catch (failure) {
      throw TransportFailure(ConnectionPath.direct, 'unreachable', '$failure');
    }
    socket.add(jsonEncode(<String, Object?>{'type': 'auth', 'token': token}));
    final _IoDirectSubscription subscription = _IoDirectSubscription(
      socket,
      _open.remove,
    );
    _open.add(subscription);
    return subscription;
  }

  @override
  Future<void> close() async {
    _closed = true;
    for (final _IoDirectSubscription subscription
        in List<_IoDirectSubscription>.of(_open)) {
      await subscription.cancel();
    }
    _client.close(force: true);
  }
}

class _IoDirectSubscription implements EventSubscription {
  _IoDirectSubscription(this._socket, this._done) {
    _socket.listen(
      (Object? data) {
        if (data is! String) return;
        try {
          final Object? frame = jsonDecode(data);
          if (frame is Map<String, Object?> && !_finished) {
            if (frame['type'] == 'closing') {
              _reason = frame['reason'] as String?;
            }
            _controller.add(frame);
          }
        } on FormatException {
          // JSON frames only.
        }
      },
      onDone: () {
        final int? code = _socket.closeCode;
        if (code != null && code >= 4000) {
          _reason ??= '$code ${_socket.closeReason ?? 'closed'}';
        }
        _finish(
          code != null && code >= 4000 || _cancelled
              ? null
              : TransportFailure(
                  ConnectionPath.direct,
                  'stream-closed',
                  'close $code',
                ),
        );
      },
      onError: (Object _) {},
    );
  }

  final WebSocket _socket;
  final void Function(_IoDirectSubscription subscription) _done;
  final StreamController<Map<String, Object?>> _controller =
      StreamController<Map<String, Object?>>();
  String? _reason;
  bool _cancelled = false;
  bool _finished = false;

  @override
  Stream<Map<String, Object?>> get events => _controller.stream;

  @override
  String? get closeReason => _reason;

  void _finish(Object? error) {
    if (_finished) return;
    _finished = true;
    if (error != null) _controller.addError(error);
    unawaited(_controller.close());
    _done(this);
  }

  @override
  Future<void> cancel() async {
    _cancelled = true;
    await _socket.close(1000);
    _finish(null);
  }
}

/// One socket to `<relayUrl>/app`, subscribed to a fingerprint.
class IoRelayTransport implements ServerTransport, RelayLink {
  IoRelayTransport(String url, this.fingerprint, {required this.origin})
    : _url = '${_trimSlash(url)}/app';

  final String _url;
  final String fingerprint;
  final String origin;
  late final WebSocket _socket;
  final Completer<void> _subscribed = Completer<void>();
  final Map<int, Completer<ApiResponse>> _pending =
      <int, Completer<ApiResponse>>{};
  final Map<int, _IoRelaySubscription> _streams = <int, _IoRelaySubscription>{};
  int _nextId = 1;
  bool _closed = false;
  bool _online = false;
  Completer<void> _back = Completer<void>();

  @override
  ConnectionPath get path => ConnectionPath.relay;

  @override
  bool get serverOnline => _online && !_closed;

  @override
  bool get isClosed => _closed;

  @override
  Future<void> get serverBack => _back.future;

  Future<void> open() async {
    try {
      _socket = await WebSocket.connect(
        _url,
        headers: <String, Object>{'Origin': origin},
      );
    } on Object catch (failure) {
      throw TransportFailure(
        ConnectionPath.relay,
        'relay-unreachable',
        '$failure',
      );
    }
    _socket.listen(
      (Object? data) {
        if (data is! String) return;
        try {
          final Object? frame = jsonDecode(data);
          if (frame is Map<String, Object?>) _frame(frame);
        } on FormatException {
          // JSON frames only.
        }
      },
      onDone: () => _fail(
        _subscribed.isCompleted ? 'dropped' : 'relay-unreachable',
        'close ${_socket.closeCode}',
      ),
      onError: (Object _) {},
    );
    _socket.add(
      jsonEncode(<String, Object?>{
        'type': 'subscribe',
        'protocol': 1,
        'fingerprint': fingerprint,
      }),
    );
    await _subscribed.future.timeout(
      const Duration(seconds: 5),
      onTimeout: () {
        _fail('relay-unreachable', 'no subscribed frame');
        throw const TransportFailure(ConnectionPath.relay, 'relay-unreachable');
      },
    );
  }

  void _serverLeft() {
    const TransportFailure failure = TransportFailure(
      ConnectionPath.relay,
      'server-left',
    );
    for (final Completer<ApiResponse> waiting in _pending.values) {
      waiting.completeError(failure);
    }
    _pending.clear();
    for (final _IoRelaySubscription stream in _streams.values.toList()) {
      stream.finish(failure);
    }
    _streams.clear();
    if (_back.isCompleted) _back = Completer<void>();
  }

  void _frame(Map<String, Object?> frame) {
    switch (frame['type']) {
      case 'subscribed':
        _online = frame['online'] == true;
        if (!_online && _back.isCompleted) _back = Completer<void>();
        if (!_subscribed.isCompleted) _subscribed.complete();
      case 'status':
        final bool online = frame['online'] == true;
        if (online == _online) return;
        _online = online;
        if (!online) {
          _serverLeft();
        } else if (!_back.isCompleted) {
          _back.complete();
        }
      case 'response':
        final int id = (frame['id']! as num).toInt();
        final Completer<ApiResponse>? waiting = _pending.remove(id);
        final Map<String, String> headers = <String, String>{
          if (frame['headers'] is Map<String, Object?>)
            for (final MapEntry<String, Object?> entry
                in (frame['headers']! as Map<String, Object?>).entries)
              if (entry.value is String) entry.key: entry.value! as String,
        };
        waiting?.complete(
          ApiResponse(
            (frame['status']! as num).toInt(),
            frame['body'],
            headers: headers,
            path: ConnectionPath.relay,
          ),
        );
      case 'event':
        final _IoRelaySubscription? stream =
            _streams[(frame['id']! as num).toInt()];
        final Object? value = frame['event'];
        if (stream != null && value is Map<String, Object?>) stream.add(value);
      case 'close':
        final int id = (frame['id']! as num).toInt();
        _streams.remove(id)?.finish(null);
        _pending
            .remove(id)
            ?.completeError(
              const TransportFailure(ConnectionPath.relay, 'dropped'),
            );
      case 'error':
        _fail('${frame['code']}', '${frame['message']}');
    }
  }

  void _fail(String code, [String? detail]) {
    if (_closed) return;
    _closed = true;
    if (!_back.isCompleted) _back.complete();
    final TransportFailure failure = TransportFailure(
      ConnectionPath.relay,
      code,
      detail,
    );
    if (!_subscribed.isCompleted) _subscribed.completeError(failure);
    for (final Completer<ApiResponse> waiting in _pending.values) {
      waiting.completeError(failure);
    }
    _pending.clear();
    for (final _IoRelaySubscription stream in _streams.values.toList()) {
      stream.finish(failure);
    }
    _streams.clear();
    unawaited(_socket.close(1000));
  }

  String _frameOf(int id, ApiRequest request) => jsonEncode(<String, Object?>{
    'type': 'request',
    'id': id,
    'method': request.method,
    'path': request.path,
    if (request.headers.isNotEmpty) 'headers': request.headers,
    if (request.body != null) 'body': request.body,
  });

  int _sendFrame(ApiRequest request) {
    if (_closed) throw const TransportFailure(ConnectionPath.relay, 'closed');
    final int id = _nextId++;
    _socket.add(_frameOf(id, request));
    return id;
  }

  @override
  Future<ApiResponse> send(ApiRequest request) {
    if (_closed) throw const TransportFailure(ConnectionPath.relay, 'closed');
    final int id = _nextId++;
    final String frame = _frameOf(id, request);
    final ApiResponse? refused = relayRefusal(frame);
    if (refused != null) return Future<ApiResponse>.value(refused);
    _socket.add(frame);
    final Completer<ApiResponse> completer = Completer<ApiResponse>();
    _pending[id] = completer;
    return completer.future;
  }

  @override
  Future<EventSubscription> subscribe(String token) async {
    final int id = _sendFrame(
      ApiRequest(
        'GET',
        '/v1/events',
        headers: <String, String>{'Authorization': 'Bearer $token'},
      ),
    );
    final _IoRelaySubscription stream = _IoRelaySubscription(id, (int id) {
      _streams.remove(id);
      if (!_closed) {
        _socket.add(jsonEncode(<String, Object?>{'type': 'close', 'id': id}));
      }
    });
    _streams[id] = stream;
    final Completer<ApiResponse> answer = Completer<ApiResponse>();
    _pending[id] = answer;
    final ApiResponse response = await answer.future;
    if (!response.ok) {
      _streams.remove(id);
      stream.finish(null);
      throw StreamRefused(
        response.status,
        response.errorCode ?? 'refused',
        retryAfter: response.retryAfter,
      );
    }
    return stream;
  }

  @override
  Future<void> close() async {
    for (final _IoRelaySubscription stream in _streams.values.toList()) {
      await stream.cancel();
    }
    _fail('closed');
  }
}

class _IoRelaySubscription implements EventSubscription {
  _IoRelaySubscription(this.id, this._cancel);

  final int id;
  final void Function(int id) _cancel;
  final StreamController<Map<String, Object?>> _controller =
      StreamController<Map<String, Object?>>();
  String? _reason;
  bool _finished = false;

  void add(Map<String, Object?> event) {
    if (_finished) return;
    if (event['type'] == 'closing') _reason = event['reason'] as String?;
    _controller.add(event);
  }

  void finish(Object? error) {
    if (_finished) return;
    _finished = true;
    if (error is TransportFailure) _reason ??= error.code;
    if (error != null) _controller.addError(error);
    unawaited(_controller.close());
  }

  @override
  Stream<Map<String, Object?>> get events => _controller.stream;

  @override
  String? get closeReason => _reason;

  @override
  Future<void> cancel() async {
    if (_finished) return;
    _cancel(id);
    finish(null);
  }
}
