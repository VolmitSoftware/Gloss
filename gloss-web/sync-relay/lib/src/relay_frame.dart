import 'dart:convert';

/// Relay protocol version, `protocol` in every `challenge`, `register` and
/// `subscribe` frame.
const int relayProtocol = 1;

/// The text a server signs to answer a challenge: this prefix followed by the
/// challenge nonce exactly as sent.
const String challengePrefix = 'gloss-relay/1 ';

final RegExp fingerprintPattern = RegExp(r'^sha256:[0-9a-f]{64}$');
final RegExp _key32Pattern = RegExp(r'^[A-Za-z0-9_-]{43}$');
final RegExp _signaturePattern = RegExp(r'^[A-Za-z0-9_-]{86}$');
final RegExp _headerNamePattern = RegExp(
  r"^[A-Za-z0-9!#$%&'*+.^_`|~-]{1,128}$",
);
final RegExp _pathPattern = RegExp(r'^/v1/[\x21-\x7e]*$');
const int maxFrameId = 9007199254740991;
const int maxHeaders = 64;
const int maxHeaderValueCharacters = 8192;
const int maxPathCharacters = 2048;

/// Codes of `relay-error` frames, sent before the relay closes a connection.
abstract final class RelayErrorCode {
  static const String invalidFrame = 'invalid-frame';
  static const String unsupportedProtocol = 'unsupported-protocol';
  static const String tooLarge = 'too-large';
  static const String rateLimited = 'rate-limited';
  static const String handshakeFailed = 'handshake-failed';
  static const String timeout = 'timeout';
  static const String replaced = 'replaced';
  static const String capacity = 'capacity';
  static const String queueFull = 'queue-full';
}

/// A frame the relay refuses, with the `relay-error` code to report.
final class FrameException implements Exception {
  final String code;
  final String message;

  const FrameException(this.code, this.message);

  @override
  String toString() => 'FrameException($code): $message';
}

/// Frames a browser sends on `/app`.
sealed class AppFrame {}

/// Frames a server sends on `/agent`.
sealed class AgentFrame {}

final class SubscribeFrame implements AppFrame {
  final String fingerprint;

  const SubscribeFrame(this.fingerprint);
}

final class RegisterFrame implements AgentFrame {
  final String serverKey;
  final String signature;

  const RegisterFrame(this.serverKey, this.signature);
}

/// `relay-request`: one API call. [id] is the sender's id; the relay rewrites
/// it per server.
final class RequestFrame implements AppFrame {
  final int id;
  final String method;
  final String path;
  final Map<String, String> headers;
  final bool hasBody;
  final Object? body;

  const RequestFrame({
    required this.id,
    required this.method,
    required this.path,
    required this.headers,
    required this.hasBody,
    this.body,
  });

  /// True for `GET /v1/events`, which stays open after a 2xx answer.
  bool get opensStream =>
      method == 'GET' &&
      (path == '/v1/events' || path.startsWith('/v1/events?'));

  RequestFrame withRoute(int newId, Map<String, String> newHeaders) =>
      RequestFrame(
        id: newId,
        method: method,
        path: path,
        headers: newHeaders,
        hasBody: hasBody,
        body: body,
      );

  Map<String, Object?> toJson() => <String, Object?>{
    'type': 'request',
    'id': id,
    'method': method,
    'path': path,
    'headers': headers,
    if (hasBody) 'body': body,
  };
}

/// `relay-response`: the answer to one request.
final class ResponseFrame implements AgentFrame {
  final int id;
  final int status;
  final Map<String, String>? headers;
  final bool hasBody;
  final Object? body;

  const ResponseFrame({
    required this.id,
    required this.status,
    this.headers,
    required this.hasBody,
    this.body,
  });

  ResponseFrame withId(int newId) => ResponseFrame(
    id: newId,
    status: status,
    headers: headers,
    hasBody: hasBody,
    body: body,
  );

