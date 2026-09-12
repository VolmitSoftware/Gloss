package art.arcane.gloss.importer;

import art.arcane.gloss.doc.AtomicFiles;
import art.arcane.gloss.editor.sync.EditorSyncDocumentKind;
import art.arcane.gloss.history.HistoryKinds;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Copies documents out of the data folder for backup or for hand-off.
 *
 * <p>The plain export is pretty JSON under {@code <dir>/<kind>/<id>.json} plus a
 * {@code workspace.json} index of what was written. The bundle export is the editor's own
 * {@code gloss-editor-workspace} shape, so a folder of documents can be dropped straight into the
 * web editor's workspace import.
 */
public final class ExportService {
    public static final String INDEX_FILE = "workspace.json";
    public static final String BUNDLE_FILE = "workspace-bundle.json";
    private static final String BUNDLE_FORMAT = "gloss-editor-workspace";
    private static final int BUNDLE_VERSION = 1;
    private static final int WORKSPACE_SCHEMA_VERSION = 2;
    private static final Gson GSON = new GsonBuilder()
            .serializeNulls()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create();
    private static final Map<String, String> EDITOR_KINDS = Map.ofEntries(
            Map.entry("menus", "menu"),
            Map.entry("previews", "containerPreview"),
            Map.entry("panels", "panel"),
            Map.entry("holograms", "hologram"),
            Map.entry("animations", "animation"),
            Map.entry("boards", "scoreboard"),
            Map.entry("motd", "motd"),
            Map.entry("emoji", "emoji"),
            Map.entry("bubbles", "bubbleStyle"),
            Map.entry("damage-indicators", "damageIndicators"),
            Map.entry("entity-overlays", "entityOverlays"),
            Map.entry("tablist", "tablist"),
            Map.entry("real-drops", "realDrops"));

    private final Path dataDirectory;
    private final SecureRandom random;

    public ExportService(Path dataDirectory) {
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory")
                .toAbsolutePath().normalize();
        this.random = new SecureRandom();
    }

    /** Every document on disk, keyed by collection then id. */
    public Map<String, Map<String, String>> read(Optional<String> kind, Optional<String> id)
            throws IOException {
        Map<String, Map<String, String>> documents = new TreeMap<>();
        for (EditorSyncDocumentKind documentKind : EditorSyncDocumentKind.ORDERED) {
            String collection = HistoryKinds.collection(documentKind);
            if (kind.isPresent() && !kind.get().equals(collection)) {
                continue;
            }
            Map<String, String> byId = readKind(documentKind);
            if (id.isPresent()) {
                String only = id.get();
                byId = byId.containsKey(only) ? Map.of(only, byId.get(only)) : Map.of();
            }
            if (!byId.isEmpty()) {
                documents.put(collection, byId);
            }
        }
        return Map.copyOf(documents);
    }

