import 'dart:convert';
import 'dart:math';
import 'dart:typed_data';

import 'package:crypto/crypto.dart';
import 'package:cryptography/cryptography.dart';

/// What the server signs ahead of the challenge. It differs from the relay
/// registration prefix `gloss-relay/1 `, so neither signature passes as the
/// other.
const String editorProofPrefix = 'gloss-editor-proof/1 ';

const String challengeHeader = 'X-Gloss-Challenge';
const String serverKeyHeader = 'X-Gloss-Server-Key';
const String proofHeader = 'X-Gloss-Proof';

/// 32 random bytes in base64url without padding (43 characters).
String newChallenge([Random? random]) {
  final Random source = random ?? Random.secure();
  return base64UrlNoPadding(
    List<int>.generate(32, (int index) => source.nextInt(256)),
  );
}

String base64UrlNoPadding(List<int> bytes) =>
    base64Url.encode(bytes).replaceAll('=', '');

/// The value of header [name] in [headers], ignoring case.
String? headerValue(Map<String, String> headers, String name) {
  final String wanted = name.toLowerCase();
  for (final MapEntry<String, String> entry in headers.entries) {
    if (entry.key.toLowerCase() == wanted) return entry.value;
  }
  return null;
}

/// Whether the hello [headers] prove the answer came from the server pinned
/// by [fingerprint]: a 32-byte Ed25519 key hashing to it, and that key's
/// signature over [editorProofPrefix] and [challenge].
Future<bool> verifyServerProof(
  Map<String, String> headers, {
  required String challenge,
  required String fingerprint,
}) async {
  final Uint8List? key = _decode(headerValue(headers, serverKeyHeader));
  final Uint8List? proof = _decode(headerValue(headers, proofHeader));
  if (key == null || key.length != 32 || proof == null || proof.length != 64) {
    return false;
  }
  if ('sha256:${sha256.convert(key)}' != fingerprint) return false;
  try {
    return await Ed25519().verify(
      utf8.encode('$editorProofPrefix$challenge'),
      signature: Signature(
        proof,
        publicKey: SimplePublicKey(key, type: KeyPairType.ed25519),
      ),
    );
  } on Object {
    return false;
  }
}

Uint8List? _decode(String? text) {
  if (text == null || !RegExp(r'^[A-Za-z0-9_-]+$').hasMatch(text)) return null;
  try {
    return base64Url.decode(base64Url.normalize(text));
  } on FormatException {
    return null;
  }
}
