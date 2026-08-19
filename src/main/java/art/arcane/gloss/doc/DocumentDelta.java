package art.arcane.gloss.doc;

import java.util.List;

public record DocumentDelta(List<String> loaded, List<String> removed) {
    public static final DocumentDelta EMPTY = new DocumentDelta(List.of(), List.of());

    public DocumentDelta {
        loaded = List.copyOf(loaded);
        removed = List.copyOf(removed);
    }

    public boolean isEmpty() {
        return loaded.isEmpty() && removed.isEmpty();
    }
}
