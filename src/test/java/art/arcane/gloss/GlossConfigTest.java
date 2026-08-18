package art.arcane.gloss;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlossConfigTest {
    @Test
    void emptyConfigLoadsDocumentedDefaults() {
        GlossConfig config = GlossConfig.load(new YamlConfiguration());

        assertTrue(config.splashScreen());
        assertEquals(20, config.hotload().watchIntervalTicks());
        assertTrue(config.animations().enabled());
        assertTrue(config.text().placeholders());
        assertTrue(config.text().functions());
        assertTrue(config.holograms().perViewerPlaceholders());
        assertEquals(2, config.holograms().temporaryUpdateIntervalTicks());
        assertEquals(48, config.holograms().textArtMaxWidth());
        assertTrue(config.tablist().groupListNames());
        assertTrue(config.groups().useVault());
        assertEquals(5, config.bubbles().lineStaggerTicks());
        assertTrue(config.bubbles().flyAway());
        assertTrue(config.indicators().showHeals());
        assertTrue(config.commands().sounds());
    }

    @Test
    void explicitFalseTogglesLoad() {
        YamlConfiguration source = new YamlConfiguration();
        source.set("splash-screen", false);
        source.set("features.animations", false);
        source.set("text.placeholders", false);
        source.set("text.functions", false);
        source.set("holograms.per-viewer-placeholders", false);
        source.set("tablist.group-list-names", false);
        source.set("groups.use-vault", false);
        source.set("chat-bubbles.fly-away", false);
        source.set("damage-indicators.show-heals", false);
        source.set("commands.sounds", false);

        GlossConfig config = GlossConfig.load(source);

        assertFalse(config.splashScreen());
        assertFalse(config.animations().enabled());
        assertFalse(config.text().placeholders());
        assertFalse(config.text().functions());
        assertFalse(config.holograms().perViewerPlaceholders());
        assertFalse(config.tablist().groupListNames());
        assertFalse(config.groups().useVault());
        assertFalse(config.bubbles().flyAway());
        assertFalse(config.indicators().showHeals());
        assertFalse(config.commands().sounds());
    }

    @Test
    void outOfRangeValuesClampToDocumentedBounds() {
        YamlConfiguration low = new YamlConfiguration();
        low.set("hotload.watch-interval-ticks", 0);
        low.set("holograms.temporary-update-interval-ticks", 0);
        low.set("holograms.text-art-max-width", 1);
        low.set("chat-bubbles.line-stagger-ticks", -5);

        GlossConfig lowConfig = GlossConfig.load(low);
        assertEquals(1, lowConfig.hotload().watchIntervalTicks());
        assertEquals(1, lowConfig.holograms().temporaryUpdateIntervalTicks());
        assertEquals(8, lowConfig.holograms().textArtMaxWidth());
        assertEquals(0, lowConfig.bubbles().lineStaggerTicks());

        YamlConfiguration high = new YamlConfiguration();
        high.set("hotload.watch-interval-ticks", 9999);
        high.set("holograms.temporary-update-interval-ticks", 9999);
        high.set("holograms.text-art-max-width", 9999);
        high.set("chat-bubbles.line-stagger-ticks", 9999);

        GlossConfig highConfig = GlossConfig.load(high);
        assertEquals(200, highConfig.hotload().watchIntervalTicks());
        assertEquals(20, highConfig.holograms().temporaryUpdateIntervalTicks());
        assertEquals(128, highConfig.holograms().textArtMaxWidth());
        assertEquals(40, highConfig.bubbles().lineStaggerTicks());
    }
}
