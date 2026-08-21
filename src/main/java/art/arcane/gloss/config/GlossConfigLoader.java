package art.arcane.gloss.config;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.doc.DocumentHashes;
import art.arcane.volmlib.util.config.ConfigExposePolicy;
import art.arcane.volmlib.util.config.ConfigFileSupport;
import art.arcane.volmlib.util.config.ConfigIo;
import art.arcane.volmlib.util.config.TomlCodec;
import art.arcane.volmlib.util.io.IO;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

public final class GlossConfigLoader {
    public static final String FILE_NAME = "config.toml";

    private static final String SOURCE_TAG = "gloss";
    private static final long MAX_CONFIG_BYTES = 2L * 1024L * 1024L;

    private final File dataFolder;
    private final File file;
    private final ConfigIo io;

    private volatile String lastCanonicalSha256 = "";

    public GlossConfigLoader(File dataFolder) {
        this.dataFolder = dataFolder;
        this.file = new File(dataFolder, FILE_NAME);
        this.io = new ConfigIo() {
            @Override
            public void info(String message) {
                Gloss.info(message);
            }

            @Override
            public void warn(String message) {
                Gloss.warn(message);
            }

            @Override
            public void verbose(String message) {
                Gloss.verbose(message);
            }

            @Override
            public File dataFolder() {
                return GlossConfigLoader.this.dataFolder;
            }
        };
    }

    public GlossConfigFile loadForBoot() throws IOException {
        return load(true);
    }

    public GlossConfigFile loadForReload() throws IOException {
        return load(false);
    }

    public GlossConfigFile loadForReload(ReloadSnapshot snapshot) throws IOException {
        ReloadSnapshot stable = Objects.requireNonNull(snapshot, "snapshot");
        GlossConfigFile loaded = TomlCodec.fromToml(stable.rawContent(), GlossConfigFile.class);
        if (loaded == null) {
            throw new IOException("Config parser returned null");
        }
        loaded.normalize();
        lastCanonicalSha256 = stable.sha256();
        return loaded;
    }

    public ReloadSnapshot captureReloadSnapshot() throws IOException {
        BasicFileAttributes before = Files.readAttributes(
            file.toPath(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile()) {
            throw new IOException("Config path is not a regular file");
        }
        if (before.size() > MAX_CONFIG_BYTES) {
            throw new IOException("Config file is too large (" + before.size() + " bytes)");
        }
        byte[] content = readBoundedContent();
        BasicFileAttributes after = Files.readAttributes(
            file.toPath(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!sameSnapshot(before, after) || content.length != after.size()) {
            throw new IOException("Config file changed while it was being captured");
        }
        return new ReloadSnapshot(
            new String(content, StandardCharsets.UTF_8),
            DocumentHashes.sha256(content)
        );
    }

    /**
     * Re-serializes the given config through the same commented-TOML writer the loader uses, so
     * overlays applied by the data importers land on disk with every comment regenerated. The
     * write updates the canonical hash, keeping self-write suppression intact.
     */
    public void save(GlossConfigFile config) throws IOException {
        Objects.requireNonNull(config, "config").normalize();
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        IO.writeAll(file, TomlCodec.toToml(config, SOURCE_TAG, ConfigExposePolicy.ALL));
        lastCanonicalSha256 = hashCurrentFile();
    }

    public File file() {
        return file;
    }

    public String lastCanonicalSha256() {
        return lastCanonicalSha256;
    }

    public boolean isSelfWrite() {
        String current = hashCurrentFile();
        return !current.isEmpty() && current.equals(lastCanonicalSha256);
    }

    public boolean isSelfWrite(ReloadSnapshot snapshot) {
        return snapshot != null
            && !snapshot.sha256().isEmpty()
            && snapshot.sha256().equals(lastCanonicalSha256);
    }

    public String hashCurrentFile() {
        try {
            return DocumentHashes.sha256(readBoundedContent());
        } catch (IOException failure) {
            return "";
        }
    }

    private GlossConfigFile load(boolean overwriteOnReadFailure) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        GlossConfigFile loaded = ConfigFileSupport.load(
            io,
            file,
            null,
            GlossConfigFile.class,
            new GlossConfigFile(),
            overwriteOnReadFailure,
            SOURCE_TAG,
            "Created config.toml with defaults.",
            GlossConfigFile::normalize,
            true,
            null,
            ConfigExposePolicy.ALL
        );
        lastCanonicalSha256 = hashCurrentFile();
        return loaded;
    }

    private boolean sameSnapshot(BasicFileAttributes before, BasicFileAttributes after) {
        return before.isRegularFile()
            && after.isRegularFile()
            && before.size() == after.size()
            && before.lastModifiedTime().equals(after.lastModifiedTime())
            && Objects.equals(before.fileKey(), after.fileKey());
    }

    private byte[] readBoundedContent() throws IOException {
        try (InputStream input = Files.newInputStream(file.toPath())) {
            byte[] content = input.readNBytes((int) MAX_CONFIG_BYTES + 1);
            if (content.length > MAX_CONFIG_BYTES) {
                throw new IOException("Config file is too large (more than " + MAX_CONFIG_BYTES + " bytes)");
            }
            return content;
        }
    }

    public record ReloadSnapshot(String rawContent, String sha256) {
        public ReloadSnapshot {
            rawContent = Objects.requireNonNull(rawContent, "rawContent");
            sha256 = Objects.requireNonNull(sha256, "sha256");
        }
    }
}
