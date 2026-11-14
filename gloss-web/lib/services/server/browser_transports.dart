/// The browser's two paths to a server: fetch and WebSocket against the
/// plugin's listener, and one WebSocket to the relay.
library;

import 'dart:async';
import 'dart:convert';
import 'dart:js_interop';

import 'package:web/web.dart' as web;

import 'error_copy.dart';
import 'page_activity.dart';
import 'transport.dart';

/// Opens [path] at [url] in this browser.
Future<ServerTransport> openBrowserTransport(
  ConnectionPath path,
  String url,
  String fingerprint,
) async {
  switch (path) {
    case ConnectionPath.direct:
      return DirectTransport(url);
    case ConnectionPath.relay:
      final RelayTransport relay = RelayTransport(url, fingerprint);
      await relay.open();
      return relay;
  }
}

/// Calls [wake] when the browser comes back online or the tab becomes
/// visible, and keeps [PageActivity.hidden] current.
void onBrowserWake(void Function() wake) {
  PageActivity.hidden = web.document.visibilityState == 'hidden';
  web.window.addEventListener('online', ((web.Event _) => wake()).toJS);
  web.document.addEventListener(
    'visibilitychange',
    ((web.Event _) {
      PageActivity.hidden = web.document.visibilityState == 'hidden';
      if (!PageActivity.hidden) wake();
    }).toJS,
  );
}

String _trimSlash(String url) =>
    url.endsWith('/') ? url.substring(0, url.length - 1) : url;

Duration requestTimeout = const Duration(seconds: 15);

/// How long the `no-cors` probe after a failed request may take.
Duration probeTimeout = const Duration(seconds: 3);

/// How long an event stream or the relay subscription may take to open: a
/// path that hangs (a pending Local Network Access prompt, a dead proxy)
/// fails over instead of stalling the connection.
Duration openTimeout = const Duration(seconds: 10);

/// `<directUrl>/gloss/v1/...` over fetch, events over a WebSocket whose
/// first frame is `{"type":"auth","token":...}`.
class DirectTransport implements ServerTransport {
  DirectTransport(String url) : _base = '${_trimSlash(url)}/gloss';

  final String _base;
  final Set<_DirectSubscription> _open = <_DirectSubscription>{};
  final Set<web.AbortController> _inFlight = <web.AbortController>{};
  bool _closed = false;

  @override
  ConnectionPath get path => ConnectionPath.direct;

  @override
  Future<ApiResponse> send(ApiRequest request) async {
    if (_closed) throw const TransportFailure(ConnectionPath.direct, 'closed');
    final web.Headers headers = web.Headers();
    request.headers.forEach(
      (String name, String value) => headers.append(name, value),
    );
    if (request.body != null) {
      headers.append('Content-Type', 'application/json');
    }
    final web.AbortController abort = web.AbortController();
    _inFlight.add(abort);
    final Timer timer = Timer(requestTimeout, () => abort.abort());
    try {
      final web.Response response = await web.window
          .fetch(
            '$_base${request.path}'.toJS,
            web.RequestInit(
              method: request.method,
              headers: headers,
              body: request.body == null ? null : jsonEncode(request.body).toJS,
              mode: 'cors',
              credentials: 'omit',
              cache: 'no-store',
              signal: abort.signal,
            ),
          )
          .toDart;
      final Map<String, String> answerHeaders = <String, String>{};
      for (final String name in const <String>['ETag', 'Retry-After']) {
        final String? value = response.headers.get(name);
        if (value != null) answerHeaders[name] = value;
      }
      return ApiResponse(
        response.status,
        await _json(response),
        headers: answerHeaders,
      );
    } on TransportFailure {
      rethrow;
    } on Object catch (failure) {
      if (_closed || abort.signal.aborted) {
        throw TransportFailure(
          ConnectionPath.direct,
          _closed ? 'closed' : 'timeout',
          '$failure',
        );
      }
      // A dropped connection, a dead server and a CORS refusal all fail
      // alike here; asking `hello` again tells them apart.
      final ApiResponse? hello = await _hello(cors: true);
      final String code = directFailureCode(
        hello,
        answersWithoutCors: hello == null && await _hello(cors: false) != null,
      );
      throw TransportFailure(
        ConnectionPath.direct,
        code,
        '$failure',
        code == 'origin-not-allowed' ? web.window.location.origin : null,
      );
    } finally {
      timer.cancel();
      _inFlight.remove(abort);
    }
  }

