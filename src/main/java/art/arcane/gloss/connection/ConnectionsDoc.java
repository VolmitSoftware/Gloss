package art.arcane.gloss.connection;

import art.arcane.gloss.condition.ConditionCompiler;
import art.arcane.gloss.condition.ConditionSource;
import art.arcane.gloss.condition.ShowCondition;
import art.arcane.gloss.doc.DocumentEnvelope;
import art.arcane.gloss.doc.DocumentParsers;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The server edition of {@code connections.json}: the join and leave lines a standalone server
 * broadcasts. The file is the proxy's file, so a {@code switch} block and a section
 * {@code audience} are read without complaint and have no meaning here — one server is the whole
 * network, and a backend never sees a server switch.
 */
public record ConnectionsDoc(int schemaVersion, long revision, ShowCondition show, Section join, Section leave) {
    public static final String KIND = "connections";
    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final String AUDIENCE_NETWORK = "network";
    public static final String AUDIENCE_SERVER = "server";
    public static final List<String> AUDIENCES = List.of(AUDIENCE_NETWORK, AUDIENCE_SERVER);

    public static final ConnectionsDoc DEFAULTS = new ConnectionsDoc(CURRENT_SCHEMA_VERSION,
        DocumentEnvelope.INITIAL_REVISION, ShowCondition.ALWAYS,
        new Section(Boolean.TRUE, ShowCondition.ALWAYS, AUDIENCE_NETWORK,
            new Presentation("&a+ &f{{ subject.name }} &7joined"), List.of()),
        new Section(Boolean.TRUE, ShowCondition.ALWAYS, AUDIENCE_NETWORK,
            new Presentation("&c- &f{{ subject.name }} &7left"), List.of()));

    public ConnectionsDoc {
        show = show == null ? ShowCondition.ALWAYS : show;
        DocumentEnvelope.requireSchemaVersion(KIND, schemaVersion, CURRENT_SCHEMA_VERSION);
        DocumentEnvelope.requireRevision(KIND, revision);
        join = join == null ? Section.DISABLED : join;
        leave = leave == null ? Section.DISABLED : leave;
    }

    public static ConnectionsDoc parse(String fileName, String raw) {
        return DocumentParsers.parseJson(fileName, raw, ConnectionsDoc.class);
    }

    /**
     * One announcement. A section that is present is on unless it says otherwise, and a section
     * the file leaves out is off, so adding a block is the only way to start broadcasting.
     */
    public record Section(Boolean enabled, ShowCondition show, String audience, Presentation presentation,
                          List<Variant> variants) {
        public static final Section DISABLED = new Section(Boolean.FALSE, ShowCondition.NEVER, AUDIENCE_NETWORK,
            Presentation.EMPTY, List.of());

        public Section {
            enabled = enabled == null ? Boolean.TRUE : enabled;
            show = show == null ? ShowCondition.ALWAYS : show;
            audience = normalizeAudience(audience);
            presentation = presentation == null ? Presentation.EMPTY : presentation;
            variants = copyVariants(variants);
        }

        public boolean active() {
            return Boolean.TRUE.equals(enabled);
        }

        private static String normalizeAudience(String audience) {
            if (audience == null || audience.isBlank()) {
                return AUDIENCE_NETWORK;
            }
            String normalized = audience.trim().toLowerCase(Locale.ROOT);
            if (!AUDIENCES.contains(normalized)) {
                throw new IllegalArgumentException("connections audience must be one of " + AUDIENCES + ": "
                    + audience);
            }
            return normalized;
        }

        private static List<Variant> copyVariants(List<Variant> variants) {
            if (variants == null || variants.isEmpty()) {
                return List.of();
            }
            List<Variant> copied = new ArrayList<>(variants.size());
            for (Variant variant : variants) {
                if (variant == null) {
                    throw new IllegalArgumentException("connections variants may not contain null entries");
                }
                copied.add(variant);
            }
            copied.sort(Comparator.comparingInt(Variant::priority).reversed());
            return List.copyOf(copied);
        }
    }

    public record Presentation(String text) {
        public static final Presentation EMPTY = new Presentation("");

        public Presentation {
            text = text == null ? "" : text;
        }
    }

    /** The highest-priority variant whose condition holds for the recipient replaces the base text. */
    public record Variant(int priority, String when, Presentation presentation) {
        public Variant {
            when = when == null ? "" : when.trim();
            if (when.isEmpty()) {
                throw new IllegalArgumentException("connections variant condition may not be blank");
            }
            ConditionCompiler.compile(new ConditionSource("connections.variants.when", when));
            if (presentation == null) {
                throw new IllegalArgumentException("connections variant requires a presentation");
            }
        }
    }
}
