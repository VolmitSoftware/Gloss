package art.arcane.gloss.hologram;

import art.arcane.gloss.hologram.CharacterizationHarness.PlayerHandle;
import art.arcane.gloss.hologram.CharacterizationHarness.WorldState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HologramPagesTest {
    private static final String PAGED = """
        {
          "schemaVersion": 3, "revision": 1,
          "anchor": {"world": "world", "position": [0, 64, 0]},
          "pages": [
            { "id": "1", "lines": ["Page one"] },
            { "id": "2", "lines": ["Page two"] },
            { "id": "3", "lines": ["Page three"] }
          ]
        }
        """;

    @TempDir
    File directory;

    @Test
    void pageTargetsResolveByNameAndWrapAround() {
        List<HologramPage> pages = HologramDoc.parse("paged.json", PAGED).pages();

        assertEquals("2", HologramPage.resolve(pages, "1", "next"));
        assertEquals("3", HologramPage.resolve(pages, "2", "next"));
        assertEquals("1", HologramPage.resolve(pages, "3", "next"));
        assertEquals("3", HologramPage.resolve(pages, "1", "prev"));
        assertEquals("2", HologramPage.resolve(pages, "3", "prev"));
        assertEquals("2", HologramPage.resolve(pages, "1", "2"));
        assertNull(HologramPage.resolve(pages, "1", "9"));
        assertEquals("2", HologramPage.resolve(pages, null, "next"));
        assertEquals("3", HologramPage.resolve(pages, null, "prev"));
    }

    @Test
    void theViewerIndexKeepsOnePageIdPerViewerAndHologram() {
        HologramViewerIndex index = new HologramViewerIndex();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();

        assertNull(index.page(alice, "shop"));
        index.setPage(alice, "shop", "2");
        index.setPage(bob, "shop", "3");
        index.setPage(alice, "quest", "5");

        assertEquals("2", index.page(alice, "shop"));
        assertEquals("3", index.page(bob, "shop"));
        assertEquals("5", index.page(alice, "quest"));

        index.forgetPages(alice);
        assertNull(index.page(alice, "shop"));
        assertNull(index.page(alice, "quest"));
        assertEquals("3", index.page(bob, "shop"));
    }

    @Test
    void eachViewerRendersItsOwnPage() {
        try (CharacterizationHarness harness = new CharacterizationHarness(directory)) {
            WorldState world = harness.world("world");
            PlayerHandle alice = harness.join("Alice", world, 0.0D, 64.0D, 0.0D);
            PlayerHandle bob = harness.join("Bob", world, 1.0D, 64.0D, 0.0D);
            PersistentHologram hologram = harness.persistent("paged", harness.at(world, 0.0D, 64.0D, 0.0D));
            hologram.apply(HologramDoc.parse("paged.json", PAGED));

            harness.service.setPage("paged", bob.uuid, "2");
            hologram.update();
            harness.drainDelayed();
            harness.animator.pass(System.currentTimeMillis());

            assertEquals("Page one", lastTextFor(harness, alice));
            assertEquals("Page two", lastTextFor(harness, bob));
            assertTrue(harness.schedulerErrors.isEmpty());
        }
    }

    private static String lastTextFor(CharacterizationHarness harness, PlayerHandle viewer) {
        List<String> texts = new ArrayList<>();
        for (CharacterizationHarness.Sent sent : harness.sender.sent) {
            for (org.bukkit.entity.Player player : sent.viewers()) {
                if (player.getUniqueId().equals(viewer.uuid)) {
                    texts.add(sent.text());
                }
            }
        }
        return texts.isEmpty() ? null : texts.getLast();
    }
}
