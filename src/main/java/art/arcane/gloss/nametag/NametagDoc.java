package art.arcane.gloss.nametag;

import art.arcane.gloss.condition.ConditionCompiler;
import art.arcane.gloss.condition.ConditionSource;
import art.arcane.gloss.condition.ShowCondition;
import art.arcane.gloss.doc.DocumentEnvelope;
import art.arcane.gloss.doc.DocumentParsers;
import art.arcane.gloss.util.common.TeamAllocator;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public record NametagDoc(int schemaVersion, long revision, ShowCondition show, Selection select,
                         Presentation presentation, List<Variant> variants) {
    public static final String KIND = "nametags";
    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final NametagDoc DEFAULTS = new NametagDoc(CURRENT_SCHEMA_VERSION,
        DocumentEnvelope.INITIAL_REVISION, ShowCondition.ALWAYS, Selection.NEVER,
        new Presentation("", "", "white", "always", "always"), List.of());

    public NametagDoc {
        DocumentEnvelope.requireSchemaVersion(KIND, schemaVersion, CURRENT_SCHEMA_VERSION);
        DocumentEnvelope.requireRevision(KIND, revision);
        show = show == null ? ShowCondition.ALWAYS : show;
        select = select == null ? Selection.NEVER : select;
        presentation = presentation == null ? Presentation.PLAIN : presentation;
        variants = copyVariants(variants);
    }

    public static NametagDoc parse(String fileName, String raw) {
        return DocumentParsers.parseJson(fileName, raw, NametagDoc.class);
    }

    private static List<Variant> copyVariants(List<Variant> variants) {
        if (variants == null) {
            return List.of();
        }
        List<Variant> copied = new ArrayList<>(variants.size());
        Set<String> ids = new HashSet<>(variants.size());
        for (Variant variant : variants) {
            if (variant == null) {
                throw new IllegalArgumentException("nametag variants may not contain null entries");
            }
            if (!ids.add(variant.id())) {
                throw new IllegalArgumentException("nametag variant id is duplicated: " + variant.id());
            }
            copied.add(variant);
        }
        return List.copyOf(copied);
    }

    public record Selection(int priority, String when) {
        public static final Selection NEVER = new Selection(0, "false");

        public Selection {
            when = normalizeCondition(when, "nametag selection");
            ConditionCompiler.compile(new ConditionSource("nametags.select.when", when));
        }
    }

    public record Presentation(String prefix, String suffix, String color, String nameTagVisibility,
                               String collision) {
        public static final Presentation PLAIN = new Presentation("", "", "white", "always", "always");

        public Presentation {
            prefix = prefix == null ? "" : prefix;
            suffix = suffix == null ? "" : suffix;
            color = color == null || color.isBlank() ? "white" : color.trim().toLowerCase(Locale.ROOT);
            nameTagVisibility = requireName(nameTagVisibility, "always", "nametag nameTagVisibility",
                "always", "never", "hide_for_other_teams", "hide_for_own_team");
            collision = requireName(collision, "always", "nametag collision",
                "always", "never", "push_other_teams", "push_own_team");
        }

        public TeamAllocator.TeamStyle style(String renderedPrefix, String renderedSuffix) {
            return new TeamAllocator.TeamStyle(renderedPrefix, renderedSuffix, color,
                TeamAllocator.NameTagVisibility.valueOf(nameTagVisibility.toUpperCase(Locale.ROOT)),
                TeamAllocator.CollisionRule.valueOf(collision.toUpperCase(Locale.ROOT)));
        }

        private static String requireName(String value, String fallback, String owner, String... allowed) {
            String normalized = value == null || value.isBlank()
                ? fallback : value.trim().toLowerCase(Locale.ROOT);
            for (String candidate : allowed) {
                if (candidate.equals(normalized)) {
                    return normalized;
                }
            }
            throw new IllegalArgumentException(owner + " must be one of " + String.join(", ", allowed)
                + ": " + value);
        }
    }

    public record Variant(String id, int priority, String when, Presentation presentation) {
        public Variant {
            id = normalizeId(id);
            when = normalizeCondition(when, "nametag variant " + id);
            ConditionCompiler.compile(new ConditionSource("nametags.variants." + id + ".when", when));
            if (presentation == null) {
                throw new IllegalArgumentException("nametag variant " + id + " requires a presentation");
            }
        }

        private static String normalizeId(String id) {
            String normalized = id == null ? "" : id.trim();
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException("nametag variant id may not be blank");
            }
            for (int index = 0; index < normalized.length(); index++) {
                char character = normalized.charAt(index);
                if (!Character.isLetterOrDigit(character) && character != '-' && character != '_'
                    && character != '.') {
                    throw new IllegalArgumentException("nametag variant id contains an unsupported character: " + id);
                }
            }
            return normalized;
        }
    }

    private static String normalizeCondition(String when, String owner) {
        String normalized = when == null ? "" : when.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(owner + " condition may not be blank");
        }
        return normalized;
    }
}
