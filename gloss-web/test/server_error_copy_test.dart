import 'package:gloss_editor/services/server/error_copy.dart';
import 'package:gloss_editor/services/server/server_connection.dart';
import 'package:gloss_editor/services/server/transport.dart';
import 'package:test/test.dart';

import '../tool/fake_server/protocol.dart' show errorStatuses;

const List<String> relayCodes = <String>[
  'invalid-frame',
  'too-large',
  'rate-limited',
  'handshake-failed',
  'replaced',
  'capacity',
  'queue-full',
];

const List<String> transportCodes = <String>[
  'unreachable',
  'no-answer',
  'timeout',
  'dropped',
  'closed',
  'stream-closed',
  'server-offline',
  'server-left',
  'restarting',
  'relay-unreachable',
  'wrong-server',
  'no-token',
];

/// One sentence of cause and one of next action, plain punctuation.
void expectSound(String code, ErrorCopy? copy, {bool next = true}) {
  expect(copy, isNotNull, reason: 'no copy for $code');
  expect(copy!.code, code);
  expect(copy.cause, endsWith('.'), reason: code);
  if (next) expect(copy.next, endsWith('.'), reason: code);
  expect(copy.text, isNot(contains('—')), reason: code);
  expect(copy.text, isNot(contains('..')), reason: code);
  expect(copy.text, isNot(contains('  ')), reason: code);
}

