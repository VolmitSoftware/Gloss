package art.arcane.gloss.inventory;

import java.util.List;

/** Page arithmetic for a list section. Every entry point clamps, so no click can page off the end. */
public final class InventoryPager {
    public static final String NEXT = "next";
    public static final String PREVIOUS = "prev";

    private InventoryPager() {
    }

    public static List<Object> page(List<Object> source, int page, int pageSize) {
        if (source == null || source.isEmpty() || pageSize < 1) {
            return List.of();
        }
        int from = Math.max(0, page) * pageSize;
        if (from >= source.size()) {
            return List.of();
        }
        return List.copyOf(source.subList(from, Math.min(source.size(), from + pageSize)));
    }

    /** At least one page, so an empty list still draws an empty window instead of nothing. */
    public static int pages(int entries, int pageSize) {
        if (pageSize < 1 || entries <= 0) {
            return 1;
        }
        return Math.max(1, (entries + pageSize - 1) / pageSize);
    }

    public static int clamp(int page, int entries, int pageSize) {
        return Math.clamp(page, 0, pages(entries, pageSize) - 1);
    }

    /** Resolves a {@code navigate mode: page} target against the page the viewer is on. */
    public static int target(String target, int currentPage, int entries, int pageSize) {
        if (NEXT.equalsIgnoreCase(target)) {
            return clamp(currentPage + 1, entries, pageSize);
        }
        if (PREVIOUS.equalsIgnoreCase(target)) {
            return clamp(currentPage - 1, entries, pageSize);
        }
        try {
            return clamp(Integer.parseInt(target.trim()), entries, pageSize);
        } catch (NumberFormatException | NullPointerException notAPage) {
            return clamp(currentPage, entries, pageSize);
        }
    }
}
