package art.arcane.gloss.doc;

import java.util.Objects;

public record GlossDocument<T>(String id, String contentHash, String raw, T value, long revision) {
    public GlossDocument {
        id = Objects.requireNonNull(id, "id");
        contentHash = Objects.requireNonNull(contentHash, "contentHash");
        raw = Objects.requireNonNull(raw, "raw");
        value = Objects.requireNonNull(value, "value");
    }

    public static <T> GlossDocument<T> of(String id, String raw, T value, long revision) {
        return new GlossDocument<>(id, DocumentHashes.sha256(raw), raw, value, revision);
    }
}