  /// The body of [response] as JSON, or null when it is empty or not JSON.
  static Future<Object?> _json(web.Response response) async {
    final String text = (await response.text().toDart).toDart;
    if (text.isEmpty) return null;
    try {
      return jsonDecode(text);
    } on FormatException {
      return null;
    }
  }

  /// `GET /v1/hello` after a failed request, or null when it got no answer.
  /// In `no-cors` mode the browser hides the answer (status 0, no body),
  /// but a server that is down, or a call the browser blocks, fails there
  /// too.
  Future<ApiResponse?> _hello({required bool cors}) async {
    final web.AbortController abort = web.AbortController();
    _inFlight.add(abort);
    final Timer timer = Timer(probeTimeout, () => abort.abort());
    try {
      final web.Response response = await web.window
          .fetch(
            '$_base/v1/hello'.toJS,
            web.RequestInit(
              mode: cors ? 'cors' : 'no-cors',
              credentials: 'omit',
              cache: 'no-store',
              signal: abort.signal,
            ),
          )
          .toDart;
      if (_closed) return null;
      return cors
          ? ApiResponse(response.status, await _json(response))
          : const ApiResponse(0, null);
    } on Object {
      return null;
    } finally {
      timer.cancel();
      _inFlight.remove(abort);
    }
  }

  @override
  Future<EventSubscription> subscribe(String token) async {
    if (_closed) throw const TransportFailure(ConnectionPath.direct, 'closed');
    final String socketUrl =
        '${_base.replaceFirst(RegExp('^http'), 'ws')}/v1/events';
    final _DirectSubscription subscription = _DirectSubscription(
      socketUrl,
      token,
      _open.remove,
    );
    _open.add(subscription);
    try {
      await subscription.opened.timeout(openTimeout);
    } on TimeoutException {
      await subscription.cancel();
      throw const TransportFailure(
        ConnectionPath.direct,
        'timeout',
        'the event stream did not open',
      );
    }
    return subscription;
  }

  @override
  Future<void> close() async {
    _closed = true;
    for (final web.AbortController abort in List<web.AbortController>.of(
      _inFlight,
    )) {
      abort.abort();
    }
    for (final _DirectSubscription subscription in List<_DirectSubscription>.of(
      _open,
    )) {
      await subscription.cancel();
    }
  }
}

class _DirectSubscription implements EventSubscription {
  _DirectSubscription(String url, String token, this._done)
    : _socket = web.WebSocket(url) {
    _socket.addEventListener(
      'open',
      ((web.Event _) {
        _socket.send(
          jsonEncode(<String, Object?>{'type': 'auth', 'token': token}).toJS,
        );
        if (!_opened.isCompleted) _opened.complete();
      }).toJS,
    );
    _socket.addEventListener(
      'message',
      ((web.MessageEvent event) {
        final JSAny? data = event.data;
        if (data == null || !data.isA<JSString>()) return;
        try {
          final Object? frame = jsonDecode((data as JSString).toDart);
          if (frame is Map<String, Object?>) {
            if (frame['type'] == 'closing') {
              _reason = frame['reason'] as String?;
            }
            _controller.add(frame);
          }
        } on FormatException {
          // The server sends only JSON frames; ignore anything else.
        }
      }).toJS,
    );
    _socket.addEventListener(
      'close',
      ((web.CloseEvent event) {
        if (event.code >= 4000) {
          _reason ??=
              '${event.code} ${event.reason.isEmpty ? 'closed' : event.reason}';
        }
        if (!_opened.isCompleted) {
          _opened.completeError(
            const TransportFailure(
              ConnectionPath.direct,
              'unreachable',
              'the event stream did not open',
            ),
          );
        }
        _finish(
          event.code >= 4000 || _cancelled
              ? null
              : TransportFailure(
                  ConnectionPath.direct,
                  'stream-closed',
                  'close ${event.code}',
                ),
        );
      }).toJS,
    );
  }

