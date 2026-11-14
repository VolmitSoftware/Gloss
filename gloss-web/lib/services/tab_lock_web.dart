library;

import 'dart:async';
import 'dart:js_interop';

import 'package:web/web.dart' as web;

import 'tab_lock.dart';

TabLock createPlatformTabLock() {
  try {
    final web.LockManager locks = web.window.navigator.locks;
    return _WebTabLock(locks);
  } catch (_) {
    return const SoleTabLock();
  }
}

/// One held or waiting request.
final class _Claim {
  _Claim(this.events);

  final TabLockEvents events;
  Completer<void>? held;
  web.AbortController? waiting;
  bool lost = false;
}

/// Web Locks: the browser grants a lock to one tab, queues the others, hands
/// it on when the holder lets go or closes, and lets a tab steal it.
final class _WebTabLock implements TabLock {
  _WebTabLock(this._locks);

  final web.LockManager _locks;
  final Map<String, _Claim> _claims = <String, _Claim>{};

  static String _lockName(String name) => 'gloss.workspace.$name';

  /// Requests [name]; [granted] hears whether this request got it. When
  /// the lock it got is stolen later, the claim's [TabLockEvents.onLost]
  /// runs. A queued request withdrawn before it was granted says nothing.
  void _request(
    String name,
    web.LockOptions options,
    _Claim claim,
    void Function(bool granted) granted,
  ) {
    Completer<void>? mine;
    final JSFunction callback = ((web.Lock? lock) {
      if (lock == null) {
        granted(false);
        return null;
      }
      final Completer<void> held = Completer<void>();
      mine = held;
      claim.held = held;
      granted(true);
      return held.future.toJS;
    }).toJS;
    unawaited(
      _locks
          .request(_lockName(name), options, callback)
          .toDart
          .then(
            (JSAny? _) {},
            onError: (Object _) {
              final Completer<void>? held = mine;
              if (held == null ||
                  held.isCompleted ||
                  !identical(_claims[name], claim) ||
                  !identical(claim.held, held)) {
                return;
              }
              claim.held = null;
              claim.lost = true;
              unawaited(claim.events.onLost());
              // Wait in line again: this tab takes over by itself once the
              // other one lets go.
              _queue(name, claim);
            },
          ),
    );
  }

  @override
  void hold(String name, TabLockEvents events) {
    release(name);
    final _Claim claim = _Claim(events);
    _claims[name] = claim;
    _request(
      name,
      web.LockOptions(mode: 'exclusive', ifAvailable: true),
      claim,
      (bool granted) {
        if (!identical(_claims[name], claim)) return;
        if (granted) {
          events.onHeld();
        } else {
          events.onWaiting();
          _queue(name, claim);
        }
      },
    );
  }

  void _queue(String name, _Claim claim) {
    final web.AbortController abort = web.AbortController();
    claim.waiting = abort;
    _request(
      name,
      web.LockOptions(mode: 'exclusive', signal: abort.signal),
      claim,
      (bool granted) {
        claim.waiting = null;
        if (granted && identical(_claims[name], claim)) claim.events.onHeld();
      },
    );
  }

  @override
  void takeOver(String name) {
    final _Claim? claim = _claims[name];
    if (claim == null || claim.held != null) return;
    claim.waiting?.abort();
    claim.waiting = null;
    _request(name, web.LockOptions(mode: 'exclusive', steal: true), claim, (
      bool granted,
    ) {
      if (granted && identical(_claims[name], claim)) claim.events.onHeld();
    });
  }

  @override
  void release(String name) {
    final _Claim? claim = _claims.remove(name);
    if (claim == null) return;
    claim.waiting?.abort();
    final Completer<void>? held = claim.held;
    claim.held = null;
    if (held != null && !held.isCompleted) held.complete();
  }
}
