package art.arcane.gloss.tab;

import art.arcane.gloss.condition.ConditionCompiler;
import art.arcane.gloss.condition.ConditionSource;
import art.arcane.gloss.condition.ShowCondition;
import art.arcane.gloss.doc.DocumentEnvelope;
import art.arcane.gloss.doc.DocumentParsers;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record TablistDoc(int schemaVersion, long revision, ShowCondition show, HeaderFooter headerFooter,
                         ListNames listNames) {
    public static final String KIND = "tablist";
    public static final int CURRENT_SCHEMA_VERSION = 2;
    public static final String FALLBACK_FORMAT = "$player";

    public static final TablistDoc DEFAULTS = new TablistDoc(CURRENT_SCHEMA_VERSION,
        DocumentEnvelope.INITIAL_REVISION, ShowCondition.ALWAYS,
        new HeaderFooter(true, ShowCondition.ALWAYS,
            new HeaderFooterPresentation("&d&lGloss", "&7VolmitSoftware.com"), List.of()),
        new ListNames(true, ShowCondition.ALWAYS, new ListNamePresentation(FALLBACK_FORMAT), List.of(
            new ListNameVariant("operator", 100, "subject.op", new ListNamePresentation("&6$player")))));

    public TablistDoc {
        show = show == null ? ShowCondition.ALWAYS : show;
        DocumentEnvelope.requireSchemaVersion(KIND, schemaVersion, CURRENT_SCHEMA_VERSION);
        DocumentEnvelope.requireRevision(KIND, revision);
        headerFooter = headerFooter == null ? HeaderFooter.DEFAULTS : headerFooter;
        listNames = listNames == null ? ListNames.DEFAULTS : listNames;
    }

    public static TablistDoc parse(String fileName, String raw) {
        return DocumentParsers.parseJson(fileName, raw, TablistDoc.class);
    }

    public record HeaderFooter(boolean enabled, ShowCondition show, HeaderFooterPresentation presentation,
                               List<HeaderFooterVariant> variants) {
        public static final HeaderFooter DEFAULTS = new HeaderFooter(true, ShowCondition.ALWAYS,
            new HeaderFooterPresentation("&d&lGloss", "&7VolmitSoftware.com"), List.of());

        public HeaderFooter {
            show = show == null ? ShowCondition.ALWAYS : show;
            presentation = presentation == null ? HeaderFooterPresentation.EMPTY : presentation;
            variants = copyHeaderFooterVariants(variants);
        }
    }

    public record HeaderFooterPresentation(String header, String footer) {
        public static final HeaderFooterPresentation EMPTY = new HeaderFooterPresentation("", "");

        public HeaderFooterPresentation {
            header = header == null ? "" : header;
            footer = footer == null ? "" : footer;
        }
    }

    public record HeaderFooterVariant(String id, int priority, String when,
                                      HeaderFooterPresentation presentation) {
        public HeaderFooterVariant {
            id = normalizeId(id, "tablist header/footer variant");
            when = normalizeCondition(when, "tablist header/footer variant " + id);
            ConditionCompiler.compile(new ConditionSource(
                "tablist.headerFooter.variants." + id + ".when", when));
            if (presentation == null) {
                throw new IllegalArgumentException(
                    "tablist header/footer variant " + id + " requires a presentation");
            }
        }
    }

    public record ListNames(boolean enabled, ShowCondition show, ListNamePresentation presentation,
                            List<ListNameVariant> variants) {
        public static final ListNames DEFAULTS = new ListNames(true, ShowCondition.ALWAYS,
            new ListNamePresentation(FALLBACK_FORMAT), List.of());

        public ListNames {
            show = show == null ? ShowCondition.ALWAYS : show;
            presentation = presentation == null ? new ListNamePresentation(FALLBACK_FORMAT) : presentation;
            variants = copyListNameVariants(variants);
        }
    }

    public record ListNamePresentation(String format) {
        public ListNamePresentation {
            format = format == null ? FALLBACK_FORMAT : format;
        }
    }

    public record ListNameVariant(String id, int priority, String when, ListNamePresentation presentation) {
        public ListNameVariant {
            id = normalizeId(id, "tablist list-name variant");
            when = normalizeCondition(when, "tablist list-name variant " + id);
            ConditionCompiler.compile(new ConditionSource(
                "tablist.listNames.variants." + id + ".when", when));
            if (presentation == null) {
                throw new IllegalArgumentException("tablist list-name variant " + id + " requires a presentation");
            }
        }
    }

    private static List<HeaderFooterVariant> copyHeaderFooterVariants(List<HeaderFooterVariant> variants) {
        if (variants == null) {
            return List.of();
        }
        List<HeaderFooterVariant> copied = new ArrayList<>(variants.size());
        Set<String> ids = new HashSet<>(variants.size());
        for (HeaderFooterVariant variant : variants) {
            if (variant == null) {
                throw new IllegalArgumentException("tablist header/footer variants may not contain null entries");
            }
            requireUnique(ids, variant.id(), "tablist header/footer variant");
            copied.add(variant);
        }
        return List.copyOf(copied);
    }

    private static List<ListNameVariant> copyListNameVariants(List<ListNameVariant> variants) {
        if (variants == null) {
            return List.of();
        }
        List<ListNameVariant> copied = new ArrayList<>(variants.size());
        Set<String> ids = new HashSet<>(variants.size());
        for (ListNameVariant variant : variants) {
            if (variant == null) {
                throw new IllegalArgumentException("tablist list-name variants may not contain null entries");
            }
            requireUnique(ids, variant.id(), "tablist list-name variant");
            copied.add(variant);
        }
        return List.copyOf(copied);
    }

    private static void requireUnique(Set<String> ids, String id, String owner) {
        if (!ids.add(id)) {
            throw new IllegalArgumentException(owner + " id is duplicated: " + id);
        }
    }

    private static String normalizeId(String id, String owner) {
        String normalized = id == null ? "" : id.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(owner + " id may not be blank");
        }
        for (int index = 0; index < normalized.length(); index++) {
            char character = normalized.charAt(index);
            if (!Character.isLetterOrDigit(character) && character != '-' && character != '_'
                && character != '.') {
                throw new IllegalArgumentException(owner + " id contains an unsupported character: " + id);
            }
        }
        return normalized;
    }

    private static String normalizeCondition(String when, String owner) {
        String normalized = when == null ? "" : when.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(owner + " condition may not be blank");
        }
        return normalized;
    }
}