    public Result export(Path destination, Optional<String> kind, Optional<String> id)
            throws IOException {
        Map<String, Map<String, String>> documents = read(kind, id);
        Path target = requireDestination(destination);
        JsonArray index = new JsonArray();
        int written = 0;
        for (Map.Entry<String, Map<String, String>> collection : documents.entrySet()) {
            for (Map.Entry<String, String> document : collection.getValue().entrySet()) {
                Path file = target.resolve(collection.getKey())
                        .resolve(document.getKey() + ".json").normalize();
                if (!file.startsWith(target)) {
                    continue;
                }
                AtomicFiles.replace(file, pretty(document.getValue()));
                JsonObject entry = new JsonObject();
                entry.addProperty("kind", collection.getKey());
                entry.addProperty("id", document.getKey());
                entry.addProperty("path", collection.getKey() + "/" + document.getKey() + ".json");
                index.add(entry);
                written++;
            }
        }
        JsonObject workspace = new JsonObject();
        workspace.addProperty("format", "gloss-workspace-export");
        workspace.addProperty("version", 1);
        workspace.addProperty("exportedAt", java.time.Instant.now().toString());
        workspace.add("documents", index);
        AtomicFiles.replace(target.resolve(INDEX_FILE),
                (GSON.toJson(workspace) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
        return new Result(target, written, INDEX_FILE);
    }

    /** The editor's workspace bundle, ready for its Import workspace action. */
    public Result exportBundle(Path destination) throws IOException {
        Map<String, Map<String, String>> documents = read(Optional.empty(), Optional.empty());
        Path target = requireDestination(destination);
        JsonArray docs = new JsonArray();
        int written = 0;
        for (Map.Entry<String, Map<String, String>> collection : documents.entrySet()) {
            String editorKind = EDITOR_KINDS.get(collection.getKey());
            if (editorKind == null) {
                continue;
            }
            for (Map.Entry<String, String> document : collection.getValue().entrySet()) {
                JsonObject entry = new JsonObject();
                entry.addProperty("id", uuid());
                entry.addProperty("title", document.getKey());
                entry.addProperty("runtimeId", document.getKey());
                entry.addProperty("json", document.getValue());
                entry.addProperty("updatedAt", System.currentTimeMillis());
                entry.addProperty("kind", editorKind);
                entry.add("folderId", com.google.gson.JsonNull.INSTANCE);
                docs.add(entry);
                written++;
            }
        }
        JsonObject workspace = new JsonObject();
        workspace.addProperty("schemaVersion", WORKSPACE_SCHEMA_VERSION);
        workspace.addProperty("workspaceId", uuid());
        workspace.add("folders", new JsonArray());
        workspace.add("documents", docs);
        workspace.add("activeDocumentId", com.google.gson.JsonNull.INSTANCE);
        JsonObject bundle = new JsonObject();
        bundle.addProperty("format", BUNDLE_FORMAT);
        bundle.addProperty("version", BUNDLE_VERSION);
        bundle.add("workspace", workspace);
        bundle.add("images", images());
        AtomicFiles.replace(target.resolve(BUNDLE_FILE),
                (GSON.toJson(bundle) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
        return new Result(target, written, BUNDLE_FILE);
    }

    private JsonArray images() throws IOException {
        JsonArray images = new JsonArray();
        Path root = dataDirectory.resolve("images");
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return images;
        }
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.sorted(Comparator.comparing(Path::toString)).toList()) {
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                byte[] content = Files.readAllBytes(file);
                JsonObject image = new JsonObject();
                image.addProperty("path", root.relativize(file).toString()
                        .replace(java.io.File.separatorChar, '/'));
                image.addProperty("dataUri", "data:" + mediaType(file) + ";base64,"
                        + Base64.getEncoder().encodeToString(content));
                image.addProperty("width", 0);
                image.addProperty("height", 0);
                images.add(image);
            }
        }
        return images;
    }

    private Map<String, String> readKind(EditorSyncDocumentKind kind) throws IOException {
        Map<String, String> documents = new TreeMap<>();
        if (kind.layout() == EditorSyncDocumentKind.Layout.SINGLE) {
            Path file = dataDirectory.resolve(kind.storageName());
            if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                documents.put(kind.singletonId(), Files.readString(file, StandardCharsets.UTF_8));
            }
            return documents;
        }
        Path root = dataDirectory.resolve(kind.storageName());
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return documents;
        }
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.sorted(Comparator.comparing(Path::toString)).toList()) {
                String name = file.getFileName().toString();
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                        || !name.endsWith(".json")) {
                    continue;
                }
                String relative = root.relativize(file).toString()
                        .replace(java.io.File.separatorChar, '/');
                documents.put(relative.substring(0, relative.length() - 5),
                        Files.readString(file, StandardCharsets.UTF_8));
            }
        }
        return documents;
    }

    private Path requireDestination(Path destination) throws IOException {
        Path target = destination.toAbsolutePath().normalize();
        Files.createDirectories(target);
        return target;
    }

    private static byte[] pretty(String source) {
        JsonElement parsed = JsonParser.parseString(source);
        return (GSON.toJson(parsed) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
    }

    private static String mediaType(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (name.endsWith(".gif")) {
            return "image/gif";
        }
        if (name.endsWith(".webp")) {
            return "image/webp";
        }
        if (name.endsWith(".bmp")) {
            return "image/bmp";
        }
        return "image/png";
    }

    private String uuid() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        bytes[6] = (byte) ((bytes[6] & 0x0f) | 0x40);
        bytes[8] = (byte) ((bytes[8] & 0x3f) | 0x80);
        String hex = HexFormat.of().formatHex(bytes);
        return hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16)
                + "-" + hex.substring(16, 20) + "-" + hex.substring(20);
    }

    /** Where the export landed and how much it carried. */
    public record Result(Path directory, int documents, String indexFile) {
        public List<String> summary() {
            return List.of(directory.toString(), Integer.toString(documents), indexFile);
        }
    }
}