  Map<String, Object?> toJson() => <String, Object?>{
    'type': 'response',
    'id': id,
    'status': status,
    if (headers != null) 'headers': headers,
    if (hasBody) 'body': body,
  };
}

/// `relay-event`: one event on the stream opened by request [id].
final class EventFrame implements AgentFrame {
  final int id;
  final Map<String, Object?> event;

  const EventFrame(this.id, this.event);

  Map<String, Object?> toJson() => <String, Object?>{
    'type': 'event',
    'id': id,
    'event': event,
  };
}

/// `relay-close`: ends the request or event stream [id]. Either side sends it.
final class CloseFrame implements AppFrame, AgentFrame {
  final int id;

  const CloseFrame(this.id);

  Map<String, Object?> toJson() => <String, Object?>{'type': 'close', 'id': id};
}

/// Decodes and checks relay frames (README.md, golden frames in
/// `schema/editor/fixtures/relay`). Unknown fields are refused.
abstract final class RelayFrames {
  static Map<String, Object?> _object(String text, int maximumBytes) {
    if (text.length > maximumBytes || utf8.encode(text).length > maximumBytes) {
      throw const FrameException(RelayErrorCode.tooLarge, 'frame too large');
    }
    final Object? raw;
    try {
      raw = jsonDecode(text);
    } on FormatException {
      throw const FrameException(RelayErrorCode.invalidFrame, 'not JSON');
    }
    if (raw is! Map<String, Object?>) {
      throw const FrameException(
        RelayErrorCode.invalidFrame,
        'frame is not an object',
      );
    }
    return raw;
  }

  static AppFrame decodeApp(String text, int maximumBytes) {
    final Map<String, Object?> json = _object(text, maximumBytes);
    switch (json['type']) {
      case 'subscribe':
        _fields(json, const <String>{'type', 'protocol', 'fingerprint'});
        _protocol(json);
        final Object? fingerprint = json['fingerprint'];
        if (fingerprint is! String ||
            !fingerprintPattern.hasMatch(fingerprint)) {
          throw _invalid('fingerprint must be sha256:<64 hex>');
        }
        return SubscribeFrame(fingerprint);
      case 'request':
        return request(json);
      case 'close':
        return _close(json);
      default:
        throw _invalid('unsupported app frame type');
    }
  }

  static AgentFrame decodeAgent(String text, int maximumBytes) {
    final Map<String, Object?> json = _object(text, maximumBytes);
    switch (json['type']) {
      case 'register':
        _fields(json, const <String>{
          'type',
          'protocol',
          'serverKey',
          'signature',
        });
        _protocol(json);
        final Object? key = json['serverKey'];
        final Object? signature = json['signature'];
        if (key is! String || !_key32Pattern.hasMatch(key)) {
          throw _invalid('serverKey must be a 32-byte key in base64url');
        }
        if (signature is! String || !_signaturePattern.hasMatch(signature)) {
          throw _invalid('signature must be 64 bytes in base64url');
        }
        return RegisterFrame(key, signature);
      case 'response':
        return response(json);
      case 'event':
        _fields(json, const <String>{'type', 'id', 'event'});
        final Object? event = json['event'];
        if (event is! Map<String, Object?> || event['type'] is! String) {
          throw _invalid('event must be an object with a type');
        }
        return EventFrame(_id(json), event);
      case 'close':
        return _close(json);
      default:
        throw _invalid('unsupported agent frame type');
    }
  }

