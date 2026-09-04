package art.arcane.gloss.board;

import art.arcane.gloss.condition.ConditionCompiler;
import art.arcane.gloss.condition.ConditionSource;
import art.arcane.gloss.condition.ShowCondition;
import art.arcane.gloss.doc.DocumentEnvelope;
import art.arcane.gloss.doc.DocumentParsers;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record BoardDoc(int schemaVersion, long revision, ShowCondition show, Selection select, Presentation presentation,
                       List<Variant> variants) {
    public static final String KIND = "boards";
    public static final int CURRENT_SCHEMA_VERSION = 2;

    public BoardDoc {
        DocumentEnvelope.requireSchemaVersion(KIND, schemaVersion, CURRENT_SCHEMA_VERSION);
        DocumentEnvelope.requireRevision(KIND, revision);
        show = show == null ? ShowCondition.ALWAYS : show;
        select = select == null ? Selection.NEVER : select;
        presentation = presentation == null ? Presentation.EMPTY : presentation;
        variants = copyVariants(variants);
    }

    public BoardDoc withRevision(long revision) {
        return new BoardDoc(schemaVersion, revision, show, select, presentation, variants);
    }

    public static BoardDoc parse(String fileName, String raw) {
        return DocumentParsers.parseJson(fileName, raw, BoardDoc.class);
    }

    private static List<Variant> copyVariants(List<Variant> variants) {
        if (variants == null) {
            return List.of();
        }
        List<Variant> copied = new ArrayList<>(variants.size());
        Set<String> ids = new HashSet<>(variants.size());
        for (Variant variant : variants) {
            if (variant == null) {
                throw new IllegalArgumentException("board variants may not contain null entries");
            }
            if (!ids.add(variant.id())) {
                throw new IllegalArgumentException("board variant id is duplicated: " + variant.id());
            }
            copied.add(variant);
        }
        return List.copyOf(copied);
    }

    public record Selection(int priority, String when) {
        public static final Selection NEVER = new Selection(0, "false");

        public Selection {
            when = normalizeCondition(when, "board selection");
            ConditionCompiler.compile(new ConditionSource("boards.select.when", when));
        }
    }

    public record Presentation(String title, List<String> lines, boolean hideNumbers) {
        public static final Presentation EMPTY = new Presentation("", List.of(), false);

        public Presentation {
            title = title == null ? "" : title;
            lines = copyLines(lines);
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

    public record Variant(String id, int priority, String when, Presentation presentation) {
        public Variant {
            id = normalizeId(id);
            when = normalizeCondition(when, "board variant " + id);
            ConditionCompiler.compile(new ConditionSource("boards.variants." + id + ".when", when));
            if (presentation == null) {
                throw new IllegalArgumentException("board variant " + id + " requires a presentation");
            }
        }

        private static String normalizeId(String id) {
            String normalized = id == null ? "" : id.trim();
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException("board variant id may not be blank");
            }
            for (int index = 0; index < normalized.length(); index++) {
                char character = normalized.charAt(index);
                if (!Character.isLetterOrDigit(character) && character != '-' && character != '_'
                    && character != '.') {
                    throw new IllegalArgumentException("board variant id contains an unsupported character: " + id);
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
