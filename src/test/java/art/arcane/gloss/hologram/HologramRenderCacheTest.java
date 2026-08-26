package art.arcane.gloss.hologram;

import art.arcane.gloss.hologram.CharacterizationHarness.DisplayHandle;
import art.arcane.gloss.hologram.CharacterizationHarness.PlayerHandle;
import art.arcane.gloss.hologram.CharacterizationHarness.WorldState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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

    private Map<UUID, String> latestViewerText() {
        harness.animator.pass(System.currentTimeMillis());
        Map<UUID, String> latest = new HashMap<>();
        for (CharacterizationHarness.Sent sent : harness.sender.sent) {
            if (sent.viewers().size() == 1) {
                latest.put(sent.viewers().getFirst().getUniqueId(), sent.text());
            }
        }
        return latest;
    }

    @Test
    void unchangedSharedLinesRenderOnlyOnce() {
        PersistentHologram hologram = hologram("h-cache", List.of("&aOne", "&bTwo"));
        hologram.update();
        int afterSpawn = renders.size();

        hologram.update();
        hologram.update();

        assertEquals(afterSpawn, renders.size(), "unchanged static lines must not re-enter the pipeline");
        assertEquals("§aOne§r\n§bTwo", harness.onlySpawned(world).lastText());
    }

    @Test
    void editingALineInvalidatesTheSharedRenderCache() {
        PersistentHologram hologram = hologram("h-edit", List.of("&aOne", "&bTwo"));
        hologram.update();
        DisplayHandle display = harness.onlySpawned(world);

        hologram.setLine(0, "&cChanged");
        hologram.update();

        assertEquals("§cChanged§r\n§bTwo", display.lastText());
    }

    @Test
    void perViewerRenderingSharesStaticSegmentsAcrossViewers() {
        PersistentHologram hologram = hologram("h-split", List.of("&aStatic", "%p% dynamic"));
        hologram.update();
        harness.drainDelayed();

        assertEquals(1, harness.liveSpawned(world).size());
        assertEquals(1L, renderCount("&aStatic"), "static lines must render once for every viewer");
        assertEquals(2L, renderCount("%p% dynamic"), "dynamic lines must render per viewer");

        hologram.update();

        assertEquals(1L, renderCount("&aStatic"), "static segments must survive across drives");
        assertEquals(4L, renderCount("%p% dynamic"));
    }

    @Test
    void personalizedAnimationTemplatesRefreshAtPersistentCadence() {
        PersistentHologram hologram = hologram("h-personal-animation",
            List.of("%player_name% " + CharacterizationHarness.FAST_CLIP_LINE));
        hologram.update();
        harness.drainDelayed();
        int afterFirstRefresh = renders.size();
        long afterFirstDispatch = harness.service.viewerWorkDispatchCount();

        hologram.update();
        hologram.update();

        assertEquals(afterFirstRefresh, renders.size(),
            "the fast driver must reuse personalized templates until the persistent refresh cadence");
        assertEquals(afterFirstDispatch, harness.service.viewerWorkDispatchCount(),
            "fresh personalized templates must return before entering the player scheduler");
        assertEquals(2, harness.animator.targetCount());
    }

    @Test
    void personalizedClockAnimationRefreshesEveryTick() throws InterruptedException {
        PersistentHologram hologram = hologram("h-personal-clock-animation",
            List.of("%player_name% {{ time.ms }} " + CharacterizationHarness.FAST_CLIP_LINE));
        hologram.update();
        harness.drainDelayed();
        long firstDispatches = harness.service.viewerWorkDispatchCount();

        hologram.update();
        assertEquals(firstDispatches, harness.service.viewerWorkDispatchCount(),
            "multiple updates inside one tick must reuse the personalized template");

        Thread.sleep(60L);
        hologram.update();
        assertTrue(harness.service.viewerWorkDispatchCount() > firstDispatches,
            "clock-driven personalized animation literals must become due after one tick");
    }

    @Test
    void globalDynamicLineStaysLiveBesideViewerSpecificContent() throws InterruptedException {
        PersistentHologram hologram = hologram("h-mixed-dynamic",
            List.of("%player_name%", "{{ time.ms }}"));
        hologram.update();
        harness.drainDelayed();
        Set<String> first = new HashSet<>(latestViewerText().values());

        Thread.sleep(5L);
        hologram.update();

        Set<String> second = new HashSet<>(latestViewerText().values());
        assertTrue(!first.equals(second),
            "global time expressions must not be frozen by the per-viewer static-segment cache");
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

    @Test
    void animationReloadInvalidatesPublishedTemplate() {
        harness.configureAnimation("fast", List.of("old-a", "old-b"));
        PersistentHologram hologram = hologram("h-animation-reload",
            List.of(CharacterizationHarness.FAST_CLIP_LINE));
        hologram.update();
        hologram.update();
        harness.animator.pass(0L);
        assertEquals("old-a", harness.sender.sent.getLast().text());

        harness.configureAnimation("fast", List.of("new-a", "new-b"));
        hologram.update();
        harness.animator.pass(0L);

        assertEquals("new-a", harness.sender.sent.getLast().text(),
            "a same-id clip reload must replace the cached animation template");
    }

    @Test
    void dynamicLiteralBesideAnimationIsRecomposed() throws InterruptedException {
        PersistentHologram hologram = hologram("h-animation-dynamic-literal",
            List.of("{{ time.ms }} " + CharacterizationHarness.FAST_CLIP_LINE));
        hologram.update();
        hologram.update();
        harness.animator.pass(0L);
        String first = harness.sender.sent.getLast().text();

        Thread.sleep(5L);
        hologram.update();
        harness.animator.pass(0L);

        assertTrue(!first.equals(harness.sender.sent.getLast().text()),
            "dynamic literal segments must not freeze inside an animation template memo");
    }
}
