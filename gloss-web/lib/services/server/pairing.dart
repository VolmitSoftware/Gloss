/// The one-time pairing link: `<editorUrl>/#/pair/GLS1.<base64url JSON>`.
/// Pure Dart.
library;

import 'dart:convert';
import 'dart:typed_data';

import 'package:crypto/crypto.dart';

/// A decoded `GLS1.` pairing code (sync-relay/README.md, editor API section
/// 2). The secret is single use; everything else is pinned into the saved
/// server.
class PairingPayload {
  const PairingPayload({
    required this.fingerprint,
    required this.serverKey,
    required this.tokenId,
    required this.secret,
    required this.expiresAt,
    this.directUrl,
    this.relayUrl,
    this.label,
    this.serverName,
    this.scopes = const <String>[],
    this.folders,
  });

  final String? directUrl;
  final String? relayUrl;

  /// `sha256:` and the hex SHA-256 of [serverKey]'s 32 raw bytes.
  final String fingerprint;
  final String serverKey;
  final String tokenId;
  final String secret;
  final DateTime expiresAt;
  final String? label;
  final String? serverName;

  /// What the token will allow, so the card can say it before pairing.
  final List<String> scopes;

  /// The folders the token reaches; null for every folder.
  final List<String>? folders;

  /// A short stable id for routes and storage: the first 16 hex digits of
  /// the fingerprint.
  String get serverId => serverIdOf(fingerprint);
}

String serverIdOf(String fingerprint) =>
    fingerprint.substring('sha256:'.length, 'sha256:'.length + 16);

class PairingCodeError implements Exception {
  const PairingCodeError(this.message);

  final String message;

  @override
  String toString() => message;
}

const String pairingPrefix = 'GLS1.';

/// The contract's ceiling for a whole code.
const int pairingCodeMaxChars = 4096;

/// Takes a pasted code or link (`.../#/pair/GLS1...`) and returns the code.
String extractPairingCode(String text) {
  final String trimmed = text.trim();
  final int marker = trimmed.indexOf('#/pair/');
  final String code = marker >= 0
      ? trimmed.substring(marker + '#/pair/'.length)
      : trimmed;
  final int end = code.indexOf(RegExp(r'[\s?&]'));
  return end < 0 ? code : code.substring(0, end);
}

final RegExp _base64Url = RegExp(r'^[A-Za-z0-9_-]+$');
final RegExp _fingerprint = RegExp(r'^sha256:[0-9a-f]{64}$');
final RegExp _tokenId = RegExp(r'^tok_[A-Za-z0-9_-]{4,64}$');

String? _url(Object? value, {required String secure, required String plain}) {
  if (value == null) return null;
  if (value is! String) throw const PairingCodeError('address');
  final Uri? uri = Uri.tryParse(value);
  if (uri == null || uri.host.isEmpty) throw const PairingCodeError('address');
  final bool loopback = uri.host == '127.0.0.1' || uri.host == 'localhost';
  if (uri.scheme == secure || (uri.scheme == plain && loopback)) return value;
  throw const PairingCodeError('address');
}

List<String> _strings(Object? value) {
  if (value is! List<Object?>) throw const PairingCodeError('list');
  return <String>[
    for (final Object? item in value)
      if (item is String) item else throw const PairingCodeError('list'),
  ];
}

/// Decodes and checks a pairing code: the prefix, unpadded base64url, the
/// fields, and that the fingerprint is the server key's SHA-256, which pins
/// the server for every later connection. Throws [PairingCodeError].
PairingPayload decodePairingCode(String code) {
  if (!code.startsWith(pairingPrefix) || code.length > pairingCodeMaxChars) {
    throw const PairingCodeError('prefix');
  }
  final String body = code.substring(pairingPrefix.length);
  if (body.isEmpty || !_base64Url.hasMatch(body)) {
    throw const PairingCodeError('base64');
  }
  final Object? json;
  try {
    json = jsonDecode(utf8.decode(base64Url.decode(base64Url.normalize(body))));
  } on FormatException {
    throw const PairingCodeError('json');
  }
  if (json is! Map<String, Object?> || json['v'] != 1) {
    throw const PairingCodeError('version');
  }
  final Object? fingerprint = json['fingerprint'];
  final Object? serverKey = json['serverKey'];
  final Object? tokenId = json['tokenId'];
  final Object? secret = json['secret'];
  if (fingerprint is! String ||
      !_fingerprint.hasMatch(fingerprint) ||
      serverKey is! String ||
      !_base64Url.hasMatch(serverKey) ||
      tokenId is! String ||
      !_tokenId.hasMatch(tokenId) ||
      secret is! String ||
      secret.length != 43 ||
      !_base64Url.hasMatch(secret)) {
    throw const PairingCodeError('fields');
  }
  if (fingerprintOf(serverKey) != fingerprint) {
    throw const PairingCodeError('fingerprint');
  }
  final DateTime? expires = DateTime.tryParse('${json['expiresAt']}');
  if (expires == null) throw const PairingCodeError('expiry');
  final String? directUrl = _url(
    json['directUrl'],
    secure: 'https',
    plain: 'http',
  );
  final String? relayUrl = _url(json['relayUrl'], secure: 'wss', plain: 'ws');
  if (directUrl == null && relayUrl == null) {
    throw const PairingCodeError('no-path');
  }
  final Object? label = json['label'];
  final Object? serverName = json['serverName'];
  return PairingPayload(
    directUrl: directUrl,
    relayUrl: relayUrl,
    fingerprint: fingerprint,
    serverKey: serverKey,
    tokenId: tokenId,
    secret: secret,
    expiresAt: expires.toUtc(),
    label: label is String && label.trim().isNotEmpty ? label.trim() : null,
    serverName: serverName is String && serverName.trim().isNotEmpty
        ? serverName.trim()
        : null,
    scopes: json['scopes'] == null
        ? const <String>[]
        : _strings(json['scopes']),
    folders: json['folders'] == null ? null : _strings(json['folders']),
  );
}

/// `sha256:<hex>` of a base64url raw key.
String fingerprintOf(String serverKey) {
  final Uint8List bytes = base64Url.decode(base64Url.normalize(serverKey));
  return 'sha256:${sha256.convert(bytes)}';
}
