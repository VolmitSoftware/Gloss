package art.arcane.gloss.hologram;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** One page of a paged hologram. Each viewer reads exactly one page at a time. */
public record HologramPage(String id, List<HologramLine> lines) {
    public static final String NEXT = "next";
    public static final String PREVIOUS = "prev";

    public HologramPage {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("hologram page needs an id");
        }
        id = id.trim();
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("hologram page " + id + " needs at least one line");
        }
        List<HologramLine> copied = new ArrayList<>(lines.size());
        for (HologramLine line : lines) {
            copied.add(line == null ? HologramLine.text("") : line);
        }
        lines = List.copyOf(copied);
    }

    /** The first page id, or null when the hologram is not paged. */
    public static String firstId(List<HologramPage> pages) {
        return pages == null || pages.isEmpty() ? null : pages.getFirst().id();
    }

    public static boolean contains(List<HologramPage> pages, String id) {
        return indexOf(pages, id) >= 0;
    }

    /**
     * The page a {@code next}, {@code prev} or exact-id target selects, or null when the target
     * names no page. Both relative targets wrap around, and a viewer who has not navigated counts
     * as reading the first page.
     */
    public static String resolve(List<HologramPage> pages, String current, String target) {
        if (pages == null || pages.isEmpty() || target == null || target.isBlank()) {
            return null;
        }
        String requested = target.trim();
        String normalized = requested.toLowerCase(Locale.ROOT);
        int index = Math.max(0, indexOf(pages, current));
        if (NEXT.equals(normalized)) {
            return pages.get((index + 1) % pages.size()).id();
        }
        if (PREVIOUS.equals(normalized)) {
            return pages.get((index + pages.size() - 1) % pages.size()).id();
        }
        return contains(pages, requested) ? requested : null;
    }

    private static int indexOf(List<HologramPage> pages, String id) {
        if (pages == null || id == null) {
            return -1;
        }
        for (int index = 0; index < pages.size(); index++) {
            if (pages.get(index).id().equals(id)) {
                return index;
            }
        }
        return -1;
    }
}
