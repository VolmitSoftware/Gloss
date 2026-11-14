import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:gloss_sync_relay/gloss_sync_relay.dart';
import 'package:test/test.dart';
import 'package:web_socket_channel/io.dart';

import 'support.dart';

const String _origin = 'http://127.0.0.1:8098';

/// One test WebSocket with a frame queue.
final class _Peer {
  final IOWebSocketChannel channel;
  final StreamQueueLite frames;

  _Peer(this.channel) : frames = StreamQueueLite(channel.stream);

  void send(Map<String, Object?> frame) => channel.sink.add(jsonEncode(frame));

  void sendText(String text) => channel.sink.add(text);

  Future<Map<String, Object?>> next() async =>
      jsonDecode(await frames.next() as String) as Map<String, Object?>;

  Future<void> close() => channel.sink.close();
}

/// A minimal queue over a single-subscription stream.
final class StreamQueueLite {
  final List<Object?> _buffer = <Object?>[];
  final List<Completer<Object?>> _waiters = <Completer<Object?>>[];
  bool done = false;

  StreamQueueLite(Stream<Object?> stream) {
    stream.listen(
      (Object? value) {
        if (_waiters.isNotEmpty) {
          _waiters.removeAt(0).complete(value);
        } else {
          _buffer.add(value);
        }
      },
      onDone: () {
        done = true;
        for (final Completer<Object?> waiter in _waiters) {
          waiter.completeError(StateError('closed'));
        }
        _waiters.clear();
      },
      onError: (Object error) {},
    );
  }

  Future<Object?> next() {
    if (_buffer.isNotEmpty) return Future<Object?>.value(_buffer.removeAt(0));
    if (done) return Future<Object?>.error(StateError('closed'));
    final Completer<Object?> waiter = Completer<Object?>();
    _waiters.add(waiter);
    return waiter.future.timeout(const Duration(seconds: 5));
  }
}

