package art.arcane.gloss.hologram;

import art.arcane.gloss.hologram.CharacterizationHarness.DisplayHandle;
import art.arcane.gloss.hologram.CharacterizationHarness.PlayerHandle;
import art.arcane.gloss.hologram.CharacterizationHarness.WorldState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterization pins for persistent-hologram routing and text assignment (STEP 1, Char-1).
 *
 * <p>Frozen contract for Lane A (A2 static-revision caching, A3 per-viewer split rendering,
 * A12 shared world snapshots). Pins assert which routing shape serves a line set (shared entity
 * vs one entity per in-range viewer), the rendered text each viewer ends up with, animator
 * publish/retract transitions, and the reposition epsilon — all invariant under the planned
 * caching work.</p>
 */
class CharacterizationPersistentHologramRoutingTest {
    @TempDir
    File dataFolder;

    private CharacterizationHarness harness;
    private WorldState world;
    private PlayerHandle alice;
    private PlayerHandle bob;

    @BeforeEach
    void setUp() {
        harness = new CharacterizationHarness(dataFolder);
        world = harness.world("overworld");
        alice = harness.join("Alice", world, 1.0D, 64.0D, 1.0D);
        bob = harness.join("Bob", world, 2.0D, 64.0D, 2.0D);
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    private PersistentHologram hologram(String id, List<String> lines) {
        PersistentHologram hologram = harness.persistent(id, harness.at(world, 0.5D, 64.0D, 0.5D));
        hologram.setLines(lines);
        return hologram;
    }

    @Test
    void staticLinesShareOneDisplayForAllViewers() {
        PersistentHologram hologram = hologram("h-static", List.of("&aHello", "World"));
        hologram.update();

        DisplayHandle display = harness.onlySpawned(world);
        assertEquals("§aHello\nWorld", display.lastText(),
            "static lines must render once through the static pipeline");
        assertNull(display.visibleByDefault, "the shared display must stay visible to everyone");

        int assignments = display.textHistory.size();
        hologram.update();
        hologram.update();
        assertEquals(assignments, display.textHistory.size(),
            "unchanged static lines must not reassign text");
        assertEquals(1, harness.liveSpawned(world).size(), "static lines must never fan out per viewer");
    }

    @Test
    void literalPercentAndPipeCharactersKeepSharedRouting() {
        PersistentHologram hologram = hologram("h-literal-markers", List.of("100% ready", "A | B"));
        hologram.update();

        assertEquals("100% ready\nA | B", harness.onlySpawned(world).lastText());
        assertEquals(1, harness.liveSpawned(world).size());
    }

    @Test
    void placeholderLinesRouteToPrivatePerViewerDisplays() {
        harness.registerFunction("who", player -> player == null ? "console" : player.getName());
        PersistentHologram hologram = hologram("h-viewer", List.of("%score% |who|"));
        hologram.update();

        List<DisplayHandle> live = harness.liveSpawned(world);
        assertEquals(2, live.size(), "each in-range viewer must get a private display");

        Set<String> texts = new HashSet<>();
        for (DisplayHandle display : live) {
            assertEquals(Boolean.FALSE, display.visibleByDefault,
                "per-viewer displays must spawn hidden by default");
            texts.add(display.lastText());
        }
        assertEquals(Set.of("%score% Alice", "%score% Bob"), texts,
            "per-viewer rendering must resolve viewer-dependent content per player");

        for (DisplayHandle display : live) {
            boolean aliceSees = Boolean.TRUE.equals(alice.perceivedVisibility(display));
            boolean bobSees = Boolean.TRUE.equals(bob.perceivedVisibility(display));
            assertTrue(aliceSees ^ bobSees, "each private display must be shown to exactly one viewer");
            PlayerHandle owner = aliceSees ? alice : bob;
            assertTrue(display.lastText().contains(owner.name),
                "the display shown to a viewer must carry that viewer's rendering");
        }
    }

    @Test
    void inlinePlayerExpressionsRouteToPrivatePerViewerDisplays() {
        PersistentHologram hologram = hologram("h-expression", List.of("Hello {{ player.name }}"));
        hologram.update();

        List<DisplayHandle> live = harness.liveSpawned(world);
        assertEquals(2, live.size());
        Set<String> texts = new HashSet<>();
        for (DisplayHandle display : live) {
            texts.add(display.lastText());
        }
        assertEquals(Set.of("Hello Alice", "Hello Bob"), texts);
    }

    @Test
    void perViewerPlaceholdersDisabledKeepsSharedRouting() {
        harness.configure(file -> file.holograms.perViewerPlaceholders = false);
        PersistentHologram hologram = hologram("h-sharedpct", List.of("%hp% hearts"));
        hologram.update();

        DisplayHandle display = harness.onlySpawned(world);
        assertEquals("%hp% hearts", display.lastText(),
            "with per-viewer routing disabled the placeholder line renders once, unresolved");
        assertEquals(1, harness.liveSpawned(world).size());
    }

    @Test
    void viewerLeavingRangeDespawnsThePrivateDisplayAndReturnRespawns() {
        harness.registerFunction("who", player -> player == null ? "console" : player.getName());
        PersistentHologram hologram = hologram("h-range", List.of("%score% |who|"));
        hologram.update();
        assertEquals(2, harness.liveSpawned(world).size());

        harness.moveTo(bob, world, 200.0D, 64.0D, 200.0D);
        hologram.update();

        List<DisplayHandle> live = harness.liveSpawned(world);
        assertEquals(1, live.size(), "leaving view range must despawn the private display");
        assertTrue(live.get(0).lastText().contains("Alice"));

        harness.moveTo(bob, world, 2.0D, 64.0D, 2.0D);
        hologram.update();
        assertEquals(2, harness.liveSpawned(world).size(), "re-entering range must respawn the display");
    }

    @Test
    void perViewerTextReassignsOnlyWhenTheRenderingChanges() {
        AtomicReference<String> mood = new AtomicReference<>("happy");
        harness.registerFunction("mood", player -> mood.get());
        PersistentHologram hologram = hologram("h-change", List.of("%s% |mood|"));
        hologram.update();

        List<DisplayHandle> live = harness.liveSpawned(world);
        assertEquals(2, live.size());
        for (DisplayHandle display : live) {
            assertEquals("%s% happy", display.lastText());
        }
        int assignments = live.get(0).textHistory.size() + live.get(1).textHistory.size();

        hologram.update();
        assertEquals(assignments, live.get(0).textHistory.size() + live.get(1).textHistory.size(),
            "identical per-viewer renders must not reassign text");

        mood.set("grim");
        hologram.update();
        for (DisplayHandle display : live) {
            assertEquals("%s% grim", display.lastText(),
                "changed renders must be reassigned to every viewer display");
        }
    }

    @Test
    void animationLinePublishesOnePrivateTargetPerViewerAndRetractsWithTheLineSet() {
        PersistentHologram hologram = hologram("h-anim", List.of(CharacterizationHarness.FAST_CLIP_LINE));
        hologram.update();

        hologram.update();
        assertEquals(2, harness.animator.targetCount(), "viewer-capable animation lines publish one target per viewer");
        assertEquals(2, harness.animator.pass(0L));
        for (CharacterizationHarness.Sent sent : harness.sender.sent) {
            assertEquals(1, sent.viewers().size());
        }

        hologram.setLines(List.of("plain"));
        hologram.update();
        assertEquals(0, harness.animator.targetCount(), "dropping the animated line must retract the target");
        assertEquals("plain", harness.onlySpawned(world).lastText(),
            "the shared tick path must take back over after retraction");
    }

    @Test
    void perViewerAnimationSubsFollowTheirViewers() {
        PersistentHologram hologram = hologram("h-anim-viewer", List.of("%p% " + CharacterizationHarness.FAST_CLIP_LINE));
        hologram.update();

        assertEquals(2, harness.animator.targetCount(),
            "a placeholder+animation line must publish one animator sub per in-range viewer");
        assertEquals(2, harness.animator.pass(0L));
        for (CharacterizationHarness.Sent sent : harness.sender.sent) {
            assertEquals(1, sent.viewers().size(), "per-viewer animation sends target exactly one viewer");
            assertEquals("%p% A", sent.text(), "literal segments stay verbatim around the clip slot");
        }

        harness.moveTo(bob, world, 200.0D, 64.0D, 200.0D);
        hologram.update();
        assertEquals(1, harness.animator.targetCount(),
            "a viewer leaving range must retract that viewer's animator sub");
        assertEquals(1, harness.liveSpawned(world).size());
    }

    @Test
    void repositionHonorsTheEpsilonGate() {
        PersistentHologram hologram = hologram("h-move", List.of("hi"));
        hologram.update();
        DisplayHandle display = harness.onlySpawned(world);

        hologram.teleport(harness.at(world, 0.5001D, 64.0D, 0.5D));
        hologram.update();
        assertTrue(display.teleports.isEmpty(), "sub-epsilon moves must not reposition the display");

        hologram.teleport(harness.at(world, 5.5D, 64.0D, 0.5D));
        hologram.update();
        assertEquals(1, display.teleports.size(), "real moves must reposition the display");
        assertEquals(5.5D, display.teleports.get(0).getX());
    }

    @Test
    void missingWorldOrEmptyLinesDespawnEverything() {
        PersistentHologram hologram = hologram("h-gone", List.of("hi"));
        hologram.update();
        DisplayHandle display = harness.onlySpawned(world);

        hologram.clearLines();
        hologram.update();
        assertTrue(display.removed, "empty line sets must despawn the display");

        hologram.setLines(List.of("back"));
        hologram.update();
        DisplayHandle respawned = harness.onlySpawned(world);
        assertFalse(respawned.removed);

        harness.worlds.remove("overworld");
        hologram.update();
        assertTrue(respawned.removed, "an unloaded world must despawn the display");
    }
}