void main() {
  test('every server error code of the contract has a cause and a next action', () {
    for (final String code in errorStatuses.keys) {
      expectSound(code, knownServerCopy(code), next: code != 'internal');
    }
    expect(knownServerCopy('internal')!.text, 'The server could not complete this; see its log.');
  });

  test('every status maps back to a code the server answers with that status', () {
    for (final int status in errorStatuses.values.toSet()) {
      expect(errorStatuses[codeForStatus(status)], status, reason: '$status');
    }
  });

  test('every relay error, closing reason and transport failure has copy', () {
    for (final String code in relayCodes) {
      expectSound(code, knownRelayCopy(code));
    }
    for (final String reason in closingReasons) {
      expectSound(reason, knownClosingCopy(reason));
    }
    for (final String code in transportCodes) {
      expectSound(code, knownTransportCopy(code));
    }
  });

  test('the token codes all read as an ended pairing with the command to pair again', () {
    for (final String code in tokenErrorCodes) {
      final ErrorCopy copy = serverCopy(code);
      expect(copy.cause, 'Pairing ended.');
      expect(copy.next, contains(pairCommand));
    }
  });

  test('answers without an error body read by their status, never as a bare number', () {
    expect(serverCopy(null, status: 503), knownServerCopy('busy'));
    expect(serverCopy(null, status: 500), knownServerCopy('internal'));
    expect(serverCopy(null, status: 418).text, isNot(contains('418')));
    final ErrorCopy unknown = serverCopy('no-such-code');
    expect(unknown.code, 'no-such-code');
    expect(unknown.text, isNot(contains('no-such-code')));
    expect(unknown.text, 'The server refused the request. Try again, or check the server console.');
  });

  test('transport codes fall back to relay copy, then to a dropped connection', () {
    expect(transportCopy('capacity'), knownRelayCopy('capacity'));
    expect(transportCopy('whatever'), knownTransportCopy('dropped'));
    expect(relayCopy('whatever').cause, 'The relay refused the connection.');
    expect(closingCopyOf('whatever').cause, 'The server closed the connection.');
  });

  test('copy is compared by value', () {
    const ErrorCopy copy = ErrorCopy('busy', 'The server is busy.', 'Try again in a moment.');
    expect(serverCopy('busy'), copy);
    expect(serverCopy('busy').hashCode, copy.hashCode);
    expect(copy, isNot(const ErrorCopy('rate-limited', 'The server is busy.', 'Try again in a moment.')));
    expect(copy.text, 'The server is busy. Try again in a moment.');
    expect(const ErrorCopy('x', 'Done.', '').text, 'Done.');
  });

  test('copy carries the scope, folders, origin and message the error names', () {
    expect(
      serverCopy('scope-missing', error: <String, Object?>{'scope': 'publish:chat'}).cause,
      'This pairing cannot publish chat.',
    );
    expect(serverCopy('scope-missing').cause, 'This pairing does not allow that.');
    expect(
      serverCopy('folder-denied', error: <String, Object?>{'folders': <Object?>['shop', 'lobby']}).cause,
      'This pairing only reaches folders shop, lobby.',
    );
    expect(
      serverCopy('origin-not-allowed', origin: 'http://localhost:8800').next,
      'Add http://localhost:8800 to allowedOrigins under [editor] in gloss.toml.',
      reason: 'a refused request after pairing: nothing to press',
    );
    expect(
      transportCopy('origin-not-allowed', origin: 'http://localhost:8800'),
      serverCopy('origin-not-allowed', origin: 'http://localhost:8800'),
      reason: 'a refusal without CORS headers reads like one with them',
    );
    expect(
      serverCopy('invalid-request', error: <String, Object?>{'message': 'Unknown kind.'}).cause,
      'The server refused the request: Unknown kind.',
    );
    expect(serverCopy('too-large', relay: true).cause, 'This is too large to send over the relay.');
    expect(serverCopy('too-large').cause, 'This is too large to send.');
  });

  test('a publish whose staged images the server lost says to publish again', () {
    final ErrorCopy copy = serverCopy(
      'invalid-request',
      error: <String, Object?>{
        'message': 'Upload these images with POST /v1/images first.',
        'missing': <Object?>['sha256:0f'],
      },
    );
    expect(copy.cause, 'The server no longer holds the images this publish uploaded.');
    expect(copy.next, 'Publish again to send them.');
  });

  test('an answer words its own error and its retry hint', () {
    const ApiResponse overRelay = ApiResponse(
      413,
      <String, Object?>{
        'error': <String, Object?>{'code': 'too-large', 'message': 'big'},
      },
      path: ConnectionPath.relay,
    );
    expect(overRelay.problem().cause, 'This is too large to send over the relay.');
    expect(
      const ApiResponse(429, <String, Object?>{
        'error': <String, Object?>{'code': 'rate-limited', 'retryAfterMillis': 250},
      }).retryAfter,
      const Duration(milliseconds: 250),
    );
    expect(
      const ApiResponse(503, null, headers: <String, String>{'Retry-After': '2'}).retryAfter,
      const Duration(seconds: 2),
    );
    expect(const ApiResponse(500, null, headers: <String, String>{'Retry-After': '2'}).retryAfter, isNull);
    expect(const ApiResponse(204, null).problem(), isNotNull);
  });

  test('a transport failure says its cause in words', () {
    const TransportFailure failure = TransportFailure(ConnectionPath.relay, 'relay-unreachable', 'close 1006');
    expect(failure.message, 'The relay did not answer. Check your network.');
    expect(failure.toString(), 'relay: relay-unreachable (close 1006)');
  });

  group('a direct request the browser could not complete', () {
    const ApiResponse refusedOrigin = ApiResponse(403, <String, Object?>{
      'error': <String, Object?>{'code': 'origin-not-allowed'},
    });

    test('should say the connection dropped when the server still answers this page', () {
      expect(directFailureCode(const ApiResponse(200, <String, Object?>{'protocol': 1}), answersWithoutCors: true), 'dropped');
      expect(directFailureCode(const ApiResponse(503, null), answersWithoutCors: true), 'dropped');
      expect(transportCopy(directFailureCode(const ApiResponse(200, null), answersWithoutCors: true)).text,
          isNot(contains('allowedOrigins')));
    });

    test('should name allowedOrigins only when the server refused this page', () {
      expect(directFailureCode(refusedOrigin, answersWithoutCors: true), 'origin-not-allowed');
      expect(directFailureCode(null, answersWithoutCors: true), 'origin-not-allowed',
          reason: 'answered only without CORS: refused with no CORS headers');
    });

    test('should say unreachable when nothing answers', () {
      expect(directFailureCode(null, answersWithoutCors: false), 'unreachable');
    });
  });

  test('a pairing refusal names its case and leaves the words to the card', () {
    for (final PairingFailure failure in PairingFailure.values) {
      expect(PairingFailure.of(failure.code), failure);
      final PairingRefused refused = PairingRefused.because(failure);
      expect(refused.code, failure.code);
      expect(refused.failure, failure);
      expect(refused.copy, ErrorCopy(failure.code, '', ''));
    }
    expect(PairingFailure.of('busy'), PairingFailure.other);
    expect(PairingRefused('busy', serverCopy('busy')).failure, PairingFailure.other);
  });
}
