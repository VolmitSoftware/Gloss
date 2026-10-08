package art.arcane.gloss.board;

import art.arcane.gloss.condition.BoundedConditionErrorCallback;
import art.arcane.gloss.condition.ShowCondition;
import art.arcane.gloss.expr.ExprScope;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public record BoardLayout(Map<String, List<BoardLine>> sections, List<Page> pages, String overflow, Refresh refresh) {
    public static final int MAX_AUTHORED_ROWS = 256;
    public static final BoardLayout DEFAULTS = new BoardLayout(null, null, null, null);

    public BoardLayout {
        Map<String, List<BoardLine>> copied = new LinkedHashMap<>();
        if (sections != null) {
            if (sections.size() > 64) {
                throw new IllegalArgumentException("A board may declare at most 64 sections");
            }
            for (Map.Entry<String, List<BoardLine>> entry : sections.entrySet()) {
                copied.put(requireId(entry.getKey(), "section"), copyLines(entry.getValue()));
            }
        }
        sections = Map.copyOf(copied);
        pages = pages == null ? List.of() : List.copyOf(pages);
        if (pages.size() > 64) {
            throw new IllegalArgumentException("A board may declare at most 64 pages");
        }
        Set<String> pageIds = new HashSet<>();
        for (Page page : pages) {
            if (!pageIds.add(page.id())) {
                throw new IllegalArgumentException("Duplicate board page: " + page.id());
            }
        }
        overflow = overflow == null ? "truncate" : overflow.trim().toLowerCase(Locale.ROOT);
        if (!overflow.equals("truncate") && !overflow.equals("error")) {
            throw new IllegalArgumentException("Board overflow must be truncate or error");
        }
        refresh = refresh == null ? Refresh.DEFAULTS : refresh;
    }

    public List<BoardLine> expand(List<BoardLine> lines) {
        List<BoardLine> expanded = new ArrayList<>();
        expand(lines, ShowCondition.ALWAYS, new HashSet<>(), expanded);
        Set<String> ids = new HashSet<>();
        for (BoardLine line : expanded) {
            if (line.id() != null && !ids.add(line.id())) {
                throw new IllegalArgumentException("Duplicate board row id: " + line.id());
            }
        }
        if (overflow.equals("error") && expanded.size() > 15) {
            throw new IllegalArgumentException("Board overflow=error allows at most 15 expanded rows per page");
        }
        return List.copyOf(expanded);
    }

    public Page page(ExprScope scope, BoundedConditionErrorCallback errors, long tick) {
        if (pages.isEmpty()) {
            return null;
        }
        long duration = 0;
        long enabled = 0;
        for (int index = 0; index < pages.size(); index++) {
            Page page = pages.get(index);
            if (page.show().matches(scope, errors)) {
                duration += page.durationTicks();
                enabled |= 1L << index;
            }
        }
        if (duration == 0) {
            return null;
        }
        long position = Math.floorMod(tick, duration);
        for (int index = 0; index < pages.size(); index++) {
            if ((enabled & 1L << index) == 0) {
                continue;
            }
            Page page = pages.get(index);
            if (position < page.durationTicks()) {
                return page;
            }
            position -= page.durationTicks();
        }
        return null;
    }

    boolean needsTickDriver() {
        return !pages.isEmpty() || refresh.titleTicks() != null || refresh.textTicks() != null || refresh.valueTicks() != null;
    }

    static String requireId(String id, String owner) {
        if (id == null || !id.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("Board " + owner + " id must contain 1..64 letters, digits, dots, underscores or hyphens");
        }
        return id;
    }

    private void expand(List<BoardLine> lines, ShowCondition inherited, Set<String> path, List<BoardLine> target) {
        for (BoardLine row : lines) {
            ShowCondition show = combine(inherited, row.show());
            if (row.section() == null) {
                target.add(new BoardLine(row.text(), row.value(), row.format(), row.id(), show, null));
                if (target.size() > MAX_AUTHORED_ROWS) {
                    throw new IllegalArgumentException("A board page may expand to at most " + MAX_AUTHORED_ROWS + " rows");
                }
                continue;
            }
            List<BoardLine> section = sections.get(row.section());
            if (section == null) {
                throw new IllegalArgumentException("Unknown board section: " + row.section());
            }
            if (!path.add(row.section())) {
                throw new IllegalArgumentException("Recursive board section: " + row.section());
            }
            expand(section, show, path, target);
            path.remove(row.section());
        }
    }

    private static ShowCondition combine(ShowCondition first, ShowCondition second) {
        if (first.isAlwaysVisible()) {
            return second;
        }
        if (second.isAlwaysVisible()) {
            return first;
        }
        return ShowCondition.of("(" + first.expression() + ") && (" + second.expression() + ")");
    }

    private static List<BoardLine> copyLines(List<BoardLine> lines) {
        if (lines == null) {
            return List.of();
        }
        if (lines.size() > MAX_AUTHORED_ROWS) {
            throw new IllegalArgumentException("A board line list may contain at most " + MAX_AUTHORED_ROWS + " entries");
        }
        return List.copyOf(lines);
    }

    public record Page(String id, String title, List<BoardLine> lines, ShowCondition show, Integer durationTicks) {
        public Page {
            id = requireId(id, "page");
            lines = copyLines(lines);
            show = show == null ? ShowCondition.ALWAYS : show;
            durationTicks = durationTicks == null ? 100 : durationTicks;
            if (durationTicks < 1 || durationTicks > 72000) {
                throw new IllegalArgumentException("Board page durationTicks must be within 1..72000");
            }
        }
    }

    public record Refresh(Integer titleTicks, Integer textTicks, Integer valueTicks) {
        public static final Refresh DEFAULTS = new Refresh(null, null, null);

        public Refresh {
            validate(titleTicks);
            validate(textTicks);
            validate(valueTicks);
        }

        private static void validate(Integer ticks) {
            if (ticks != null && (ticks < 1 || ticks > 72000)) {
                throw new IllegalArgumentException("Board refresh ticks must be within 1..72000");
            }
        }
    }
}
