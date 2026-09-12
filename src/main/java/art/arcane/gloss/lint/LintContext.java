package art.arcane.gloss.lint;

import art.arcane.gloss.editor.sync.EditorSyncDocumentKind;
import art.arcane.gloss.history.HistoryKinds;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Everything the rules read, captured once so they can run off the main thread.
 *
 * <p>Documents are held as parsed JSON rather than as their typed records: the registries already
 * refuse anything that does not parse, and the rules are about references between documents, not
 * about shapes.
 */
public final class LintContext {
    private final Map<String, Map<String, JsonElement>> documents;
    private final Set<String> imagePaths;
    private final Set<String> declaredPermissions;
    private final Set<String> papiExpansions;
    private final Set<String> publishedMetricKeys;
    private final Map<String, String> skippedSchemaDocuments;
    private final Set<String> packOwnedPaths;

    private LintContext(Builder builder) {
        Map<String, Map<String, JsonElement>> byKind = new TreeMap<>();
        builder.documents.forEach((kind, byId) -> byKind.put(kind, Map.copyOf(byId)));
        this.documents = Map.copyOf(byKind);
        this.imagePaths = Set.copyOf(builder.imagePaths);
        this.declaredPermissions = Set.copyOf(builder.declaredPermissions);
        this.papiExpansions = Set.copyOf(builder.papiExpansions);
        this.publishedMetricKeys = Set.copyOf(builder.publishedMetricKeys);
        this.skippedSchemaDocuments = Map.copyOf(builder.skippedSchemaDocuments);
        this.packOwnedPaths = Set.copyOf(builder.packOwnedPaths);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Reads a data folder straight from disk, which is what {@code /gloss check} does on a fixture. */
    public static LintContext fromDataDirectory(Path dataDirectory) throws IOException {
        Builder builder = builder();
        Path data = dataDirectory.toAbsolutePath().normalize();
        for (EditorSyncDocumentKind kind : EditorSyncDocumentKind.ORDERED) {
            readKind(builder, data, kind);
        }
        Path images = data.resolve("images");
        if (Files.isDirectory(images, LinkOption.NOFOLLOW_LINKS)) {
            try (Stream<Path> files = Files.walk(images)) {
                for (Path file : files.toList()) {
                    if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                        builder.image(images.relativize(file).toString()
                                .replace(java.io.File.separatorChar, '/'));
                    }
                }
            }
        }
        return builder.build();
    }

    private static void readKind(Builder builder, Path data, EditorSyncDocumentKind kind)
            throws IOException {
        String collection = HistoryKinds.collection(kind);
        if (kind.layout() == EditorSyncDocumentKind.Layout.SINGLE) {
            Path file = data.resolve(kind.storageName());
            if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                builder.document(collection, kind.singletonId(), read(file));
            }
            return;
        }
        Path root = data.resolve(kind.storageName());
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.sorted(Comparator.comparing(Path::toString)).toList()) {
                String name = file.getFileName().toString();
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !name.endsWith(".json")) {
                    continue;
                }
                String relative = root.relativize(file).toString()
                        .replace(java.io.File.separatorChar, '/');
                builder.document(collection, relative.substring(0, relative.length() - 5),
                        read(file));
            }
        }
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    public Map<String, JsonElement> documents(String kind) {
        return documents.getOrDefault(kind, Map.of());
    }

    public Set<String> kinds() {
        return documents.keySet();
    }

    public Set<String> ids(String kind) {
        return documents(kind).keySet();
    }

    public boolean hasKind(String kind) {
        return documents.containsKey(kind);
    }

    public Set<String> imagePaths() {
        return imagePaths;
    }

    public Set<String> declaredPermissions() {
        return declaredPermissions;
    }

    public Set<String> papiExpansions() {
        return papiExpansions;
    }

    public Set<String> publishedMetricKeys() {
        return publishedMetricKeys;
    }

    public Map<String, String> skippedSchemaDocuments() {
        return skippedSchemaDocuments;
    }

    public Set<String> packOwnedPaths() {
        return packOwnedPaths;
    }

    /** Every (kind, id, json) triple, in kind then id order. */
    public List<Document> all() {
        List<Document> all = new ArrayList<>();
        documents.forEach((kind, byId) -> new TreeMap<>(byId)
                .forEach((id, json) -> all.add(new Document(kind, id, json))));
        return List.copyOf(all);
    }

    public record Document(String kind, String id, JsonElement json) {
    }

    public static final class Builder {
        private final Map<String, Map<String, JsonElement>> documents = new TreeMap<>();
        private final Set<String> imagePaths = new LinkedHashSet<>();
        private final Set<String> declaredPermissions = new LinkedHashSet<>();
        private final Set<String> papiExpansions = new LinkedHashSet<>();
        private final Set<String> publishedMetricKeys = new LinkedHashSet<>();
        private final Map<String, String> skippedSchemaDocuments = new LinkedHashMap<>();
        private final Set<String> packOwnedPaths = new LinkedHashSet<>();

        private Builder() {
        }

        public Builder document(String kind, String id, String rawJson) {
            JsonElement parsed;
            try {
                parsed = JsonParser.parseString(Objects.requireNonNull(rawJson, "rawJson"));
            } catch (RuntimeException notJson) {
                return this;
            }
            documents.computeIfAbsent(kind, ignored -> new TreeMap<>()).put(id, parsed);
            return this;
        }

        public Builder image(String relativePath) {
            imagePaths.add(relativePath);
            return this;
        }

        public Builder permissions(java.util.Collection<String> nodes) {
            declaredPermissions.addAll(nodes);
            return this;
        }

        public Builder papiExpansions(java.util.Collection<String> expansions) {
            papiExpansions.addAll(expansions);
            return this;
        }

        public Builder metricKeys(java.util.Collection<String> keys) {
            publishedMetricKeys.addAll(keys);
            return this;
        }

        public Builder skippedSchema(String kind, String id, String reason) {
            skippedSchemaDocuments.put(kind + "/" + id, reason);
            return this;
        }

        public Builder packOwned(java.util.Collection<String> relativePaths) {
            packOwnedPaths.addAll(relativePaths);
            return this;
        }

        public LintContext build() {
            return new LintContext(this);
        }
    }
}
