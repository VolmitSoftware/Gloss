package art.arcane.gloss.bubble;

import art.arcane.gloss.doc.DocumentEnvelope;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Step 1 characterization pins for bubble style capture and resolution: the DEFAULTS
 * fallback every spawn uses when no style resolves, canonicalization at capture time,
 * the exact permission node consulted for an explicit choice, and the glob/select
 * semantics that Lane B's snapshot caching and Pattern memoization must preserve
 * byte-for-byte. Frozen contracts; assertion changes require an explicit report note.
 */
class CharacterizationBubbleStyleCaptureTest {
    private static BubbleStyleDoc style(BubbleStyleDoc.Select select) {
        return new BubbleStyleDoc(BubbleStyleDoc.CURRENT_SCHEMA_VERSION, DocumentEnvelope.INITIAL_REVISION,
            "&7", new Vector(0.0D, 1.0D, 0.0D), 32, 5000L, true, true,
            BubbleStyleDoc.DEFAULTS.motion(), BubbleStyleDoc.DEFAULTS.shimmer(), select);
    }

    // --- the DEFAULTS style is the terminal fallback of every spawn ---

    @Test
    void defaultsStyleCarriesTheExactLegacySpawnParameters() {
        BubbleStyleDoc defaults = BubbleStyleDoc.DEFAULTS;
        assertEquals("&7", defaults.prefix());
        assertEquals(new Vector(0.0D, 1.0D, 0.0D), defaults.offset());
        assertEquals(32, defaults.wordWrapChars());
        assertEquals(5000L, defaults.maxAliveMs());
        assertEquals(BubbleStyleDoc.DEFAULT_TRANSLATION_Y, defaults.motion().translation().y());
        assertTrue(defaults.followPlayer());
        assertTrue(defaults.hideOwn());
        assertNull(defaults.select());
    }

    @Test
    void emptyStyleMapResolvesToNullSoTheServiceFallsBackToDefaults() {
        assertNull(BubbleStyles.resolveStyleId(null, node -> true, Map.of(), "world", null));
        assertNull(BubbleStyles.resolveStyleId("fancy", node -> true, Map.of(), "world", null));
    }

    // --- capture-time canonicalization ---

    @Test
    void captureClampsTheLifecycleBounds() {
        BubbleStyleDoc doc = new BubbleStyleDoc(BubbleStyleDoc.CURRENT_SCHEMA_VERSION,
            DocumentEnvelope.INITIAL_REVISION, "&7", null, 1, 1L, false, false, null, null, null);
        assertEquals(8, doc.wordWrapChars());
        assertEquals(500L, doc.maxAliveMs());

        BubbleStyleDoc high = new BubbleStyleDoc(BubbleStyleDoc.CURRENT_SCHEMA_VERSION,
            DocumentEnvelope.INITIAL_REVISION, "&7", null, 9999, 999_999_999L, false, false, null, null, null);
        assertEquals(128, high.wordWrapChars());
        assertEquals(60000L, high.maxAliveMs());
    }

    @Test
    void captureDefensivelyClonesTheOffsetVector() {
        Vector offset = new Vector(0.5D, 2.0D, -0.5D);
        BubbleStyleDoc doc = new BubbleStyleDoc(BubbleStyleDoc.CURRENT_SCHEMA_VERSION,
            DocumentEnvelope.INITIAL_REVISION, "&7", offset, 32, 5000L, true, true, null, null, null);
        offset.setY(99.0D);
        assertEquals(new Vector(0.5D, 2.0D, -0.5D), doc.offset());
    }

    @Test
    void captureFillsNullPrefixAndOffsetWithTheLegacyDefaults() {
        BubbleStyleDoc doc = new BubbleStyleDoc(BubbleStyleDoc.CURRENT_SCHEMA_VERSION,
            DocumentEnvelope.INITIAL_REVISION, null, null, 32, 5000L, true, true, null, null, null);
        assertEquals("&7", doc.prefix());
        assertEquals(new Vector(0.0D, 1.0D, 0.0D), doc.offset());
    }

    @Test
    void selectCaptureLowercasesGroupsButPreservesWorldCase() {
        BubbleStyleDoc.Select select = new BubbleStyleDoc.Select(
            List.of("MyWorld_Nether"), List.of("VIP", "Staff"), 3);
        assertEquals(List.of("MyWorld_Nether"), select.worlds());
        assertEquals(List.of("vip", "staff"), select.groups());
    }

    // --- explicit-choice resolution: exact permission node, exact consultation rules ---

    @Test
    void explicitChoiceConsultsExactlyThePerStylePermissionNode() {
        List<String> consulted = new ArrayList<>();
        Predicate<String> recorder = node -> {
            consulted.add(node);
            return true;
        };
        Map<String, BubbleStyleDoc> styles = Map.of("fancy", style(null));

        assertEquals("fancy", BubbleStyles.resolveStyleId("fancy", recorder, styles, "world", null));
        assertEquals(List.of("gloss.bubbles.style.fancy"), consulted);
    }

