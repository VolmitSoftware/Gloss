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
            () -> assertEquals(1D, value(samples, IntegrationMetricSchema.GLOSS_ANIMATIONS_ACTIVE), 0D,
                "animations-active counts animating hologram targets, not loaded clips"));
    }

    @Test
    void animationsActiveCountsTickDrivenClipsAlongsideAnimatorTargets() {
        harness.configureAnimation("rainbow", 1000.0D / 53.0D, List.of("[FF0000]", "[00FF00]", "[0000FF]"));
        harness.configureAnimation("marquee", 1.0D, List.of("&b{{ marquee('GLOSS REALMS', 12, floor(time.seconds * 4)) }}"));
        harness.configureAnimation("still", 1.0D, List.of("z"));
        spawned("fast-holo", 0.5D, CharacterizationHarness.FAST_CLIP_LINE);
        spawned("rainbow-holo", 2.5D, "|animation.rainbow|&lREACT PERF");
        spawned("marquee-holo", 4.5D, "|animation.marquee|");
        spawned("still-holo", 6.5D, "|animation.still|");
        spawned("plain-holo", 8.5D, "plain");
        PersistentHologram far = harness.persistent("far-holo", harness.at(world, 900.5D, 64.0D, 900.5D));
        far.setLines(List.of("|animation.rainbow|far"));
        far.update();
        harness.drainDelayed();

        double animations = value(new GlossIntegrationService().sampleMetrics(
            Set.of(IntegrationMetricSchema.GLOSS_ANIMATIONS_ACTIVE)), IntegrationMetricSchema.GLOSS_ANIMATIONS_ACTIVE);

        assertAll(
            () -> assertEquals(5, harness.service.spawnedHologramCount()),
            () -> assertEquals(1, harness.animator.targetCount(), "only the fast clip rides the async animator"),
            () -> assertEquals(3D, animations, 0D,
                "the fast, rainbow and marquee holograms animate; the still clip, plain text and unspawned hologram do not"));
    }

    @Test
    void animationsActiveCountsEachViewerOfAPersonalizedTickDrivenClip() {
        harness.join("Bob", world, 2.0D, 64.0D, 2.0D);
        harness.registerFunction("who", player -> player == null ? "console" : player.getName());
        harness.configureAnimation("rainbow", 1000.0D / 53.0D, List.of("[FF0000]", "[00FF00]", "[0000FF]"));
        spawned("personal-holo", 0.5D, "|animation.rainbow||who|");

        double animations = value(new GlossIntegrationService().sampleMetrics(
            Set.of(IntegrationMetricSchema.GLOSS_ANIMATIONS_ACTIVE)), IntegrationMetricSchema.GLOSS_ANIMATIONS_ACTIVE);

        assertAll(
            () -> assertEquals(0, harness.animator.targetCount()),
            () -> assertEquals(2D, animations, 0D, "each viewer receives its own animated text"));
    }

    @Test
    void animationsActiveCountsTickDrivenTemporaryHolograms() {
        harness.configureAnimation("rainbow", 1000.0D / 53.0D, List.of("[FF0000]", "[00FF00]", "[0000FF]"));
        TemporaryHologramDisplay animated = harness.temporary("temp-rainbow", harness.at(world, 0.5D, 64.0D, 0.5D), 60_000L);
        animated.setLines(List.of("|animation.rainbow|temp"));
        animated.drive(true);
        TemporaryHologramDisplay plain = harness.temporary("temp-plain", harness.at(world, 2.5D, 64.0D, 0.5D), 60_000L);
        plain.setLines(List.of("plain"));
        plain.drive(true);
        harness.drainDelayed();

        double animations = value(new GlossIntegrationService().sampleMetrics(
            Set.of(IntegrationMetricSchema.GLOSS_ANIMATIONS_ACTIVE)), IntegrationMetricSchema.GLOSS_ANIMATIONS_ACTIVE);

        assertAll(
            () -> assertEquals(2, harness.liveSpawned(world).size()),
            () -> assertEquals(0, harness.animator.targetCount()),
            () -> assertEquals(1D, animations, 0D, "only the temporary hologram with an animated clip counts"));
    }

    private void spawned(String id, double x, String line) {
        PersistentHologram hologram = harness.persistent(id, harness.at(world, x, 64.0D, 0.5D));
        hologram.setLines(List.of(line));
        hologram.update();
        harness.drainDelayed();
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
