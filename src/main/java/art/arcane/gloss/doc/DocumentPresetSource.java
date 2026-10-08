package art.arcane.gloss.doc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public final class DocumentPresetSource {
    private static final Map<Path, DocumentPresetSource> SOURCES = new HashMap<>();
    private static final long REFRESH_NANOS = TimeUnit.MILLISECONDS.toNanos(50L);
    private static final long CONTENT_REFRESH_NANOS = TimeUnit.SECONDS.toNanos(6L);

    private final Path root;
    private Snapshot snapshot;
    private long nextRead;
    private long nextContentRead;
    private BasicFileAttributes attributes;
    private int users;

    private DocumentPresetSource(Path root) {
        this.root = root;
    }

    public static synchronized DocumentPresetSource acquire(Path root) {
        Path canonical = root.toAbsolutePath().normalize();
        DocumentPresetSource source = SOURCES.computeIfAbsent(canonical, DocumentPresetSource::new);
        source.users++;
        return source;
    }

    public static synchronized void release(DocumentPresetSource source) {
        if (--source.users == 0) {
            SOURCES.remove(source.root, source);
        }
    }

    public synchronized Snapshot refresh(boolean force) {
        long now = System.nanoTime();
        if (!force && snapshot != null && now - nextRead < 0L) {
            return snapshot;
        }
        nextRead = now + REFRESH_NANOS;
        try {
            BasicFileAttributes current = attributes(root.resolve(DocumentPresetCatalog.FILE_NAME));
            if (!force && snapshot != null && sameFile(attributes, current) && now - nextContentRead < 0L) {
                return snapshot;
            }
            String raw = DocumentPresetCatalog.readSource(root);
            String fingerprint = raw == null ? "" : DocumentHashes.sha256(raw);
            if (snapshot == null || !snapshot.fingerprint().equals(fingerprint)) {
                snapshot = parse(fingerprint, raw);
            }
            attributes = current;
            nextContentRead = now + CONTENT_REFRESH_NANOS;
        } catch (IOException | RuntimeException failure) {
            snapshot = new Snapshot("failure:" + failure.getMessage(), null, failure);
        }
        return snapshot;
    }

    private static BasicFileAttributes attributes(Path file) throws IOException {
        try {
            return Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException absent) {
            return null;
        }
    }

    private static boolean sameFile(BasicFileAttributes before, BasicFileAttributes after) {
        if (before == null || after == null) {
            return before == after;
        }
        return before.isRegularFile() == after.isRegularFile() && before.size() == after.size()
            && before.lastModifiedTime().equals(after.lastModifiedTime())
            && Objects.equals(before.fileKey(), after.fileKey());
    }

    private static Snapshot parse(String fingerprint, String raw) {
        try {
            return new Snapshot(fingerprint, raw == null ? DocumentPresetCatalog.empty()
                : DocumentPresetCatalog.parse(DocumentPresetCatalog.FILE_NAME, raw), null);
        } catch (RuntimeException failure) {
            return new Snapshot(fingerprint, null, failure);
        }
    }

    public record Snapshot(String fingerprint, DocumentPresetCatalog catalog, Exception failure) {
        public String resolve(String kind, String raw) {
            if (failure != null) {
                throw new IllegalArgumentException("Unable to load presets: " + failure.getMessage(), failure);
            }
            return catalog.resolve(kind, raw);
        }
    }
}