void main() {
  late RelayServer server;
  late Uri base;

  Future<_Peer> connect(String path, {String? origin}) async {
    final IOWebSocketChannel channel = IOWebSocketChannel.connect(
      base.resolve(path),
      headers: <String, Object>{'Origin': ?origin},
    );
    await channel.ready;
    return _Peer(channel);
  }

  Future<_Peer> registeredAgent(TestServer identity) async {
    final _Peer agent = await connect('/agent');
    final Map<String, Object?> challenge = await agent.next();
    expect(challenge['type'], 'challenge');
    expect(challenge['protocol'], relayProtocol);
    agent.sendText(await identity.register(challenge['nonce']! as String));
    expect(await agent.next(), <String, Object?>{
      'type': 'registered',
      'fingerprint': identity.fingerprint,
    });
    return agent;
  }

  setUp(() async {
    server = await startRelayServer(
      port: 0,
      address: '127.0.0.1',
      allowedAppOrigins: const <String>[_origin],
      limits: const RelayLimits(
        handshakeTimeout: Duration(milliseconds: 300),
        maxAppMessagesPerWindow: 5,
      ),
    );
    base = Uri.parse('ws://127.0.0.1:${server.port}/');
  });

  tearDown(() => server.close());

  test(
    'should relay a request, its response and a stream end to end',
    () async {
      final TestServer identity = await TestServer.create();
      final _Peer agent = await registeredAgent(identity);
      final _Peer browser = await connect('/app', origin: _origin);
      browser.send(<String, Object?>{
        'type': 'subscribe',
        'protocol': 1,
        'fingerprint': identity.fingerprint,
      });
      expect(await browser.next(), <String, Object?>{
        'type': 'subscribed',
        'fingerprint': identity.fingerprint,
        'online': true,
      });

      browser.sendText(fixtureText('relay/relay-request.0.json'));
      final Map<String, Object?> forwarded = await agent.next();
      expect(forwarded['id'], 1);
      expect(
        forwarded['body'],
        (readFixture('relay/relay-request.0.json')!
            as Map<String, Object?>)['body'],
      );
      expect(
        (forwarded['headers']! as Map<String, Object?>)['Origin'],
        _origin,
      );
      expect(
        (forwarded['headers']! as Map<String, Object?>)['X-Forwarded-For'],
        '127.0.0.1',
      );
      final Map<String, Object?> answer =
          readFixture('relay/relay-response.0.json')! as Map<String, Object?>;
      agent.send(<String, Object?>{
        'type': 'response',
        'id': 1,
        'status': 200,
        'body': answer['body'],
      });
      expect(await browser.next(), answer);

      browser.sendText(fixtureText('relay/relay-request.1.json'));
      expect((await agent.next())['path'], '/v1/events');
      agent.send(<String, Object?>{
        'type': 'response',
        'id': 2,
        'status': 200,
        'body': <String, Object?>{},
      });
      expect((await browser.next())['status'], 200);
      final Map<String, Object?> event =
          readFixture('relay/relay-event.0.json')! as Map<String, Object?>;
      agent.send(<String, Object?>{
        'type': 'event',
        'id': 2,
        'event': event['event'],
      });
      expect(await browser.next(), event);

      final HttpClient client = HttpClient();
      final HttpClientResponse health = await (await client.getUrl(
        Uri.parse('http://127.0.0.1:${server.port}/healthz'),
      )).close();
      final Map<String, Object?> counts =
          jsonDecode(await utf8.decodeStream(health)) as Map<String, Object?>;
      client.close();
      expect(counts, containsPair('servers', 1));
      expect(counts, containsPair('streams', 1));

      await browser.close();
      expect(await agent.next(), <String, Object?>{'type': 'close', 'id': 2});
      await agent.close();
    },
  );

  test(
    'should refuse browsers without an allowed Origin and servers with one',
    () async {
      await expectLater(connect('/app'), throwsA(anything));
      await expectLater(
        connect('/app', origin: 'https://evil.example'),
        throwsA(anything),
      );
      await expectLater(connect('/agent', origin: _origin), throwsA(anything));
    },
  );

  test('should close a server that signs with another key', () async {
    final TestServer identity = await TestServer.create();
    final TestServer other = await TestServer.create();
    final _Peer agent = await connect('/agent');
    final Map<String, Object?> challenge = await agent.next();
    agent.send(<String, Object?>{
      'type': 'register',
      'protocol': 1,
      'serverKey': identity.serverKey,
      'signature': await other.sign(challenge['nonce']! as String),
    });
    expect(
      await agent.next(),
      containsPair('code', RelayErrorCode.handshakeFailed),
    );
    expect(agent.channel.closeCode, anyOf(isNull, policyCloseCode));
  });

  test('should close a server that does not register in time', () async {
    final _Peer agent = await connect('/agent');
    await agent.next();
    expect(await agent.next(), containsPair('code', RelayErrorCode.timeout));
  });

  test('should close a browser over its message rate', () async {
    final _Peer browser = await connect('/app', origin: _origin);
    browser.send(<String, Object?>{
      'type': 'subscribe',
      'protocol': 1,
      'fingerprint': 'sha256:${'c' * 64}',
    });
    expect((await browser.next())['online'], isFalse);
    for (int index = 1; index <= 6; index++) {
      browser.send(request(index));
    }
    final List<Map<String, Object?>> seen = <Map<String, Object?>>[];
    while (true) {
      final Map<String, Object?> frame = await browser.next();
      seen.add(frame);
      if (frame['type'] == 'error') break;
    }
    expect(seen.last, containsPair('code', RelayErrorCode.rateLimited));
    expect(
      seen.where((Map<String, Object?> f) => f['type'] == 'response'),
      hasLength(4),
    );
  });

  test(
    'should close a browser that sends a request before subscribing',
    () async {
      final _Peer browser = await connect('/app', origin: _origin);
      browser.send(request(1));
      expect(
        await browser.next(),
        containsPair('code', RelayErrorCode.invalidFrame),
      );
    },
  );

  test(
    'should close servers and browsers with going away when it stops',
    () async {
      final TestServer identity = await TestServer.create();
      final _Peer agent = await registeredAgent(identity);
      final _Peer browser = await connect('/app', origin: _origin);
      final int port = server.port;
      final Stopwatch stopping = Stopwatch()..start();

      await server.close();
      stopping.stop();
      await agent.channel.sink.done.timeout(const Duration(seconds: 2));
      await browser.channel.sink.done.timeout(const Duration(seconds: 2));

      expect(agent.channel.closeCode, WebSocketStatus.goingAway);
      expect(browser.channel.closeCode, WebSocketStatus.goingAway);
      expect(stopping.elapsed, lessThan(const Duration(seconds: 2)));
      await expectLater(
        IOWebSocketChannel.connect(Uri.parse('ws://127.0.0.1:$port/agent'))
            .ready,
        throwsA(anything),
      );
    },
  );

  group('with tight limits', () {
    late RelayServer tight;
    late Uri tightBase;

    setUp(() async {
      tight = await startRelayServer(
        port: 0,
        address: '127.0.0.1',
        allowedAppOrigins: const <String>[_origin],
        limits: const RelayLimits(
          maxFrameBytes: 1024,
          maxAgentConnectionsPerAddress: 2,
          maxAppConnectionsPerAddress: 2,
          signInTimeout: Duration(milliseconds: 300),
        ),
      );
      tightBase = Uri.parse('ws://127.0.0.1:${tight.port}/');
    });

    tearDown(() => tight.close());

    Future<_Peer> open(
      String path,
      String forwardedFor, {
      String? origin,
    }) async {
      final IOWebSocketChannel channel = IOWebSocketChannel.connect(
        tightBase.resolve(path),
        headers: <String, Object>{
          'Origin': ?origin,
          'X-Forwarded-For': forwardedFor,
        },
      );
      await channel.ready;
      return _Peer(channel);
    }

    test(
      'should forward the address the front end appended, not the browser\'s',
      () async {
        final TestServer identity = await TestServer.create();
        final IOWebSocketChannel agentChannel = IOWebSocketChannel.connect(
          tightBase.resolve('/agent'),
        );
        await agentChannel.ready;
        final _Peer agent = _Peer(agentChannel);
        final Map<String, Object?> challenge = await agent.next();
        agent.sendText(await identity.register(challenge['nonce']! as String));
        await agent.next();
        for (final (String header, String expected) in <(String, String)>[
          ('6.6.6.6, 203.0.113.9', '203.0.113.9'),
          ('203.0.113.7', '203.0.113.7'),
          ('6.6.6.6, not-an-address', '127.0.0.1'),
        ]) {
          final _Peer browser = await open('/app', header, origin: _origin);
          browser.send(<String, Object?>{
            'type': 'subscribe',
            'protocol': 1,
            'fingerprint': identity.fingerprint,
          });
          await browser.next();
          browser.send(request(1));
          final Map<String, Object?> forwarded = await agent.next();
          expect(
            (forwarded['headers']! as Map<String, Object?>)['X-Forwarded-For'],
            expected,
            reason: header,
          );
          await browser.close();
          await agent.next();
        }
      },
    );

    test('should refuse connections past the per-address limit', () async {
      final List<_Peer> open2 = <_Peer>[
        await open('/app', '198.51.100.1', origin: _origin),
        await open('/app', '198.51.100.1', origin: _origin),
        await open('/agent', '198.51.100.1'),
        await open('/agent', '198.51.100.1'),
      ];
      await expectLater(
        open('/app', '198.51.100.1', origin: _origin),
        throwsA(anything),
      );
      await expectLater(open('/agent', '198.51.100.1'), throwsA(anything));
      final _Peer other = await open('/app', '198.51.100.2', origin: _origin);
      await open2.first.close();
      await other.close();
      final _Peer again = await _eventually(
        () => open('/app', '198.51.100.1', origin: _origin),
      );
      await again.close();
      for (final _Peer peer in open2.skip(1)) {
        await peer.close();
      }
    });

    test('should close a browser that does not sign in on time', () async {
      final _Peer browser = await open('/app', '198.51.100.3', origin: _origin);
      browser.send(<String, Object?>{
        'type': 'subscribe',
        'protocol': 1,
        'fingerprint': 'sha256:${'c' * 64}',
      });
      expect((await browser.next())['type'], 'subscribed');
      expect(
        await browser.next(),
        containsPair('code', RelayErrorCode.timeout),
      );
    });

    for (final (String name, List<List<int>> frames)
        in <(String, List<List<int>>)>[
          (
            'a frame header over the limit',
            <List<int>>[
              _frameHeader(fin: true, opcode: 1, length: 64 * 1024 * 1024),
            ],
          ),
          (
            'fragments that add up past the limit',
            <List<int>>[
              _frame(
                fin: false,
                opcode: 1,
                payload: List<int>.filled(800, 0x61),
              ),
              _frame(
                fin: false,
                opcode: 0,
                payload: List<int>.filled(800, 0x61),
              ),
            ],
          ),
        ]) {
      test('should drop a connection that sends $name', () async {
        final Socket socket = await Socket.connect('127.0.0.1', tight.port);
        final Completer<void> closed = Completer<void>();
        final BytesBuilder received = BytesBuilder();
        socket.listen(
          received.add,
          onDone: closed.complete,
          onError: (Object error) {
            if (!closed.isCompleted) closed.complete();
          },
        );
        socket.write(
          'GET /agent HTTP/1.1\r\n'
          'Host: 127.0.0.1:${tight.port}\r\n'
          'Upgrade: websocket\r\n'
          'Connection: Upgrade\r\n'
          'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n'
          'Sec-WebSocket-Version: 13\r\n\r\n',
        );
        await _eventually(() async {
          if (!latin1.decode(received.toBytes()).contains('\r\n\r\n')) {
            throw StateError('no upgrade yet');
          }
          return true;
        });
        for (final List<int> frame in frames) {
          socket.add(frame);
        }
        await socket.flush();
        await closed.future.timeout(const Duration(seconds: 3));
        socket.destroy();
      });
    }
  });
}

