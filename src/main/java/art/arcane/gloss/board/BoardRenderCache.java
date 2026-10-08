package art.arcane.gloss.board;

import art.arcane.volmlib.util.board.BoardRowSlots;

import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/**
 * Per-viewer render memo for the animation cadence. A board is promoted to the one-tick manager as
 * soon as a single line needs per-tick refresh; without this every other dynamic line on that board
 * (placeholders included) would be re-rendered twenty times a second for every viewer. Fast lines
 * still render every pass; the rest keep the ordinary board interval.
 */
final class BoardRenderCache {
    private final Map<UUID, Entry> entries;

    BoardRenderCache() {
        this.entries = new ConcurrentHashMap<>();
    }

    Entry entry(UUID viewer) {
        return entries.computeIfAbsent(viewer, key -> new Entry());
    }

    void forget(UUID viewer) {
        entries.remove(viewer);
    }

    void clear() {
        entries.clear();
    }

    record Frame(GlossBoardMeta.RenderPlan plan, int[] rows, int[] slots) {
    }

    /** One viewer's last render. Touched only by the thread that owns that viewer. */
    static final class Entry {
        private static final long TICK_NANOS = 50_000_000L;
        private GlossBoardMeta.RenderPlan linePlan;
        private GlossBoardMeta.RenderPlan titlePlan;
        private String[] lines;
        private String[] values;
        private long[] nextLinesNanos;
        private long[] nextValuesNanos;
        private String title;
        private long nextTitleNanos;
        private Frame frame;
        private final BoardRowSlots rowSlots = new BoardRowSlots();

        Frame frame() {
            return frame;
        }

        Frame frame(GlossBoardMeta.RenderPlan plan, int[] rows) {
            List<String> identities = new ArrayList<>(rows.length);
            for (int row : rows) {
                identities.add(plan.rowId(row));
            }
            frame = new Frame(plan, rows, rowSlots.assign(identities));
            return frame;
        }

        String[] lines(GlossBoardMeta.RenderPlan plan, long nowNanos, long intervalNanos, long phaseNanos,
                       UnaryOperator<String> render) {
            int[] selected = new int[plan.lineCount()];
            for (int index = 0; index < selected.length; index++) {
                selected[index] = index;
            }
            return lines(plan, selected, nowNanos, intervalNanos, phaseNanos, render);
        }

        String[] lines(GlossBoardMeta.RenderPlan plan, int[] selected, long nowNanos, long intervalNanos,
                       long phaseNanos, UnaryOperator<String> render) {
            prepare(plan);
            String[] out = new String[selected.length];
            for (int position = 0; position < selected.length; position++) {
                int index = selected[position];
                String cached = plan.staticLine(index);
                if (cached != null) {
                    out[position] = cached;
                    continue;
                }
                boolean first = lines[index] == null;
                boolean fast = plan.refresh().textTicks() == null && plan.fastLine(index);
                if (first || fast || nowNanos - nextLinesNanos[index] >= 0L) {
                    lines[index] = render.apply(plan.rawLine(index));
                    nextLinesNanos[index] = nextRefreshNanos(nowNanos,
                        interval(plan.refresh().textTicks(), intervalNanos), phaseNanos, first);
                }
                out[position] = lines[index];
            }
            return out;
        }

        String value(GlossBoardMeta.RenderPlan plan, int index, long nowNanos, long intervalNanos,
                     long phaseNanos, UnaryOperator<String> render) {
            String cached = plan.staticValue(index);
            if (cached != null || plan.rawValue(index) == null) {
                return cached;
            }
            prepare(plan);
            boolean first = values[index] == null;
            boolean fast = plan.refresh().valueTicks() == null && plan.fastValue(index);
            if (first || fast || nowNanos - nextValuesNanos[index] >= 0L) {
                values[index] = render.apply(plan.rawValue(index));
                nextValuesNanos[index] = nextRefreshNanos(nowNanos,
                    interval(plan.refresh().valueTicks(), intervalNanos), phaseNanos, first);
            }
            return values[index];
        }

        String title(GlossBoardMeta.RenderPlan plan, long nowNanos, long intervalNanos, long phaseNanos,
                     UnaryOperator<String> render) {
            String cached = plan.staticTitle();
            if (cached != null) {
                return cached;
            }
            boolean first = titlePlan != plan || title == null;
            boolean fast = plan.refresh().titleTicks() == null && plan.fastTitle();
            if (!first && !fast && nowNanos - nextTitleNanos < 0L) {
                return title;
            }
            String rendered = render.apply(plan.rawTitle());
            nextTitleNanos = nextRefreshNanos(nowNanos,
                interval(plan.refresh().titleTicks(), intervalNanos), phaseNanos, first);
            titlePlan = plan;
            title = rendered;
            return rendered;
        }

        private void prepare(GlossBoardMeta.RenderPlan plan) {
            if (linePlan == plan) {
                return;
            }
            linePlan = plan;
            lines = new String[plan.lineCount()];
            values = new String[plan.lineCount()];
            nextLinesNanos = new long[plan.lineCount()];
            nextValuesNanos = new long[plan.lineCount()];
        }

        private static long interval(Integer ticks, long fallback) {
            return ticks == null ? fallback : ticks * TICK_NANOS;
        }

        /**
         * The viewer phase shortens only the first wait, so the fleet's slow refreshes land on
         * different ticks instead of all firing on the same one.
         */
        private static long nextRefreshNanos(long nowNanos, long intervalNanos, long phaseNanos, boolean first) {
            if (!first) {
                return nowNanos + intervalNanos;
            }
            long phase = intervalNanos <= 0L ? 0L : Math.floorMod(phaseNanos, intervalNanos);
            return nowNanos + intervalNanos - phase;
        }
    }
}
