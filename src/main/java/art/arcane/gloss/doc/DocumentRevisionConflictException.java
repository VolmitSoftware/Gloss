package art.arcane.gloss.doc;

public final class DocumentRevisionConflictException extends RuntimeException {
    private final String id;
    private final long expectedRevision;
    private final long actualRevision;

    public DocumentRevisionConflictException(String id, long expectedRevision, long actualRevision) {
        super("document " + id + " is at revision " + actualRevision + ", expected " + expectedRevision);
        this.id = id;
        this.expectedRevision = expectedRevision;
        this.actualRevision = actualRevision;
    }

    public String id() {
        return id;
    }

    public long expectedRevision() {
        return expectedRevision;
    }

    public long actualRevision() {
        return actualRevision;
    }
}
