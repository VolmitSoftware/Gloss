import 'dart:convert';

import 'package:gloss_sync_relay/gloss_sync_relay.dart';
import 'package:test/test.dart';

import 'support.dart';

const int _max = RelayLimits.defaultMaxFrameBytes;

void main() {
  group('shared fixtures', () {
    test('should decode every valid relay message fixture on its side', () {
      final List<Object?> messages =
          (readFixture('relay/index.json')!
                  as Map<String, Object?>)['messages']!
              as List<Object?>;
      int checked = 0;
      for (final Object? raw in messages) {
        final Map<String, Object?> entry = raw! as Map<String, Object?>;
        final String definition = entry['definition']! as String;
        if (!definition.startsWith('relay-')) continue;
        final String text = fixtureText(entry['path']! as String);
        final bool valid = entry['valid']! as bool;
        final bool appSide = const <String>{
          'relay-subscribe',
          'relay-request',
          'relay-close',
        }.contains(definition);
        final bool agentSide = const <String>{
          'relay-register',
          'relay-response',
          'relay-event',
          'relay-close',
        }.contains(definition);
        if (!appSide && !agentSide) continue;
        if (appSide) {
          expect(
            () => RelayFrames.decodeApp(text, _max),
            valid ? returnsNormally : throwsA(isA<FrameException>()),
            reason: entry['path'] as String,
          );
        }
        if (agentSide) {
          expect(
            () => RelayFrames.decodeAgent(text, _max),
            valid ? returnsNormally : throwsA(isA<FrameException>()),
            reason: entry['path'] as String,
          );
        }
        checked++;
      }
      expect(checked, greaterThanOrEqualTo(10));
    });

    test('should re-encode a request fixture to the same JSON', () {
      final String text = fixtureText('relay/relay-request.0.json');
      final RequestFrame frame =
          RelayFrames.decodeApp(text, _max) as RequestFrame;
      expect(frame.toJson(), readFixture('relay/relay-request.0.json'));
    });

    test('should build relay frames that match the shared fixtures', () {
      final Map<String, Object?> handshake =
          readFixture('relay-handshake.json')! as Map<String, Object?>;
      expect(
        RelayFrames.challenge(handshake['nonce']! as String),
        readFixture('relay/relay-challenge.0.json'),
      );
      expect(
        RelayFrames.registered(handshake['fingerprint']! as String),
        readFixture('relay/relay-registered.0.json'),
      );
      expect(
        RelayFrames.subscribed(handshake['fingerprint']! as String, true),
        readFixture('relay/relay-subscribed.0.json'),
      );
      expect(
        RelayFrames.status(false),
        readFixture('relay/relay-status.0.json'),
      );
      expect(
        RelayFrames.failure(
          9,
          503,
          'server-offline',
          'the server is not connected to the relay',
        ).toJson(),
        readFixture('relay/relay-response.1.json'),
      );
    });
  });

  group('app frames', () {
    FrameException refusal(Map<String, Object?> json, [int max = _max]) {
      try {
        RelayFrames.decodeApp(jsonEncode(json), max);
      } on FrameException catch (error) {
        return error;
      }
      fail('accepted $json');
    }

    test('should refuse unknown fields', () {
      expect(
        refusal(<String, Object?>{...request(1), 'extra': true}).code,
        RelayErrorCode.invalidFrame,
      );
    });

    test('should refuse paths outside /v1 and traversal', () {
      for (final String path in <String>[
        '/api/v1/x',
        '/v1/../admin',
        '/v1/a b',
        '/v1/a\\b',
        'v1/x',
      ]) {
        expect(
          refusal(request(1, path: path)).code,
          RelayErrorCode.invalidFrame,
          reason: path,
        );
      }
    });

    test('should refuse GET with a body and unsupported methods', () {
      expect(
        refusal(<String, Object?>{...request(1), 'body': <String, Object?>{}})
            .code,
        RelayErrorCode.invalidFrame,
      );
      expect(
        refusal(request(1, method: 'PUT')).code,
        RelayErrorCode.invalidFrame,
      );
    });

    test('should refuse ids that are not positive integers', () {
      for (final Object id in <Object>[0, -1, 1.5, '1']) {
        expect(
          refusal(<String, Object?>{...request(1), 'id': id}).code,
          RelayErrorCode.invalidFrame,
          reason: '$id',
        );
      }
    });

    test('should refuse header values with line breaks and bad names', () {
      expect(
        refusal(request(1, headers: <String, String>{'X-A': 'a\r\nB: c'})).code,
        RelayErrorCode.invalidFrame,
      );
      expect(
        refusal(request(1, headers: <String, String>{'Bad Name': 'a'})).code,
        RelayErrorCode.invalidFrame,
      );
    });

    test('should refuse frames over the byte limit by UTF-8 size', () {
      final Map<String, Object?> big = <String, Object?>{
        ...request(1, method: 'POST', path: '/v1/changes'),
        'body': <String, Object?>{'text': 'é' * 700},
      };
      expect(refusal(big, 1024).code, RelayErrorCode.tooLarge);
    });

    test('should refuse another protocol on subscribe', () {
      expect(
        refusal(<String, Object?>{
          'type': 'subscribe',
          'protocol': 2,
          'fingerprint': 'sha256:${'0' * 64}',
        }).code,
        RelayErrorCode.unsupportedProtocol,
      );
    });

    test('should treat GET /v1/events as a stream', () {
      final RequestFrame frame = RelayFrames.decodeApp(
        encode(request(1, path: '/v1/events')),
        _max,
      ) as RequestFrame;
      expect(frame.opensStream, isTrue);
      final RequestFrame other = RelayFrames.decodeApp(
        encode(request(1, path: '/v1/eventsx')),
        _max,
      ) as RequestFrame;
      expect(other.opensStream, isFalse);
    });

    test('should refuse frames a server sends on the browser path', () {
      expect(
        refusal(<String, Object?>{'type': 'response', 'id': 1, 'status': 200})
            .code,
        RelayErrorCode.invalidFrame,
      );
    });
  });

  group('agent frames', () {
    test('should refuse events without an object event', () {
      expect(
        () => RelayFrames.decodeAgent(
          encode(<String, Object?>{'type': 'event', 'id': 1, 'event': 'x'}),
          _max,
        ),
        throwsA(isA<FrameException>()),
      );
    });

    test('should refuse statuses outside 100..599', () {
      expect(
        () => RelayFrames.decodeAgent(
          encode(<String, Object?>{'type': 'response', 'id': 1, 'status': 99}),
          _max,
        ),
        throwsA(isA<FrameException>()),
      );
    });
  });
}
