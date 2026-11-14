package art.arcane.gloss.editor.transport;

import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * The transport-neutral {@code /gloss/v1} API. The direct listener and the relay client both call it, so
 * every check (tokens, scopes, counters, origins, limits) lives behind it. Implementations never block the
 * calling thread on server work.
 */
public interface EditorRequestHandler {
  /**
   * What a request head earns before its body is read: a refusal to send instead, or whether it carried a
   * valid token, which decides how large a body the transport accepts.
   */
  record Admission(Optional<EditorResponse> refusal, boolean authenticated) {
    public static final Admission OPEN = new Admission(Optional.empty(), false);
    public static final Admission AUTHENTICATED = new Admission(Optional.empty(), true);

    public static Admission refused(EditorResponse response) {
      return new Admission(Optional.of(response), false);
    }
  }

  /** Checks a request head (body empty) on the calling thread without blocking. */
  default Admission admit(EditorRequest head) {
    return Admission.OPEN;
  }

  /** Answers one request other than {@code GET /v1/events}. */
  CompletionStage<EditorResponse> handle(EditorRequest request);

  /**
   * Opens {@code GET /v1/events}. Over the relay the token is in the {@code Authorization} header; the direct
   * listener copies it from the browser's first {@code auth} frame. A 2xx response keeps the stream open.
   */
  CompletionStage<EditorEventStream> openEvents(EditorRequest request, EditorEventSink sink);
}