    @Test
    void unknownExplicitChoiceNeverConsultsPermissions() {
        List<String> consulted = new ArrayList<>();
        Predicate<String> recorder = node -> {
            consulted.add(node);
            return true;
        };
        Map<String, BubbleStyleDoc> styles = Map.of("default", style(null));

        assertEquals("default", BubbleStyles.resolveStyleId("missing", recorder, styles, "world", null));
        assertTrue(consulted.isEmpty());
    }

    @Test
    void deniedExplicitChoiceFallsThroughToTheDefaultStyle() {
        Map<String, BubbleStyleDoc> styles = Map.of("fancy", style(null), "default", style(null));
        assertEquals("default", BubbleStyles.resolveStyleId("fancy", node -> false, styles, "world", null));
    }

    // --- select gating: the semantics Lane B's cached snapshot must reproduce ---

    @Test
    void emptySelectListsMatchEveryWorldAndGroupIncludingNulls() {
        BubbleStyleDoc.Select open = new BubbleStyleDoc.Select(List.of(), List.of(), 0);
        assertTrue(BubbleStyles.matches(open, "anything", "anygroup"));
        assertTrue(BubbleStyles.matches(open, null, null));
    }

    @Test
    void worldGatedSelectRejectsANullWorld() {
        BubbleStyleDoc.Select select = new BubbleStyleDoc.Select(List.of("world"), List.of(), 0);
        assertFalse(BubbleStyles.matches(select, null, "vip"));
    }

    @Test
    void groupGatedSelectRejectsANullGroupEvenWhenTheWorldMatches() {
        BubbleStyleDoc.Select select = new BubbleStyleDoc.Select(List.of("world"), List.of("vip"), 0);
        assertFalse(BubbleStyles.matches(select, "world", null));
    }

    @Test
    void groupMatchingIsCaseInsensitiveOnTheResolvedGroup() {
        BubbleStyleDoc.Select select = new BubbleStyleDoc.Select(List.of(), List.of("vip"), 0);
        assertTrue(BubbleStyles.matches(select, "world", "VIP"));
        assertTrue(BubbleStyles.matches(select, "world", "vip"));
        assertFalse(BubbleStyles.matches(select, "world", "mvp"));
    }

    @Test
    void bothGatesMustPassWhenWorldsAndGroupsArePresent() {
        BubbleStyleDoc.Select select = new BubbleStyleDoc.Select(List.of("world*"), List.of("vip"), 0);
        assertTrue(BubbleStyles.matches(select, "world_nether", "vip"));
        assertFalse(BubbleStyles.matches(select, "hub", "vip"));
        assertFalse(BubbleStyles.matches(select, "world_nether", "member"));
    }

    // --- glob semantics: exact contracts for Lane B's compiled-Pattern memoization ---

    @Test
    void globTreatsRegexMetacharactersAsLiterals() {
        assertTrue(BubbleStyles.globMatches("world.nether", "world.nether"));
        assertFalse(BubbleStyles.globMatches("world.nether", "worldXnether"));
        assertTrue(BubbleStyles.globMatches("w(1)+[a]", "w(1)+[a]"));
        assertFalse(BubbleStyles.globMatches("w(1)+[a]", "w1a"));
    }

    @Test
    void globStarSpansZeroOrMoreCharacters() {
        assertTrue(BubbleStyles.globMatches("nether*", "nether"));
        assertTrue(BubbleStyles.globMatches("nether*", "nether_roof"));
        assertTrue(BubbleStyles.globMatches("*", ""));
        assertTrue(BubbleStyles.globMatches("*end*", "the_end_again"));
        assertFalse(BubbleStyles.globMatches("nether*", "the_nether"));
    }

    @Test
    void globQuestionMarkMatchesExactlyOneCharacter() {
        assertTrue(BubbleStyles.globMatches("w?rld", "world"));
        assertFalse(BubbleStyles.globMatches("w?rld", "wrld"));
        assertFalse(BubbleStyles.globMatches("w?rld", "woorld"));
    }

    @Test
    void emptyGlobMatchesOnlyTheEmptyString() {
        assertTrue(BubbleStyles.globMatches("", ""));
        assertFalse(BubbleStyles.globMatches("", "world"));
    }

    @Test
    void globResultsAreStableAcrossInterleavedPatternsAndValues() {
        // A memoized Pattern cache must key on the pattern alone; interleaving
        // patterns and values must never bleed state between calls.
        for (int repeat = 0; repeat < 3; repeat++) {
            assertTrue(BubbleStyles.globMatches("world*", "world_nether"));
            assertFalse(BubbleStyles.globMatches("hub?", "world_nether"));
            assertTrue(BubbleStyles.globMatches("hub?", "hub1"));
            assertFalse(BubbleStyles.globMatches("world*", "hub1"));
        }
    }
}
