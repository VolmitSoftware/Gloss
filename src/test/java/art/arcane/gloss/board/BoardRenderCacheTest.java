package art.arcane.gloss.board;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A board carrying one animated line used to re-render every dynamic line, placeholders included,
 * twenty times a second for every viewer. These pin the split: fast lines still render every pass,
 * everything else is served from the viewer's last render until the ordinary interval elapses.
 */
class BoardRenderCacheTest {
    private static final long INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(1000L);
    private static final UUID VIEWER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void fastLinesRenderEveryPassAndSlowLinesAreServedFromTheCache() {
        GlossBoardMeta.RenderPlan plan = plan("Title", "{{ floor(time.seconds * 4) }}", "%vault_prefix%");
        BoardRenderCache cache = new BoardRenderCache();
        List<String> rendered = new ArrayList<>();
        UnaryOperator<String> render = counting(rendered);

        String[] first = cache.entry(VIEWER).lines(plan, 0L, INTERVAL_NANOS, 0L, render);
        rendered.clear();
        String[] second = cache.entry(VIEWER).lines(plan, INTERVAL_NANOS / 2, INTERVAL_NANOS, 0L, render);

        assertArrayEquals(first, second);
        assertEquals(List.of("{{ floor(time.seconds * 4) }}"), rendered);
    }

    @Test
    void slowLinesRefreshOnceTheOrdinaryIntervalElapses() {
        GlossBoardMeta.RenderPlan plan = plan("Title", "{{ floor(time.seconds * 4) }}", "%vault_prefix%");
        BoardRenderCache cache = new BoardRenderCache();
        List<String> rendered = new ArrayList<>();
        UnaryOperator<String> render = counting(rendered);

        cache.entry(VIEWER).lines(plan, 0L, INTERVAL_NANOS, 0L, render);
        rendered.clear();
        cache.entry(VIEWER).lines(plan, INTERVAL_NANOS, INTERVAL_NANOS, 0L, render);

        assertEquals(List.of("{{ floor(time.seconds * 4) }}", "%vault_prefix%"), rendered);
    }

    @Test
    void aNewPlanDropsTheCachedValues() {
        BoardRenderCache cache = new BoardRenderCache();
        List<String> rendered = new ArrayList<>();
        UnaryOperator<String> render = counting(rendered);
        GlossBoardMeta.RenderPlan first = plan("Title", "{{ time.ticks }}", "%vault_prefix%");

        cache.entry(VIEWER).lines(first, 0L, INTERVAL_NANOS, 0L, render);
        rendered.clear();
        GlossBoardMeta.RenderPlan second = plan("Title", "{{ time.ticks }}", "%other%");
        cache.entry(VIEWER).lines(second, 1L, INTERVAL_NANOS, 0L, render);

        assertEquals(List.of("{{ time.ticks }}", "%other%"), rendered);
    }

    @Test
    void staticLinesAreNeverRenderedByTheViewerPass() {
        GlossBoardMeta.RenderPlan plan = plan("Title", "&7plain", "{{ time.ticks }}");
        BoardRenderCache cache = new BoardRenderCache();
        List<String> rendered = new ArrayList<>();

        String[] lines = cache.entry(VIEWER).lines(plan, 0L, INTERVAL_NANOS, 0L, counting(rendered));

        assertEquals("&7plain", lines[0]);
        assertEquals(List.of("{{ time.ticks }}"), rendered);
    }

    @Test
    void theTitleFollowsTheSameSplit() {
        GlossBoardMeta.RenderPlan plan = plan("%server_online%", "{{ time.ticks }}");
        BoardRenderCache cache = new BoardRenderCache();
        List<String> rendered = new ArrayList<>();
        UnaryOperator<String> render = counting(rendered);

        cache.entry(VIEWER).title(plan, 0L, INTERVAL_NANOS, 0L, render);
        rendered.clear();
        cache.entry(VIEWER).title(plan, INTERVAL_NANOS / 2, INTERVAL_NANOS, 0L, render);
        assertEquals(List.of(), rendered);

        cache.entry(VIEWER).title(plan, INTERVAL_NANOS, INTERVAL_NANOS, 0L, render);
        assertEquals(List.of("%server_online%"), rendered);
    }

    @Test
    void theViewerPhaseDelaysTheFirstSlowRefresh() {
        GlossBoardMeta.RenderPlan plan = plan("Title", "{{ time.ticks }}", "%vault_prefix%");
        BoardRenderCache cache = new BoardRenderCache();
        List<String> rendered = new ArrayList<>();
        UnaryOperator<String> render = counting(rendered);
        long phase = INTERVAL_NANOS / 2;

        cache.entry(VIEWER).lines(plan, 0L, INTERVAL_NANOS, phase, render);
        rendered.clear();
        cache.entry(VIEWER).lines(plan, phase - 1L, INTERVAL_NANOS, phase, render);
        assertEquals(List.of("{{ time.ticks }}"), rendered);

        rendered.clear();
        cache.entry(VIEWER).lines(plan, phase, INTERVAL_NANOS, phase, render);
        assertEquals(List.of("{{ time.ticks }}", "%vault_prefix%"), rendered);
    }

    @Test
    void forgettingAViewerDropsItsCachedRender() {
        GlossBoardMeta.RenderPlan plan = plan("Title", "{{ time.ticks }}", "%vault_prefix%");
        BoardRenderCache cache = new BoardRenderCache();
        List<String> rendered = new ArrayList<>();
        UnaryOperator<String> render = counting(rendered);

        cache.entry(VIEWER).lines(plan, 0L, INTERVAL_NANOS, 0L, render);
        cache.forget(VIEWER);
        rendered.clear();
        cache.entry(VIEWER).lines(plan, 1L, INTERVAL_NANOS, 0L, render);

        assertEquals(List.of("{{ time.ticks }}", "%vault_prefix%"), rendered);
    }

    private static UnaryOperator<String> counting(List<String> rendered) {
        return raw -> {
            rendered.add(raw);
            return "rendered:" + raw;
        };
    }

    private static GlossBoardMeta.RenderPlan plan(String title, String... lines) {
        GlossBoardMeta meta = new GlossBoardMeta("test");
        meta.setTitle(title);
        for (String line : lines) {
            meta.addLine(line);
        }
        return meta.renderPlan("base", meta.presentation(), 0L, 15, UnaryOperator.identity());
    }
}
