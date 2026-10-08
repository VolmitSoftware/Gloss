package art.arcane.gloss.doc;

import art.arcane.gloss.menu.action.ActionReferences;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

public final class DocumentStore<T> {
    private static final String EXTENSION = ".json";

    private final String kind;
    private final File folder;
    private final DocumentReviser<T> reviser;
    private final Map<String, String> writtenHashes;

    public DocumentStore(String kind, File folder, DocumentReviser<T> reviser) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.folder = Objects.requireNonNull(folder, "folder");
        this.reviser = Objects.requireNonNull(reviser, "reviser");
        this.writtenHashes = new ConcurrentHashMap<>();
    }

    public File fileFor(String id) {
        return new File(folder, requireSafeId(id) + EXTENSION);
    }

    public void write(String id, T value) throws IOException {
        Objects.requireNonNull(value, "value");
        File file = fileFor(id);
        byte[] encoded = (encode(file, value) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
        writtenHashes.put(file.getAbsolutePath(), DocumentHashes.sha256(encoded));
        AtomicFiles.replace(file.toPath(), encoded);
    }

    public boolean delete(String id) throws IOException {
        File file = fileFor(id);
        writtenHashes.remove(file.getAbsolutePath());
        return Files.deleteIfExists(file.toPath());
    }

    public boolean isOwnWrite(File file) {
        if (file == null) {
            return false;
        }
        String expected = writtenHashes.get(file.getAbsolutePath());
        if (expected == null) {
            return false;
        }
        try (InputStream input = Files.newInputStream(file.toPath())) {
            byte[] content = input.readNBytes((int) DocumentRegistry.MAX_DOCUMENT_BYTES + 1);
            return content.length <= DocumentRegistry.MAX_DOCUMENT_BYTES
                && expected.equals(DocumentHashes.sha256(content));
        } catch (IOException failure) {
            return false;
        }
    }

    public T mutate(String id, T current, long expectedRevision, UnaryOperator<T> update) throws IOException {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(update, "update");
        long actual = reviser.revisionOf(current);
        if (actual != expectedRevision) {
            throw new DocumentRevisionConflictException(kind, id, expectedRevision, actual);
        }
        if (actual >= DocumentEnvelope.MAX_SAFE_REVISION) {
            throw new IllegalStateException(kind + " revision overflow: " + id);
        }
        T changed = Objects.requireNonNull(update.apply(current), "update result");
        T next = reviser.withRevision(changed, actual + 1L);
        write(id, next);
        return next;
    }

    public void forgetAll() {
        writtenHashes.clear();
    }

    private String encode(File file, T value) throws IOException {
        String canonical = DocumentParsers.GSON.toJson(value);
        if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            return canonical;
        }
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Document must be a regular file: " + file);
        }
        String collection = folder.getName();
        if (!DocumentPresetCatalog.supports(collection)) {
            return canonical;
        }
        try (InputStream input = Files.newInputStream(file.toPath())) {
            byte[] content = input.readNBytes((int) DocumentRegistry.MAX_DOCUMENT_BYTES + 1);
            if (content.length > DocumentRegistry.MAX_DOCUMENT_BYTES) {
                throw new IOException("Document exceeds " + DocumentRegistry.MAX_DOCUMENT_BYTES + " bytes: " + file);
            }
            String source = new String(content, StandardCharsets.UTF_8);
            DocumentPresetCatalog catalog = DocumentPresetCatalog.read(folder.toPath().toAbsolutePath().getParent());
            JsonObject authored = JsonParser.parseString(source).getAsJsonObject();
            boolean actionLibrary = (collection.equals("inventories") || collection.equals("menus"))
                && authored.has("actions") && authored.get("actions").isJsonObject();
            if (!catalog.applies(collection, source) && !actionLibrary) {
                return canonical;
            }
            String effective = catalog.resolve(collection, source);
            if (collection.equals("inventories") || collection.equals("menus")) {
                effective = ActionReferences.resolve(effective);
            }
            Object previous = DocumentParsers.GSON.fromJson(effective, value.getClass());
            JsonObject before = DocumentParsers.GSON.toJsonTree(previous).getAsJsonObject();
            JsonObject after = JsonParser.parseString(canonical).getAsJsonObject();
            return DocumentParsers.GSON.toJson(DocumentPresetCatalog.applyEdits(authored, before, after));
        } catch (RuntimeException failure) {
            throw new IOException("Unable to preserve presets while saving " + kind + " " + file.getName(), failure);
        }
    }

    private static String requireSafeId(String id) {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("document id may not be blank");
        }
        if (id.contains("/") || id.contains("\\") || id.contains("..")) {
            throw new IllegalArgumentException("document id may not contain path characters: " + id);
        }
        return id;
    }
}
