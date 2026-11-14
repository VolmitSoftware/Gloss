/// One WebSocket connection as the broker sees it.
abstract interface class RelaySink {
  /// Queues one encoded JSON frame. Frames after [closeWithError] are dropped.
  void send(String frame);

  /// Sends a `relay-error` frame and closes the connection.
  void closeWithError(String code, String message);
}
