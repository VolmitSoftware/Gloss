import 'package:gloss_sync_relay/gloss_sync_relay.dart';
import 'package:test/test.dart';

import 'support.dart';

void main() {
  final Map<String, Object?> handshake =
      readFixture('relay-handshake.json')! as Map<String, Object?>;

  test('should derive the fingerprint as the SHA-256 of the raw key', () {
    final List<int> key = List<int>.generate(32, (int index) => index);
    expect(
      fingerprintOf(key),
      'sha256:630dcd2966c4336691125448bbb25b4ff412a49c732db2c8abc1b8581bd710dd',
    );
  });

  test('should reproduce the handshake golden from its seed', () async {
    final TestServer server = await TestServer.create(
      (handshake['seedBytes']! as List<Object?>).cast<int>(),
    );
    expect(server.serverKey, handshake['serverKey']);
    expect(server.fingerprint, handshake['fingerprint']);
    expect(
      await server.sign(handshake['nonce']! as String),
      handshake['signature'],
    );
  });

  test(
    'should accept the register fixture for the challenge fixture',
    () async {
      final Map<String, Object?> challenge =
          readFixture('relay/relay-challenge.0.json')! as Map<String, Object?>;
      final Map<String, Object?> register =
          readFixture('relay/relay-register.0.json')! as Map<String, Object?>;
      expect(
        await ChallengeVerifier().verify(
          nonce: challenge['nonce']! as String,
          serverKey: register['serverKey']! as String,
          signature: register['signature']! as String,
        ),
        isTrue,
      );
    },
  );

  test('should accept the golden signature and refuse altered ones', () async {
    final ChallengeVerifier verifier = ChallengeVerifier();
    final String nonce = handshake['nonce']! as String;
    final String key = handshake['serverKey']! as String;
    final String signature = handshake['signature']! as String;
    expect(
      await verifier.verify(nonce: nonce, serverKey: key, signature: signature),
      isTrue,
    );
    final String other = signature.startsWith('A')
        ? 'B${signature.substring(1)}'
        : 'A${signature.substring(1)}';
    expect(
      await verifier.verify(nonce: nonce, serverKey: key, signature: other),
      isFalse,
    );
    expect(
      await verifier.verify(
        nonce: verifier.issueNonce(),
        serverKey: key,
        signature: signature,
      ),
      isFalse,
    );
    expect(
      await verifier.verify(
        nonce: nonce,
        serverKey: 'short',
        signature: signature,
      ),
      isFalse,
    );
  });

  test('should issue distinct 32-byte nonces', () {
    final ChallengeVerifier verifier = ChallengeVerifier();
    final String first = verifier.issueNonce();
    expect(first, hasLength(43));
    expect(decodeBase64Url(first), hasLength(32));
    expect(verifier.issueNonce(), isNot(first));
  });
}
