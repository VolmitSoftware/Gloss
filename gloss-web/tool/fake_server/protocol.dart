import 'dart:convert';
import 'dart:math';
import 'dart:typed_data';

import 'package:crypto/crypto.dart';

/// Which path a request arrived on. Only direct requests are origin-checked;
/// the relay checks the browser's origin on its own handshake.
enum Transport { direct, relay }

/// Contract section 14 limits the fake enforces.
const int relayFrameBytes = 1048576;

/// Room left for an answer inside one relay frame.
const int relayRoom = relayFrameBytes - 16 * 1024;
const int openBodyBytes = 4 * 1024;
const int requestBodyBytes = 4 * 1024 * 1024;
const int catalogPageBytes = 512 * 1024;
const int batchAnswerBytes = 1024 * 1024;
const int defaultPageBytes = 1024 * 1024;
const int maxPageBytes = 4 * 1024 * 1024;
const int batchRefs = 200;
const int imageHashesPerRead = 64;
const int imageBytes = 512 * 1024;
const int imageSide = 4096;
const int imagePixels = 16777216;
const int stagedBytes = 64 * 1024 * 1024;
const int publishDocuments = 2000;
const int publishImages = 4096;

/// A publish deleting more than this many documents and images, and more than
/// [confirmDeletesPercent] percent of what the token reaches, needs
/// `confirmDeletes`.
const int confirmDeletesAbove = 25;
const int confirmDeletesPercent = 20;
const int documentBytes = 2 * 1024 * 1024;
const int concurrentRequestsPerToken = 8;
const int maxTokens = 256;
const int maxWaitingLinks = 64;

/// HTTP status of every error code in contract section 13.
const Map<String, int> errorStatuses = <String, int>{
  'pairing-invalid': 401,
  'pairing-expired': 410,
  'origin-not-allowed': 403,
  'server-offline': 503,
  'unauthorized': 401,
  'token-expired': 401,
  'token-revoked': 401,
  'scope-missing': 403,
  'folder-denied': 403,
  'counter-replayed': 409,
  'confirm-deletes': 428,
  'too-large': 413,
  'rate-limited': 429,
  'busy': 503,
  'timeout': 504,
  'invalid-request': 400,
  'unsupported-protocol': 400,
  'not-found': 404,
  'internal': 500,
};

/// A transport-neutral `/v1` request.
class FakeRequest {
  FakeRequest({
    required this.transport,
    required this.method,
    required this.target,
    required Map<String, String> headers,
    this.body,
    this.address = '127.0.0.1',
  }) : headers = <String, String>{
          for (final MapEntry<String, String> entry in headers.entries) entry.key.toLowerCase(): entry.value,
        };

  final Transport transport;
  final String method;

  /// `/v1/...` with the query string, still percent-encoded.
  final String target;
  final Map<String, String> headers;

  /// The decoded JSON body, or null when there is none.
  final Object? body;
  final String address;

  String get route {
    final int question = target.indexOf('?');
    return question < 0 ? target : target.substring(0, question);
  }

  String? header(String name) => headers[name.toLowerCase()];

  /// The decoded query string.
  Map<String, String> get query {
    final int question = target.indexOf('?');
    if (question < 0) return <String, String>{};
    try {
      return Uri.splitQueryString(target.substring(question + 1));
    } on Object {
      throw invalid('The query string is not URL encoded.');
    }
  }
}

/// A response: a status, extra headers and a JSON body (null for none).
class FakeResponse {
  const FakeResponse(this.status, {this.body, this.headers = const <String, String>{}});

  final int status;
  final Object? body;
  final Map<String, String> headers;

  FakeResponse withHeaders(Map<String, String> extra) =>
      FakeResponse(status, body: body, headers: <String, String>{...headers, ...extra});
}

/// A refused request: `{"error":{"code","message",...extra}}` with the status
/// [errorStatuses] gives the code.
class ApiError implements Exception {
  ApiError(this.code, this.message, [this.extra = const <String, Object?>{}]);

  final String code;
  final String message;
  final Map<String, Object?> extra;

  int get status => errorStatuses[code] ?? 500;

  Map<String, Object?> get body => <String, Object?>{
        'error': <String, Object?>{'code': code, 'message': message, ...extra},
      };

  FakeResponse toResponse() {
    final Object? retry = extra['retryAfterMillis'];
    return FakeResponse(
      status,
      body: body,
      headers: retry is int ? <String, String>{'Retry-After': '${max(1, (retry + 999) ~/ 1000)}'} : const <String, String>{},
    );
  }

  @override
  String toString() => 'ApiError($code: $message)';
}

ApiError invalid(String message, [Map<String, Object?> extra = const <String, Object?>{}]) =>
    ApiError('invalid-request', message, extra);

final RegExp tokenIdPattern = RegExp(r'^tok_[A-Za-z0-9_-]{8,64}$');
final RegExp secretPattern = RegExp(r'^[A-Za-z0-9_-]{43}$');
final RegExp hashPattern = RegExp(r'^sha256:[0-9a-f]{64}$');
final RegExp idempotencyPattern = RegExp(r'^[A-Za-z0-9_-]{16,64}$');

final Random _random = Random.secure();

/// [bytes] random bytes as base64url without padding.
String randomText(int bytes) {
  final Uint8List value = Uint8List(bytes);
  for (int index = 0; index < bytes; index++) {
    value[index] = _random.nextInt(256);
  }
  return base64UrlNoPadding(value);
}

String base64UrlNoPadding(List<int> bytes) => base64Url.encode(bytes).replaceAll('=', '');

String sha256Text(String text) => sha256.convert(utf8.encode(text)).toString();

/// Constant-time comparison of two ASCII strings.
bool sameText(String a, String b) {
  if (a.length != b.length) return false;
  int difference = 0;
  for (int index = 0; index < a.length; index++) {
    difference |= a.codeUnitAt(index) ^ b.codeUnitAt(index);
  }
  return difference == 0;
}

/// ISO-8601 UTC with whole seconds, as every time on the wire.
String instant(DateTime time) {
  final DateTime utc = time.toUtc();
  String two(int value) => value.toString().padLeft(2, '0');
  return '${utc.year.toString().padLeft(4, '0')}-${two(utc.month)}-${two(utc.day)}T${two(utc.hour)}:${two(utc.minute)}:${two(utc.second)}Z';
}

DateTime now() => DateTime.now().toUtc();

int jsonBytes(Object? value) => utf8.encode(jsonEncode(value)).length;

/// One percent-encoded path segment, decoded.
String decodeSegment(String segment) {
  try {
    return Uri.decodeComponent(segment);
  } on ArgumentError {
    throw invalid('The path is not URL encoded.');
  } on FormatException {
    throw invalid('The path is not URL encoded.');
  }
}

/// The request body as one JSON object.
Map<String, Object?> bodyObject(FakeRequest request) {
  final Object? body = request.body;
  if (body is! Map<String, Object?>) throw invalid('The body must be one JSON object.');
  return body;
}

/// Refuses fields outside [allowed].
void onlyFields(Map<String, Object?> object, String at, Set<String> allowed) {
  for (final String key in object.keys) {
    if (!allowed.contains(key)) throw invalid('$at.$key: unknown field');
  }
}

String requireString(Map<String, Object?> object, String key, String at, {RegExp? pattern, int maxLength = 512}) {
  final Object? value = object[key];
  if (value is! String || value.isEmpty || value.length > maxLength || (pattern != null && !pattern.hasMatch(value))) {
    throw invalid('$at.$key: expected ${pattern == null ? 'a string' : 'a string matching ${pattern.pattern}'}');
  }
  return value;
}
