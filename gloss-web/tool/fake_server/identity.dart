import 'dart:convert';

import 'package:cryptography/cryptography.dart';

import 'protocol.dart';

/// Prefix of the text the server signs to answer `X-Gloss-Challenge`.
const String editorProofPrefix = 'gloss-editor-proof/1 ';
const String challengeHeader = 'X-Gloss-Challenge';
const String serverKeyHeader = 'X-Gloss-Server-Key';
const String proofHeader = 'X-Gloss-Proof';

/// Pairing codes start with this.
const String pairingPrefix = 'GLS1.';

/// Seed of the fake server's Ed25519 key, made up for tests: bytes 0x47 to 0x66.
final List<int> fakeSeed = List<int>.unmodifiable(List<int>.generate(32, (int index) => 0x47 + index));

/// The public key of [fakeSeed]: raw 32 bytes, base64url without padding.
const String fakeServerKey = 'ijkAhPI4dnZQ7hE14ZdZ9slZil4ooS8fvRUtBKX_KTM';

/// `sha256:` and the hex SHA-256 of [fakeServerKey]'s raw bytes.
const String fakeFingerprint = 'sha256:bcdfdff9a185496c992758fc5aab2de47683e089e2085e3e3fadfc8c27b90088';

Future<SimpleKeyPair>? _keyPair;

/// `X-Gloss-Server-Key` and `X-Gloss-Proof` answering [challenge]: an Ed25519
/// signature over [editorProofPrefix] followed by the challenge.
Future<Map<String, String>> proofHeaders(String challenge) async {
  final Ed25519 ed25519 = Ed25519();
  final SimpleKeyPair pair = await (_keyPair ??= ed25519.newKeyPairFromSeed(fakeSeed));
  final Signature signature = await ed25519.sign(utf8.encode('$editorProofPrefix$challenge'), keyPair: pair);
  return <String, String>{serverKeyHeader: fakeServerKey, proofHeader: base64UrlNoPadding(signature.bytes)};
}

/// A `GLS1.` pairing code: the payload JSON, fields in contract section 2's
/// order, as base64url without padding.
String pairingCode({
  String? directUrl,
  String? relayUrl,
  required String tokenId,
  required String secret,
  required DateTime expiresAt,
  String? label,
  required String serverName,
  required List<String> scopes,
  required List<String> folders,
}) {
  final Map<String, Object?> payload = <String, Object?>{
    'v': 1,
    'directUrl': ?directUrl,
    'relayUrl': ?relayUrl,
    'fingerprint': fakeFingerprint,
    'serverKey': fakeServerKey,
    'tokenId': tokenId,
    'secret': secret,
    'expiresAt': instant(expiresAt),
    'label': ?label,
    'serverName': serverName,
    'scopes': scopes,
    if (folders.isNotEmpty) 'folders': folders,
  };
  return '$pairingPrefix${base64UrlNoPadding(utf8.encode(jsonEncode(payload)))}';
}

/// The payload of a pairing code or link, for tests.
Map<String, Object?> decodePairingCode(String codeOrLink) {
  final String code = codeOrLink.substring(codeOrLink.indexOf(pairingPrefix) + pairingPrefix.length);
  return jsonDecode(utf8.decode(base64Url.decode(base64Url.normalize(code)))) as Map<String, Object?>;
}
