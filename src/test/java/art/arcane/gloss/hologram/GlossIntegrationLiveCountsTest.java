package art.arcane.gloss.hologram;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.board.BoardService;
import art.arcane.gloss.hologram.CharacterizationHarness.WorldState;
import art.arcane.gloss.service.GlossIntegrationService;
import art.arcane.volmlib.integration.IntegrationMetricSample;
import art.arcane.volmlib.integration.IntegrationMetricSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

class GlossIntegrationLiveCountsTest {
    @TempDir
    File dataFolder;

    private CharacterizationHarness harness;
    private WorldState world;
    private Gloss previousInstance;

    @BeforeEach
    void setUp() {
        harness = new CharacterizationHarness(dataFolder);
        world = harness.world("overworld");
        harness.join("Alice", world, 1.0D, 64.0D, 1.0D);
        previousInstance = Gloss.instance;
        Gloss.instance = harness.gloss;
    }

    @AfterEach
    void tearDown() {
        Gloss.instance = previousInstance;
        harness.close();
    }

    @Test
    void liveGaugesReadWhatIsOnScreenRatherThanWhatIsLoaded() throws ReflectiveOperationException {
        harness.configureAnimation("slow", List.of("x", "y"));
        harness.configureAnimation("idle", List.of("z"));
        PersistentHologram near = harness.persistent("near-holo", harness.at(world, 0.5D, 64.0D, 0.5D));
        near.setLines(List.of(CharacterizationHarness.FAST_CLIP_LINE));
        PersistentHologram far = harness.persistent("far-holo", harness.at(world, 900.5D, 64.0D, 900.5D));
        far.setLines(List.of("far"));
        near.update();
        far.update();
        BoardService boards = boards(2, 3);

        Map<String, IntegrationMetricSample> samples = new GlossIntegrationService().sampleMetrics(Set.of(
            IntegrationMetricSchema.GLOSS_HOLOGRAMS_ACTIVE,
            IntegrationMetricSchema.GLOSS_BOARDS_ACTIVE,
            IntegrationMetricSchema.GLOSS_ANIMATIONS_ACTIVE));

        assertEquals(2, harness.service.hologramCount());
        assertEquals(3, harness.animations.names().size());
        assertAll(
            () -> assertEquals(harness.service.spawnedHologramCount(),
                value(samples, IntegrationMetricSchema.GLOSS_HOLOGRAMS_ACTIVE), 0D,
                "holograms-active counts spawned holograms, not loaded definitions"),
            () -> assertEquals(1D, value(samples, IntegrationMetricSchema.GLOSS_HOLOGRAMS_ACTIVE), 0D),
            () -> assertEquals(boards.activeBoardCount(),
                value(samples, IntegrationMetricSchema.GLOSS_BOARDS_ACTIVE), 0D,
                "boards-active counts players shown a sidebar, not board definitions"),
            () -> assertEquals(2D, value(samples, IntegrationMetricSchema.GLOSS_BOARDS_ACTIVE), 0D),
            () -> assertEquals(harness.animator.targetCount(),
                value(samples, IntegrationMetricSchema.GLOSS_ANIMATIONS_ACTIVE), 0D,
                "animations-active counts animator targets, not loaded clips"),
            () -> assertEquals(1D, value(samples, IntegrationMetricSchema.GLOSS_ANIMATIONS_ACTIVE), 0D));
    }

    private BoardService boards(int shown, int defined) throws ReflectiveOperationException {
        BoardService boards = allocate(BoardService.class);
        Map<UUID, Object> profiles = new HashMap<>();
        for (int index = 0; index < shown; index++) {
            profiles.put(UUID.randomUUID(), null);
        }
        Map<String, Object> metas = new HashMap<>();
        for (int index = 0; index < defined; index++) {
            metas.put("board-" + index, null);
        }
        field(BoardService.class, "profiles").set(boards, profiles);
        field(BoardService.class, "metas").set(boards, metas);
        field(Gloss.class, "boards").set(harness.gloss, boards);
        return boards;
    }

    private static double value(Map<String, IntegrationMetricSample> samples, String key) {
        return samples.get(key).valueOr(-1D);
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static <T> T allocate(Class<T> type) throws ReflectiveOperationException {
        sun.reflect.ReflectionFactory factory = sun.reflect.ReflectionFactory.getReflectionFactory();
        Constructor<?> constructor = factory.newConstructorForSerialization(type, Object.class.getDeclaredConstructor());
        return type.cast(constructor.newInstance());
    }
}