  final web.WebSocket _socket;
  final void Function(_DirectSubscription subscription) _done;
  final StreamController<Map<String, Object?>> _controller =
      StreamController<Map<String, Object?>>();
  final Completer<void> _opened = Completer<void>();
  String? _reason;
  bool _cancelled = false;
  bool _finished = false;

  Future<void> get opened => _opened.future;

  @override
  Stream<Map<String, Object?>> get events => _controller.stream;

  @override
  String? get closeReason => _reason;

  void _finish(Object? error) {
    if (_finished) return;
    _finished = true;
    if (error != null) _controller.addError(error);
    _controller.close();
    _done(this);
  }

  @override
  Future<void> cancel() async {
    _cancelled = true;
    if (_socket.readyState <= 1) _socket.close(1000);
    _finish(null);
  }
}

/// One WebSocket to `<relayUrl>/app`, subscribed to the server's
/// fingerprint. Requests and the event stream are relay frames. The socket
/// stays open while the server is away from the relay: requests and streams
/// in flight fail, and [serverBack] completes when the relay says it is back.
class RelayTransport implements ServerTransport, RelayLink {
  RelayTransport(String url, this.fingerprint)
    : _url = '${_trimSlash(url)}/app';

  final String _url;
  final String fingerprint;
  late final web.WebSocket _socket;
  final Completer<void> _subscribed = Completer<void>();
  final Map<int, Completer<ApiResponse>> _pending =
      <int, Completer<ApiResponse>>{};
  final Map<int, _RelaySubscription> _streams = <int, _RelaySubscription>{};
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

  Future<void> open() {
    try {
      _socket = web.WebSocket(_url);
    } on Object catch (failure) {
      throw TransportFailure(
        ConnectionPath.relay,
        'relay-unreachable',
        '$_url: $failure',
      );
    }
    _socket.addEventListener(
      'open',
      ((web.Event _) {
        _socket.send(
          jsonEncode(<String, Object?>{
            'type': 'subscribe',
            'protocol': 1,
            'fingerprint': fingerprint,
          }).toJS,
        );
      }).toJS,
    );
    _socket.addEventListener(
      'message',
      ((web.MessageEvent event) {
        final JSAny? data = event.data;
        if (data == null || !data.isA<JSString>()) return;
        try {
          final Object? frame = jsonDecode((data as JSString).toDart);
          if (frame is Map<String, Object?>) _frame(frame);
        } on FormatException {
          // Relay frames are JSON.
        }
      }).toJS,
    );
    _socket.addEventListener(
      'close',
      ((web.CloseEvent event) => _fail(
        _subscribed.isCompleted ? 'dropped' : 'relay-unreachable',
        'close ${event.code}',
      )).toJS,
    );
    final Timer timer = Timer(
      openTimeout,
      () => _fail('relay-unreachable', 'no subscribed frame'),
    );
    return _subscribed.future.whenComplete(timer.cancel);
  }

