/// The two ways a browser reaches a Gloss server (sync-relay/README.md,
/// editor API sections 8 and 9) and the request, answer and event-stream
/// types both share. Pure Dart:
/// the browser implementations are in `browser_transports.dart`, the test
/// ones in `test/support`.
library;

import 'dart:async';

import 'error_copy.dart';

/// How the browser reaches a server: the plugin's own listener, or the relay
/// the plugin connects out to.
enum ConnectionPath { direct, relay }

/// The largest relay frame in UTF-8 bytes: the relay's `MAX_FRAME_BYTES` and
/// the plugin's `relayFrameBytes` default, which the browser is not told.
const int relayFrameBytes = 1024 * 1024;

/// What a request body may take of a relay frame: the rest is the envelope
/// and the headers the relay adds.
const int relayBodyBytes = relayFrameBytes - 16 * 1024;

/// The UTF-8 length of [text], without encoding it.
int utf8Length(String text) {
  int bytes = 0;
  for (int index = 0; index < text.length; index++) {
    final int unit = text.codeUnitAt(index);
    if (unit < 0x80) {
      bytes += 1;
    } else if (unit < 0x800) {
      bytes += 2;
    } else if (unit >= 0xD800 &&
        unit <= 0xDBFF &&
        index + 1 < text.length &&
        (text.codeUnitAt(index + 1) & 0xFC00) == 0xDC00) {
      bytes += 4;
      index++;
    } else {
      bytes += 3;
    }
  }
  return bytes;
}

/// The relay's `413 too-large` answer for a request [frame] it would refuse,
/// given without sending it: the relay closes a socket that sends a frame
/// over its limit. Null when the frame fits.
ApiResponse? relayRefusal(String frame) {
  if (frame.length <= relayBodyBytes ~/ 3 ||
      utf8Length(frame) <= relayBodyBytes) {
    return null;
  }
  return const ApiResponse(
    413,
    <String, Object?>{
      'error': <String, Object?>{
        'code': 'too-large',
        'message': 'The request does not fit one relay frame.',
      },
    },
    path: ConnectionPath.relay,
  );
}

class ApiRequest {
  const ApiRequest(
    this.method,
    this.path, {
    this.headers = const <String, String>{},
    this.body,
    this.counted = false,
  });

  /// GET, POST or DELETE.
  final String method;

  /// `/v1/...`, with the query.
  final String path;
  final Map<String, String> headers;

  /// A JSON value, or null for no body.
  final Object? body;

  /// Carries `X-Gloss-Counter` (section 12.1): only the publish.
  final bool counted;

  ApiRequest withHeaders(Map<String, String> extra) => ApiRequest(
    method,
    path,
    headers: <String, String>{...headers, ...extra},
    body: body,
    counted: counted,
  );
}

class ApiResponse {
  const ApiResponse(
    this.status,
    this.body, {
    this.headers = const <String, String>{},
    this.path = ConnectionPath.direct,
  });

  final int status;

  /// The decoded JSON body, or null.
  final Object? body;
  final Map<String, String> headers;

  /// The path the answer came over.
  final ConnectionPath path;

  bool get ok => status >= 200 && status < 300;

  /// The `error` object, when the body is an error.
  Map<String, Object?>? get error {
    final Object? value = body;
    if (value is Map<String, Object?> &&
        value['error'] is Map<String, Object?>) {
      return value['error']! as Map<String, Object?>;
    }
    return null;
  }

  /// The error code, when the body is an error.
  String? get errorCode {
    final Object? code = error?['code'];
    return code is String ? code : null;
  }

  /// What went wrong and what to do next, from the error code (or the
  /// status when the body has none).
  ErrorCopy problem({String? origin}) => serverCopy(
    errorCode,
    status: status,
    origin: origin,
    error: error,
    relay: path == ConnectionPath.relay,
  );

  /// How long the server asked to wait before retrying a `rate-limited` or
  /// `busy` answer: `retryAfterMillis`, else `Retry-After`; null otherwise.
  Duration? get retryAfter {
    if (status != 429 && status != 503) return null;
    final Object? millis = error?['retryAfterMillis'];
    if (millis is num && millis >= 0) {
      return Duration(milliseconds: millis.toInt());
    }
    final int? seconds = int.tryParse(
      headers['Retry-After'] ?? headers['retry-after'] ?? '',
    );
    return seconds == null ? null : Duration(seconds: seconds);
  }
}

/// The path could not carry the request: a network failure, a closed relay
/// socket, a server the relay reports offline, or a timeout. Distinct from an
/// HTTP error answer, which arrives as an [ApiResponse].
class TransportFailure implements Exception {
  const TransportFailure(this.path, this.code, [this.detail, this.origin]);

  final ConnectionPath path;

  /// A transport or relay error code ([transportCopy]).
  final String code;

  /// What the browser reported, for the console only.
  final String? detail;

  /// This page's origin, on an `origin-not-allowed` failure: the server
  /// refused it ([directFailureCode]).
  final String? origin;

  ErrorCopy get copy => transportCopy(code, origin: origin);

  /// The plain-words cause and next action.
  String get message => copy.text;

  @override
  String toString() =>
      '${path.name}: $code${detail == null ? '' : ' ($detail)'}';
}

/// The failure code of a direct request the browser could not complete,
/// from `GET /v1/hello` sent after it: [hello] is its answer in `cors` mode
/// (null when that failed too), [answersWithoutCors] whether it got any
/// answer in `no-cors` mode. A server that still answers this page lost the
/// connection; only one that says so, or answers `no-cors` alone, refused
/// the origin.
String directFailureCode(
  ApiResponse? hello, {
  required bool answersWithoutCors,
}) {
  if (hello != null) {
    return hello.errorCode == 'origin-not-allowed'
        ? 'origin-not-allowed'
        : 'dropped';
  }
  return answersWithoutCors ? 'origin-not-allowed' : 'unreachable';
}

/// The server refused to open the event stream.
class StreamRefused implements Exception {
  const StreamRefused(this.status, this.code, {this.retryAfter});

  final int status;
  final String code;

  /// The server's wait before trying again, on a `rate-limited` or `busy`
  /// refusal.
  final Duration? retryAfter;

  @override
  String toString() => 'event stream refused: $status $code';
}

/// One open `GET /v1/events` stream.
abstract class EventSubscription {
  /// Event frames. Done when the stream closes; an error closes it because
  /// the path failed.
  Stream<Map<String, Object?>> get events;

  /// Why the server closed the stream, when it said (`4000 + status` and the
  /// error code on the direct path, the `closing` event otherwise).
  String? get closeReason;

  Future<void> cancel();
}

/// A path that knows whether the server is connected behind it: the relay.
/// While the server is away the path stays open, so the editor hears the
/// moment it is back instead of polling.
abstract class RelayLink {
  /// The server is connected to the relay now.
  bool get serverOnline;

  /// The socket to the relay is gone.
  bool get isClosed;

  /// Completes when the server is back on the relay, or the socket closes.
  Future<void> get serverBack;
}

/// One way to a server. Implementations: the browser's fetch and WebSocket
/// (direct), a relay socket (relay), and in-memory fakes in tests.
abstract class ServerTransport {
  ConnectionPath get path;

  /// Sends one request. Throws [TransportFailure] when the path fails.
  Future<ApiResponse> send(ApiRequest request);

  /// Opens the event stream with the bearer [token].
  Future<EventSubscription> subscribe(String token);

  /// Closes the path and fails everything still open on it.
  Future<void> close();
}

typedef TransportFactory = Future<ServerTransport> Function(
  ConnectionPath path,
  String url,
);
