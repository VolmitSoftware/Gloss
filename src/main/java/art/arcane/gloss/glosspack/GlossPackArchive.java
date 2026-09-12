package art.arcane.gloss.glosspack;

import art.arcane.gloss.doc.DocumentHashes;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * A {@code .glosspack} read into memory. The archive is bounded before anything is trusted: every
 * entry name has to stay inside the archive, the whole thing has to fit in {@value #MAX_BYTES}
 * bytes over at most {@value #MAX_ENTRIES} entries, and every file the manifest declares has to
 * hash to the value the manifest declares for it.
 */
public final class GlossPackArchive {
    public static final int MAX_BYTES = 8 * 1024 * 1024;
    public static final int MAX_ENTRIES = 512;

    private final GlossPackManifest manifest;
    private final Map<String, byte[]> entries;

    private GlossPackArchive(GlossPackManifest manifest, Map<String, byte[]> entries) {
        this.manifest = manifest;
        this.entries = entries;
    }

    public static GlossPackArchive read(byte[] archive) throws IOException {
        Map<String, byte[]> entries = readEntries(Objects.requireNonNull(archive, "archive"));
        byte[] manifestBytes = entries.get(GlossPackManifest.FILE_NAME);
        if (manifestBytes == null) {
            throw new IllegalArgumentException("pack archive has no " + GlossPackManifest.FILE_NAME);
        }
        GlossPackManifest manifest = GlossPackManifest.parse(
                new String(manifestBytes, StandardCharsets.UTF_8));
        verifyDeclaredHashes(manifest, entries);
        return new GlossPackArchive(manifest, Map.copyOf(entries));
    }

    public GlossPackManifest manifest() {
        return manifest;
    }

    public byte[] entry(String archivePath) {
        byte[] content = entries.get(archivePath);
        if (content == null) {
            throw new IllegalArgumentException("pack archive is missing " + archivePath);
        }
        return content.clone();
    }

    public boolean has(String archivePath) {
        return entries.containsKey(archivePath);
    }

    private static void verifyDeclaredHashes(GlossPackManifest manifest,
                                             Map<String, byte[]> entries) {
        for (GlossPackManifest.DocumentRef document : manifest.documents()) {
            requireHash(entries, document.archivePath(), document.sha256());
        }
        for (GlossPackManifest.ImageRef image : manifest.images()) {
            requireHash(entries, image.archivePath(), image.sha256());
        }
    }

    private static void requireHash(Map<String, byte[]> entries, String path, String expected) {
        byte[] content = entries.get(path);
        if (content == null) {
            throw new IllegalArgumentException("pack archive is missing " + path);
        }
        if (!DocumentHashes.sha256(content).equals(expected)) {
            throw new IllegalArgumentException("pack archive content does not match its sha256: "
                    + path);
        }
    }

    private static Map<String, byte[]> readEntries(byte[] archive) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        long total = 0L;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = safeName(entry.getName());
                if (entries.size() >= MAX_ENTRIES) {
                    throw new IllegalArgumentException("pack archive exceeds " + MAX_ENTRIES
                            + " entries");
                }
                byte[] content = readBounded(zip, MAX_BYTES - total);
                total += content.length;
                if (entries.putIfAbsent(name, content) != null) {
                    throw new IllegalArgumentException("pack archive repeats an entry: " + name);
                }
            }
        }
        return entries;
    }

    private static String safeName(String name) {
        String normalized = name.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains("..") || normalized.contains("//")
                || normalized.isBlank() || normalized.length() > 512) {
            throw new IllegalArgumentException("pack archive entry escapes the archive: " + name);
        }
        for (int index = 0; index < normalized.length(); index++) {
            char character = normalized.charAt(index);
            if (character < 0x20 || character == ':' || character == '*' || character == '?'
                    || character == '"' || character == '<' || character == '>' || character == '|') {
                throw new IllegalArgumentException("pack archive entry name is invalid: " + name);
            }
        }
        return normalized;
    }

    private static byte[] readBounded(InputStream source, long remaining) throws IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long budget = remaining;
        int read;
        while ((read = source.read(buffer)) > 0) {
            budget -= read;
            if (budget < 0L) {
                throw new IllegalArgumentException("pack archive exceeds " + MAX_BYTES + " bytes");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }
}
