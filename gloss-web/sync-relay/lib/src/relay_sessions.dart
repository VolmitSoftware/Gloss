import 'dart:async';
import 'dart:convert';

import 'package:gloss_sync_relay/src/relay_broker.dart';
import 'package:gloss_sync_relay/src/relay_frame.dart';
import 'package:gloss_sync_relay/src/relay_sink.dart';
import 'package:gloss_sync_relay/src/server_identity.dart';

/// Counts frames in fixed windows.
final class MessageRateLimiter {
  final int _maximum;
  final Duration _window;
  final Stopwatch _clock = Stopwatch()..start();
  int _count = 0;

  MessageRateLimiter(this._maximum, this._window);

  bool allow() {
    if (_clock.elapsed >= _window) {
      _clock
        ..reset()
        ..start();
      _count = 0;
    }
    if (_count >= _maximum) return false;
    _count++;
    return true;
  }
}

/// One `/agent` connection: challenge, signed registration, then responses,
/// events and closes for its requests.
final class AgentSession {
  final RelayBroker _broker;
  final ChallengeVerifier _verifier;
  final RelaySink _sink;
  final MessageRateLimiter _rate;
  final String _nonce;
  late final Timer _handshakeTimer;
  bool _answered = false;
  bool _closed = false;
  String? _fingerprint;

  AgentSession({
    required RelayBroker broker,
    required ChallengeVerifier verifier,
    required this._sink,
  }) : _broker = broker,
       _verifier = verifier,
       _rate = MessageRateLimiter(
         broker.limits.maxAgentMessagesPerWindow,
         broker.limits.messageRateWindow,
       ),
       _nonce = verifier.issueNonce() {
    _handshakeTimer = Timer(
      broker.limits.handshakeTimeout,
      () => _fail(RelayErrorCode.timeout, 'registration timed out'),
    );
    _sink.send(jsonEncode(RelayFrames.challenge(_nonce)));
  }

  String? get fingerprint => _fingerprint;

  Future<void> onText(String text) async {
    if (_closed) return;
    final AgentFrame frame;
    try {
      frame = RelayFrames.decodeAgent(text, _broker.limits.maxFrameBytes);
    } on FrameException catch (error) {
      return _fail(error.code, error.message);
    }
    final String? fingerprint = _fingerprint;
    // Answers are bounded by the requests browsers sent, so only other frames
    // spend the server's budget.
    final bool answer =
        fingerprint != null &&
        frame is ResponseFrame &&
        _broker.awaitsResponse(fingerprint, _sink, frame.id);
    if (!answer && !_rate.allow()) {
      return _fail(RelayErrorCode.rateLimited, 'message rate limit exceeded');
    }
    if (fingerprint == null) {
      if (frame is! RegisterFrame || _answered) {
        return _fail(RelayErrorCode.handshakeFailed, 'register first');
      }
      _answered = true;
      final bool valid = await _verifier.verify(
        nonce: _nonce,
        serverKey: frame.serverKey,
        signature: frame.signature,
      );
      if (_closed) return;
      if (!valid) {
        return _fail(RelayErrorCode.handshakeFailed, 'signature is not valid');
      }
      final String registered = fingerprintOf(
        decodeBase64Url(frame.serverKey)!,
      );
      if (!_broker.registerServer(registered, _sink)) {
        return _fail(RelayErrorCode.capacity, 'the relay is full');
      }
      _handshakeTimer.cancel();
      _fingerprint = registered;
      _sink.send(jsonEncode(RelayFrames.registered(registered)));
      return;
    }
    if (frame is RegisterFrame) {
      return _fail(RelayErrorCode.invalidFrame, 'already registered');
    }
    _broker.routeFromAgent(fingerprint, _sink, frame);
  }

  void onDone() {
    if (_closed) return;
    _closed = true;
    _handshakeTimer.cancel();
    final String? fingerprint = _fingerprint;
    if (fingerprint != null) _broker.unregisterServer(fingerprint, _sink);
  }

  void _fail(String code, String message) {
    if (_closed) return;
    onDone();
    _sink.closeWithError(code, message);
  }
}

/// One `/app` connection: a subscription to one server, then requests and
/// closes. A browser that has no event stream accepted by the server within
/// `signInTimeout` of subscribing is closed, so idle subscribers cannot hold a
/// server's browser slots.
final class AppSession {
  final RelayBroker _broker;
  final RelaySink _sink;
  final MessageRateLimiter _rate;
  final String? _origin;
  final String? _address;
  late final Timer _subscriptionTimer;
  Timer? _signInTimer;
  bool _closed = false;

  AppSession({
    required RelayBroker broker,
    required this._sink,
    this._origin,
    this._address,
  }) : _broker = broker,
       _rate = MessageRateLimiter(
         broker.limits.maxAppMessagesPerWindow,
         broker.limits.messageRateWindow,
       ) {
    _subscriptionTimer = Timer(
      broker.limits.subscriptionTimeout,
      () => _fail(RelayErrorCode.timeout, 'subscribe timed out'),
    );
  }

  void onText(String text) {
    if (_closed) return;
    if (!_rate.allow()) {
      return _fail(RelayErrorCode.rateLimited, 'message rate limit exceeded');
    }
    final AppFrame frame;
    try {
      frame = RelayFrames.decodeApp(text, _broker.limits.maxFrameBytes);
    } on FrameException catch (error) {
      return _fail(error.code, error.message);
    }
    if (!_broker.isSubscribed(_sink)) {
      if (frame is! SubscribeFrame) {
        return _fail(RelayErrorCode.invalidFrame, 'subscribe first');
      }
      final String? refused = _broker.subscribeApp(
        _sink,
        frame.fingerprint,
        origin: _origin,
        address: _address,
      );
      if (refused != null) return _fail(refused, 'subscription refused');
      _subscriptionTimer.cancel();
      _signInTimer = Timer(_broker.limits.signInTimeout, () {
        if (!_broker.isSignedIn(_sink)) {
          _fail(RelayErrorCode.timeout, 'no event stream opened in time');
        }
      });
      return;
    }
    if (frame is SubscribeFrame) {
      return _fail(RelayErrorCode.invalidFrame, 'already subscribed');
    }
    _broker.routeFromApp(_sink, frame);
  }

  void onDone() {
    if (_closed) return;
    _closed = true;
    _subscriptionTimer.cancel();
    _signInTimer?.cancel();
    _broker.unregisterApp(_sink);
  }

  void _fail(String code, String message) {
    if (_closed) return;
    onDone();
    _sink.closeWithError(code, message);
  }
}