  static RequestFrame request(Map<String, Object?> json) {
    _fields(json, const <String>{
      'type',
      'id',
      'method',
      'path',
      'headers',
      'body',
    });
    final int id = _id(json);
    final Object? method = json['method'];
    if (method is! String ||
        !const <String>{'GET', 'POST', 'DELETE'}.contains(method)) {
      throw _invalid('method must be GET, POST or DELETE');
    }
    final Object? path = json['path'];
    if (path is! String ||
        path.length > maxPathCharacters ||
        !_pathPattern.hasMatch(path) ||
        path.contains('\\') ||
        path.split('?').first.split('/').contains('..')) {
      throw _invalid('path must be a /v1/ path');
    }
    final bool hasBody = json.containsKey('body');
    if (hasBody && method == 'GET') {
      throw _invalid('GET requests carry no body');
    }
    return RequestFrame(
      id: id,
      method: method,
      path: path,
      headers: _headers(json['headers']) ?? <String, String>{},
      hasBody: hasBody,
      body: json['body'],
    );
  }

  static ResponseFrame response(Map<String, Object?> json) {
    _fields(json, const <String>{'type', 'id', 'status', 'headers', 'body'});
    final Object? status = json['status'];
    if (status is! int || status < 100 || status > 599) {
      throw _invalid('status must be an HTTP status');
    }
    return ResponseFrame(
      id: _id(json),
      status: status,
      headers: _headers(json['headers']),
      hasBody: json.containsKey('body'),
      body: json['body'],
    );
  }

  static CloseFrame _close(Map<String, Object?> json) {
    _fields(json, const <String>{'type', 'id'});
    return CloseFrame(_id(json));
  }

  static void _fields(Map<String, Object?> json, Set<String> allowed) {
    for (final String key in json.keys) {
      if (!allowed.contains(key)) throw _invalid('unknown field $key');
    }
  }

  static void _protocol(Map<String, Object?> json) {
    if (json['protocol'] != relayProtocol) {
      throw const FrameException(
        RelayErrorCode.unsupportedProtocol,
        'protocol must be $relayProtocol',
      );
    }
  }

  static int _id(Map<String, Object?> json) {
    final Object? id = json['id'];
    if (id is! int || id < 1 || id > maxFrameId) {
      throw _invalid('id must be a positive integer');
    }
    return id;
  }

  static Map<String, String>? _headers(Object? raw) {
    if (raw == null) return null;
    if (raw is! Map<String, Object?> || raw.length > maxHeaders) {
      throw _invalid(
        'headers must be an object of at most $maxHeaders strings',
      );
    }
    final Map<String, String> headers = <String, String>{};
    for (final MapEntry<String, Object?> entry in raw.entries) {
      final Object? value = entry.value;
      if (!_headerNamePattern.hasMatch(entry.key) ||
          value is! String ||
          value.length > maxHeaderValueCharacters ||
          value.contains('\r') ||
          value.contains('\n')) {
        throw _invalid('header ${entry.key} is invalid');
      }
      headers[entry.key] = value;
    }
    return headers;
  }

  static FrameException _invalid(String message) =>
      FrameException(RelayErrorCode.invalidFrame, message);

  static Map<String, Object?> challenge(String nonce) => <String, Object?>{
    'type': 'challenge',
    'protocol': relayProtocol,
    'nonce': nonce,
  };

  static Map<String, Object?> registered(String fingerprint) =>
      <String, Object?>{'type': 'registered', 'fingerprint': fingerprint};

  static Map<String, Object?> subscribed(String fingerprint, bool online) =>
      <String, Object?>{
        'type': 'subscribed',
        'fingerprint': fingerprint,
        'online': online,
      };

  static Map<String, Object?> status(bool online) => <String, Object?>{
    'type': 'status',
    'online': online,
  };

  static Map<String, Object?> error(String code, String message) =>
      <String, Object?>{'type': 'error', 'code': code, 'message': message};

  /// A `relay-response` the relay answers itself, with an `error-response`
  /// body using the API's error codes.
  static ResponseFrame failure(
    int id,
    int status,
    String code,
    String message,
  ) => ResponseFrame(
    id: id,
    status: status,
    hasBody: true,
    body: <String, Object?>{
      'error': <String, Object?>{'code': code, 'message': message},
    },
  );
}
