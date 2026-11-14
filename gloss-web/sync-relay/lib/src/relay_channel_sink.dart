import 'dart:async';
import 'dart:collection';
import 'dart:convert';

import 'package:gloss_sync_relay/src/relay_frame.dart';
import 'package:gloss_sync_relay/src/relay_sink.dart';
import 'package:web_socket_channel/web_socket_channel.dart';

/// Close code sent after a `relay-error` frame.
const int policyCloseCode = 4008;

/// A [RelaySink] over one WebSocket with a bounded send queue. A reader too
/// slow to keep the queue under its bound gets a `queue-full` `relay-error`
/// and a [policyCloseCode] close instead of an unbounded buffer.
final class ChannelSink implements RelaySink {
  final WebSocketSink _socket;
  final int _maximumQueued;
  final Queue<String> _queue = Queue<String>();
  bool _draining = false;
  bool _closing = false;
  bool _closed = false;

  ChannelSink(this._socket, this._maximumQueued);

  @override
  void send(String frame) {
    if (_closing || _closed) return;
    if (_queue.length >= _maximumQueued) {
      closeWithError(RelayErrorCode.queueFull, 'send queue is full');
      return;
    }
    _queue.add(frame);
    _drain();
  }

  @override
  void closeWithError(String code, String message) {
    if (_closing || _closed) return;
    _closing = true;
    if (_queue.length >= _maximumQueued) _queue.clear();
    _queue.add(jsonEncode(RelayFrames.error(code, message)));
    _drain();
  }

  void markClosed() {
    _closed = true;
    _closing = true;
    _queue.clear();
  }

  void _drain() {
    if (_draining || _closed) return;
    unawaited(_drainQueue());
  }

  /// Sends one frame at a time, so a slow reader fills [_queue] instead of an
  /// unbounded socket buffer.
  Future<void> _drainQueue() async {
    _draining = true;
    try {
      while (_queue.isNotEmpty && !_closed) {
        await _socket.addStream(Stream<String>.value(_queue.removeFirst()));
      }
    } on Object {
      markClosed();
    } finally {
      _draining = false;
    }
    if (_closing && !_closed && _queue.isEmpty) {
      _closed = true;
      await _socket.close(policyCloseCode, 'relay policy');
      return;
    }
    if (_queue.isNotEmpty && !_closed) _drain();
  }
}
