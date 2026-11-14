import 'package:gloss_sync_relay/gloss_sync_relay.dart';
import 'package:test/test.dart';

import 'support.dart';

final String _server = 'sha256:${'a' * 64}';
const int _max = RelayLimits.defaultMaxFrameBytes;

AppFrame _app(Map<String, Object?> json) =>
    RelayFrames.decodeApp(encode(json), _max);
AgentFrame _agent(Map<String, Object?> json) =>
    RelayFrames.decodeAgent(encode(json), _max);

Map<String, Object?> _response(int id, [int status = 200]) => <String, Object?>{
  'type': 'response',
  'id': id,
  'status': status,
  'body': <String, Object?>{'ok': id},
};

void main() {
  late RelayBroker broker;
  late FakeSink agent;
  late FakeSink browser;

  setUp(() {
    broker = RelayBroker();
    agent = FakeSink();
    browser = FakeSink();
    expect(broker.registerServer(_server, agent), isTrue);
    expect(
      broker.subscribeApp(
        browser,
        _server,
        origin: 'https://gloss-editor.web.app',
        address: '203.0.113.9',
      ),
      isNull,
    );
    expect(browser.take(), <String, Object?>{
      'type': 'subscribed',
      'fingerprint': _server,
      'online': true,
    });
  });

  test('should rewrite ids and answer only the browser that asked', () {
    final FakeSink other = FakeSink();
    broker.subscribeApp(other, _server);
    other.take();
    broker.routeFromApp(browser, _app(request(7)));
    broker.routeFromApp(other, _app(request(7)));
    final List<Map<String, Object?>> forwarded = agent.ofType('request');
    expect(forwarded.map((Map<String, Object?> f) => f['id']), <int>[1, 2]);

    broker.routeFromAgent(_server, agent, _agent(_response(2)));
    expect(browser.frames, isEmpty);
    expect(other.take(), <String, Object?>{
      'type': 'response',
      'id': 7,
      'status': 200,
      'body': <String, Object?>{'ok': 2},
    });
    broker.routeFromAgent(_server, agent, _agent(_response(1)));
    expect((browser.take()['body']! as Map<String, Object?>)['ok'], 1);
    expect(broker.pendingCount, 0);
  });

  test('should drop duplicate, unknown and late responses', () {
    broker.routeFromApp(browser, _app(request(1)));
    broker.routeFromAgent(_server, agent, _agent(_response(1)));
    broker.routeFromAgent(_server, agent, _agent(_response(1)));
    broker.routeFromAgent(_server, agent, _agent(_response(99)));
    expect(browser.ofType('response'), hasLength(1));
  });

  test('should replace browser Origin and address headers with its own', () {
    broker.routeFromApp(
      browser,
      _app(
        request(
          1,
          headers: <String, String>{
            'Authorization': 'Bearer gls_x',
            'origin': 'https://evil.example',
            'X-Forwarded-For': '10.0.0.1',
            'Host': 'x',
          },
        ),
      ),
    );
    expect(agent.last['headers'], <String, Object?>{
      'Authorization': 'Bearer gls_x',
      'Origin': 'https://gloss-editor.web.app',
      'X-Forwarded-For': '203.0.113.9',
    });
  });

  test('should answer offline, busy and limit failures itself', () {
    broker.routeFromApp(browser, _app(request(1)));
    broker.routeFromApp(browser, _app(request(1)));
    expect(browser.take(), containsPair('status', 400));

    final RelayBroker small = RelayBroker(
      limits: const RelayLimits(maxPendingPerApp: 1, maxPendingGlobal: 1),
    );
    final FakeSink app = FakeSink();
    small.subscribeApp(app, _server);
    expect(app.take()['online'], isFalse);
    small.routeFromApp(app, _app(request(1)));
    expect(app.take(), <String, Object?>{
      'type': 'response',
      'id': 1,
      'status': 503,
      'body': <String, Object?>{
        'error': <String, Object?>{
          'code': 'server-offline',
          'message': 'the server is not connected to the relay',
        },
      },
    });
    final FakeSink server = FakeSink();
    small.registerServer(_server, server);
    expect(app.take(), <String, Object?>{'type': 'status', 'online': true});
    small.routeFromApp(app, _app(request(1)));
    small.routeFromApp(app, _app(request(2)));
    expect(app.take(), containsPair('status', 429));
    final FakeSink second = FakeSink();
    small.subscribeApp(second, _server);
    second.take();
    small.routeFromApp(second, _app(request(1)));
    expect(second.take(), containsPair('status', 503));
    expect(server.ofType('request'), hasLength(1));
  });

  test('should refuse requests the server could not receive', () {
    final RelayBroker small = RelayBroker(
      limits: const RelayLimits(maxFrameBytes: 1024),
    );
    final FakeSink server = FakeSink();
    final FakeSink app = FakeSink();
    small.registerServer(_server, server);
    small.subscribeApp(app, _server, origin: 'https://${'o' * 500}.example');
    app.take();
    small.routeFromApp(
      app,
      RelayFrames.decodeApp(
        encode(request(1, headers: <String, String>{'X-Pad': 'p' * 600})),
        _max,
      ),
    );
    expect(app.take(), containsPair('status', 413));
    expect(server.frames, isEmpty);
  });

  test('should keep a 2xx event stream open until either side closes it', () {
    broker.routeFromApp(browser, _app(request(5, path: '/v1/events')));
    broker.routeFromAgent(_server, agent, _agent(_response(1)));
    expect(browser.take(), containsPair('status', 200));
    expect(broker.pendingCount, 0);
    expect(broker.streamCount, 1);
    broker.routeFromAgent(
      _server,
      agent,
      _agent(<String, Object?>{
        'type': 'event',
        'id': 1,
        'event': <String, Object?>{'type': 'closing', 'reason': 'limit'},
      }),
    );
    expect(browser.take(), <String, Object?>{
      'type': 'event',
      'id': 5,
      'event': <String, Object?>{'type': 'closing', 'reason': 'limit'},
    });
    broker.routeFromAgent(
      _server,
      agent,
      _agent(<String, Object?>{'type': 'close', 'id': 1}),
    );
    expect(browser.take(), <String, Object?>{'type': 'close', 'id': 5});
    expect(broker.streamCount, 0);

    broker.routeFromApp(browser, _app(request(6, path: '/v1/events')));
    broker.routeFromAgent(_server, agent, _agent(_response(2)));
    broker.routeFromApp(
      browser,
      _app(<String, Object?>{'type': 'close', 'id': 6}),
    );
    expect(agent.last, <String, Object?>{'type': 'close', 'id': 2});
    broker.routeFromAgent(
      _server,
      agent,
      _agent(<String, Object?>{
        'type': 'event',
        'id': 2,
        'event': <String, Object?>{'type': 'x'},
      }),
    );
    expect(browser.ofType('event'), isEmpty);
  });

  test('should end a stream refused by the server and drop events for it', () {
    broker.routeFromApp(browser, _app(request(5, path: '/v1/events')));
    broker.routeFromAgent(_server, agent, _agent(_response(1, 401)));
    broker.routeFromAgent(
      _server,
      agent,
      _agent(<String, Object?>{
        'type': 'event',
        'id': 1,
        'event': <String, Object?>{'type': 'x'},
      }),
    );
    expect(browser.frames, hasLength(1));
    expect(broker.streamCount, 0);
  });

  test('should bound streams per browser', () {
    final RelayBroker small = RelayBroker(
      limits: const RelayLimits(maxStreamsPerApp: 1),
    );
    final FakeSink server = FakeSink();
    final FakeSink app = FakeSink();
    small.registerServer(_server, server);
    small.subscribeApp(app, _server);
    app.take();
    small.routeFromApp(app, _app(request(1, path: '/v1/events')));
    small.routeFromApp(app, _app(request(2, path: '/v1/events')));
    expect(app.take(), containsPair('status', 429));
  });

  test(
    'should fail pending requests and close streams when the server leaves',
    () {
      broker.routeFromApp(browser, _app(request(5, path: '/v1/events')));
      broker.routeFromAgent(_server, agent, _agent(_response(1)));
      browser.take();
      broker.routeFromApp(browser, _app(request(6)));
      broker.unregisterServer(_server, agent);
      expect(
        browser.frames.map((Map<String, Object?> f) => f['type']).toSet(),
        <String>{'close', 'response', 'status'},
      );
      expect(browser.ofType('response').single['status'], 503);
      expect(browser.ofType('status').single['online'], isFalse);
      expect(broker.pendingCount, 0);
      expect(broker.streamCount, 0);
    },
  );

  test('should replace a server connection with the same key', () {
    broker.routeFromApp(browser, _app(request(6)));
    final FakeSink newer = FakeSink();
    broker.registerServer(_server, newer);
    expect(agent.closedCode, RelayErrorCode.replaced);
    expect(browser.take(), containsPair('status', 503));
    broker.unregisterServer(_server, agent);
    expect(broker.isOnline(_server), isTrue);
    broker.routeFromAgent(_server, agent, _agent(_response(1)));
    expect(browser.frames, isEmpty);
    broker.routeFromApp(browser, _app(request(7)));
    expect(newer.last['id'], 1);
  });

  test('should cancel at the server when a browser leaves', () {
    broker.routeFromApp(browser, _app(request(5)));
    broker.unregisterApp(browser);
    expect(agent.last, <String, Object?>{'type': 'close', 'id': 1});
    expect(broker.pendingCount, 0);
    expect(broker.appCount, 0);
  });

  test('should time out a request and tell the server', () async {
    final RelayBroker quick = RelayBroker(
      limits: const RelayLimits(requestTimeout: Duration(milliseconds: 30)),
    );
    final FakeSink server = FakeSink();
    final FakeSink app = FakeSink();
    quick.registerServer(_server, server);
    quick.subscribeApp(app, _server);
    app.take();
    quick.routeFromApp(app, _app(request(3)));
    await Future<void>.delayed(const Duration(milliseconds: 80));
    expect(app.take(), containsPair('status', 504));
    expect(server.last, <String, Object?>{'type': 'close', 'id': 1});
    quick.routeFromAgent(_server, server, _agent(_response(1)));
    expect(app.frames, isEmpty);
  });

  test('should refuse subscriptions past the per-server limit', () {
    final RelayBroker small = RelayBroker(
      limits: const RelayLimits(maxAppsPerServer: 1),
    );
    expect(small.subscribeApp(FakeSink(), _server), isNull);
    expect(small.subscribeApp(FakeSink(), _server), RelayErrorCode.capacity);
  });

  test('should refuse servers past the server limit', () {
    final RelayBroker small = RelayBroker(
      limits: const RelayLimits(maxAgents: 1),
    );
    expect(small.registerServer(_server, FakeSink()), isTrue);
    expect(small.registerServer('sha256:${'b' * 64}', FakeSink()), isFalse);
  });

  test('should bound subscriptions from one address to one server', () {
    final RelayBroker small = RelayBroker(
      limits: const RelayLimits(maxAppsPerAddressPerServer: 2),
    );
    expect(
      small.subscribeApp(FakeSink(), _server, address: '198.51.100.1'),
      isNull,
    );
    expect(
      small.subscribeApp(FakeSink(), _server, address: '198.51.100.1'),
      isNull,
    );
    expect(
      small.subscribeApp(FakeSink(), _server, address: '198.51.100.1'),
      RelayErrorCode.capacity,
    );
    expect(
      small.subscribeApp(FakeSink(), _server, address: '198.51.100.2'),
      isNull,
    );
    expect(
      small.subscribeApp(
        FakeSink(),
        'sha256:${'b' * 64}',
        address: '198.51.100.1',
      ),
      isNull,
    );
  });

  void signIn(RelayBroker relay, FakeSink server, FakeSink app, int relayId) {
    relay.routeFromApp(app, _app(request(1, path: '/v1/events')));
    relay.routeFromAgent(_server, server, _agent(_response(relayId)));
    expect(relay.isSignedIn(app), isTrue);
  }

  test('should keep slots for browsers that signed in', () {
    final RelayBroker small = RelayBroker(
      limits: const RelayLimits(
        maxAppsPerServer: 3,
        maxAppsSigningInPerServer: 1,
      ),
    );
    final FakeSink server = FakeSink();
    small.registerServer(_server, server);
    final FakeSink first = FakeSink();
    expect(small.subscribeApp(first, _server, address: '198.51.100.1'), isNull);
    expect(
      small.subscribeApp(FakeSink(), _server, address: '198.51.100.2'),
      RelayErrorCode.capacity,
    );
    signIn(small, server, first, 1);
    final FakeSink second = FakeSink();
    expect(
      small.subscribeApp(second, _server, address: '198.51.100.2'),
      isNull,
    );
    expect(
      small.subscribeApp(FakeSink(), _server, address: '198.51.100.3'),
      RelayErrorCode.capacity,
    );
    signIn(small, server, second, 2);
    expect(
      small.subscribeApp(FakeSink(), _server, address: '198.51.100.3'),
      isNull,
    );
    expect(
      small.subscribeApp(FakeSink(), _server, address: '198.51.100.4'),
      RelayErrorCode.capacity,
    );
  });

  test('should bound browsers signing in from one address group', () {
    final RelayBroker small = RelayBroker(
      limits: const RelayLimits(maxAppsSigningInPerAddressPerServer: 1),
    );
    final FakeSink server = FakeSink();
    small.registerServer(_server, server);
    final FakeSink first = FakeSink();
    expect(small.subscribeApp(first, _server, address: '2001:db8::1'), isNull);
    expect(
      small.subscribeApp(FakeSink(), _server, address: '2001:db8::2'),
      RelayErrorCode.capacity,
    );
    expect(
      small.subscribeApp(FakeSink(), _server, address: '2001:db8:0:1::1'),
      isNull,
    );
    signIn(small, server, first, 1);
    expect(
      small.subscribeApp(FakeSink(), _server, address: '2001:db8::2'),
      isNull,
    );
  });

  test(
    'should count a browser as signed in once the server opens its stream',
    () {
      expect(broker.isSignedIn(browser), isFalse);
      broker.routeFromApp(browser, _app(request(1, path: '/v1/hello')));
      broker.routeFromAgent(_server, agent, _agent(_response(1)));
      expect(broker.isSignedIn(browser), isFalse);
      broker.routeFromApp(browser, _app(request(2, path: '/v1/events')));
      broker.routeFromAgent(_server, agent, _agent(_response(2, 401)));
      expect(broker.isSignedIn(browser), isFalse);
      broker.routeFromApp(browser, _app(request(3, path: '/v1/events')));
      broker.routeFromAgent(_server, agent, _agent(_response(3)));
      expect(broker.isSignedIn(browser), isTrue);
    },
  );
}
