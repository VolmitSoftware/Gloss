package art.arcane.gloss.drop;

import art.arcane.gloss.config.GlossConfigFile;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DropNameFormatterTest {
    private static final IntFunction<String> MORE = remaining -> "+" + remaining + " more";

    private static DropNameFormatter.BundleContent content(String type, int amount) {
        return new DropNameFormatter.BundleContent(type, amount);
    }

    private static String bundle(List<DropNameFormatter.BundleContent> contents, int entryLimit) {
        return DropNameFormatter.formatBundle(RealDropSettingsDoc.BUNDLE_FORMAT_DEFAULT, contents, entryLimit, MORE);
    }

    @Test
    void materialNameTitleCasesWordsAndKeepsJoiningWordsLower() {
        assertEquals("Diamond Sword", DropNameFormatter.materialName("DIAMOND_SWORD"));
        assertEquals("Cobblestone", DropNameFormatter.materialName("COBBLESTONE"));
        assertEquals("Oak Log", DropNameFormatter.materialName("OAK_LOG"));
        assertEquals("Heart of the Sea", DropNameFormatter.materialName("HEART_OF_THE_SEA"));
        assertEquals("Music Disc 13", DropNameFormatter.materialName("MUSIC_DISC_13"));
    }

    @Test
    void typeNamePrefersTheAuthoredMaterialName() {
        Map<String, String> names = Map.of("COBBLESTONE", "&7Cobble");

        assertEquals("&7Cobble", DropNameFormatter.typeName(names, "COBBLESTONE"));
        assertEquals("Oak Log", DropNameFormatter.typeName(names, "OAK_LOG"));
        assertEquals("Oak Log", DropNameFormatter.typeName(Map.of(), "OAK_LOG"));
    }

    @Test
    void formatReplacesCountAndType() {
        assertEquals("&732x diamond sword", DropNameFormatter.format("&7{count}x {type}", 32, "diamond sword"));
    }

    @Test
    void formatReplacesRepeatedTokens() {
        assertEquals("3 stone 3 stone", DropNameFormatter.format("{count} {type} {count} {type}", 3, "stone"));
    }

    @Test
    void formatLeavesUnknownTokensAlone() {
        assertEquals("{other} 1 dirt", DropNameFormatter.format("{other} {count} {type}", 1, "dirt"));
    }

    @Test
    void aggregateSumsAmountsPerTypeAndOrdersLargestFirst() {
        List<DropNameFormatter.BundleContent> aggregated = DropNameFormatter.aggregate(List.of(
            content("dirt", 4),
            content("stone", 2),
            content("stone", 3),
            content("oak log", 4)
        ));

        assertEquals(List.of(content("stone", 5), content("dirt", 4), content("oak log", 4)), aggregated);
    }

    @Test
    void aggregateDropsNullAndNonPositiveEntries() {
        List<DropNameFormatter.BundleContent> aggregated = DropNameFormatter.aggregate(Arrays.asList(
            null,
            content("stone", 0),
            content("dirt", -3),
            content("stone", 7)
        ));

        assertEquals(1, aggregated.size());
        assertEquals(content("stone", 7), aggregated.get(0));
    }

    @Test
    void formatBundleReplacesTotalAndContentsTokens() {
        assertEquals("&7Bundle &8(&79 items&8): &75x stone&8, &74x dirt",
            bundle(List.of(content("stone", 5), content("dirt", 4)), 3));
    }

    @Test
    void formatBundleCollapsesEntriesBeyondTheLimitIntoTheRemainder() {
        assertEquals("&7Bundle &8(&712 items&8): &75x stone&8, &74x dirt&8, &7+2 more",
            bundle(List.of(
                content("stone", 5),
                content("dirt", 4),
                content("oak log", 2),
                content("sand", 1)
            ), 2));
    }

    @Test
    void formatBundleTreatsAZeroOrNegativeLimitAsOneEntry() {
        assertEquals("&7Bundle &8(&79 items&8): &75x stone&8, &7+1 more",
            bundle(List.of(content("stone", 5), content("dirt", 4)), 0));
    }

    @Test
    void formatBundleReturnsEmptyForAnEmptyBundleSoTheNormalFormatApplies() {
        assertTrue(bundle(List.of(), 3).isEmpty());
        assertTrue(bundle(List.of(content("stone", 0)), 3).isEmpty());
    }

    @Test
    void formatBundleSupportsRepeatedAndUnknownTokens() {
        assertEquals("{other} 5 5x stone 5",
            DropNameFormatter.formatBundle("{other} {total} {contents} {total}", List.of(content("stone", 5)), 3, MORE));
    }

    @Test
    void formatBundlePassesTheEntryRemainderToTheRenderer() {
        List<Integer> observed = new ArrayList<>();
        DropNameFormatter.formatBundle(
            "{contents}",
            List.of(content("stone", 5), content("dirt", 4), content("sand", 1)),
            1,
            remaining -> {
                observed.add(remaining);
                return "+" + remaining + " more";
            });

        assertEquals(List.of(2), observed);
    }

    @Test
    void formatBundleLinesBuildsOneExplicitDisplayLinePerEntry() {
        assertEquals(List.of(
                "&eBundle &8(&e12 items&8)",
                "&7- &f5x stone",
                "&7- &f4x dirt",
                "&8+2 more"
            ),
            DropNameFormatter.formatBundleLines(
                RealDropSettingsDoc.BUNDLE_HEADER_FORMAT_DEFAULT,
                RealDropSettingsDoc.BUNDLE_ENTRY_FORMAT_DEFAULT,
                RealDropSettingsDoc.BUNDLE_MORE_FORMAT_DEFAULT,
                List.of(
                    content("stone", 5),
                    content("dirt", 4),
                    content("oak log", 2),
                    content("sand", 1)
                ),
                2));
    }

    @Test
    void formatBundleLinesReturnsEmptyForAnEmptyBundle() {
        assertTrue(DropNameFormatter.formatBundleLines("{total}", "{count} {type}",
            "{remaining}", List.of(), 3).isEmpty());
    }

    @Test
    void shippedBundleFormatDeclaresBothTokensAndTheDefaultLimitIsThree() {
        assertTrue(RealDropSettingsDoc.BUNDLE_FORMAT_DEFAULT.contains("{total}"));
        assertTrue(RealDropSettingsDoc.BUNDLE_FORMAT_DEFAULT.contains("{contents}"));
        RealDropSettingsDoc.LabelBundle defaults = new RealDropSettingsDoc.LabelBundle(null, null, null, null, null, null);
        assertEquals(3, defaults.entryLimit());
        assertEquals(RealDropSettingsDoc.BUNDLE_FORMAT_DEFAULT, defaults.format());
    }

    @Test
    void preservesForeignCustomNamesOnlyWhenTheKnobIsOn() {
        assertTrue(DropNameFormatter.preservesExistingName(true, true, false));
        assertFalse(DropNameFormatter.preservesExistingName(true, true, true));
        assertFalse(DropNameFormatter.preservesExistingName(true, false, false));
        assertFalse(DropNameFormatter.preservesExistingName(false, true, false));
        assertFalse(DropNameFormatter.preservesExistingName(false, true, true));
    }

    @Test
    void ownershipRelinquishesWhenAnotherPluginReplacesTheRenderedName() {
        assertFalse(DropNameFormatter.ownsExistingName(false, null, "foreign"));
        assertTrue(DropNameFormatter.ownsExistingName(true, null, "legacy gloss name"));
        assertTrue(DropNameFormatter.ownsExistingName(true, "gloss name", "gloss name"));
        assertFalse(DropNameFormatter.ownsExistingName(true, "gloss name", "foreign name"));
    }

    @Test
    void typeLabelPrefersTheItemDisplayNameWhenEnabled() {
        assertEquals("&bExcalibur", DropNameFormatter.typeLabel(true, "&bExcalibur", "diamond sword"));
        assertEquals("diamond sword", DropNameFormatter.typeLabel(false, "&bExcalibur", "diamond sword"));
        assertEquals("diamond sword", DropNameFormatter.typeLabel(true, null, "diamond sword"));
        assertEquals("diamond sword", DropNameFormatter.typeLabel(true, "  ", "diamond sword"));
        assertEquals("diamond sword", DropNameFormatter.typeLabel(true, "", "diamond sword"));
    }

    @Test
    void itemDisplayNamesRequireExplicitOptIn() {
        assertTrue(RealDropSettingsDoc.DEFAULTS.presentation().labels().preserveCustomNames());
        assertFalse(RealDropSettingsDoc.DEFAULTS.toConfig(true).labels().useItemDisplayNames());
    }
}
