package art.arcane.gloss.glosspack;

import java.util.Map;
import java.util.Objects;

/**
 * What one installed pack owns: the manifest it was installed from, where it came from, and the
 * hash each file had when the pack wrote it. The hash is what tells an operator edit apart from an
 * untouched copy on the next update or removal.
 */
public record GlossPackLedger(GlossPackManifest manifest, String source, long installedAt,
                              Map<String, String> installedHashes) {
    public GlossPackLedger {
        manifest = Objects.requireNonNull(manifest, "manifest");
        source = source == null ? "" : source;
        installedHashes = Map.copyOf(installedHashes);
    }

    public String id() {
        return manifest.id();
    }

    public boolean owns(String relativePath) {
        return installedHashes.containsKey(relativePath);
    }
}
