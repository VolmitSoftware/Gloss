package art.arcane.gloss.nameplate;

import art.arcane.gloss.api.HologramBox;
import art.arcane.gloss.api.IconBillboard;
import art.arcane.gloss.api.IconDisplayStyle;
import art.arcane.gloss.condition.ConditionCompiler;
import art.arcane.gloss.condition.ConditionSource;
import art.arcane.gloss.condition.ShowCondition;
import art.arcane.gloss.doc.DocumentEnvelope;
import art.arcane.gloss.doc.DocumentParsers;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A pane above a player's head, replacing the vanilla tag. The conditional shape is the board
 * model: one {@code select}, one {@code presentation}, and {@code variants} that replace the
 * presentation wholesale rather than merging into it.
 */
public record NameplateDoc(int schemaVersion, long revision, ShowCondition show, Selection select,
                           Presentation presentation, List<Variant> variants) {
    public static final String KIND = "nameplates";
    public static final String DEFAULT_ID = "default";
    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final double DEFAULT_OFFSET = 0.3D;
    public static final int MAX_LINES = 16;
    public static final IconDisplayStyle DEFAULT_STYLE = new IconDisplayStyle(
        IconBillboard.CENTER, null, null, null, null, null, null, null, null,
        null, null, null, null, null, null, 0.75F, 0.75F, 0.75F);

    public NameplateDoc {
        DocumentEnvelope.requireSchemaVersion(KIND, schemaVersion, CURRENT_SCHEMA_VERSION);
        DocumentEnvelope.requireRevision(KIND, revision);
        show = show == null ? ShowCondition.ALWAYS : show;
        select = select == null ? Selection.ALWAYS : select;
        presentation = presentation == null ? Presentation.EMPTY : presentation;
        variants = copyVariants(variants);
    }

    public static NameplateDoc parse(String fileName, String raw) {
        return DocumentParsers.parseJson(fileName, raw, NameplateDoc.class);
    }

    private static List<Variant> copyVariants(List<Variant> variants) {
        if (variants == null) {
            return List.of();
        }
        List<Variant> copied = new ArrayList<>(variants.size());
        Set<String> ids = new HashSet<>(variants.size());
        for (Variant variant : variants) {
            Objects.requireNonNull(variant, "nameplate variants may not contain null entries");
            if (!ids.add(variant.id())) {
                throw new IllegalArgumentException("nameplate variant id is duplicated: " + variant.id());
            }
            copied.add(variant);
        }
        return List.copyOf(copied);
    }

    public record Selection(int priority, String when) {
        public static final Selection ALWAYS = new Selection(0, "true");

        public Selection {
            when = when == null || when.isBlank() ? "true" : when.trim();
            ConditionCompiler.compile(new ConditionSource("nameplates.select.when", when));
        }
    }

    public record Variant(String id, int priority, String when, Presentation presentation) {
        public Variant {
            id = Objects.requireNonNull(id, "nameplate variant id").trim();
            if (id.isEmpty() || id.length() > 64) {
                throw new IllegalArgumentException("a nameplate variant id must be 1 to 64 characters");
            }
            when = when == null || when.isBlank() ? "true" : when.trim();
            ConditionCompiler.compile(new ConditionSource("nameplates.variants." + id + ".when", when));
            presentation = presentation == null ? Presentation.EMPTY : presentation;
        }
    }

    public record Presentation(List<Line> lines, IconDisplayStyle style, HologramBox box, Double offset,
                               Boolean hideSneaking, List<Relation> relations) {
        public static final Presentation EMPTY = new Presentation(null, null, null, null, null, null);

        public Presentation {
            lines = copyLines(lines);
            style = style == null ? DEFAULT_STYLE : style;
            box = box == null ? HologramBox.defaults() : box;
            offset = offset == null ? DEFAULT_OFFSET : Math.clamp(offset, -2.0D, 8.0D);
            hideSneaking = hideSneaking == null || hideSneaking;
            relations = relations == null ? List.of() : List.copyOf(relations);
        }

        private static List<Line> copyLines(List<Line> lines) {
            if (lines == null) {
                return List.of();
            }
            if (lines.size() > MAX_LINES) {
                throw new IllegalArgumentException("a nameplate may declare at most " + MAX_LINES + " lines");
            }
            return List.copyOf(lines);
        }
    }

    public record Line(String text, ShowCondition show) {
        public Line {
            text = text == null ? "" : text;
            if (text.length() > 4096) {
                throw new IllegalArgumentException("a nameplate line must not exceed 4096 characters");
            }
            show = show == null ? ShowCondition.ALWAYS : show;
        }
    }

    /** A colour applied to the whole pane when its condition holds; the first match wins. */
    public record Relation(ShowCondition when, String color) {
        public Relation {
            when = when == null ? ShowCondition.ALWAYS : when;
            color = color == null ? "" : color;
        }
    }
}
