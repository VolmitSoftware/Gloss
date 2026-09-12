package art.arcane.gloss.glosspack;

import java.util.Objects;

/** What installing, updating or removing a pack would do to one file. */
public final class GlossPackPreview {
    private GlossPackPreview() {
    }

    public enum Disposition {
        /** The file does not exist yet and the pack would write it. */
        CREATE,
        /** The file is the pack's own untouched copy and would be replaced. */
        UPDATE,
        /** The operator edited the file, so it stays and the pack version lands beside it. */
        KEEP_MODIFIED,
        /** The id already belongs to something this pack does not own; the install refuses. */
        CONFLICT,
        /** This server cannot take the file: unknown kind, unsupported schema, missing dependency. */
        SKIP_REQUIREMENT,
        /** A {@code source: server} command action was removed before the file was written. */
        STRIP_SERVER_COMMAND
    }

    public record Outcome(String path, Disposition disposition, String reason) {
        public Outcome {
            path = Objects.requireNonNull(path, "path");
            disposition = Objects.requireNonNull(disposition, "disposition");
            reason = reason == null ? "" : reason;
        }

        public String wire() {
            return disposition.name().toLowerCase(java.util.Locale.ROOT) + "|" + path
                    + (reason.isBlank() ? "" : "|" + reason);
        }
    }
}
