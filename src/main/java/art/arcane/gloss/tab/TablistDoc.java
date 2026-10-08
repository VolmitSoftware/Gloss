package art.arcane.gloss.tab;

import art.arcane.gloss.condition.ConditionCompiler;
import art.arcane.gloss.condition.ConditionReferences;
import art.arcane.gloss.condition.ConditionSource;
import art.arcane.gloss.condition.ShowCondition;
import art.arcane.gloss.doc.DocumentEnvelope;
import art.arcane.gloss.doc.DocumentParsers;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import art.arcane.gloss.tab.TablistLayoutDefinition.LayoutPresentation;
import art.arcane.gloss.tab.TablistLayoutDefinition.LayoutVariant;
import art.arcane.gloss.tab.TablistLayoutDefinition.Section;
import art.arcane.gloss.tab.TablistLayoutDefinition.Skin;
import art.arcane.gloss.tab.TablistLayoutDefinition.Slot;
import java.util.Set;

public record TablistDoc(int schemaVersion, long revision, ShowCondition show, HeaderFooter headerFooter,
                         ListNames listNames, Sort sort, Layout layout) {
    public static final String KIND = "tablist";
    public static final int CURRENT_SCHEMA_VERSION = 3;
    public static final String FALLBACK_FORMAT = "$player";

    public static final TablistDoc DEFAULTS = new TablistDoc(CURRENT_SCHEMA_VERSION,
        DocumentEnvelope.INITIAL_REVISION, ShowCondition.ALWAYS,
        new HeaderFooter(true, ShowCondition.ALWAYS,
            new HeaderFooterPresentation("&d&lGloss", "&7VolmitSoftware.com"), List.of()),
        new ListNames(true, ShowCondition.ALWAYS, new ListNamePresentation(FALLBACK_FORMAT), List.of(
            new ListNameVariant("operator", 100, "subject.op", new ListNamePresentation("&6$player")))),
        Sort.DISABLED, Layout.DISABLED);

    public TablistDoc {
        show = show == null ? ShowCondition.ALWAYS : show;
        DocumentEnvelope.requireSchemaVersion(KIND, schemaVersion, CURRENT_SCHEMA_VERSION);
        DocumentEnvelope.requireRevision(KIND, revision);
        headerFooter = headerFooter == null ? HeaderFooter.DEFAULTS : headerFooter;
        listNames = listNames == null ? ListNames.DEFAULTS : listNames;
        sort = sort == null ? Sort.DISABLED : sort;
        layout = layout == null ? Layout.DISABLED : layout;
    }

    public static TablistDoc parse(String fileName, String raw) {
        return DocumentParsers.parseJson(fileName, raw, TablistDoc.class);
    }

    /**
     * Optional list ordering. {@code weight} is a number expression evaluated with {@code subject}
     * as the listed player and {@code viewer} as the observer; higher weights sort first.
     */
    public record Sort(boolean enabled, String weight) {
        public static final Sort DISABLED = new Sort(false, null);

        public Sort {
            weight = weight == null || weight.isBlank() ? null : weight.trim();
            if (enabled && weight == null) {
                throw new IllegalArgumentException("tablist sort requires a weight expression when enabled");
            }
            if (weight != null) {
                references(weight);
            }
        }

        public boolean active() {
            return enabled && weight != null;
        }

        /**
         * Type-checks and collects the weight's references. The condition compiler validates
         * booleans, so the weight is compared against zero to reuse it for a number expression.
         */
        public static ConditionReferences references(String weight) {
            return ConditionCompiler.compile(new ConditionSource("tablist.sort.weight", "(" + weight + ") > 0"))
                .references();
        }
    }

    /**
     * Optional fixed grid of client-side tab entries. Real players are unlisted for viewers that
     * receive a layout and reappear in the {@code players} column, so {@code /list}, proxies and
     * {@code Bukkit.getOnlinePlayers()} are unaffected.
     */
    public record Layout(boolean enabled, int entries, List<Slot> slots, List<Section> sections,
                         ShowCondition show, Map<String, Skin> skins, List<LayoutVariant> variants) {
        public static final ShowCondition NOT_BEDROCK = ShowCondition.of("!viewer.bedrock");
        public static final Layout DISABLED = new Layout(false, 1, List.of(), List.of(), null, Map.of(), List.of());

        public Layout {
            LayoutPresentation presentation = new LayoutPresentation(entries == 0 && !enabled ? 1 : entries,
                slots, sections, skins);
            entries = presentation.entries();
            slots = presentation.slots();
            sections = presentation.sections();
            skins = presentation.skins();
            validateLayout(presentation);
            show = show == null ? NOT_BEDROCK : show;
            variants = variants == null ? List.of() : List.copyOf(variants);
            Set<String> ids = new HashSet<>();
            for (LayoutVariant variant : variants) {
                requireUnique(ids, variant.id(), "tablist layout variant");
                ConditionCompiler.compile(new ConditionSource("tablist.layout.variants." + variant.id() + ".when", variant.when()));
                validateLayout(variant.presentation());
            }
        }

        public boolean active() {
            return enabled;
        }

        public LayoutPresentation presentation() {
            return new LayoutPresentation(entries, slots, sections, skins);
        }
    }

    private static void validateLayout(LayoutPresentation presentation) {
        for (Section section : presentation.sections()) {
            ConditionCompiler.compile(new ConditionSource("tablist.layout.sections." + section.id() + ".filter", section.filter()));
        }
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
