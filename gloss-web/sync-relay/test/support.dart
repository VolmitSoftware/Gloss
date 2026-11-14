import 'dart:convert';
import 'dart:io';

import 'package:gloss_sync_relay/gloss_sync_relay.dart';
import 'package:cryptography/cryptography.dart';

/// The repository's shared editor fixtures, `schema/editor/fixtures`.
final Directory fixtureDirectory = Directory('../../schema/editor/fixtures');

Object? readFixture(String relative) =>
    jsonDecode(File('${fixtureDirectory.path}/$relative').readAsStringSync());

String fixtureText(String relative) =>
    File('${fixtureDirectory.path}/$relative').readAsStringSync();

/// Records what the broker sends to one connection.
final class FakeSink implements RelaySink {
  final List<Map<String, Object?>> frames = <Map<String, Object?>>[];
  String? closedCode;

  @override
  void send(String frame) {
    if (closedCode != null) return;
    frames.add(jsonDecode(frame) as Map<String, Object?>);
  }

  @override
  void closeWithError(String code, String message) {
    closedCode ??= code;
  }

  Map<String, Object?> get last => frames.last;

  List<Map<String, Object?>> ofType(String type) => frames
      .where((Map<String, Object?> frame) => frame['type'] == type)
      .toList();

  Map<String, Object?> take() => frames.removeAt(0);
}

/// An Ed25519 server identity for tests.
final class TestServer {
  final SimpleKeyPair keyPair;
  final List<int> publicKey;

  TestServer._(this.keyPair, this.publicKey);

  static Future<TestServer> create([List<int>? seed]) async {
    final Ed25519 algorithm = Ed25519();
    final SimpleKeyPair pair = seed == null
        ? await algorithm.newKeyPair()
        : await algorithm.newKeyPairFromSeed(seed);
    final SimplePublicKey key = await pair.extractPublicKey();
    return TestServer._(pair, key.bytes);
  }

  String get serverKey => encodeBase64Url(publicKey);
  String get fingerprint => fingerprintOf(publicKey);

  Future<String> sign(String nonce) async {
    final Signature signature = await Ed25519().sign(
      utf8.encode('$challengePrefix$nonce'),
      keyPair: keyPair,
    );
    return encodeBase64Url(signature.bytes);
  }

  Future<String> register(String nonce) async => jsonEncode(<String, Object?>{
    'type': 'register',
    'protocol': relayProtocol,
    'serverKey': serverKey,
    'signature': await sign(nonce),
  });
}

String encode(Map<String, Object?> frame) => jsonEncode(frame);

Map<String, Object?> request(
  int id, {
  String method = 'GET',
  String path = '/v1/catalog',
  Map<String, String>? headers,
}) => <String, Object?>{
  'type': 'request',
  'id': id,
  'method': method,
  'path': path,
  'headers': headers ?? <String, String>{'Authorization': 'Bearer gls_x'},
};
