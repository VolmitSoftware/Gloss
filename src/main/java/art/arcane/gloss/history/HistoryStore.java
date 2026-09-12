package art.arcane.gloss.history;

import art.arcane.gloss.doc.AtomicFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Versioned copies of documents under {@code history/<kind>/<id>/<epochMillis>-<source>.json}.
 *
 * <p>A source spells its origin with a colon ({@code editor:<session>}, {@code pack:<id>}); the file
 * name carries {@code ~} in its place because a colon is not a legal file name character on every
 * platform Gloss runs on. Nothing else about the name is rewritten, so the listing reports exactly
 * the source that was recorded.
 */
public final class HistoryStore {
    static final String DIRECTORY = "history";
    private static final String EXTENSION = ".json";
    private static final char SOURCE_SEPARATOR = '~';
    private static final Pattern SOURCE = Pattern.compile("[a-z0-9][a-z0-9:._-]{0,63}");
    private static final Pattern KIND = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");
    private static final Pattern ID_SEGMENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    private final Path root;
    private final LongSupplier clock;

    public HistoryStore(Path dataDirectory) {
        this(dataDirectory, System::currentTimeMillis);
    }

    public HistoryStore(Path dataDirectory, LongSupplier clock) {
        this.root = Objects.requireNonNull(dataDirectory, "dataDirectory")
                .toAbsolutePath().normalize().resolve(DIRECTORY);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public HistoryEntry record(String kind, String id, byte[] content, String source)
            throws IOException {
        Path directory = requireDirectory(kind, id);
        String normalizedSource = requireSource(source);
        byte[] copied = Objects.requireNonNull(content, "content").clone();
        long epochMillis = clock.getAsLong();
        Path file = directory.resolve(epochMillis + "-"
                + normalizedSource.replace(':', SOURCE_SEPARATOR) + EXTENSION);
        AtomicFiles.replace(file, copied);
        return new HistoryEntry(kind, id, epochMillis, normalizedSource, copied.length, file);
    }

    public List<HistoryEntry> list(String kind, String id) {
        Path directory = requireDirectory(kind, id);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        List<HistoryEntry> entries = new ArrayList<>();
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.toList()) {
                entryOf(kind, id, file).ifPresent(entries::add);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("cannot read document history: " + directory, failure);
        }
        entries.sort(Comparator.comparingLong(HistoryEntry::epochMillis).reversed()
                .thenComparing(HistoryEntry::source));
        return List.copyOf(entries);
    }

    public HistoryEntry version(String kind, String id, long epochMillis) {
        for (HistoryEntry entry : list(kind, id)) {
            if (entry.epochMillis() == epochMillis) {
                return entry;
            }
        }
        return null;
    }

    public byte[] read(HistoryEntry entry) throws IOException {
        Path file = Objects.requireNonNull(entry, "entry").file();
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("history version is not a regular file: " + file);
        }
        return Files.readAllBytes(file);
    }

    /** Drops versions beyond {@code maxVersions} per document and anything older than {@code maxAgeDays}. */
    public int prune(int maxVersions, int maxAgeDays) throws IOException {
        if (maxVersions < 1 || maxAgeDays < 1) {
            throw new IllegalArgumentException("history retention must be positive");
        }
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        long oldest = clock.getAsLong() - (maxAgeDays * 86_400_000L);
        int removed = 0;
        for (Path directory : documentDirectories()) {
            removed += pruneDirectory(directory, maxVersions, oldest);
        }
        return removed;
    }

    private int pruneDirectory(Path directory, int maxVersions, long oldest) throws IOException {
        List<Path> files;
        try (Stream<Path> stream = Files.list(directory)) {
            files = stream.filter(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                    .filter(file -> file.getFileName().toString().endsWith(EXTENSION))
                    .sorted(Comparator.comparingLong(HistoryStore::epochOf).reversed())
                    .toList();
        }
        int removed = 0;
        for (int index = 0; index < files.size(); index++) {
            Path file = files.get(index);
            long epochMillis = epochOf(file);
            if (index < maxVersions && epochMillis >= oldest) {
                continue;
            }
            Files.deleteIfExists(file);
            removed++;
        }
        if (removed > 0) {
            deleteEmptyDirectories(directory);
        }
        return removed;
    }

    private List<Path> documentDirectories() throws IOException {
        List<Path> directories = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path path : stream.toList()) {
                if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || path.equals(root)) {
                    continue;
                }
                try (Stream<Path> children = Files.list(path)) {
                    if (children.anyMatch(child ->
                            Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS))) {
                        directories.add(path);
                    }
                }
            }
        }
        return directories;
    }

    private void deleteEmptyDirectories(Path directory) throws IOException {
        Path current = directory;
        while (current.startsWith(root) && !current.equals(root)) {
            try (Stream<Path> children = Files.list(current)) {
                if (children.findAny().isPresent()) {
                    return;
                }
            }
            Files.deleteIfExists(current);
            current = current.getParent();
        }
    }

    private java.util.Optional<HistoryEntry> entryOf(String kind, String id, Path file) {
        String name = file.getFileName().toString();
        if (!name.endsWith(EXTENSION)
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return java.util.Optional.empty();
        }
        int separator = name.indexOf('-');
        if (separator <= 0) {
            return java.util.Optional.empty();
        }
        long epochMillis;
        try {
            epochMillis = Long.parseLong(name.substring(0, separator));
        } catch (NumberFormatException malformed) {
            return java.util.Optional.empty();
        }
        String source = name.substring(separator + 1, name.length() - EXTENSION.length())
                .replace(SOURCE_SEPARATOR, ':');
        long bytes;
        try {
            bytes = Files.size(file);
        } catch (IOException unreadable) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new HistoryEntry(kind, id, epochMillis, source, bytes, file));
    }

    private static long epochOf(Path file) {
        String name = file.getFileName().toString();
        int separator = name.indexOf('-');
        if (separator <= 0) {
            return 0L;
        }
        try {
            return Long.parseLong(name.substring(0, separator));
        } catch (NumberFormatException malformed) {
            return 0L;
        }
    }

    private Path requireDirectory(String kind, String id) {
        if (kind == null || !KIND.matcher(kind).matches()) {
            throw new IllegalArgumentException("history kind is invalid: " + kind);
        }
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("history document id is invalid");
        }
        Path directory = root.resolve(kind);
        for (String segment : id.split("/", -1)) {
            if (!ID_SEGMENT.matcher(segment).matches()) {
                throw new IllegalArgumentException("history document id is invalid: " + id);
            }
            directory = directory.resolve(segment);
        }
        Path normalized = directory.normalize();
        if (!normalized.startsWith(root)) {
            throw new IllegalArgumentException("history document id escapes the history folder: " + id);
        }
        return normalized;
    }

    private static String requireSource(String source) {
        String normalized = source == null ? "" : source.toLowerCase(Locale.ROOT);
        if (!SOURCE.matcher(normalized).matches()) {
            throw new IllegalArgumentException("history source is invalid: " + source);
        }
        return normalized;
    }
}
