package art.arcane.gloss.config;

import art.arcane.gloss.Gloss;
import art.arcane.volmlib.util.config.ConfigExposePolicy;
import art.arcane.volmlib.util.config.ConfigFileSupport;
import art.arcane.volmlib.util.config.ConfigIo;
import art.arcane.volmlib.util.config.TomlCodec;
import art.arcane.volmlib.util.io.IO;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

public final class GlossConfigLoader {
    public static final String FILE_NAME = "config.toml";

    private static final String SOURCE_TAG = "gloss";

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

    public String hashCurrentFile() {
        try {
            byte[] content = Files.readAllBytes(file.toPath());
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (IOException | NoSuchAlgorithmException failure) {
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
}
