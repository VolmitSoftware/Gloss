package art.arcane.gloss.hologram;

import art.arcane.gloss.hologram.CharacterizationHarness.DisplayHandle;
import art.arcane.gloss.hologram.CharacterizationHarness.WorldState;
import org.bukkit.util.Transformation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PersistentHologramScaleTest {
    @TempDir
    File dataFolder;

    private CharacterizationHarness harness;
    private WorldState world;
    private PersistentHologram hologram;

    @BeforeEach
    void setUp() {
        harness = new CharacterizationHarness(dataFolder);
        world = harness.world("overworld");
        harness.join("viewer", world, 0.5D, 64.0D, 1.5D);
        hologram = harness.persistent("scaled", harness.at(world, 0.5D, 64.0D, 0.5D));
        hologram.setLines(List.of("Testing2"));
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    @Test
    void spawnAndLiveUpdatesUseTheUniformTextDisplayTransformation() {
        hologram.setScale(3.5D);
        harness.driveHolograms();

        DisplayHandle display = harness.onlySpawned(world);
        assertUniformScale(display.transformation, 3.5F);

        hologram.setScale(6.25D);

        assertUniformScale(display.transformation, 6.25F);
    }

    @Test
    void hotloadedScaleUpdatesTheLiveDisplay() {
        harness.driveHolograms();
        DisplayHandle display = harness.onlySpawned(world);
        HologramDoc current = hologram.toDoc(2L);
        HologramDoc changed = new HologramDoc(HologramDoc.CURRENT_SCHEMA_VERSION, 3L,
            current.anchor(), current.lines(), current.seeThrough(), 4.5D,
            current.billboard(), current.yaw(), current.pitch(), current.particleLayers());

        hologram.apply(changed);

        assertEquals(4.5D, hologram.scale());
        assertUniformScale(display.transformation, 4.5F);
    }

    @Test
    void invalidScaleLeavesValueAndPersistenceRevisionUnchanged() throws Exception {
        double scale = hologram.scale();
        long revision = revision();

        assertThrows(IllegalArgumentException.class, () -> hologram.setScale(0.0D));
        assertEquals(scale, hologram.scale());
        assertEquals(revision, revision());

        assertThrows(IllegalArgumentException.class, () -> hologram.setScale(Double.NaN));
        assertEquals(scale, hologram.scale());
        assertEquals(revision, revision());
    }

    private void assertUniformScale(Transformation transformation, float expected) {
        assertEquals(expected, transformation.getScale().x, 0.000001F);
        assertEquals(expected, transformation.getScale().y, 0.000001F);
        assertEquals(expected, transformation.getScale().z, 0.000001F);
    }

    private long revision() throws Exception {
        Field field = PersistentHologram.class.getDeclaredField("revision");
        field.setAccessible(true);
        return field.getLong(hologram);
    }
}
