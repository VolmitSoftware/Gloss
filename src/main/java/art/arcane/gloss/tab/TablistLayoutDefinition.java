package art.arcane.gloss.tab;

import art.arcane.gloss.expr.ExprParser;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class TablistLayoutDefinition {
    public static final String SLOT_NAME_PREFIX = " gloss_slot_";
    private TablistLayoutDefinition() {
    }

    public record LayoutPresentation(int entries, List<Slot> slots, List<Section> sections, Map<String, Skin> skins) {
        public LayoutPresentation {
            requireRange(entries, 1, 80, "tablist layout entries");
            slots = slots == null ? List.of() : List.copyOf(slots);
            sections = sections == null ? List.of() : List.copyOf(sections);
            skins = skins == null ? Map.of() : Map.copyOf(skins);
            int columns = (entries + 19) / 20;
            int rows = (entries + columns - 1) / columns;
            Set<Integer> taken = new HashSet<>();
            Set<String> ids = new HashSet<>();
            for (Slot slot : slots) {
                requireCell(slot.column(), slot.row(), columns, rows, entries);
                if (!taken.add(slot.column() * rows + slot.row())) {
                    throw new IllegalArgumentException("tablist layout slots overlap");
                }
            }
            for (Section section : sections) {
                requireUnique(ids, section.id(), "tablist roster section");
                for (int column = section.column(); column < section.column() + section.columns(); column++) {
                    for (int row = section.row(); row < section.row() + section.rows(); row++) {
                        requireCell(column, row, columns, rows, entries);
                        if (!taken.add(column * rows + row)) {
                            throw new IllegalArgumentException("tablist section " + section.id() + " overlaps another section or fixed slot");
                        }
                    }
                }
            }
            for (Map.Entry<String, Skin> skin : skins.entrySet()) {
                normalizeId(skin.getKey(), "tablist skin");
            }
        }

        public int columns() {
            return (entries + 19) / 20;
        }

        public int rows() {
            return (entries + columns() - 1) / columns();
        }
    }

    public record LayoutVariant(String id, int priority, String when, LayoutPresentation presentation) {
        public LayoutVariant {
            id = normalizeId(id, "tablist layout variant");
            when = normalizeCondition(when, "tablist layout variant " + id);
            ExprParser.parse(when);
            if (presentation == null) {
                throw new IllegalArgumentException("tablist layout variant requires a presentation");
            }
        }
    }

    public record Skin(String value, String signature) {
        public Skin {
            if (value == null || value.isBlank() || value.length() > 16384) {
                throw new IllegalArgumentException("tablist skin value requires 1..16384 characters");
            }
            if (signature != null && signature.length() > 16384) {
                throw new IllegalArgumentException("tablist skin signature may not exceed 16384 characters");
            }
        }
    }

    public record Slot(int column, int row, String text, String skin, Integer ping, Boolean hat) {
        public Slot {
            text = text == null ? "" : text;
            skin = skin == null || skin.isBlank() ? null : skin.trim();
            ping = ping == null ? null : Integer.valueOf(Math.clamp(ping.intValue(), -1, 10_000));
            hat = hat == null ? Boolean.TRUE : hat;
        }
    }

    public record Section(String id, int column, int row, int columns, int rows, String filter, String format,
                          List<SortKey> sort, String overflow, String overflowFormat, Boolean includeNpcs,
                          String skin, Boolean hat) {
        public Section {
            id = normalizeId(id, "tablist roster section");
            requireRange(column, 0, 3, "tablist section column");
            requireRange(row, 0, 19, "tablist section row");
            requireRange(columns, 1, 4, "tablist section columns");
            requireRange(rows, 1, 20, "tablist section rows");
            filter = filter == null || filter.isBlank() ? "true" : filter.trim();
            ExprParser.parse(filter);
            sort = sort == null ? List.of() : List.copyOf(sort);
            if (sort.size() > 16) {
                throw new IllegalArgumentException("tablist section supports at most 16 sort keys");
            }
            overflow = overflow == null || overflow.isBlank() ? "hide" : overflow.toLowerCase(Locale.ROOT);
            if (!overflow.equals("hide") && !overflow.equals("count")) {
                throw new IllegalArgumentException("tablist section overflow must be hide or count");
            }
            overflowFormat = overflowFormat == null ? "+{count}" : overflowFormat;
            includeNpcs = includeNpcs == null ? Boolean.FALSE : includeNpcs;
            skin = skin == null || skin.isBlank() ? null : skin.trim();
            hat = hat == null ? Boolean.TRUE : hat;
        }

        public boolean countsOverflow() {
            return overflow.equals("count");
        }
    }

    public record SortKey(String expression, String type, String direction) {
        public SortKey {
            if (expression == null || expression.isBlank()) {
                throw new IllegalArgumentException("tablist sort key requires an expression");
            }
            ExprParser.parse(expression);
            type = type == null ? "text" : type.toLowerCase(Locale.ROOT);
            direction = direction == null ? "ascending" : direction.toLowerCase(Locale.ROOT);
            if (!type.equals("number") && !type.equals("text")) {
                throw new IllegalArgumentException("tablist sort key type must be number or text");
            }
            if (!direction.equals("ascending") && !direction.equals("descending")) {
                throw new IllegalArgumentException("tablist sort key direction must be ascending or descending");
            }
        }
    }

    private static void requireCell(int column, int row, int columns, int rows, int entries) {
        if (column < 0 || column >= columns || row < 0 || row >= rows || column * rows + row >= entries) {
            throw new IllegalArgumentException("tablist cell " + column + "," + row + " falls outside the " + entries + " client entries");
        }
    }

    private static int requireRange(int value, int min, int max, String owner) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(owner + " must be between " + min + " and " + max + ": " + value);
        }
        return value;
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
