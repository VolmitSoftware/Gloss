package art.arcane.gloss.hologram;

import art.arcane.gloss.hologram.CharacterizationHarness.WorldState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PersistentHologramOrientationTest {
    @TempDir
    File dataFolder;

    private CharacterizationHarness harness;
    private PersistentHologram hologram;

    @BeforeEach
    void setUp() {
        harness = new CharacterizationHarness(dataFolder);
        WorldState world = harness.world("overworld");
        hologram = harness.persistent("orientation", harness.at(world, 0.5D, 64.0D, 0.5D));
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    @Test
    void everyInvalidArgumentLeavesOrientationAndPersistenceRevisionUnchanged() throws Exception {
        long revision = revision();

        assertThrows(IllegalArgumentException.class,
            () -> hologram.setOrientation("SPIN", 10.0D, 20.0D));
        assertUnchanged(revision);

        assertThrows(IllegalArgumentException.class,
            () -> hologram.setOrientation("FIXED", 181.0D, 20.0D));
        assertUnchanged(revision);

        assertThrows(IllegalArgumentException.class,
            () -> hologram.setOrientation("VERTICAL", -30.0D, 91.0D));
        assertUnchanged(revision);
    }

    private void assertUnchanged(long revision) throws Exception {
        assertEquals(HologramDoc.DEFAULT_BILLBOARD, hologram.billboard());
        assertEquals(0.0D, hologram.yaw());
        assertEquals(0.0D, hologram.pitch());
        assertEquals(revision, revision());
    }

    private long revision() throws Exception {
        Field field = PersistentHologram.class.getDeclaredField("revision");
        field.setAccessible(true);
        return field.getLong(hologram);
    }
}
