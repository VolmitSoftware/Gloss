package art.arcane.gloss.inventory;

import art.arcane.gloss.condition.ConditionCompiler;
import art.arcane.gloss.condition.ConditionSource;
import art.arcane.gloss.condition.ShowCondition;
import art.arcane.gloss.config.components.ButtonComponentData;
import art.arcane.gloss.config.components.ComponentData;
import art.arcane.gloss.config.components.ToggleComponentData;
import art.arcane.gloss.doc.DocumentEnvelope;
import art.arcane.gloss.doc.DocumentParsers;
import art.arcane.gloss.menu.action.MenuAction;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A chest menu. The window is drawn as a picture: {@code mask} rows name a component per slot
 * through {@code keys}, {@code slots} overrides individual cells by index, and {@code list} fills
 * one mask character with pages of a template.
 *
 * <p>Variants replace {@code title}, {@code mask}, {@code keys} and {@code slots} wholesale on the
 * board model; nothing merges.
 */
public record InventoryDoc(int schemaVersion, long revision, String title, String resolution,
                           List<String> mask, Map<String, ComponentData> keys,
                           Map<String, ComponentData> slots, ListSection list,
                           Boolean closeOnTeleport, ShowCondition show, Selection select,
                           List<Variant> variants) {
    public static final String KIND = "inventories";
    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final String DEFAULT_RESOLUTION = "9x3";
    public static final List<String> RESOLUTIONS =
        List.of("9x1", "9x2", "9x3", "9x4", "9x5", "9x6", "5x1", "3x3");

    public InventoryDoc {
        DocumentEnvelope.requireSchemaVersion(KIND, schemaVersion, CURRENT_SCHEMA_VERSION);
        DocumentEnvelope.requireRevision(KIND, revision);
        title = title == null ? "" : title;
        resolution = requireResolution(resolution);
        keys = keys == null ? Map.of() : Map.copyOf(keys);
        int width = widthOf(resolution);
        int rows = rowsOf(resolution);
        mask = requireMask(mask, width, rows);
        slots = requireSlots(slots, width * rows);
        list = requireList(list, mask);
        closeOnTeleport = closeOnTeleport == null || closeOnTeleport;
        show = show == null ? ShowCondition.ALWAYS : show;
        select = select == null ? Selection.NEVER : select;
        variants = requireVariants(variants, width, rows);
    }

    public static InventoryDoc parse(String fileName, String raw) {
        InventoryDoc doc = DocumentParsers.parseJson(fileName, raw, InventoryDoc.class);
        doc.precompileActions();
        return doc;
    }

    public InventoryDoc withRevision(long revision) {
        return new InventoryDoc(schemaVersion, revision, title, resolution, mask, keys, slots, list,
            closeOnTeleport, show, select, variants);
    }

    public int width() {
        return widthOf(resolution);
    }

    public int rows() {
        return rowsOf(resolution);
    }

    public int size() {
        return width() * rows();
    }

    /** Every filled slot of the base presentation, keyed by slot index. */
    public Map<Integer, ComponentData> resolveSlots() {
        return InventoryMask.resolve(width(), mask, keys, slots);
    }

    /** The slots the {@code list} section fills, in reading order; empty when there is no list. */
    public List<Integer> listSlots() {
        return list == null ? List.of() : InventoryMask.areaSlots(width(), mask, list.area().charAt(0));
    }

    private void precompileActions() {
        String owner = "inventory:" + resolution;
        for (Map.Entry<Integer, ComponentData> entry : resolveSlots().entrySet()) {
            resolveActions(entry.getValue(), owner, "slot:" + entry.getKey());
        }
        if (list != null) {
            resolveActions(list.template(), owner, "list:" + list.var());
        }
        for (Variant variant : variants) {
            Presentation presentation = variant.presentation();
            Map<Integer, ComponentData> resolved = InventoryMask.resolve(width(),
                presentation.mask().isEmpty() ? mask : presentation.mask(),
                presentation.keys().isEmpty() ? keys : presentation.keys(),
                presentation.slots());
            for (Map.Entry<Integer, ComponentData> entry : resolved.entrySet()) {
                resolveActions(entry.getValue(), owner, "variant:" + variant.id() + "/slot:" + entry.getKey());
            }
        }
    }

    private static void resolveActions(ComponentData component, String owner, String componentId) {
        if (component instanceof ButtonComponentData button) {
            MenuAction.resolve(button.actions(), owner, componentId);
        } else if (component instanceof ToggleComponentData toggle) {
            MenuAction.resolve(toggle.trueActions(), owner, componentId);
            MenuAction.resolve(toggle.falseActions(), owner, componentId);
        }
    }

    static int widthOf(String resolution) {
        return Integer.parseInt(resolution.substring(0, resolution.indexOf('x')));
    }

    static int rowsOf(String resolution) {
        return Integer.parseInt(resolution.substring(resolution.indexOf('x') + 1));
    }

    private static String requireResolution(String resolution) {
        String value = resolution == null ? DEFAULT_RESOLUTION : resolution.trim();
        if (!RESOLUTIONS.contains(value)) {
            throw new IllegalArgumentException("inventory resolution must be one of " + RESOLUTIONS + ": " + value);
        }
        return value;
    }

    private static List<String> requireMask(List<String> mask, int width, int rows) {
        if (mask == null) {
            return List.of();
        }
        if (mask.size() > rows) {
            throw new IllegalArgumentException("inventory mask declares " + mask.size()
                + " rows but the resolution has " + rows);
        }
        for (String row : mask) {
            if (row == null || row.length() != width) {
                throw new IllegalArgumentException("inventory mask row \"" + row + "\" must be exactly "
                    + width + " characters wide");
            }
        }
        return List.copyOf(mask);
    }

    private static Map<String, ComponentData> requireSlots(Map<String, ComponentData> slots, int size) {
        if (slots == null) {
            return Map.of();
        }
        Map<String, ComponentData> copied = new LinkedHashMap<>(slots.size());
        for (Map.Entry<String, ComponentData> entry : slots.entrySet()) {
            int index;
            try {
                index = Integer.parseInt(entry.getKey().trim());
            } catch (NumberFormatException notAnIndex) {
                throw new IllegalArgumentException("inventory slot key must be a slot index: " + entry.getKey());
            }
            if (index < 0 || index >= size) {
                throw new IllegalArgumentException("inventory slot " + index + " is outside the window (0.."
                    + (size - 1) + ")");
            }
            if (entry.getValue() == null) {
                throw new IllegalArgumentException("inventory slot " + index + " declares no component");
            }
            copied.put(String.valueOf(index), entry.getValue());
        }
        return Map.copyOf(copied);
    }

    private static ListSection requireList(ListSection list, List<String> mask) {
        if (list == null) {
            return null;
        }
        char area = list.area().charAt(0);
        boolean present = mask.stream().anyMatch(row -> row.indexOf(area) >= 0);
        if (!present) {
            throw new IllegalArgumentException("inventory list area '" + area + "' does not appear in the mask");
        }
        int areaSize = (int) mask.stream().flatMapToInt(String::chars).filter(value -> value == area).count();
        return list.withResolvedPageSize(areaSize);
    }

    private static List<Variant> requireVariants(List<Variant> variants, int width, int rows) {
        if (variants == null) {
            return List.of();
        }
        Set<String> ids = new HashSet<>(variants.size());
        for (Variant variant : variants) {
            if (variant == null) {
                throw new IllegalArgumentException("inventory variants may not contain null entries");
            }
            if (!ids.add(variant.id())) {
                throw new IllegalArgumentException("inventory variant id is duplicated: " + variant.id());
            }
            requireMask(variant.presentation().mask(), width, rows);
            requireSlots(variant.presentation().slots(), width * rows);
        }
        return List.copyOf(variants);
    }

    /** A paged list of one template, filling every cell of one mask character. */
    public record ListSection(String area, String var, String source, Integer pageSize,
                              ComponentData template, Integer refreshTicks) {
        public static final int DEFAULT_REFRESH_TICKS = 20;
        public static final int MAX_REFRESH_TICKS = 1200;

        public ListSection {
            area = area == null ? "" : area.trim();
            if (area.length() != 1) {
                throw new IllegalArgumentException("inventory list area must be exactly one mask character");
            }
            var = var == null ? "" : var.trim();
            if (!var.matches("[a-z][a-z0-9_]*")) {
                throw new IllegalArgumentException("inventory list var must match [a-z][a-z0-9_]*: " + var);
            }
            if (source == null || source.isBlank()) {
                throw new IllegalArgumentException("inventory list " + var + " requires a source expression");
            }
            source = source.trim();
            if (template == null) {
                throw new IllegalArgumentException("inventory list " + var + " requires a template component");
            }
            if (pageSize != null && pageSize < 1) {
                throw new IllegalArgumentException("inventory list pageSize must be at least 1");
            }
            refreshTicks = refreshTicks == null
                ? DEFAULT_REFRESH_TICKS
                : Math.clamp(refreshTicks.intValue(), 0, MAX_REFRESH_TICKS);
        }

        ListSection withResolvedPageSize(int areaSize) {
            return pageSize != null
                ? this
                : new ListSection(area, var, source, Math.max(1, areaSize), template, refreshTicks);
        }
    }

    /** The board selection model: this document offers itself at {@code priority} when {@code when} holds. */
    public record Selection(int priority, String when) {
        public static final Selection NEVER = new Selection(0, "false");

        public Selection {
            when = when == null || when.isBlank() ? "false" : when.trim();
            ConditionCompiler.compile(new ConditionSource("inventories.select.when", when));
        }
    }

    /** A variant's replacement window; nothing merges with the base document. */
    public record Presentation(String title, List<String> mask, Map<String, ComponentData> keys,
                               Map<String, ComponentData> slots) {
        public Presentation {
            title = title == null ? "" : title;
            mask = mask == null ? List.of() : List.copyOf(mask);
            keys = keys == null ? Map.of() : Map.copyOf(keys);
            slots = slots == null ? Map.of() : Map.copyOf(slots);
        }
    }

    /** One conditional replacement of the base window. */
    public record Variant(String id, int priority, String when, Presentation presentation) {
        public Variant {
            id = id == null ? "" : id.trim();
            if (id.isEmpty()) {
                throw new IllegalArgumentException("inventory variant requires an id");
            }
            when = when == null || when.isBlank() ? "false" : when.trim();
            ConditionCompiler.compile(new ConditionSource("inventories.variants." + id + ".when", when));
            if (presentation == null) {
                throw new IllegalArgumentException("inventory variant " + id + " requires a presentation");
            }
        }
    }
}