/// Retries [attempt] until it succeeds or two seconds pass.
Future<T> _eventually<T>(Future<T> Function() attempt) async {
  final Stopwatch clock = Stopwatch()..start();
  while (true) {
    try {
      return await attempt();
    } on Object {
      if (clock.elapsed > const Duration(seconds: 2)) rethrow;
      await Future<void>.delayed(const Duration(milliseconds: 20));
    }
  }
}

/// A masked client frame header announcing [length] payload bytes.
List<int> _frameHeader({
  required bool fin,
  required int opcode,
  required int length,
}) {
  final BytesBuilder bytes = BytesBuilder()..addByte((fin ? 0x80 : 0) | opcode);
  if (length < 126) {
    bytes.addByte(0x80 | length);
  } else if (length < 65536) {
    bytes
      ..addByte(0x80 | 126)
      ..add(<int>[length >> 8, length & 0xff]);
  } else {
    bytes.addByte(0x80 | 127);
    for (int shift = 56; shift >= 0; shift -= 8) {
      bytes.addByte((length >> shift) & 0xff);
    }
  }
  bytes.add(const <int>[0, 0, 0, 0]);
  return bytes.toBytes();
}

/// A whole masked client frame (a zero mask leaves [payload] as is).
List<int> _frame({
  required bool fin,
  required int opcode,
  required List<int> payload,
}) => <int>[
  ..._frameHeader(fin: fin, opcode: opcode, length: payload.length),
  ...payload,
];
