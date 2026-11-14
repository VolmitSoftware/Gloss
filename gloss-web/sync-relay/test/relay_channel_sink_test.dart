import 'dart:async';
import 'dart:convert';

import 'package:gloss_sync_relay/gloss_sync_relay.dart';
import 'package:test/test.dart';
import 'package:web_socket_channel/web_socket_channel.dart';

/// A socket whose reader takes each frame only when the test lets it.
final class _SlowSocket implements WebSocketSink {
  final List<String> delivered = <String>[];
  final List<Completer<void>> _waiting = <Completer<void>>[];
  final Completer<void> _done = Completer<void>();
  int? closeCode;
  String? closeReason;

  /// Lets the reader take up to [count] more frames.
  Future<void> read([int count = 1 << 30]) async {
    for (int index = 0; index < count && _waiting.isNotEmpty; index++) {
      _waiting.removeAt(0).complete();
      await Future<void>.delayed(Duration.zero);
    }
  }

  @override
  Future<void> addStream(Stream<dynamic> stream) async {
    final List<dynamic> frames = await stream.toList();
    final Completer<void> taken = Completer<void>();
    _waiting.add(taken);
    await taken.future;
    for (final dynamic frame in frames) {
      delivered.add(frame as String);
    }
  }

  @override
  void add(dynamic data) => delivered.add(data as String);

  @override
  void addError(Object error, [StackTrace? stackTrace]) {}

  @override
  Future<void> close([int? closeCode, String? closeReason]) async {
    this.closeCode = closeCode;
    this.closeReason = closeReason;
    if (!_done.isCompleted) _done.complete();
  }

  @override
  Future<void> get done => _done.future;
}

Future<void> _settle() async {
  for (int index = 0; index < 10; index++) {
    await Future<void>.delayed(Duration.zero);
  }
}

String _event(int index) => jsonEncode(<String, Object?>{
  'type': 'event',
  'id': 1,
  'event': <String, Object?>{'type': 'catalog', 'n': index},
});

void main() {
  test(
    'should deliver every frame in order to a reader that keeps up',
    () async {
      final _SlowSocket socket = _SlowSocket();
      final ChannelSink sink = ChannelSink(socket, 4);
      for (int index = 0; index < 10; index++) {
        sink.send(_event(index));
        await _settle();
        await socket.read();
      }
      await _settle();
      expect(socket.delivered, <String>[
        for (int index = 0; index < 10; index++) _event(index),
      ]);
      expect(socket.closeCode, isNull);
    },
  );

  test(
    'should close a slow reader with queue-full once its queue is full',
    () async {
      final _SlowSocket socket = _SlowSocket();
      final int limit = const RelayLimits().maxAppOutboundQueue;
      final ChannelSink sink = ChannelSink(socket, limit);
      // One frame is in the socket; [limit] more wait in the queue.
      for (int index = 0; index <= limit + 1; index++) {
        sink.send(_event(index));
      }
      sink.send(_event(9999));
      await _settle();
      await socket.read(1);
      await _settle();
      expect(socket.delivered, <String>[_event(0)]);
      expect(socket.closeCode, isNull, reason: 'the error frame goes first');
      await socket.read(1);
      await _settle();
      final Map<String, Object?> error =
          jsonDecode(socket.delivered.last) as Map<String, Object?>;
      expect(error['type'], 'error');
      expect(error['code'], RelayErrorCode.queueFull);
      expect(socket.delivered, hasLength(2), reason: 'the backlog is dropped');
      expect(socket.closeCode, policyCloseCode);
    },
  );

  test('should send nothing after it closed', () async {
    final _SlowSocket socket = _SlowSocket();
    final ChannelSink sink = ChannelSink(socket, 2);
    sink.closeWithError(RelayErrorCode.timeout, 'subscribe timed out');
    sink.send(_event(1));
    await _settle();
    await socket.read();
    await _settle();
    expect(socket.delivered, hasLength(1));
    expect(socket.closeCode, policyCloseCode);
  });
}
