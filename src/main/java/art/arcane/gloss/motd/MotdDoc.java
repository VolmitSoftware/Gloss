package art.arcane.gloss.motd;

import art.arcane.gloss.doc.DocumentEnvelope;
import art.arcane.gloss.doc.DocumentParsers;
import art.arcane.gloss.util.common.TextUtils;

import java.util.ArrayList;
import java.util.List;

public record MotdDoc(int schemaVersion, long revision, List<MotdEntry> entries) {
    public static final String KIND = "motd";
    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final int MAX_LINES_PER_ENTRY = 2;

    public static final MotdDoc DEFAULTS = new MotdDoc(CURRENT_SCHEMA_VERSION, DocumentEnvelope.INITIAL_REVISION,
        List.of(new MotdEntry(List.of("&dA glossy server"))));

    public MotdDoc {
        DocumentEnvelope.requireSchemaVersion(KIND, schemaVersion, CURRENT_SCHEMA_VERSION);
        DocumentEnvelope.requireRevision(KIND, revision);
        if (entries == null || entries.isEmpty()) {
            throw new IllegalArgumentException("motd document requires at least one entry");
        }
        entries = List.copyOf(entries);
    }

    public static MotdDoc parse(String fileName, String raw) {
        return DocumentParsers.parseJson(fileName, raw, MotdDoc.class);
    }

    public record MotdEntry(List<String> lines) {
        public MotdEntry {
            if (lines == null || lines.isEmpty() || lines.size() > MAX_LINES_PER_ENTRY) {
                throw new IllegalArgumentException("motd entry requires 1 to " + MAX_LINES_PER_ENTRY + " lines");
            }
            List<String> copied = new ArrayList<>(lines.size());
            for (String line : lines) {
                copied.add(line == null ? "" : line);
            }
            lines = List.copyOf(copied);
        }

        public String joined() {
            return TextUtils.joinLegacyLines(lines);
        }
    }
}
