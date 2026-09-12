package art.arcane.gloss.history;

import java.nio.file.Path;
import java.util.Objects;

/** One stored copy of a document: when it was taken, what took it, and where the bytes live. */
public record HistoryEntry(String kind, String id, long epochMillis, String source, long bytes,
                           Path file) {
    public HistoryEntry {
        kind = Objects.requireNonNull(kind, "kind");
        id = Objects.requireNonNull(id, "id");
        source = Objects.requireNonNull(source, "source");
        file = Objects.requireNonNull(file, "file");
        if (epochMillis < 0L) {
            throw new IllegalArgumentException("history epochMillis must not be negative");
        }
        if (bytes < 0L) {
            throw new IllegalArgumentException("history bytes must not be negative");
        }
    }

    /** The version token operators type into {@code /gloss restore}. */
    public String version() {
        return Long.toString(epochMillis);
    }
}
