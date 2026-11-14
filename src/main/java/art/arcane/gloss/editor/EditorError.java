package art.arcane.gloss.editor;

import com.google.gson.JsonObject;

import java.util.Objects;

/** A refused editor request: an error code and the HTTP status it answers with. */
public final class EditorError extends Exception {
  public enum Code {
    UNAUTHORIZED("unauthorized", 401),
    TOKEN_EXPIRED("token-expired", 401),
    TOKEN_REVOKED("token-revoked", 401),
    SCOPE_MISSING("scope-missing", 403),
    FOLDER_DENIED("folder-denied", 403),
    ORIGIN_NOT_ALLOWED("origin-not-allowed", 403),
    COUNTER_REPLAYED("counter-replayed", 409),
    CONFIRM_DELETES("confirm-deletes", 428),
    PAIRING_INVALID("pairing-invalid", 401),
    PAIRING_EXPIRED("pairing-expired", 410),
    NOT_FOUND("not-found", 404),
    INVALID_REQUEST("invalid-request", 400),
    TOO_LARGE("too-large", 413),
    RATE_LIMITED("rate-limited", 429),
    BUSY("busy", 503),
    UNSUPPORTED_PROTOCOL("unsupported-protocol", 400),
    SERVER_OFFLINE("server-offline", 503),
    TIMEOUT("timeout", 504),
    INTERNAL("internal", 500);

    private final String wireName;
    private final int status;

    Code(String wireName, int status) {
      this.wireName = wireName;
      this.status = status;
    }

    public String wireName() {
      return wireName;
    }

    public int status() {
      return status;
    }
  }

  private final Code code;
  private final long retryAfterMillis;
  private final JsonObject extra;

  public EditorError(Code code, String message) {
    this(code, message, -1L, null);
  }

  /** {@code extra} holds fields added to the error object, such as {@code counter} or {@code scope}. */
  public EditorError(Code code, String message, long retryAfterMillis, JsonObject extra) {
    super(Objects.requireNonNull(message), null, false, false);
    this.code = Objects.requireNonNull(code);
    this.retryAfterMillis = retryAfterMillis;
    this.extra = extra == null ? null : extra.deepCopy();
  }

  public Code code() {
    return code;
  }

  public long retryAfterMillis() {
    return retryAfterMillis;
  }

  public JsonObject extra() {
    return extra == null ? null : extra.deepCopy();
  }
}
