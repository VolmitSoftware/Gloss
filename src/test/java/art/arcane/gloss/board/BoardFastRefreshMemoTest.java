package art.arcane.gloss.board;

import art.arcane.gloss.text.TextPipeline;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The selection sweep asks every board whether it needs the one-tick cadence, per player, per
 * interval; answering it used to re-parse every expression in the board and in each of its
 * variants. The answer is memoised, but it is a function of the document *and* of the published
 * conditional-emoji table, so the memo carries both generations and a stale write can never be
 * served.
 */
class BoardFastRefreshMemoTest {
    @AfterEach
    void resetEmojiState() {
        TextPipeline.publishConditionalEmojiTokens(List.of());
    }

    @Test
    void theAnswerIsComputedOncePerRevision() throws Exception {
        GlossBoardMeta meta = board("Static", "{{ floor(time.seconds * 4) }}");

        assertNull(memo(meta), "nothing should be computed before the first question");
        assertTrue(meta.usesFastRefreshText());
        Object first = memo(meta);
        assertNotNull(first);
        assertTrue(meta.usesFastRefreshText());
        assertSame(first, memo(meta), "a second question must not recompute");
    }

    @Test
    void editingTheContentMovesTheAnswer() {
        GlossBoardMeta meta = board("Static", "%vault_prefix%");

        assertFalse(meta.usesFastRefreshText());
        meta.addLine("|animation.rainbow|");
        assertTrue(meta.usesFastRefreshText());
        meta.removeLine(1);
        assertFalse(meta.usesFastRefreshText());
    }

    @Test
    void replacingTheVariantsMovesTheAnswer() {
        GlossBoardMeta meta = board("Static", "%vault_prefix%");
        assertFalse(meta.usesFastRefreshText());

        meta.setVariants(List.of(new BoardDoc.Variant("animated", 1, "true",
            new BoardDoc.Presentation("Static", List.of("{{ time.ticks }}"), false))));
        assertTrue(meta.usesFastRefreshText());

        meta.setVariants(List.of());
        assertFalse(meta.usesFastRefreshText());
    }

    @Test
    void aConditionalEmojiOnALineMovesTheAnswerWithTheRegistry() {
        GlossBoardMeta meta = board("Static", "&7Rank &f:vip:");

        assertFalse(meta.usesFastRefreshText());
        TextPipeline.publishConditionalEmojiTokens(List.of(":vip:"));
        assertTrue(meta.usesFastRefreshText(), "a conditional emoji must promote the board");
        TextPipeline.publishConditionalEmojiTokens(List.of());
        assertFalse(meta.usesFastRefreshText(), "removing it must release the board again");
    }

    @Test
    void aConditionalEmojiInAVariantIsTrackedToo() {
        GlossBoardMeta meta = board("Static", "plain");
        meta.setVariants(List.of(new BoardDoc.Variant("vip", 1, "true",
            new BoardDoc.Presentation("Static", List.of("&7:vip:"), false))));

        assertFalse(meta.usesFastRefreshText());
        TextPipeline.publishConditionalEmojiTokens(List.of(":vip:"));
        assertTrue(meta.usesFastRefreshText());
    }

    @Test
    void theBasePresentationAndProfileAreSharedUntilTheContentChanges() {
        GlossBoardMeta meta = board("Title", "a", "b");

        BoardDoc.Presentation presentation = meta.presentation();
        assertSame(presentation, meta.presentation());

        meta.setLine(0, "c");
        BoardDoc.Presentation rebuilt = meta.presentation();
        assertNotNull(rebuilt);
        assertNotSame(presentation, rebuilt, "a content change must rebuild the presentation");
        assertEquals(List.of("c", "b"), rebuilt.lines());
        assertSame(rebuilt, meta.presentation());
    }

    @Test
    void theCachedBaseCarriesTheContentGenerationItWasBuiltFrom() throws Exception {
        GlossBoardMeta meta = board("Title", "a");

        meta.presentation();
        assertEquals(contentGeneration(meta), cachedGeneration(meta),
            "an unstamped cache would serve a value written after the next edit");

        meta.setLine(0, "b");
        meta.presentation();
        assertEquals(contentGeneration(meta), cachedGeneration(meta));
    }

    private static long contentGeneration(GlossBoardMeta meta) throws Exception {
        Field field = GlossBoardMeta.class.getDeclaredField("contentGeneration");
        field.setAccessible(true);
        return ((AtomicLong) field.get(meta)).get();
    }

    private static long cachedGeneration(GlossBoardMeta meta) throws Exception {
        Field field = GlossBoardMeta.class.getDeclaredField("base");
        field.setAccessible(true);
        Object cached = field.get(meta);
        assertNotNull(cached);
        Method accessor = cached.getClass().getDeclaredMethod("contentGeneration");
        accessor.setAccessible(true);
        return (long) accessor.invoke(cached);
    }

    private static Object memo(GlossBoardMeta meta) throws Exception {
        Field field = GlossBoardMeta.class.getDeclaredField("fastRefreshText");
        field.setAccessible(true);
        return field.get(meta);
    }

    private static GlossBoardMeta board(String title, String... lines) {
        GlossBoardMeta meta = new GlossBoardMeta("test");
        meta.setTitle(title);
        for (String line : lines) {
            meta.addLine(line);
        }
        return meta;
    }
}
