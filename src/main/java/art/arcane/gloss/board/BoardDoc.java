package art.arcane.gloss.board;

import art.arcane.gloss.doc.DocumentEnvelope;
import art.arcane.gloss.doc.DocumentParsers;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public record BoardDoc(int schemaVersion, long revision, String title, List<String> lines, boolean primary,
                       boolean hideNumbers, String permission, List<String> groups) {
    public static final String KIND = "boards";
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public BoardDoc {
        DocumentEnvelope.requireSchemaVersion(KIND, schemaVersion, CURRENT_SCHEMA_VERSION);
        DocumentEnvelope.requireRevision(KIND, revision);
        title = title == null ? "" : title;
        lines = copyLines(lines);
        permission = normalizePermission(permission);
        groups = normalizeGroups(groups);
    }

    public BoardDoc withRevision(long revision) {
        return new BoardDoc(schemaVersion, revision, title, lines, primary, hideNumbers, permission, groups);
    }

    public static BoardDoc parse(String fileName, String raw) {
        return DocumentParsers.parseJson(fileName, raw, BoardDoc.class);
    }

    public static String normalizePermission(String permission) {
        String normalized = permission == null ? "" : permission.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? GlossBoardMeta.UNRESTRICTED_PERMISSION : normalized;
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

    private static List<String> normalizeGroups(List<String> groups) {
        if (groups == null) {
            return List.of();
        }
        List<String> copied = new ArrayList<>(groups.size());
        for (String group : groups) {
            if (group == null || group.isBlank()) {
                continue;
            }
            String normalized = group.trim().toLowerCase(Locale.ROOT);
            if (!copied.contains(normalized)) {
                copied.add(normalized);
            }
        }
        return List.copyOf(copied);
    }
}
