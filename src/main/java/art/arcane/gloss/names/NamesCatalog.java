package art.arcane.gloss.names;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class NamesCatalog {
    private static final int CACHE_LIMIT = 4096;

    private final NamesDoc document;
    private final Map<String, String> fallbackNames = new ConcurrentHashMap<>();

    public NamesCatalog(NamesDoc document) {
        this.document = Objects.requireNonNull(document, "document");
    }

    public String resolve(NameCategory category, String key) {
        String normalized = category.normalize(key);
        if (normalized.isEmpty()) {
            return "";
        }
        String authored = document.values(category).get(normalized);
        if (authored != null) {
            return authored;
        }
        String cached = fallbackNames.get(normalized);
        if (cached != null) {
            return cached;
        }
        String name = NameFallback.titleCase(normalized);
        if (fallbackNames.size() < CACHE_LIMIT) {
            fallbackNames.putIfAbsent(normalized, name);
        }
        return name;
    }

}
