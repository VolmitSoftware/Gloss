package art.arcane.gloss.condition;

public final class RoleSnapshotPendingException extends RuntimeException {
    public RoleSnapshotPendingException() {
        super("The owning region has not published this role value yet", null, false, false);
    }
}
