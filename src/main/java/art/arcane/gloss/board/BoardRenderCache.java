package art.arcane.gloss.board;

import java.util.Map;
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

    /** One viewer's last render. Touched only by the thread that owns that viewer. */
    static final class Entry {
        private GlossBoardMeta.RenderPlan linePlan;
        private GlossBoardMeta.RenderPlan titlePlan;
        private String[] lines;
        private String title;
        private long nextLinesNanos;
        private long nextTitleNanos;

        String[] lines(GlossBoardMeta.RenderPlan plan, long nowNanos, long intervalNanos, long phaseNanos,
                       UnaryOperator<String> render) {
            String[] previous = linePlan == plan ? lines : null;
            boolean refreshSlow = previous == null || nowNanos - nextLinesNanos >= 0L;
            int count = plan.lineCount();
            String[] out = new String[count];
            for (int index = 0; index < count; index++) {
                String cached = plan.staticLine(index);
                if (cached != null) {
                    out[index] = cached;
                    continue;
                }
                if (plan.fastLine(index) || refreshSlow || previous[index] == null) {
                    out[index] = render.apply(plan.rawLine(index));
                    continue;
                }
                out[index] = previous[index];
            }
            if (refreshSlow) {
                nextLinesNanos = nextRefreshNanos(nowNanos, intervalNanos, phaseNanos, previous == null);
            }
            linePlan = plan;
            lines = out;
            return out;
        }

        String title(GlossBoardMeta.RenderPlan plan, long nowNanos, long intervalNanos, long phaseNanos,
                     UnaryOperator<String> render) {
            String cached = plan.staticTitle();
            if (cached != null) {
                return cached;
            }
            boolean first = titlePlan != plan || title == null;
            if (!first && !plan.fastTitle() && nowNanos - nextTitleNanos < 0L) {
                return title;
            }
            String rendered = render.apply(plan.rawTitle());
            if (first || !plan.fastTitle()) {
                nextTitleNanos = nextRefreshNanos(nowNanos, intervalNanos, phaseNanos, first);
            }
            titlePlan = plan;
            title = rendered;
            return rendered;
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
