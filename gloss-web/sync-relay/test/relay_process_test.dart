@Timeout(Duration(minutes: 2))
library;

import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:test/test.dart';
import 'package:web_socket_channel/io.dart';

import 'support.dart';

/// The relay as deployed: `bin/relay.dart` in its own process, stopped by a
/// signal the way a service manager or `pkill` stops it.
void main() {
  for (final ProcessSignal signal in <ProcessSignal>[
    ProcessSignal.sigterm,
    ProcessSignal.sigint,
  ]) {
    test(
      'should close its sockets with going away and exit on $signal',
      () async {
        final ServerSocket probe = await ServerSocket.bind('127.0.0.1', 0);
        final int port = probe.port;
        await probe.close();
        final Process relay = await Process.start(
          Platform.resolvedExecutable,
          <String>['run', 'bin/relay.dart'],
          environment: <String, String>{
            'PORT': '$port',
            'BIND_ADDRESS': '127.0.0.1',
            'ALLOWED_APP_ORIGINS': 'http://127.0.0.1:8098',
            'TRUSTED_PROXY_HOPS': '0',
          },
        );
        final Completer<void> listening = Completer<void>();
        relay.stdout.transform(utf8.decoder).listen((String text) {
          if (text.contains('listening') && !listening.isCompleted) {
            listening.complete();
          }
        });
        relay.stderr.drain<void>();
        try {
          await listening.future.timeout(const Duration(seconds: 60));
          final TestServer identity = await TestServer.create();
          final IOWebSocketChannel agent = IOWebSocketChannel.connect(
            Uri.parse('ws://127.0.0.1:$port/agent'),
          );
          await agent.ready;
          final StreamIterator<Object?> frames = StreamIterator<Object?>(
            agent.stream,
          );
          expect(await frames.moveNext(), isTrue);
          final Map<String, Object?> challenge =
              jsonDecode(frames.current! as String) as Map<String, Object?>;
          agent.sink.add(
            await identity.register(challenge['nonce']! as String),
          );
          expect(await frames.moveNext(), isTrue);
          expect(
            (jsonDecode(frames.current! as String)
                as Map<String, Object?>)['type'],
            'registered',
          );

          final Stopwatch stopping = Stopwatch()..start();
          relay.kill(signal);
          final int exitCode = await relay.exitCode.timeout(
            const Duration(seconds: 3),
          );
          stopping.stop();
          while (await frames.moveNext().timeout(const Duration(seconds: 2))) {}

          expect(exitCode, 0);
          expect(agent.closeCode, WebSocketStatus.goingAway);
          expect(stopping.elapsed, lessThan(const Duration(seconds: 3)));
        } finally {
          relay.kill(ProcessSignal.sigkill);
        }
      },
    );
  }
}
