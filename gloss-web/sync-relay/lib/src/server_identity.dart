import 'dart:convert';
import 'dart:math';

import 'package:gloss_sync_relay/src/relay_frame.dart';
import 'package:crypto/crypto.dart';
import 'package:cryptography/cryptography.dart';

/// `sha256:` and the hex SHA-256 of a raw 32-byte Ed25519 public key, the id a
/// server is registered and routed under. The same value is `fingerprint` in
/// pairing codes and `GET /v1/hello`.
String fingerprintOf(List<int> rawPublicKey) =>
    'sha256:${sha256.convert(rawPublicKey)}';

/// Decodes base64url without padding, or returns null.
List<int>? decodeBase64Url(String text) {
  try {
    return base64Url.decode(base64Url.normalize(text));
  } on FormatException {
    return null;
  }
}

String encodeBase64Url(List<int> bytes) =>
    base64Url.encode(bytes).replaceAll('=', '');

/// Issues challenge nonces and checks the signatures servers answer with.
class ChallengeVerifier {
  final Random _random;

  ChallengeVerifier({Random? random}) : _random = random ?? Random.secure();

  /// 32 random bytes in base64url without padding.
  String issueNonce() => encodeBase64Url(
    List<int>.generate(32, (int index) => _random.nextInt(256)),
  );

  /// True when [signature] is the Ed25519 signature by [serverKey] of
  /// [challengePrefix] followed by [nonce].
  Future<bool> verify({
    required String nonce,
    required String serverKey,
    required String signature,
  }) async {
    final List<int>? key = decodeBase64Url(serverKey);
    final List<int>? signatureBytes = decodeBase64Url(signature);
    if (key == null || key.length != 32) return false;
    if (signatureBytes == null || signatureBytes.length != 64) return false;
    try {
      return await Ed25519().verify(
        utf8.encode('$challengePrefix$nonce'),
        signature: Signature(
          signatureBytes,
          publicKey: SimplePublicKey(key, type: KeyPairType.ed25519),
        ),
      );
    } on Object {
      return false;
    }
  }
}
