package art.arcane.gloss.doc;

public final class DocumentEnvelope {
    public static final long INITIAL_REVISION = 1L;
    public static final long MAX_SAFE_REVISION = 9_007_199_254_740_991L;

    private DocumentEnvelope() {
    }

    public static int requireSchemaVersion(String kind, int schemaVersion, int supported) {
        if (schemaVersion != supported) {
            throw new UnsupportedSchemaVersionException(schemaVersion, supported);
        }
        return schemaVersion;
    }

    public static boolean isUnsupportedSchemaVersion(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof UnsupportedSchemaVersionException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    public static long requireRevision(String kind, long revision) {
        if (revision < INITIAL_REVISION || revision > MAX_SAFE_REVISION) {
            throw new IllegalArgumentException(kind + " revision must be between " + INITIAL_REVISION
                + " and " + MAX_SAFE_REVISION + ": " + revision);
        }
        return revision;
    }

    /** Names both numbers: the reader has to know which way the file needs to move. */
    private static final class UnsupportedSchemaVersionException extends IllegalArgumentException {
        private UnsupportedSchemaVersionException(int schemaVersion, int supported) {
            super("declares schemaVersion " + schemaVersion + "; this build reads " + supported);
        }
    }
}
