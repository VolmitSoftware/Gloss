import 'package:gloss_sync_relay/gloss_sync_relay.dart';
import 'package:test/test.dart';

import 'support.dart';

Map<String, Object?> _response(int id) => <String, Object?>{
  'type': 'response',
  'id': id,
  'status': 200,
  'body': <String, Object?>{},
};

Map<String, Object?> _event(int id) => <String, Object?>{
  'type': 'event',
  'id': id,
  'event': <String, Object?>{'type': 'change'},
};

void main() {
  late TestServer identity;
  late RelayBroker broker;
  late FakeSink agentSink;
  late AgentSession agent;
  late FakeSink browserSink;
  late AppSession browser;

  setUp(() async {
    identity = await TestServer.create();
    broker = RelayBroker(
      limits: const RelayLimits(
        maxAgentMessagesPerWindow: 4,
        messageRateWindow: Duration(minutes: 1),
      ),
    );
    agentSink = FakeSink();
    agent = AgentSession(
      broker: broker,
      verifier: ChallengeVerifier(),
      sink: agentSink,
    );
    final String nonce = agentSink.take()['nonce']! as String;
    await agent.onText(await identity.register(nonce));
    expect(agentSink.take()['type'], 'registered');

    browserSink = FakeSink();
    browser = AppSession(
      broker: broker,
      sink: browserSink,
      address: '203.0.113.9',
    );
    browser.onText(
      encode(<String, Object?>{
        'type': 'subscribe',
        'protocol': relayProtocol,
        'fingerprint': identity.fingerprint,
      }),
    );
    expect(browserSink.take()['type'], 'subscribed');
  });

  tearDown(() {
    browser.onDone();
    agent.onDone();
  });

  test(
    'should keep a server that answers more requests than its frame rate',
    () async {
      for (int id = 1; id <= 20; id++) {
        browser.onText(encode(request(id, path: '/v1/hello')));
      }
      expect(agentSink.ofType('request'), hasLength(20));
      for (int id = 1; id <= 20; id++) {
        await agent.onText(encode(_response(id)));
      }
      expect(agentSink.closedCode, isNull);
      expect(browserSink.ofType('response'), hasLength(20));
      expect(broker.isOnline(identity.fingerprint), isTrue);
    },
  );

  test(
    'should count events and stray answers against the server frame rate',
    () async {
      browser.onText(encode(request(1, path: '/v1/events')));
      await agent.onText(encode(_response(1)));
      expect(browserSink.take()['status'], 200);

      await agent.onText(encode(_response(1)));
      await agent.onText(encode(_response(99)));
      await agent.onText(encode(_event(1)));
      expect(agentSink.closedCode, isNull);
      await agent.onText(encode(_event(1)));
      expect(agentSink.closedCode, RelayErrorCode.rateLimited);
      expect(browserSink.ofType('event'), hasLength(1));
      expect(broker.isOnline(identity.fingerprint), isFalse);
    },
  );
}
