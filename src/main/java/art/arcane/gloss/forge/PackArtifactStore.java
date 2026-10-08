package art.arcane.gloss.forge;

import art.arcane.gloss.GlossConfig;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

public final class PackArtifactStore {
    private final Object state = new Object();
    private final Object maintenance = new Object();
    private final Map<String, Entry> entries = new HashMap<>();
    private final Set<String> deleting = new HashSet<>();
    private final LongSupplier clock;
    private final RetirementObserver retirementObserver;
    private final long startedAt;
    private volatile long retentionMillis = GlossConfig.PackLimits.DEFAULT.artifactRetentionSeconds() * 1000;
    private String current = "";

    public PackArtifactStore() {
        this(System::currentTimeMillis);
    }

    PackArtifactStore(LongSupplier clock) {
        this(clock, artifact -> { });
    }

    PackArtifactStore(LongSupplier clock, RetirementObserver retirementObserver) {
        this.clock = clock;
        this.retirementObserver = retirementObserver;
        this.startedAt = clock.getAsLong();
    }

    Lease install(PackArtifact artifact, Path stagedZip, GlossConfig.PackLimits limits) throws IOException {
        synchronized (maintenance) {
            long nextRetention = limits.artifactRetentionSeconds() * 1000;
            synchronized (state) {
                long extension = Math.max(0, nextRetention - retentionMillis);
                for (Entry entry : entries.values()) {
                    entry.eligibleAfter += extension;
                }
                retentionMillis = nextRetention;
            }
            Path directory = artifact.zip().getParent();
            Files.createDirectories(directory);
            if (Files.isSymbolicLink(directory)) {
                throw new IOException("Pack artifact directory must not be symbolic");
            }
            prune();
            inventory(artifact.directory().getParent(), directory, limits);
            prune();
            long size = Files.size(stagedZip);
            Lease reused;
            synchronized (state) {
                Entry existing = entries.get(artifact.sha1Hex());
                reused = existing == null ? null : acquire(existing);
            }
            if (reused != null) {
                try {
                    if (Files.size(reused.artifact().zip()) != size
                        || Files.mismatch(stagedZip, reused.artifact().zip()) != -1) {
                        throw new IOException("Immutable pack artifact differs from its content hash");
                    }
                    return reused;
                } catch (IOException | RuntimeException failure) {
                    reused.close();
                    throw failure;
                }
            }
            synchronized (state) {
                long bytes = 0;
                for (Entry entry : entries.values()) {
                    bytes = Math.addExact(bytes, entry.bytes);
                }
                if (entries.size() >= limits.maxRetainedArtifacts() || size > limits.maxRetainedBytes() - bytes) {
                    throw new IOException("Pack artifact retention budget is full; keeping the previous pack");
                }
            }
            Path temporary = Files.createTempFile(directory, ".artifact-", ".tmp");
            try {
                Files.copy(stagedZip, temporary, StandardCopyOption.REPLACE_EXISTING);
                try {
                    Files.move(temporary, artifact.zip(), StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(temporary, artifact.zip());
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
            synchronized (state) {
                Entry installed = entries.get(artifact.sha1Hex());
                if (installed == null) {
                    installed = new Entry(artifact, size, true, clock.getAsLong() + retentionMillis);
                    entries.put(artifact.sha1Hex(), installed);
                } else {
                    installed.bytes = size;
                    installed.managed = true;
                }
                return acquire(installed);
            }
        }
    }

    public Lease retain(PackArtifact artifact) {
        if (artifact == null) {
            return null;
        }
        synchronized (state) {
            if (deleting.contains(artifact.sha1Hex())) {
                throw new IllegalStateException("Pack artifact is already retiring");
            }
            Entry entry = entries.computeIfAbsent(artifact.sha1Hex(), ignored ->
                new Entry(artifact, 0, false, clock.getAsLong() + retentionMillis));
            return acquire(entry);
        }
    }

    public Lease acquireRoute(String route) {
        if (!route.startsWith("/gloss-pack-") || !route.endsWith(".zip")) {
            return null;
        }
        String hash = route.substring("/gloss-pack-".length(), route.length() - 4);
        synchronized (state) {
            Entry entry = entries.get(hash);
            return entry == null ? null : acquire(entry);
        }
    }

    void published(PackArtifact artifact) {
        synchronized (state) {
            Entry previous = entries.get(current);
            if (previous != null) {
                previous.eligibleAfter = clock.getAsLong() + retentionMillis;
            }
            current = artifact.sha1Hex();
        }
    }

    private Lease acquire(Entry entry) {
        entry.leases++;
        return new Lease(entry);
    }

    private void inventory(Path output, Path directory, GlossConfig.PackLimits limits) throws IOException {
        Path marker = output.resolve(PackBuilder.SHA1_NAME);
        if (Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
            String hash;
            try (InputStream input = Files.newInputStream(marker, LinkOption.NOFOLLOW_LINKS)) {
                hash = new String(input.readNBytes(42), StandardCharsets.US_ASCII).strip();
            }
            if (hash.matches("[0-9a-f]{40}")) {
                synchronized (state) {
                    current = hash;
                }
            }
        }
        try (DirectoryStream<Path> paths = Files.newDirectoryStream(directory)) {
            for (Path path : paths) {
                String name = path.getFileName().toString();
                if (!name.matches("[0-9a-f]{40}\\.zip")) {
                    continue;
                }
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Pack artifact must be a regular non-symbolic file: " + path);
                }
                String hash = name.substring(0, 40);
                long modifiedAt = Files.getLastModifiedTime(path).toMillis();
                long bytes = Files.size(path);
                long eligibleAfter = Math.max(startedAt, modifiedAt) + retentionMillis;
                boolean expired;
                synchronized (state) {
                    Entry existing = entries.get(hash);
                    if (existing != null) {
                        existing.bytes = bytes;
                        existing.managed = true;
                        continue;
                    }
                    expired = !hash.equals(current) && clock.getAsLong() >= eligibleAfter;
                    if (expired) {
                        deleting.add(hash);
                    } else {
                        if (entries.size() >= limits.maxRetainedArtifacts()) {
                            throw new IOException("Existing pack artifacts exceed the retention count budget");
                        }
                        PackArtifact artifact = new PackArtifact(output.resolve(PackBuilder.PACK_DIRECTORY), path,
                            HexFormat.of().parseHex(hash), hash, modifiedAt);
                        entries.put(hash, new Entry(artifact, bytes, true, eligibleAfter));
                    }
                }
                if (expired) {
                    try {
                        Files.delete(path);
                    } finally {
                        synchronized (state) {
                            deleting.remove(hash);
                        }
                    }
                }
            }
        }
    }

    private void prune() throws IOException {
        List<Entry> retired = new ArrayList<>();
        synchronized (state) {
            long now = clock.getAsLong();
            for (Entry entry : entries.values()) {
                if (entry.leases == 0 && !entry.artifact.sha1Hex().equals(current) && now >= entry.eligibleAfter) {
                    retired.add(entry);
                }
            }
            for (Entry entry : retired) {
                entries.remove(entry.artifact.sha1Hex(), entry);
                deleting.add(entry.artifact.sha1Hex());
            }
        }
        for (Entry entry : retired) {
            try {
                if (entry.managed) {
                    retirementObserver.beforeDelete(entry.artifact);
                    Files.deleteIfExists(entry.artifact.zip());
                }
            } catch (IOException failure) {
                synchronized (state) {
                    for (Entry pending : retired) {
                        if (deleting.remove(pending.artifact.sha1Hex())) {
                            entries.putIfAbsent(pending.artifact.sha1Hex(), pending);
                        }
                    }
                }
                throw failure;
            } finally {
                synchronized (state) {
                    deleting.remove(entry.artifact.sha1Hex());
                }
            }
        }
    }

    public final class Lease implements AutoCloseable {
        private final Entry entry;
        private boolean closed;

        private Lease(Entry entry) {
            this.entry = entry;
        }

        public PackArtifact artifact() {
            return entry.artifact;
        }

        @Override
        public void close() {
            synchronized (state) {
                if (closed) {
                    return;
                }
                closed = true;
                entry.leases--;
                entry.eligibleAfter = clock.getAsLong() + retentionMillis;
            }
        }
    }

    private static final class Entry {
        private final PackArtifact artifact;
        private long bytes;
        private boolean managed;
        private long eligibleAfter;
        private int leases;

        private Entry(PackArtifact artifact, long bytes, boolean managed, long eligibleAfter) {
            this.artifact = artifact;
            this.bytes = bytes;
            this.managed = managed;
            this.eligibleAfter = eligibleAfter;
        }
    }

    @FunctionalInterface
    interface RetirementObserver {
        void beforeDelete(PackArtifact artifact) throws IOException;
    }
}
