package art.arcane.gloss.doc;

import art.arcane.volmlib.util.bukkit.json.BukkitJson;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
        if (!folder.isDirectory()) {
            folder.mkdirs();
        }
        byte[] encoded = (BukkitJson.GSON.toJson(value) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
        writtenHashes.put(file.getAbsolutePath(), DocumentHashes.sha256(encoded));
        Path temporary = Files.createTempFile(folder.toPath(), "." + file.getName() + ".", ".tmp");
        try {
            Files.write(temporary, encoded);
            Files.move(temporary, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
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
        try {
            return expected.equals(DocumentHashes.sha256(Files.readAllBytes(file.toPath())));
        } catch (IOException failure) {
            return false;
        }
    }

    public T mutate(String id, T current, long expectedRevision, UnaryOperator<T> update) throws IOException {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(update, "update");
        long actual = reviser.revisionOf(current);
        if (actual != expectedRevision) {
            throw new DocumentRevisionConflictException(id, expectedRevision, actual);
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
