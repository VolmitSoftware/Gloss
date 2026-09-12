package art.arcane.gloss.surface;

import art.arcane.gloss.condition.ConditionCompiler;
import art.arcane.gloss.condition.ConditionSource;
import art.arcane.gloss.condition.ShowCondition;
import art.arcane.gloss.doc.DocumentEnvelope;
import art.arcane.gloss.doc.DocumentParsers;
import art.arcane.gloss.expr.Expr;
import art.arcane.gloss.expr.ExprException;
import art.arcane.gloss.expr.ExprParser;
import art.arcane.volmlib.util.hud.HudSlot;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public record SurfaceDoc(int schemaVersion, long revision, SurfaceKind surface, ShowCondition show,
                         Selection select, Presentation presentation, List<Variant> variants) {
    public static final String KIND = "surfaces";
    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final int MAX_TTL_TICKS = 1200;
    public static final int MAX_FADE_TICKS = 1200;
    public static final int MAX_REPEAT_TICKS = 72_000;
    public static final int DEFAULT_FADE_IN_TICKS = 10;
    public static final int DEFAULT_STAY_TICKS = 40;
    public static final int DEFAULT_FADE_OUT_TICKS = 10;
    public static final String DEFAULT_TRIGGER = "select";
    public static final List<String> TRIGGERS = List.of("select", "once", "repeat");
    public static final List<String> COLORS = List.of("pink", "blue", "red", "green", "yellow", "purple", "white");
    public static final List<String> STYLES = List.of("solid", "segmented_6", "segmented_10", "segmented_12", "segmented_20");
    public static final SurfaceDoc DEFAULTS = new SurfaceDoc(CURRENT_SCHEMA_VERSION,
        DocumentEnvelope.INITIAL_REVISION, SurfaceKind.ACTIONBAR, ShowCondition.ALWAYS, Selection.NEVER,
        new Presentation("&7Surface", null, null, null, null, null, null, null, null, null, null, null, null, null),
        List.of());

    public SurfaceDoc {
        DocumentEnvelope.requireSchemaVersion(KIND, schemaVersion, CURRENT_SCHEMA_VERSION);
        DocumentEnvelope.requireRevision(KIND, revision);
        if (surface == null) {
            throw new IllegalArgumentException("surface document requires a surface of "
                + "actionbar, bossbar or title");
        }
        show = show == null ? ShowCondition.ALWAYS : show;
        select = select == null ? Selection.NEVER : select;
        if (presentation == null) {
            throw new IllegalArgumentException("surface document requires a presentation");
        }
        presentation = presentation.forKind(surface, "surfaces.presentation");
        variants = copyVariants(surface, variants);
    }

    public static SurfaceDoc parse(String fileName, String raw) {
        return DocumentParsers.parseJson(fileName, raw, SurfaceDoc.class);
    }

    private static List<Variant> copyVariants(SurfaceKind surface, List<Variant> variants) {
        if (variants == null) {
            return List.of();
        }
        List<Variant> copied = new ArrayList<>(variants.size());
        Set<String> ids = new HashSet<>(variants.size());
        for (Variant variant : variants) {
            if (variant == null) {
                throw new IllegalArgumentException("surface variants may not contain null entries");
            }
            if (!ids.add(variant.id())) {
                throw new IllegalArgumentException("surface variant id is duplicated: " + variant.id());
            }
            copied.add(new Variant(variant.id(), variant.priority(), variant.when(),
                variant.presentation().forKind(surface, "surfaces.variants." + variant.id() + ".presentation")));
        }
        return List.copyOf(copied);
    }

    public record Selection(int priority, String when) {
        public static final Selection NEVER = new Selection(0, "false");

        public Selection {
            when = normalizeCondition(when, "surface selection");
            ConditionCompiler.compile(new ConditionSource("surfaces.select.when", when));
        }
    }

    public record Presentation(String text, List<String> slots, String title, String subtitle, String progress,
                               String color, String style, String priority, Integer ttlTicks, Integer fadeInTicks,
                               Integer stayTicks, Integer fadeOutTicks, String trigger, Integer repeatTicks) {
        public Presentation {
            text = trimToNull(text);
            slots = copySlots(slots);
            title = trimToNull(title);
            subtitle = subtitle == null ? null : subtitle;
            progress = normalizeProgress(progress);
            color = normalizeName(color, COLORS, "surface color");
            style = normalizeName(style, STYLES, "surface style");
            priority = priority == null ? null : SurfacePriorities.name(priority);
            if (priority != null) {
                SurfacePriorities.of(priority);
            }
            ttlTicks = clamp(ttlTicks, 1, MAX_TTL_TICKS);
            fadeInTicks = clamp(fadeInTicks, 0, MAX_FADE_TICKS);
            stayTicks = clamp(stayTicks, 0, MAX_FADE_TICKS);
            fadeOutTicks = clamp(fadeOutTicks, 0, MAX_FADE_TICKS);
            trigger = normalizeName(trigger, TRIGGERS, "surface trigger");
            repeatTicks = clamp(repeatTicks, 1, MAX_REPEAT_TICKS);
        }

        public List<HudSlot> hudSlots() {
            List<HudSlot> resolved = new ArrayList<>(slots.size());
            for (String slot : slots) {
                resolved.add(HudSlot.valueOf(slot.toUpperCase(Locale.ROOT)));
            }
            return List.copyOf(resolved);
        }

        public int priorityValue() {
            return SurfacePriorities.of(priority);
        }

        Presentation forKind(SurfaceKind surface, String owner) {
            return switch (surface) {
                case ACTIONBAR -> forActionBar(owner);
                case BOSSBAR -> forBossBar(owner);
                case TITLE -> forTitle(owner);
            };
        }

        private Presentation forActionBar(String owner) {
            if (text == null) {
                throw new IllegalArgumentException(owner + " requires text for an actionbar surface");
            }
            return new Presentation(text, slots, null, null, null, null, null,
                SurfacePriorities.name(priority), ttlTicks, null, null, null, null, null);
        }

        private Presentation forBossBar(String owner) {
            if (title == null) {
                throw new IllegalArgumentException(owner + " requires a title for a bossbar surface");
            }
            return new Presentation(null, slots, title, null, progress == null ? "1" : progress,
                color == null ? "white" : color, style == null ? "solid" : style,
                SurfacePriorities.name(priority), ttlTicks, null, null, null, null, null);
        }

        private Presentation forTitle(String owner) {
            if (title == null) {
                throw new IllegalArgumentException(owner + " requires a title for a title surface");
            }
            String resolvedTrigger = trigger == null ? DEFAULT_TRIGGER : trigger;
            int stay = stayTicks == null ? DEFAULT_STAY_TICKS : stayTicks;
            Integer resolvedRepeat = repeatTicks;
            if (resolvedTrigger.equals("repeat")) {
                resolvedRepeat = Integer.valueOf(Math.max(stay, repeatTicks == null ? stay : repeatTicks.intValue()));
            }
            return new Presentation(null, slots, title, subtitle == null ? "" : subtitle, null, null, null,
                SurfacePriorities.name(priority), ttlTicks,
                fadeInTicks == null ? DEFAULT_FADE_IN_TICKS : fadeInTicks, Integer.valueOf(stay),
                fadeOutTicks == null ? DEFAULT_FADE_OUT_TICKS : fadeOutTicks, resolvedTrigger, resolvedRepeat);
        }

        private static List<String> copySlots(List<String> slots) {
            if (slots == null || slots.isEmpty()) {
                return List.of("center");
            }
            List<String> copied = new ArrayList<>(slots.size());
            for (String slot : slots) {
                String normalized = slot == null ? "" : slot.trim().toLowerCase(Locale.ROOT);
                if (HudSlot.fromCode(slotCode(normalized)) == null) {
                    throw new IllegalArgumentException("surface slot must be one of left, center, right: " + slot);
                }
                if (!copied.contains(normalized)) {
                    copied.add(normalized);
                }
            }
            return List.copyOf(copied);
        }

        private static char slotCode(String slot) {
            return switch (slot) {
                case "left" -> 'L';
                case "center" -> 'C';
                case "right" -> 'R';
                default -> '?';
            };
        }

        private static String normalizeProgress(String progress) {
            String normalized = trimToNull(progress);
            if (normalized == null) {
                return null;
            }
            if (normalized.startsWith("{{") && normalized.endsWith("}}")) {
                normalized = normalized.substring(2, normalized.length() - 2).trim();
            }
            try {
                Expr parsed = ExprParser.parse(normalized);
                if (parsed == null) {
                    throw new IllegalArgumentException("surface progress expression is empty");
                }
            } catch (ExprException failure) {
                throw new IllegalArgumentException("surface progress expression does not compile: "
                    + failure.getMessage(), failure);
            }
            return normalized;
        }

        private static String normalizeName(String value, List<String> allowed, String owner) {
            String normalized = value == null ? null : value.trim().toLowerCase(Locale.ROOT);
            if (normalized == null || normalized.isEmpty()) {
                return null;
            }
            if (!allowed.contains(normalized)) {
                throw new IllegalArgumentException(owner + " must be one of "
                    + String.join(", ", allowed) + ": " + value);
            }
            return normalized;
        }

        private static Integer clamp(Integer value, int min, int max) {
            return value == null ? null : Math.clamp(value.intValue(), min, max);
        }

        private static String trimToNull(String value) {
            if (value == null) {
                return null;
            }
            return value.isBlank() ? null : value;
        }
    }

    public record Variant(String id, int priority, String when, Presentation presentation) {
        public Variant {
            id = normalizeId(id);
            when = normalizeCondition(when, "surface variant " + id);
            ConditionCompiler.compile(new ConditionSource("surfaces.variants." + id + ".when", when));
            if (presentation == null) {
                throw new IllegalArgumentException("surface variant " + id + " requires a presentation");
            }
        }

        private static String normalizeId(String id) {
            String normalized = id == null ? "" : id.trim();
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException("surface variant id may not be blank");
            }
            for (int index = 0; index < normalized.length(); index++) {
                char character = normalized.charAt(index);
                if (!Character.isLetterOrDigit(character) && character != '-' && character != '_'
                    && character != '.') {
                    throw new IllegalArgumentException("surface variant id contains an unsupported character: " + id);
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
