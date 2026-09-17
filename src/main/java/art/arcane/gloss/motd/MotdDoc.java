package art.arcane.gloss.motd;

import art.arcane.gloss.condition.ShowCondition;
import art.arcane.gloss.doc.DocumentEnvelope;
import art.arcane.gloss.doc.DocumentParsers;
import art.arcane.gloss.util.common.TextUtils;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public record MotdDoc(int schemaVersion, long revision, ShowCondition show, String favicon,
                      List<MotdEntry> entries, List<MotdLink> links) {
    public static final String KIND = "motd";
    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final int MAX_LINES_PER_ENTRY = 2;
    public static final int MAX_SAMPLE_LINES = 12;
    public static final int MAX_LINKS = 16;
    public static final Set<String> LINK_TYPES = Set.of("report_bug", "community_guidelines", "support",
        "status", "feedback", "community", "website", "forums", "news", "announcements");

    public static final MotdDoc DEFAULTS = new MotdDoc(CURRENT_SCHEMA_VERSION, DocumentEnvelope.INITIAL_REVISION,
        ShowCondition.ALWAYS, null, List.of(MotdEntry.ofLines(List.of("&dA glossy server"))), List.of());

    public MotdDoc {
        show = show == null ? ShowCondition.ALWAYS : show;
        favicon = trimToNull(favicon);
        DocumentEnvelope.requireSchemaVersion(KIND, schemaVersion, CURRENT_SCHEMA_VERSION);
        DocumentEnvelope.requireRevision(KIND, revision);
        if (entries == null || entries.isEmpty()) {
            throw new IllegalArgumentException("motd document requires at least one entry");
        }
        entries = List.copyOf(entries);
        links = copyLinks(links);
    }

    public static MotdDoc parse(String fileName, String raw) {
        return DocumentParsers.parseJson(fileName, raw, MotdDoc.class);
    }

    public String faviconFor(MotdEntry entry) {
        return entry.favicon() == null ? favicon : entry.favicon();
    }

    private static List<MotdLink> copyLinks(List<MotdLink> links) {
        if (links == null) {
            return List.of();
        }
        if (links.size() > MAX_LINKS) {
            throw new IllegalArgumentException("motd document declares more than " + MAX_LINKS + " links");
        }
        List<MotdLink> copied = new ArrayList<>(links.size());
        for (MotdLink link : links) {
            if (link == null) {
                throw new IllegalArgumentException("motd links may not contain null entries");
            }
            copied.add(link);
        }
        return List.copyOf(copied);
    }

    public record MotdEntry(List<String> lines, String favicon, List<String> sample, String online, String max,
                            String version) {
        public MotdEntry {
            if (lines == null || lines.isEmpty() || lines.size() > MAX_LINES_PER_ENTRY) {
                throw new IllegalArgumentException("motd entry requires 1 to " + MAX_LINES_PER_ENTRY + " lines");
            }
            List<String> copied = new ArrayList<>(lines.size());
            for (String line : lines) {
                copied.add(line == null ? "" : line);
            }
            lines = List.copyOf(copied);
            favicon = trimToNull(favicon);
            sample = copySample(sample);
        }

        public static MotdEntry ofLines(List<String> lines) {
            return new MotdEntry(lines, null, null, null, null, null);
        }

        public String joined() {
            return TextUtils.joinLegacyLines(lines);
        }

        private static List<String> copySample(List<String> sample) {
            if (sample == null || sample.isEmpty()) {
                return List.of();
            }
            if (sample.size() > MAX_SAMPLE_LINES) {
                throw new IllegalArgumentException("motd entry sample may not exceed " + MAX_SAMPLE_LINES
                    + " lines");
            }
            List<String> copied = new ArrayList<>(sample.size());
            for (String line : sample) {
                copied.add(line == null ? "" : line);
            }
            return List.copyOf(copied);
        }
    }

    /**
     * One pause-menu server link. A known {@code type} maps onto the client's built-in label; a
     * {@code label} publishes a custom one. Either is required, and the URL must be http or https.
     */
    public record MotdLink(String type, String label, String url) {
        public MotdLink {
            type = normalizeType(type);
            label = trimToNull(label);
            url = normalizeUrl(url);
            if (type == null && label == null) {
                throw new IllegalArgumentException("motd link requires a known type or a label: " + url);
            }
        }

        public boolean isLabelled() {
            return type == null;
        }

        public URI uri() {
            return URI.create(url);
        }

        private static String normalizeType(String type) {
            String normalized = trimToNull(type);
            if (normalized == null) {
                return null;
            }
            String lower = normalized.toLowerCase(Locale.ROOT);
            if (!LINK_TYPES.contains(lower)) {
                throw new IllegalArgumentException("motd link type must be one of " + LINK_TYPES + ": " + type);
            }
            return lower;
        }

        private static String normalizeUrl(String url) {
            String normalized = trimToNull(url);
            if (normalized == null) {
                throw new IllegalArgumentException("motd link requires a url");
            }
            URI parsed;
            try {
                parsed = new URI(normalized);
            } catch (URISyntaxException failure) {
                throw new IllegalArgumentException("motd link url is not a valid URI: " + url, failure);
            }
            String scheme = parsed.getScheme() == null ? "" : parsed.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https") || parsed.getHost() == null) {
                throw new IllegalArgumentException("motd link url must be an http or https address: " + url);
            }
            return normalized;
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
