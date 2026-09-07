package art.arcane.gloss.hologram;

import art.arcane.gloss.api.HologramBox;
import art.arcane.gloss.api.IconArgbColor;
import art.arcane.gloss.api.IconBillboard;
import art.arcane.gloss.api.IconDisplayStyle;
import art.arcane.gloss.api.IconTextAlignment;
import art.arcane.gloss.condition.ShowCondition;
import art.arcane.gloss.hologram.CharacterizationHarness.DisplayHandle;
import art.arcane.gloss.hologram.CharacterizationHarness.PlayerHandle;
import art.arcane.gloss.hologram.CharacterizationHarness.WorldState;
import org.bukkit.Color;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistentHologramDecorationTest {
    @TempDir
    File directory;

    @Test
    void sharedBoxesResizeMoveAndRetireWithTheirText() {
        try (CharacterizationHarness harness = new CharacterizationHarness(directory)) {
            WorldState world = harness.world("world");
            PlayerHandle viewer = harness.join("Alice", world, 0, 64, 3);
            PersistentHologram hologram = harness.persistent("box", harness.at(world, 0, 64, 0));
            hologram.setLines(List.of("i"));
            hologram.update();
            assertEquals(1, harness.liveSpawned(world).size());
            hologram.setBox(new HologramBox(true, 4, 2, null, null));
            hologram.update();
            List<DisplayHandle> displays = harness.liveSpawned(world);
            assertEquals(6, displays.size());
            for (DisplayHandle display : displays.subList(1, displays.size())) {
                assertEquals(Boolean.TRUE, viewer.perceivedVisibility(display));
            }
            DisplayHandle panel = displays.get(1);
            float narrow = panel.transformation.getScale().x;
            hologram.setLines(List.of("WWWWWWWW"));
            hologram.update();
            assertTrue(panel.transformation.getScale().x > narrow);
            hologram.teleport(harness.at(world, 2, 65, 2));
            hologram.update();
            for (DisplayHandle display : displays) {
                assertEquals(2D, display.teleports.getLast().getX());
            }
            hologram.setBox(HologramBox.defaults());
            assertEquals(1, harness.liveSpawned(world).size());
            hologram.setBox(new HologramBox(true, 4, 2, null, null));
            hologram.update();
            hologram.despawnAll();
            assertTrue(harness.liveSpawned(world).isEmpty());
        }
    }

    @Test
    void colourOnlyTextChangesDoNotReconfigureTheBoxParts() {
        try (CharacterizationHarness harness = new CharacterizationHarness(directory)) {
            WorldState world = harness.world("world");
            harness.join("Alice", world, 0, 64, 3);
            PersistentHologram hologram = harness.persistent("box", harness.at(world, 0, 64, 0));
            hologram.setBox(new HologramBox(true, 4, 2, null, null));
            hologram.setLines(List.of("\u00a7aHello"));
            hologram.update();
            DisplayHandle panel = harness.liveSpawned(world).get(1);
            long configured = configureCount(panel);

            hologram.setLines(List.of("\u00a7cHello"));
            hologram.update();
            assertEquals(configured, configureCount(panel),
                "a colour-only frame must not re-measure or reconfigure the box parts");

            hologram.setLines(List.of("\u00a7cHelloWWWW"));
            hologram.update();
            assertTrue(configureCount(panel) > configured,
                "a width-changing frame still has to resize the box");
        }
    }

    private static long configureCount(DisplayHandle display) {
        return display.callLog.stream().filter("setTransformation"::equals).count();
    }

    @Test
    void personalizedBoxesFitEachViewerAndFollowTheShowCondition() {
        try (CharacterizationHarness harness = new CharacterizationHarness(directory)) {
            WorldState world = harness.world("world");
            PlayerHandle alice = harness.join("Alice", world, 2, 64, 3);
            PlayerHandle bob = harness.join("Bob", world, 3, 64, 3);
            PersistentHologram hologram = harness.persistent("box", harness.at(world, 0, 64, 0));
            hologram.setLines(List.of("{{ player.name }}"));
            HologramDoc doc = hologram.toDoc(1L);
            hologram.apply(new HologramDoc(doc.schemaVersion(), doc.revision(), doc.anchor(), doc.lines(),
                doc.style(), new HologramBox(true, 4, 2, null, null), doc.yaw(), doc.pitch(), List.of(),
                ShowCondition.of("player.x > 1")));
            hologram.update();
            harness.drainDelayed();
            hologram.update();
            assertEquals(11, harness.liveSpawned(world).size());
            long aliceChildren = harness.liveSpawned(world).stream().skip(1)
                .filter(display -> Boolean.TRUE.equals(alice.perceivedVisibility(display))).count();
            long bobChildren = harness.liveSpawned(world).stream().skip(1)
                .filter(display -> Boolean.TRUE.equals(bob.perceivedVisibility(display))).count();
            assertEquals(5L, aliceChildren);
            assertEquals(5L, bobChildren);
            bob.location.setX(0D);
            hologram.update();
            assertEquals(6, harness.liveSpawned(world).size());
            for (DisplayHandle display : harness.liveSpawned(world)) {
                assertFalse(Boolean.TRUE.equals(bob.perceivedVisibility(display)));
            }
            hologram.onPlayerQuit(alice.uuid);
            assertEquals(1, harness.liveSpawned(world).size());
            assertTrue(harness.schedulerErrors.isEmpty());
        }
    }

    @Test
    void sharedStyleAppliesEveryDisplayPropertyWithoutUniformScale() {
        try (CharacterizationHarness harness = new CharacterizationHarness(directory)) {
            WorldState world = harness.world("world");
            harness.join("Alice", world, 0, 64, 3);
            PersistentHologram hologram = harness.persistent("style", harness.at(world, 0, 64, 0));
            hologram.setLines(List.of("Label"));
            hologram.setStyle(new IconDisplayStyle(IconBillboard.VERTICAL, true, true, IconTextAlignment.RIGHT,
                new IconArgbColor(0xAA123456), 128, 120, 12, 13, 2F, 3F, 0.4F,
                8F, 9F, new IconArgbColor(0xFFABCDEF), 2F, 3F, 4F));
            hologram.update();
            DisplayHandle display = harness.onlySpawned(world);
            assertEquals(List.of(Display.Billboard.VERTICAL), display.metadata.get("setBillboard"));
            assertEquals(List.of(TextDisplay.TextAlignment.RIGHT), display.metadata.get("setAlignment"));
            assertEquals(List.of(Color.fromARGB(0xAA123456)), display.metadata.get("setBackgroundColor"));
            assertEquals(List.of(120), display.metadata.get("setLineWidth"));
            assertEquals(List.of(new Display.Brightness(12, 13)), display.metadata.get("setBrightness"));
            assertEquals(List.of(Color.fromRGB(0xABCDEF)), display.metadata.get("setGlowColorOverride"));
            assertEquals(2F, display.transformation.getScale().x);
            assertEquals(3F, display.transformation.getScale().y);
            assertEquals(4F, display.transformation.getScale().z);
            assertEquals(List.of((byte) 128), display.metadata.get("setTextOpacity"));
        }
    }
}
