package art.arcane.gloss.forge;

import java.nio.file.Path;
import java.util.Objects;

/**
 * One finished pack build: the mergeable directory a third-party pack merger reads, the zip a
 * client downloads, and the sha1 the client verifies it against.
 */
public record PackArtifact(Path directory, Path zip, byte[] sha1, String sha1Hex, long builtAtMs) {
    public PackArtifact {
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(zip, "zip");
        sha1 = sha1.clone();
        Objects.requireNonNull(sha1Hex, "sha1Hex");
    }

    @Override
    public byte[] sha1() {
        return sha1.clone();
    }

    /** The one route the embedded listener serves this artifact on. */
    public String route() {
        return "/gloss-pack-" + sha1Hex + ".zip";
    }
}
