package art.arcane.gloss.command;

import art.arcane.gloss.GlossConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportPreviewCacheTest {
    @Test
    void boundsCountAcrossFormatsAndKeepsReviewedContentOnRefusal() {
        CommandGlossImport.PreviewCache cache = new CommandGlossImport.PreviewCache();
        GlossConfig.Imports limits = limits(1, 32, 10);
        CommandGlossImport.PreviewKey first = key("holoui", "operator-one");
        WeightedPreview reviewed = new WeightedPreview(12);
        assertTrue(cache.remember(first, reviewed, limits, 0));
        assertFalse(cache.remember(key("legacy", "operator-two"), new WeightedPreview(8), limits, 0));
        assertSame(reviewed, cache.take(first, 1));
        assertTrue(cache.remember(key("legacy", "operator-two"), new WeightedPreview(8), limits, 1));
    }

    @Test
    void replacementAccountsForReleasedBytesWithoutEvictingOtherSenders() {
        CommandGlossImport.PreviewCache cache = new CommandGlossImport.PreviewCache();
        GlossConfig.Imports limits = limits(4, 20, 10);
        CommandGlossImport.PreviewKey first = key("holoui", "operator-one");
        CommandGlossImport.PreviewKey second = key("legacy", "operator-two");
        WeightedPreview reviewed = new WeightedPreview(12);
        assertTrue(cache.remember(first, reviewed, limits, 0));
        assertFalse(cache.remember(second, new WeightedPreview(9), limits, 0));
        assertFalse(cache.remember(first, new WeightedPreview(21), limits, 0));
        assertSame(reviewed, cache.take(first, 1));
        assertTrue(cache.remember(first, new WeightedPreview(12), limits, 1));
        assertTrue(cache.remember(first, new WeightedPreview(4), limits, 1));
        assertTrue(cache.remember(second, new WeightedPreview(16), limits, 1));
        cache.clear();
        assertTrue(cache.isEmpty());
        assertTrue(cache.remember(first, new WeightedPreview(20), limits, 2));
    }

    @Test
    void idleExpirationReleasesBytesAtEachCapturedLifetime() {
        CommandGlossImport.PreviewCache cache = new CommandGlossImport.PreviewCache();
        CommandGlossImport.PreviewKey first = key("holoui", "operator");
        CommandGlossImport.PreviewKey second = key("legacy", "operator");
        assertTrue(cache.remember(first, new WeightedPreview(10), limits(2, 20, 1), 0));
        assertTrue(cache.remember(second, new WeightedPreview(10), limits(2, 20, 2), 0));
        cache.expire(1_000_000_000L);
        assertNull(cache.take(first, 1_000_000_000L));
        assertFalse(cache.isEmpty());
        assertTrue(cache.remember(first, new WeightedPreview(10), limits(2, 20, 1), 1_000_000_000L));
        cache.expire(2_000_000_000L);
        assertTrue(cache.isEmpty());
    }

    private static GlossConfig.Imports limits(int count, int bytes, int seconds) {
        return new GlossConfig.Imports(1, 1, 8, seconds, count, bytes);
    }

    private static CommandGlossImport.PreviewKey key(String format, String sender) {
        return new CommandGlossImport.PreviewKey(format, sender);
    }

    private record WeightedPreview(long retainedBytes) implements CommandGlossImport.Preview {
    }
}
