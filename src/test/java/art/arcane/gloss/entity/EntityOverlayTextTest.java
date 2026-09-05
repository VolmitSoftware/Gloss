package art.arcane.gloss.entity;

import org.bukkit.ChatColor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityOverlayTextTest {
    @Test
    void namedStackKeepsNameAboveHealthAndCombatStatsLast() {
        EntityOverlayText.Snapshot entity = new EntityOverlayText.Snapshot("Sentinel", 15, 20,
            20, 5, 7, 4, 12);
        List<String> lines = EntityOverlayText.render(EntityOverlayDoc.DEFAULTS, entity,
            List.of("&7Speed &f0.3"));
        assertEquals(List.of("Sentinel x12", "|||||||||| 15/20", "-5", "Speed 0.3", "ATK 7 | ARM 4"),
            lines.stream().map(ChatColor::stripColor).toList());
        assertTrue(lines.get(1).startsWith("§a||||||||§c||"));
    }

    @Test
    void unnamedStackPlacesCountBesideHealth() {
        List<String> lines = EntityOverlayText.render(EntityOverlayDoc.DEFAULTS,
            new EntityOverlayText.Snapshot(null, 20, 20, 20, 0, 3, 0, 4), List.of());
        assertEquals("|||||||||| 20/20 x4", ChatColor.stripColor(lines.getFirst()));
        assertEquals(2, lines.size());
    }

    @Test
    void damagedSegmentsExpireBackToEmptySegments() {
        EntityOverlayText.Snapshot entity = new EntityOverlayText.Snapshot(null, 4, 20,
            4, 0, 0, 0, 1);
        List<String> lines = EntityOverlayText.render(EntityOverlayDoc.DEFAULTS, entity, List.of());
        assertTrue(lines.getFirst().startsWith("§c||§c§8||||||||"));
        assertEquals(2, lines.size());
    }

    @Test
    void healthClampsAndTinyLivingHealthRetainsOneSegment() {
        assertEquals("&a||||||||||&c&8", EntityOverlayText.bar(EntityOverlayDoc.DEFAULTS,
            new EntityOverlayText.Snapshot(null, 30, 20, 100, 0, 0, 0, 1)));
        assertEquals("&c|&c&8|||||||||", EntityOverlayText.bar(EntityOverlayDoc.DEFAULTS,
            new EntityOverlayText.Snapshot(null, 0.01, 20, 0.01, 0, 0, 0, 1)));
        assertEquals("&c&c&8||||||||||", EntityOverlayText.bar(EntityOverlayDoc.DEFAULTS,
            new EntityOverlayText.Snapshot(null, 0, 0, 0, 0, 0, 0, 1)));
    }

    @Test
    void presentationSwitchesKeepInsightAndStackCount() {
        EntityOverlayDoc settings = EntityOverlayDoc.parse("default.json", """
            {"schemaVersion":1,"revision":1,"showNames":false,"showHealthNumbers":false,
             "showCombatStats":false,"healthSegments":5}
            """);
        List<String> lines = EntityOverlayText.render(settings,
            new EntityOverlayText.Snapshot("Hidden", 10, 20, 10, 0, 4, 2, 3), List.of("Type: Zombie"));
        assertEquals(List.of("||||| x3", "Type: Zombie"), lines.stream().map(ChatColor::stripColor).toList());
        assertFalse(lines.stream().anyMatch(line -> line.contains("Hidden")));
    }
}
