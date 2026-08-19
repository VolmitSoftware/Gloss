package art.arcane.gloss.drop;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Step 1 characterization pins for the drop-name lifecycle: the naming gate that
 * decides whether a spawned or merged item is (re)named, the merged-count rendering,
 * and the bundle label path. These protect Lane B's render memoization and
 * removal-listener rework. The named-set bookkeeping itself (activeCount pruning via
 * Bukkit.getEntity) is unpinnable without seams and is reported, not tested here.
 * Frozen contracts; assertion changes require an explicit report note.
 */
class CharacterizationDropNameLifecycleTest {

    // --- naming gate: full truth table of preservesExistingName ---

    @Test
    void onlyForeignCustomNamesWithTheKnobOnArePreserved() {
        // preserve && hasCustomName && !glossOwned is the ONLY preserved combination.
        assertTrue(DropNameFormatter.preservesExistingName(true, true, false));

        assertFalse(DropNameFormatter.preservesExistingName(true, true, true));
        assertFalse(DropNameFormatter.preservesExistingName(true, false, false));
        assertFalse(DropNameFormatter.preservesExistingName(true, false, true));
        assertFalse(DropNameFormatter.preservesExistingName(false, true, false));
        assertFalse(DropNameFormatter.preservesExistingName(false, true, true));
        assertFalse(DropNameFormatter.preservesExistingName(false, false, false));
        assertFalse(DropNameFormatter.preservesExistingName(false, false, true));
    }

    @Test
    void glossOwnedNamesAreAlwaysRenamedOnMerge() {
        // A merge re-runs applyName with the combined count; because the target is
        // gloss-owned, preservation never blocks the refreshed count label.
        assertFalse(DropNameFormatter.preservesExistingName(true, true, true));
    }

    // --- merged-count rendering ---

    @Test
    void mergeRendersTheCombinedStackCount() {
        assertEquals("&f96x &7Oak Log", DropNameFormatter.format("&f{count}x &7{type}", 64 + 32, "Oak Log"));
    }

    @Test
    void countIsSubstitutedBeforeTypeSoTypeTextIsNeverReSubstituted() {
        // Ordering pin: a type label containing the {count} token arrives literally.
        assertEquals("7x {count} Widget", DropNameFormatter.format("{count}x {type}", 7, "{count} Widget"));
    }

    @Test
    void typeLabelFallsBackToTheMaterialNameOnMissingOrBlankDisplayNames() {
        assertEquals("Stone", DropNameFormatter.typeLabel(true, null, "Stone"));
        assertEquals("Stone", DropNameFormatter.typeLabel(true, "   ", "Stone"));
        assertEquals("Fancy Rock", DropNameFormatter.typeLabel(true, "Fancy Rock", "Stone"));
        assertEquals("Stone", DropNameFormatter.typeLabel(false, "Fancy Rock", "Stone"));
    }

    // --- bundle labels ---

    @Test
    void bundleAggregationMergesDuplicateTypesLikeAnItemMerge() {
        List<DropNameFormatter.BundleContent> aggregated = DropNameFormatter.aggregate(List.of(
            new DropNameFormatter.BundleContent("Oak Log", 32),
            new DropNameFormatter.BundleContent("Stone", 1),
            new DropNameFormatter.BundleContent("Oak Log", 32)));

        assertEquals(2, aggregated.size());
        assertEquals(new DropNameFormatter.BundleContent("Oak Log", 64), aggregated.get(0));
        assertEquals(new DropNameFormatter.BundleContent("Stone", 1), aggregated.get(1));
    }

    @Test
    void bundleOrderingIsAmountDescendingThenTypeAscending() {
        List<DropNameFormatter.BundleContent> aggregated = DropNameFormatter.aggregate(List.of(
            new DropNameFormatter.BundleContent("Stone", 4),
            new DropNameFormatter.BundleContent("Apple", 4),
            new DropNameFormatter.BundleContent("Dirt", 9)));

        assertEquals(List.of(
            new DropNameFormatter.BundleContent("Dirt", 9),
            new DropNameFormatter.BundleContent("Apple", 4),
            new DropNameFormatter.BundleContent("Stone", 4)), aggregated);
    }

    @Test
    void bundleEntriesJoinWithTheExactLegacySeparator() {
        String label = DropNameFormatter.formatBundle("{contents}", List.of(
            new DropNameFormatter.BundleContent("Dirt", 9),
            new DropNameFormatter.BundleContent("Stone", 4)), 5, remaining -> "+" + remaining);

        assertEquals("9x Dirt&8, &74x Stone", label);
    }

    @Test
    void moreRendererIsNeverInvokedWhenEverythingFits() {
        String label = DropNameFormatter.formatBundle("{total}: {contents}", List.of(
            new DropNameFormatter.BundleContent("Dirt", 2),
            new DropNameFormatter.BundleContent("Stone", 1)), 3, remaining -> {
                throw new AssertionError("moreRenderer must not run when entries <= limit");
            });

        assertEquals("3: 2x Dirt&8, &71x Stone", label);
    }

    @Test
    void moreRendererReceivesTheExactHiddenEntryCount() {
        List<Integer> remainders = new ArrayList<>();
        List<DropNameFormatter.BundleContent> contents = List.of(
            new DropNameFormatter.BundleContent("A", 5),
            new DropNameFormatter.BundleContent("B", 4),
            new DropNameFormatter.BundleContent("C", 3),
            new DropNameFormatter.BundleContent("D", 2),
            new DropNameFormatter.BundleContent("E", 1));

        String label = DropNameFormatter.formatBundle("{contents}", contents, 2, remaining -> {
            remainders.add(remaining);
            return "and " + remaining + " more";
        });

        assertEquals(List.of(3), remainders);
        assertEquals("5x A&8, &74x B&8, &7and 3 more", label);
    }

    @Test
    void totalCountsEveryEntryIncludingHiddenOnes() {
        String label = DropNameFormatter.formatBundle("{total}", List.of(
            new DropNameFormatter.BundleContent("A", 5),
            new DropNameFormatter.BundleContent("B", 4),
            new DropNameFormatter.BundleContent("C", 3)), 1, remaining -> "");

        assertEquals("12", label);
    }

    @Test
    void emptyOrFullyFilteredBundlesYieldEmptySoTheNormalNamePathApplies() {
        assertEquals("", DropNameFormatter.formatBundle("{total}: {contents}", List.of(), 3, remaining -> ""));
        List<DropNameFormatter.BundleContent> filtered = new ArrayList<>();
        filtered.add(null);
        filtered.add(new DropNameFormatter.BundleContent("Ghost", 0));
        assertEquals("", DropNameFormatter.formatBundle("{total}: {contents}", filtered, 3, remaining -> ""));
    }
}
