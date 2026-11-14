/// Which tab of this browser edits a workspace. Two tabs writing one
/// workspace overwrite each other, and two tabs talking to one server for
/// the same workspace would both absorb its changes, so one tab holds each
/// workspace: another tab that opens it waits read-only ("Open in another
/// tab." with Take over), and takes over by itself once the first one closes
/// it or closes.
library;

import 'tab_lock_stub.dart' if (dart.library.js_interop) 'tab_lock_web.dart';

/// What a held workspace hears.
final class TabLockEvents {
  const TabLockEvents({
    required this.onHeld,
    required this.onWaiting,
    required this.onLost,
  });

  /// This tab holds the workspace: at once, after waiting, or after Take
  /// over.
  final void Function() onHeld;

  /// Another tab holds it; this tab waits read-only.
  final void Function() onWaiting;

  /// Another tab took it over; this tab stops writing at once.
  final Future<void> Function() onLost;
}

abstract interface class TabLock {
  /// Asks for [name]. Exactly one of [TabLockEvents.onHeld] or
  /// [TabLockEvents.onWaiting] runs first.
  void hold(String name, TabLockEvents events);

  /// Takes [name] from the tab that holds it.
  void takeOver(String name);

  /// Lets go of [name] (another workspace opened here).
  void release(String name);
}

TabLock createTabLock() => createPlatformTabLock();

/// A lock that never sees another tab (tests, browsers without Web Locks).
final class SoleTabLock implements TabLock {
  const SoleTabLock();

  @override
  void hold(String name, TabLockEvents events) => events.onHeld();

  @override
  void takeOver(String name) {}

  @override
  void release(String name) {}
}
