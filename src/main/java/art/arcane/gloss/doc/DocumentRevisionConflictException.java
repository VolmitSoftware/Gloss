package art.arcane.gloss.doc;

import java.util.Objects;

/**
 * Raised when a document on disk moved off the revision the caller read. Revisions are carried as
 * text because the stores disagree on their shape: menus revision by content hash, every other
 * document by a monotonic counter.
 */
public final class DocumentRevisionConflictException extends IllegalStateException {
    private final String kind;
    private final String id;
    private final String expectedRevision;
    private final String actualRevision;

    public DocumentRevisionConflictException(String kind, String id, String expectedRevision,
                                             String actualRevision) {
        super(kind + " " + id + " is at revision " + actualRevision + ", expected " + expectedRevision);
        this.kind = Objects.requireNonNull(kind, "kind");
        this.id = id;
        this.expectedRevision = expectedRevision;
        this.actualRevision = actualRevision;
    }

    public DocumentRevisionConflictException(String kind, String id, long expectedRevision,
                                             long actualRevision) {
        this(kind, id, Long.toString(expectedRevision), Long.toString(actualRevision));
    }

    public String kind() {
        return kind;
    }

    public String id() {
        return id;
    }

    public String expectedRevision() {
        return expectedRevision;
    }

    public String actualRevision() {
        return actualRevision;
    }
}
