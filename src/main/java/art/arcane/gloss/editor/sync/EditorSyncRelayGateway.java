package art.arcane.gloss.editor.sync;

import java.util.List;
import java.util.concurrent.CompletableFuture;

interface EditorSyncRelayGateway {
  CompletableFuture<EditorSyncRelayClient.RelayCreated> create(
      String endpoint, String createToken, EditorSyncProject project, int expiresInSeconds);

  CompletableFuture<EditorSyncRelayClient.RelayPoll> poll(EditorSyncStoredSession session);

  CompletableFuture<Void> answerHistory(EditorSyncStoredSession session,
                                        EditorSyncRelayClient.HistoryRequest request, String json);

  CompletableFuture<Void> acknowledge(EditorSyncStoredSession session, long revision,
                                      String status, String message,
                                      EditorSyncProject serverProject,
                                      List<EditorSyncPendingAck.Conflict> conflicts);

  CompletableFuture<Void> revoke(EditorSyncStoredSession session);

  default void cancelActiveRequests() {
  }

  default void close() {
    cancelActiveRequests();
  }
}
