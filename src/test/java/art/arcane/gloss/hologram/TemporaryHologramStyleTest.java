package art.arcane.gloss.hologram;

import art.arcane.gloss.api.HologramBox;
import art.arcane.gloss.api.HologramPresentation;
import art.arcane.gloss.api.IconArgbColor;
import art.arcane.gloss.api.IconBillboard;
import art.arcane.gloss.api.IconDisplayStyle;
import art.arcane.gloss.api.IconTextAlignment;
import art.arcane.gloss.hologram.CharacterizationHarness.DisplayHandle;
import art.arcane.gloss.hologram.CharacterizationHarness.PlayerHandle;
import art.arcane.gloss.hologram.CharacterizationHarness.WorldState;
import art.arcane.gloss.particle.ParticleFrame;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemporaryHologramStyleTest {
    @TempDir
    File dataFolder;

    private CharacterizationHarness harness;
    private WorldState world;
    private PlayerHandle viewer;

    @BeforeEach
    void setUp() {
        harness = new CharacterizationHarness(dataFolder);
        world = harness.world("world");
        viewer = harness.join("Alice", world, 0, 64, 3);
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    @Test
    void styleAppliesTheSharedDisplayContractAndComposesPresentation() {
        TemporaryHologramDisplay temporary = temporary();
        temporary.setStyle(style());
        temporary.bindPresentation(viewer.proxy,
            () -> new HologramPresentation(2, 3, 4, 0, 0, 0, 0.5));
        harness.driveTemporary(temporary, true);

        DisplayHandle display = harness.onlySpawned(world);
        assertEquals(List.of(Display.Billboard.VERTICAL), display.metadata.get("setBillboard"));
        assertEquals(List.of(TextDisplay.TextAlignment.RIGHT), display.metadata.get("setAlignment"));
        assertEquals(List.of(true), display.metadata.get("setShadowed"));
        assertEquals(List.of(true), display.metadata.get("setSeeThrough"));
        assertEquals(List.of(Color.fromARGB(0xAA123456)), display.metadata.get("setBackgroundColor"));
        assertEquals(List.of(120), display.metadata.get("setLineWidth"));
        assertEquals(List.of(new Display.Brightness(12, 13)), display.metadata.get("setBrightness"));
        assertEquals(List.of(2F), display.metadata.get("setViewRange"));
        assertEquals(List.of(3F), display.metadata.get("setShadowRadius"));
        assertEquals(List.of(0.4F), display.metadata.get("setShadowStrength"));
        assertEquals(List.of(8F), display.metadata.get("setDisplayWidth"));
        assertEquals(List.of(9F), display.metadata.get("setDisplayHeight"));
        assertEquals(List.of(Color.fromRGB(0xABCDEF)), display.metadata.get("setGlowColorOverride"));
        assertEquals(4F, display.transformation.getScale().x);
        assertEquals(9F, display.transformation.getScale().y);
        assertEquals(16F, display.transformation.getScale().z);
        assertEquals(List.of((byte) 64), display.metadata.get("setTextOpacity"));

        int calls = display.callLog.size();
        harness.driveTemporary(temporary, true);
        assertEquals(calls, display.callLog.size());

        temporary.setStyle(IconDisplayStyle.defaults());
        harness.driveTemporary(temporary, true);
        assertEquals(2F, display.transformation.getScale().x);
        assertEquals(List.of((byte) 128), display.metadata.get("setTextOpacity"));
    }

    @Test
    void boxChildrenShareVisibilityMovementAndDestruction() {
        PlayerHandle stranger = harness.join("Bob", world, 1, 64, 3);
        TemporaryHologramDisplay temporary = temporary();
        temporary.viewers().whitelist();
        temporary.viewers().add(viewer.uuid);
        temporary.setBox(new HologramBox(true, 4, 2, null, null));
        temporary.drive(true);

        List<DisplayHandle> displays = harness.liveSpawned(world);
        assertEquals(6, displays.size());
        for (DisplayHandle display : displays) {
            assertFalse(display.visibleByDefault);
            assertEquals(true, viewer.perceivedVisibility(display));
            assertEquals(false, stranger.perceivedVisibility(display));
        }

        temporary.teleport(harness.at(world, 2, 65, 2));
        temporary.drive(true);
        for (DisplayHandle display : displays) {
            assertEquals(1, display.teleports.size());
            assertEquals(2D, display.teleports.getFirst().getX());
        }

        temporary.viewers().remove(viewer.uuid);
        temporary.drive(true);
        for (DisplayHandle display : displays) {
            assertEquals(false, viewer.perceivedVisibility(display));
        }

        temporary.destroy();
        assertTrue(harness.liveSpawned(world).isEmpty());
    }

    @Test
    void boxIsAllocatedOnlyWhenEnabledAndRetiresWithEngineDisable() {
        TemporaryHologramDisplay temporary = temporary();
        temporary.drive(true);
        assertEquals(1, harness.liveSpawned(world).size());
        temporary.setBox(new HologramBox(true, null, null, null, null));
        temporary.drive(true);
        assertEquals(6, harness.liveSpawned(world).size());
        temporary.setBox(HologramBox.defaults());
        temporary.drive(true);
        assertEquals(1, harness.liveSpawned(world).size());
        temporary.setBox(new HologramBox(true, null, null, null, null));
        temporary.drive(true);
        assertEquals(6, harness.liveSpawned(world).size());
        temporary.drive(false);
        assertTrue(harness.liveSpawned(world).isEmpty());
    }

    @Test
    void boxResizesForChangedTextAndBoundAnimationFrames() {
        TemporaryHologramDisplay temporary = temporary();
        temporary.setBox(new HologramBox(true, null, null, null, null));
        temporary.drive(true);
        DisplayHandle frame = harness.liveSpawned(world).get(1);
        float initial = frame.transformation.getScale().x;
        temporary.setRenderedLines(List.of("A substantially longer line"));
        temporary.drive(true);
        assertTrue(frame.transformation.getScale().x > initial);

        AtomicReference<List<String>> frames = new AtomicReference<>(List.of("i"));
        temporary.bindRenderedFrames(time -> frames.get());
        temporary.drive(true);
        float narrow = frame.transformation.getScale().x;
        frames.set(List.of("WWWWWWWWWW"));
        temporary.drive(true);
        assertTrue(frame.transformation.getScale().x > narrow);
        assertEquals(6, harness.liveSpawned(world).size());
    }

    @Test
    void transparentPanelAndOpaquePerimeterHaveIndependentAlpha() {
        TemporaryHologramDisplay temporary = temporary();
        temporary.setBox(new HologramBox(true, 4, 2, IconArgbColor.TRANSPARENT,
            new IconArgbColor(0xFF336699)));
        temporary.drive(true);
        assertEquals(5, harness.liveSpawned(world).size());
        for (DisplayHandle display : harness.liveSpawned(world).subList(1, 5)) {
            assertEquals(List.of(Color.fromARGB(0xFF336699)), display.metadata.get("setBackgroundColor"));
        }
        temporary.setBox(new HologramBox(true, 4, 2, new IconArgbColor(0x40112233),
            new IconArgbColor(0xFF336699)));
        temporary.drive(true);
        assertEquals(6, harness.liveSpawned(world).size());
        DisplayHandle panel = harness.liveSpawned(world).get(1);
        assertEquals(List.of(Color.fromARGB(0x40112233)), panel.metadata.get("setBackgroundColor"));
    }

    @Test
    void particleOrientationFollowsBillboardAndPresentationRotation() {
        Location anchor = harness.at(world, 0, 64, 0);
        Location near = harness.at(world, 3, 66, 3);
        Location far = harness.at(world, -3, 68, -3);
        ParticleFrame fixed = TextDisplayStyle.particleFrame(anchor, near,
            HologramPresentation.identity(), IconBillboard.FIXED);
        ParticleFrame fixedElsewhere = TextDisplayStyle.particleFrame(anchor, far,
            HologramPresentation.identity(), IconBillboard.FIXED);
        assertEquals(fixed.right(), fixedElsewhere.right());
        assertEquals(fixed.up(), fixedElsewhere.up());
        ParticleFrame vertical = TextDisplayStyle.particleFrame(anchor, near,
            HologramPresentation.identity(), IconBillboard.VERTICAL);
        assertEquals(1D, vertical.up().getY(), 1.0E-6D);
        ParticleFrame rotated = TextDisplayStyle.particleFrame(anchor, near,
            new HologramPresentation(1, 1, 1, 0, 0, 90, 1), IconBillboard.FIXED);
        assertEquals(fixed.up().getY(), rotated.right().getY(), 1.0E-6D);
        assertNotEquals(fixed.right(), rotated.right());
    }

    @Test
    void destroyedBoxCannotPublishChildrenFromAQueuedSpawn() {
        TextDisplayDecoration box = new TextDisplayDecoration(harness.service, () -> {});
        harness.ownsThread = false;
        harness.deferImmediateTasks = true;
        box.update(new TextDisplayDecoration.Update(harness.at(world, 0, 64, 0), HologramPresentation.identity(), null,
            new HologramBox(true, null, null, null, null), "Health", 0));
        assertFalse(harness.immediateTasks.isEmpty());
        box.destroy(harness.at(world, 4, 65, 4));
        harness.ownsThread = true;
        harness.deferImmediateTasks = false;
        harness.drainImmediate();
        assertTrue(harness.liveSpawned(world).isEmpty());
        assertTrue(box.displays().isEmpty());
    }

    @Test
    void movingDuringQueuedDecorationSpawnUpdatesAllChildrenOnTheNextDrive() {
        TextDisplayDecoration box = new TextDisplayDecoration(harness.service, () -> {});
        HologramBox settings = new HologramBox(true, null, null, null, null);
        harness.ownsThread = false;
        harness.deferImmediateTasks = true;
        box.update(new TextDisplayDecoration.Update(harness.at(world, 0, 64, 0), HologramPresentation.identity(), null, settings, "Health", 0));
        Location destination = harness.at(world, 4, 65, 4);
        box.update(new TextDisplayDecoration.Update(destination, HologramPresentation.identity(), null, settings, "Health", 0));
        harness.ownsThread = true;
        harness.deferImmediateTasks = false;
        harness.drainImmediate();
        box.update(new TextDisplayDecoration.Update(destination, HologramPresentation.identity(), null, settings, "Health", 0));
        assertEquals(5, harness.liveSpawned(world).size());
        for (DisplayHandle display : harness.liveSpawned(world)) {
            assertEquals(destination, display.teleports.getLast());
        }
        box.destroy(destination);
        assertTrue(harness.liveSpawned(world).isEmpty());
    }

    private TemporaryHologramDisplay temporary() {
        TemporaryHologramDisplay temporary = harness.temporary("styled", harness.at(world, 0, 64, 0), 60_000L);
        temporary.setRenderedLines(List.of("Health"));
        return temporary;
    }

    private static IconDisplayStyle style() {
        return new IconDisplayStyle(IconBillboard.VERTICAL, true, true, IconTextAlignment.RIGHT,
            new IconArgbColor(0xAA123456), 128, 120, 12, 13, 2F, 3F, 0.4F,
            8F, 9F, new IconArgbColor(0xFFABCDEF), 2F, 3F, 4F);
    }
}
