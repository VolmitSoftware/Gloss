package art.arcane.gloss.hologram;

import art.arcane.gloss.hologram.CharacterizationHarness.DisplayHandle;
import art.arcane.gloss.hologram.CharacterizationHarness.PlayerHandle;
import art.arcane.gloss.hologram.CharacterizationHarness.WorldState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HologramRenderCacheTest {
    @TempDir
    File dataFolder;

    private CharacterizationHarness harness;
    private WorldState world;
    private PlayerHandle alice;
    private PlayerHandle bob;
    private final List<String> renders = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        harness = new CharacterizationHarness(dataFolder);
        world = harness.world("overworld");
        alice = harness.join("Alice", world, 1.0D, 64.0D, 1.0D);
        bob = harness.join("Bob", world, 2.0D, 64.0D, 2.0D);
        harness.text.setEmojiFilter(raw -> {
            renders.add(raw);
            return raw;
        });
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    private long renderCount(String raw) {
        long count = 0L;
        for (String rendered : renders) {
            if (rendered.equals(raw)) {
                count++;
            }
        }

        return count;
    }

    private PersistentHologram hologram(String id, List<String> lines) {
        PersistentHologram hologram = harness.persistent(id, harness.at(world, 0.5D, 64.0D, 0.5D));
        hologram.setLines(lines);
        return hologram;
    }

    @Test
    void unchangedSharedLinesRenderOnlyOnce() {
        PersistentHologram hologram = hologram("h-cache", List.of("&aOne", "&bTwo"));
        hologram.update();
        int afterSpawn = renders.size();

        hologram.update();
        hologram.update();

        assertEquals(afterSpawn, renders.size(), "unchanged static lines must not re-enter the pipeline");
        assertEquals("§aOne\n§bTwo", harness.onlySpawned(world).lastText());
    }

    @Test
    void editingALineInvalidatesTheSharedRenderCache() {
        PersistentHologram hologram = hologram("h-edit", List.of("&aOne", "&bTwo"));
        hologram.update();
        DisplayHandle display = harness.onlySpawned(world);

        hologram.setLine(0, "&cChanged");
        hologram.update();

        assertEquals("§cChanged\n§bTwo", display.lastText());
    }

    @Test
    void perViewerRenderingSharesStaticSegmentsAcrossViewers() {
        PersistentHologram hologram = hologram("h-split", List.of("&aStatic", "%p% dynamic"));
        hologram.update();

        assertEquals(2, harness.liveSpawned(world).size());
        assertEquals(1L, renderCount("&aStatic"), "static lines must render once for every viewer");
        assertEquals(2L, renderCount("%p% dynamic"), "dynamic lines must render per viewer");

        hologram.update();

        assertEquals(1L, renderCount("&aStatic"), "static segments must survive across drives");
        assertEquals(4L, renderCount("%p% dynamic"));
    }

    @Test
    void unregisteredPipeLinesStopReRenderingEveryDrive() {
        TemporaryHologramDisplay temporary = harness.temporary("t-pipe", harness.at(world, 0.5D, 64.0D, 0.5D), 60_000L);
        temporary.setLines(List.of("a | b"));
        temporary.drive(true);
        int afterSpawn = renders.size();

        temporary.drive(true);
        temporary.drive(true);

        assertEquals(afterSpawn, renders.size(), "unregistered pipe tokens must not force a re-render");
        assertEquals("a | b", harness.onlySpawned(world).lastText());
    }

    @Test
    void registeredFunctionLinesKeepRenderingEveryDrive() {
        harness.registerFunction("state", player -> "on");
        TemporaryHologramDisplay temporary = harness.temporary("t-fn", harness.at(world, 0.5D, 64.0D, 0.5D), 60_000L);
        temporary.setLines(List.of("|state|"));
        temporary.drive(true);
        int afterSpawn = renders.size();

        temporary.drive(true);
        temporary.drive(true);

        assertTrue(renders.size() > afterSpawn, "registered functions must keep refreshing at drive cadence");
    }

    @Test
    void nobodyInRangeSkipsSpawnAndRendering() {
        harness.moveTo(alice, world, 500.0D, 64.0D, 500.0D);
        harness.moveTo(bob, world, 500.0D, 64.0D, 500.0D);
        PersistentHologram hologram = hologram("h-far", List.of("&aHi"));

        hologram.update();

        assertTrue(harness.liveSpawned(world).isEmpty(), "an unwatched hologram must not spawn an entity");
        assertEquals(0, renders.size(), "an unwatched hologram must do no text work");

        harness.moveTo(alice, world, 1.0D, 64.0D, 1.0D);
        hologram.update();

        assertEquals(1, harness.liveSpawned(world).size(), "an approaching viewer must bring the hologram back");
        assertEquals("§aHi", harness.onlySpawned(world).lastText());
    }

    @Test
    void animatedTargetsAreRetractedWhileNobodyIsInRange() {
        PersistentHologram hologram = hologram("h-anim-range", List.of(CharacterizationHarness.FAST_CLIP_LINE));
        hologram.update();
        hologram.update();
        assertEquals(1, harness.animator.targetCount());

        harness.moveTo(alice, world, 500.0D, 64.0D, 500.0D);
        harness.moveTo(bob, world, 500.0D, 64.0D, 500.0D);
        hologram.update();
        assertEquals(0, harness.animator.targetCount(), "no viewer in range must retract the animator target");

        harness.moveTo(alice, world, 1.0D, 64.0D, 1.0D);
        hologram.update();
        assertEquals(1, harness.animator.targetCount(), "a returning viewer must republish the animator target");
    }
}