  /// The server left the relay: what was in flight fails, the socket stays.
  void _serverLeft() {
    const TransportFailure failure = TransportFailure(
      ConnectionPath.relay,
      'server-left',
    );
    for (final Completer<ApiResponse> waiting in _pending.values) {
      waiting.completeError(failure);
    }
    _pending.clear();
    for (final _RelaySubscription stream in _streams.values.toList()) {
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
        final _RelaySubscription? stream =
            _streams[(frame['id']! as num).toInt()];
        final Object? value = frame['event'];
        if (stream != null && value is Map<String, Object?>) stream.add(value);
      case 'close':
        final int id = (frame['id']! as num).toInt();
        _streams.remove(id)?.finish(null);
        _pending
            .remove(id)
            ?.completeError(
              const TransportFailure(
                ConnectionPath.relay,
                'dropped',
                'the relay closed the request',
              ),
            );
      case 'error':
        _fail(
          knownRelayCopy('${frame['code']}') != null
              ? '${frame['code']}'
              : 'dropped',
          '${frame['code']}: ${frame['message']}',
        );
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
    for (final _RelaySubscription stream in _streams.values.toList()) {
      stream.finish(failure);
    }
    _streams.clear();
    if (_socket.readyState <= 1) _socket.close(1000);
  }

  String _frameOf(int id, ApiRequest request) => jsonEncode(<String, Object?>{
    'type': 'request',
    'id': id,
    'method': request.method,
    'path': request.path,
    if (request.headers.isNotEmpty) 'headers': request.headers,
    if (request.body != null) 'body': request.body,
  });

  int _send(ApiRequest request) {
    if (_closed) throw const TransportFailure(ConnectionPath.relay, 'closed');
    final int id = _nextId++;
    _socket.send(_frameOf(id, request).toJS);
    return id;
  }

  @override
  Future<ApiResponse> send(ApiRequest request) {
    if (_closed) throw const TransportFailure(ConnectionPath.relay, 'closed');
    final int id = _nextId++;
    final String frame = _frameOf(id, request);
    final ApiResponse? refused = relayRefusal(frame);
    if (refused != null) return Future<ApiResponse>.value(refused);
    _socket.send(frame.toJS);
    final Completer<ApiResponse> completer = Completer<ApiResponse>();
    _pending[id] = completer;
    final Timer timer = Timer(requestTimeout, () {
      if (_pending.remove(id) != null) {
        completer.completeError(
          const TransportFailure(ConnectionPath.relay, 'timeout'),
        );
      }
    });
    return completer.future.whenComplete(timer.cancel);
  }

  @override
  Future<EventSubscription> subscribe(String token) async {
    final int id = _send(
      ApiRequest(
        'GET',
        '/v1/events',
        headers: <String, String>{'Authorization': 'Bearer $token'},
      ),
    );
    final _RelaySubscription stream = _RelaySubscription(id, (int id) {
      _streams.remove(id);
      if (!_closed) {
        _socket.send(
          jsonEncode(<String, Object?>{'type': 'close', 'id': id}).toJS,
        );
      }
    });
    _streams[id] = stream;
    final Completer<ApiResponse> answer = Completer<ApiResponse>();
    _pending[id] = answer;
    final ApiResponse response = await answer.future.timeout(
      openTimeout,
      onTimeout: () {
        _pending.remove(id);
        throw const TransportFailure(
          ConnectionPath.relay,
          'timeout',
          'the event stream did not open',
        );
      },
    );
    if (!response.ok) {
      _streams.remove(id);
      stream.refused('${response.status} ${response.errorCode ?? ''}'.trim());
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
    for (final _RelaySubscription stream in _streams.values.toList()) {
      await stream.cancel();
    }
    _fail('closed');
  }
}

class _RelaySubscription implements EventSubscription {
  _RelaySubscription(this.id, this._cancel);

  final int id;
  final void Function(int id) _cancel;
  final StreamController<Map<String, Object?>> _controller =
      StreamController<Map<String, Object?>>();
  String? _reason;
  bool _finished = false;

  void add(Map<String, Object?> event) {
    if (event['type'] == 'closing') _reason = event['reason'] as String?;
    _controller.add(event);
  }

  void refused(String reason) {
    _reason = reason;
    finish(null);
  }

  void finish(Object? error) {
    if (_finished) return;
    _finished = true;
    // The relay's own reason (capacity, replaced, ...) explains the close.
    if (error is TransportFailure) _reason ??= error.code;
    if (error != null) _controller.addError(error);
    _controller.close();
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
