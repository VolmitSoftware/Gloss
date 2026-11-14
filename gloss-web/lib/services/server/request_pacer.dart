/// Keeps one connection's requests within the server's rate limit
/// (`[editor] requestsPerSecond`, 20 by default, bursting to twice that, per
/// token), so a burst of terrain tiles or batch reads queues here instead of
/// drawing `429 rate-limited` answers; it stays a little under both. After a 429 every request waits out
/// the server's hint. [RequestSlots] keeps it within the server's in-flight
/// limit per token (`[editor] concurrentRequestsPerToken`).
library;

import 'dart:async';
import 'dart:math' as math;

class RequestPacer {
  RequestPacer({this.perSecond = 20}) : _tokens = perSecond * 2.0 - _headroom;

  /// The server's requests per second; it bursts to twice that.
  final int perSecond;

  /// Requests leave a little under the server's rate and burst: on the way
  /// they bunch up (a busy relay, a slow tick), and at the exact limit a
  /// bunch arriving together drew an occasional 429.
  static const double _margin = 0.9;
  static const double _headroom = 2;

  double get _rate => perSecond * _margin;
  double get _burst => perSecond * 2.0 - _headroom;
  double _tokens;
  final Stopwatch _clock = Stopwatch()..start();
  int _last = 0;
  int _heldUntil = 0;
  Future<void> _turn = Future<void>.value();

  /// Completes when the next request may go, in the order asked.
  Future<void> take() {
    final Future<void> turn = _turn.then((void _) => _wait());
    _turn = turn;
    return turn;
  }

  /// The server refused a request for [wait]: nothing goes before it passes.
  void hold(Duration wait) {
    _heldUntil = math.max(
      _heldUntil,
      _clock.elapsedMicroseconds + wait.inMicroseconds,
    );
    _tokens = 0;
  }

  Future<void> _wait() async {
    while (true) {
      final int now = _clock.elapsedMicroseconds;
      _tokens = math.min(_burst, _tokens + (now - _last) / 1e6 * _rate);
      _last = now;
      final int held = _heldUntil - now;
      if (held <= 0 && _tokens >= 1) {
        _tokens -= 1;
        return;
      }
      await Future<void>.delayed(
        Duration(
          microseconds: math.max(held, ((1 - _tokens) / _rate * 1e6).ceil()),
        ),
      );
    }
  }
}

/// At most [size] requests in flight; the rest wait in the order they asked.
class RequestSlots {
  RequestSlots(this.size);

  final int size;
  int _taken = 0;
  final List<Completer<void>> _waiting = <Completer<void>>[];

  /// Completes when a slot is free; [release] it once the answer is in.
  Future<void> take() {
    if (_taken < size) {
      _taken++;
      return Future<void>.value();
    }
    final Completer<void> slot = Completer<void>();
    _waiting.add(slot);
    return slot.future;
  }

  /// Hands the slot to the oldest waiting request, else frees it.
  void release() {
    if (_waiting.isNotEmpty) {
      _waiting.removeAt(0).complete();
    } else {
      _taken--;
    }
  }
}
