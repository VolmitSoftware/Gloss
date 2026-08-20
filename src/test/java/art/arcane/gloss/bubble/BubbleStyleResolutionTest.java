package art.arcane.gloss.bubble;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BubbleStyleResolutionTest {
    private static BubbleStyleDoc style(BubbleStyleDoc.Select select) {
        return new BubbleStyleDoc(2, 1L, "&7", null, 32, 5000L, true, true,
            BubbleStyleDoc.DEFAULTS.motion(), BubbleStyleDoc.DEFAULTS.shimmer(), select);
    }

    private static BubbleStyleDoc.Select select(List<String> worlds, List<String> groups, int priority) {
        return new BubbleStyleDoc.Select(worlds, groups, priority);
    }

    @Test
    void permittedExplicitChoiceWinsOverEverything() {
        Map<String, BubbleStyleDoc> styles = Map.of(
            "default", style(null),
            "fancy", style(null),
            "auto", style(select(List.of(), List.of(), 100)));

        String chosen = BubbleStyles.resolveStyleId("fancy",
            Set.of("gloss.bubbles.style.fancy")::contains, styles, "world", null);

        assertEquals("fancy", chosen);
    }

    @Test
    void unpermittedExplicitChoiceFallsThrough() {
        Map<String, BubbleStyleDoc> styles = Map.of(
            "default", style(null),
            "fancy", style(null));

        String chosen = BubbleStyles.resolveStyleId("fancy", node -> false, styles, "world", null);

        assertEquals("default", chosen);
    }

    @Test
    void unknownExplicitChoiceFallsThrough() {
        Map<String, BubbleStyleDoc> styles = Map.of("default", style(null));

        String chosen = BubbleStyles.resolveStyleId("gone", node -> true, styles, "world", null);

        assertEquals("default", chosen);
    }

    @Test
    void highestPrioritySelectMatchWins() {
        Map<String, BubbleStyleDoc> styles = Map.of(
            "default", style(null),
            "low", style(select(List.of(), List.of(), 1)),
            "high", style(select(List.of(), List.of(), 5)));

        String chosen = BubbleStyles.resolveStyleId(null, node -> false, styles, "world", null);

        assertEquals("high", chosen);
    }

    @Test
    void priorityTieBreaksLexicographically() {
        Map<String, BubbleStyleDoc> styles = Map.of(
            "beta", style(select(List.of(), List.of(), 3)),
            "alpha", style(select(List.of(), List.of(), 3)));

        String chosen = BubbleStyles.resolveStyleId(null, node -> false, styles, "world", null);

        assertEquals("alpha", chosen);
    }

    @Test
    void styleWithoutSelectIsNeverAutoMatched() {
        Map<String, BubbleStyleDoc> styles = Map.of("fancy", style(null));

        assertNull(BubbleStyles.resolveStyleId(null, node -> false, styles, "world", null));
    }

    @Test
    void worldGlobsGateSelectMatching() {
        Map<String, BubbleStyleDoc> styles = Map.of(
            "default", style(null),
            "nether", style(select(List.of("*_nether"), List.of(), 5)));

        assertEquals("nether", BubbleStyles.resolveStyleId(null, node -> false, styles, "world_nether", null));
        assertEquals("default", BubbleStyles.resolveStyleId(null, node -> false, styles, "world", null));
    }

    @Test
    void groupsGateSelectMatching() {
        Map<String, BubbleStyleDoc> styles = Map.of(
            "default", style(null),
            "staff", style(select(List.of(), List.of("staff"), 5)));

        assertEquals("staff", BubbleStyles.resolveStyleId(null, node -> false, styles, "world", "Staff"));
        assertEquals("default", BubbleStyles.resolveStyleId(null, node -> false, styles, "world", "builders"));
        assertEquals("default", BubbleStyles.resolveStyleId(null, node -> false, styles, "world", null));
    }

    @Test
    void defaultStyleIsTheLastFallback() {
        Map<String, BubbleStyleDoc> styles = Map.of("default", style(null));

        assertEquals("default", BubbleStyles.resolveStyleId(null, node -> false, styles, "world", null));
        assertNull(BubbleStyles.resolveStyleId(null, node -> false, Map.of(), "world", null));
    }

    @Test
    void globMatchingSupportsStarAndQuestionMark() {
        assertTrue(BubbleStyles.globMatches("world_*", "world_nether"));
        assertTrue(BubbleStyles.globMatches("world", "world"));
        assertTrue(BubbleStyles.globMatches("hub?", "hub1"));
        assertFalse(BubbleStyles.globMatches("hub?", "hub12"));
        assertFalse(BubbleStyles.globMatches("world_*", "lobby"));
        assertFalse(BubbleStyles.globMatches("w.rld", "world"));
    }
}
