package art.arcane.gloss.hologram;

import art.arcane.gloss.doc.DocumentEnvelope;
import art.arcane.gloss.doc.DocumentParsers;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record HologramDoc(int schemaVersion, long revision, Anchor anchor, List<String> lines,
                          Boolean seeThrough) {
    public static final String KIND = "holograms";
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public record Anchor(String world, Vector position) {
        public Anchor {
            if (world == null || world.isBlank()) {
                throw new IllegalArgumentException("hologram anchor requires a world");
            }
            position = Objects.requireNonNull(position, "hologram anchor requires a position").clone();
        }

        @Override
        public Vector position() {
            return position.clone();
        }
    }

    public HologramDoc {
        DocumentEnvelope.requireSchemaVersion(KIND, schemaVersion, CURRENT_SCHEMA_VERSION);
        DocumentEnvelope.requireRevision(KIND, revision);
        anchor = Objects.requireNonNull(anchor, "hologram requires an anchor");
        lines = copyLines(lines);
        seeThrough = seeThrough == null || seeThrough;
    }

    public HologramDoc withRevision(long revision) {
        return new HologramDoc(schemaVersion, revision, anchor, lines, seeThrough);
    }

    public static HologramDoc parse(String fileName, String raw) {
        return DocumentParsers.parseJson(fileName, raw, HologramDoc.class);
    }

    private static List<String> copyLines(List<String> lines) {
        if (lines == null) {
            return List.of();
        }
        List<String> copied = new ArrayList<>(lines.size());
        for (String line : lines) {
            copied.add(line == null ? "" : line);
        }
        return List.copyOf(copied);
    }
}
