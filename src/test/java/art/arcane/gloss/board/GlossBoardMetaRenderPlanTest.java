package art.arcane.gloss.board;

import art.arcane.gloss.text.TextPipeline;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class GlossBoardMetaRenderPlanTest {
    private static final int MAX_LINES = 15;

    private final List<String> rendered = new ArrayList<>();
    private final UnaryOperator<String> staticRender = raw -> {
        rendered.add(raw);
        return raw.toUpperCase(Locale.ROOT);
    };

    @AfterEach
    void clearPublishedTriggers() {
        TextPipeline.publishEmojiTriggers(List.of());
    }

    @Test
    void plainLinesAreServedRawWithoutTouchingTheRenderer() {
        GlossBoardMeta meta = new GlossBoardMeta("stats");
        meta.setTitle("Server");
        meta.addLine("Welcome");

        GlossBoardMeta.RenderPlan plan = plan(meta);

        assertEquals(List.of(), rendered);
        assertEquals("Server", plan.staticTitle());
        assertEquals("Welcome", plan.staticLine(0));
    }

    @Test
    void colorOnlyLinesAreRenderedOnceAndSharedAcrossLookups() {
        GlossBoardMeta meta = new GlossBoardMeta("stats");
        meta.setTitle("&aServer");
        meta.addLine("&7Online");

        GlossBoardMeta.RenderPlan first = plan(meta);
        GlossBoardMeta.RenderPlan second = plan(meta);

        assertSame(first, second);
        assertEquals(List.of("&aServer", "&7Online"), rendered);
        assertEquals("&ASERVER", first.staticTitle());
        assertEquals("&7ONLINE", first.staticLine(0));
    }

    @Test
    void placeholderAndFunctionLinesStayViewerDependent() {
        GlossBoardMeta meta = new GlossBoardMeta("stats");
        meta.setTitle("%server_name%");
        meta.addLine("&7Online");
        meta.addLine("Ping: %player_ping%");
        meta.addLine("Time: |clock|");

        GlossBoardMeta.RenderPlan plan = plan(meta);

        assertNull(plan.staticTitle());
        assertEquals("%server_name%", plan.rawTitle());
        assertEquals("&7ONLINE", plan.staticLine(0));
        assertNull(plan.staticLine(1));
        assertEquals("Ping: %player_ping%", plan.rawLine(1));
        assertNull(plan.staticLine(2));
        assertEquals("Time: |clock|", plan.rawLine(2));
        assertEquals(List.of("&7Online"), rendered);
    }

    @Test
    void aContentChangeRebuildsThePlan() {
        GlossBoardMeta meta = new GlossBoardMeta("stats");
        meta.addLine("&7Online");
        GlossBoardMeta.RenderPlan first = plan(meta);

        meta.setLine(0, "&7Offline");
        GlossBoardMeta.RenderPlan second = plan(meta);

        assertNotSame(first, second);
        assertEquals("&7OFFLINE", second.staticLine(0));
        assertEquals(List.of("&7Online", "&7Offline"), rendered);
    }

    @Test
    void aTitleChangeRebuildsThePlan() {
        GlossBoardMeta meta = new GlossBoardMeta("stats");
        meta.setTitle("&aOne");
        GlossBoardMeta.RenderPlan first = plan(meta);

        meta.setTitle("&aTwo");

        assertNotSame(first, plan(meta));
        assertEquals("&ATWO", plan(meta).staticTitle());
    }

    @Test
    void anEmojiRegistryChangeRebuildsThePlan() {
        GlossBoardMeta meta = new GlossBoardMeta("stats");
        meta.addLine(":heart:");
        GlossBoardMeta.RenderPlan first = meta.renderPlan(1L, MAX_LINES, staticRender);
        GlossBoardMeta.RenderPlan second = meta.renderPlan(2L, MAX_LINES, staticRender);

        assertNotSame(first, second);
        assertEquals(2, rendered.size());
    }

    @Test
    void aPublishedTriggerMakesAnOtherwisePlainLineRenderThroughTheEmojiStage() {
        TextPipeline.publishEmojiTriggers(List.of("<3"));
        GlossBoardMeta meta = new GlossBoardMeta("stats");
        meta.addLine("love <3");

        GlossBoardMeta.RenderPlan plan = plan(meta);

        assertEquals(List.of("love <3"), rendered);
        assertEquals("LOVE <3", plan.staticLine(0));
    }

    @Test
    void theStaticTitleIsPreservedForSharedBoardFitting() {
        GlossBoardMeta meta = new GlossBoardMeta("stats");
        meta.setTitle("&aabcdefghijklmnopqrstuvwxyz\uD840\uDC00tail");

        GlossBoardMeta.RenderPlan plan = meta.renderPlan(0L, MAX_LINES, staticRender);

        assertEquals("&AABCDEFGHIJKLMNOPQRSTUVWXYZ\uD840\uDC00TAIL", plan.staticTitle());
    }

    @Test
    void linesBeyondTheCapAreDropped() {
        GlossBoardMeta meta = new GlossBoardMeta("stats");
        for (int index = 0; index < MAX_LINES + 4; index++) {
            meta.addLine("line " + index);
        }

        assertEquals(MAX_LINES, plan(meta).lineCount());
    }

    @Test
    void aNullRenderResultBecomesAnEmptyString() {
        GlossBoardMeta meta = new GlossBoardMeta("stats");
        meta.addLine("&7Online");

        GlossBoardMeta.RenderPlan plan = meta.renderPlan(0L, MAX_LINES, raw -> null);

        assertEquals("", plan.staticLine(0));
    }

    private GlossBoardMeta.RenderPlan plan(GlossBoardMeta meta) {
        return meta.renderPlan(0L, MAX_LINES, staticRender);
    }
}
