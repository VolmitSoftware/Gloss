/// What the page is doing that background server work should yield to:
/// a hidden tab, or a bulk load of documents. Live position polling and the
/// background document fill read it; the browser keeps [hidden] current.
library;

abstract final class PageActivity {
  static int _bulk = 0;

  /// The tab is in the background.
  static bool hidden = false;

  /// A load of many documents is running.
  static bool get bulkLoading => _bulk > 0;

  static void beginBulk() => _bulk++;

  static void endBulk() {
    if (_bulk > 0) _bulk--;
  }

  /// Polling should wait: the tab is hidden or a bulk load runs.
  static bool get quiet => hidden || _bulk > 0;
}

/// A poll skipped because the page is quiet; the last answer stands.
class PollPaused implements Exception {
  const PollPaused();

  @override
  String toString() => 'Polling paused while the page is quiet.';
}
