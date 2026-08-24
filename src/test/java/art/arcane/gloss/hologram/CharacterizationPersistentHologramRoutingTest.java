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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterization pins for persistent-hologram routing and text assignment (STEP 1, Char-1).
 *
 * <p>Frozen contract for Lane A (A2 static-revision caching, A3 per-viewer packet rendering,
 * A12 shared world snapshots). Pins assert which routing shape serves a line set, the rendered
 * text each viewer ends up with, animator
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
    void placeholderLinesUseOneBlankEntityWithPerViewerMetadata() {
        harness.registerFunction("who", player -> player == null ? "console" : player.getName());
        PersistentHologram hologram = hologram("h-viewer", List.of("%score% |who|"));
        hologram.update();
        harness.drainDelayed();

        DisplayHandle display = harness.onlySpawned(world);
        assertEquals("", display.lastText(), "the server entity base text must not expose personalized content");
        assertNull(display.visibleByDefault);
        assertEquals(Map.of(alice.uuid, "%score% Alice", bob.uuid, "%score% Bob"), latestViewerText(),
            "one entity id must receive viewer-specific metadata on each player connection");
        for (CharacterizationHarness.Sent sent : harness.sender.sent) {
            assertEquals(display.entityId, sent.entityId(),
                "every personalized recipient must target the shared server entity id");
        }
    }

    @Test
    void inlinePlayerExpressionsRouteToPerViewerMetadata() {
        PersistentHologram hologram = hologram("h-expression", List.of("Hello {{ player.name }}"));
        hologram.update();
        harness.drainDelayed();

        assertEquals(1, harness.liveSpawned(world).size());
        assertEquals(Set.of("Hello Alice", "Hello Bob"), new HashSet<>(latestViewerText().values()));
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
    void viewerLeavingRangeRetractsItsMetadataWhileTheSharedEntityRemains() {
        harness.registerFunction("who", player -> player == null ? "console" : player.getName());
        PersistentHologram hologram = hologram("h-range", List.of("%score% |who|"));
        hologram.update();
        harness.drainDelayed();
        assertEquals(1, harness.liveSpawned(world).size());
        latestViewerText();

        harness.moveTo(bob, world, 200.0D, 64.0D, 200.0D);
        hologram.update();

        assertEquals(1, harness.liveSpawned(world).size());
        assertEquals(1, hologram.activeViewerCount(), "leaving view range must retract viewer state");
        assertEquals("", latestViewerText().get(bob.uuid),
            "a viewer leaving range must have stale personalized metadata blanked");

        int sends = harness.sender.sent.size();
        harness.moveTo(bob, world, 2.0D, 64.0D, 2.0D);
        hologram.update();
        latestViewerText();
        assertEquals(sends + 1, harness.sender.sent.size(),
            "re-entering range must resend personalized metadata for the tracked entity");
        assertEquals(1, harness.liveSpawned(world).size());
    }

    @Test
    void blankBaseTextKeepsPersonalizedContentPrivateOnEveryServerApi() {
        world.visibleByDefaultSupported = false;
        harness.moveTo(bob, world, 200.0D, 64.0D, 200.0D);
        PersistentHologram hologram = hologram("h-private-fallback",
            List.of("Hello {{ player.name }}"));
        hologram.update();
        harness.drainDelayed();
        DisplayHandle display = harness.onlySpawned(world);
        assertEquals("", display.lastText(), "an unaddressed tracker can only receive blank server text");

        PlayerHandle late = harness.join("Late", world, 300.0D, 64.0D, 300.0D);
        hologram.invalidateTrackingFor(late.proxy, true);
        assertTrue(!latestViewerText().containsKey(late.uuid));

        harness.moveTo(bob, world, 2.0D, 64.0D, 2.0D);
        hologram.update();
        assertEquals("Hello Bob", latestViewerText().get(bob.uuid),
            "an approaching viewer receives only its own metadata text");
    }

    @Test
    void perViewerTextReassignsOnlyWhenTheRenderingChanges() {
        AtomicReference<String> mood = new AtomicReference<>("happy");
        harness.registerFunction("mood", player -> mood.get());
        PersistentHologram hologram = hologram("h-change", List.of("%s% |mood|"));
        hologram.update();
        harness.drainDelayed();

        assertEquals(Set.of("%s% happy"), new HashSet<>(latestViewerText().values()));
        int assignments = harness.sender.sent.size();

        hologram.update();
        assertEquals(assignments, harness.sender.sent.size(),
            "identical per-viewer renders must not resend metadata");

        mood.set("grim");
        hologram.update();
        assertEquals(Set.of("%s% grim"), new HashSet<>(latestViewerText().values()));
    }

    @Test
    void viewerIndependentAnimationUsesOneSharedTargetAndRetractsWithTheLineSet() {
        PersistentHologram hologram = hologram("h-anim", List.of(CharacterizationHarness.FAST_CLIP_LINE));
        hologram.update();

        hologram.update();
        assertEquals(1, harness.animator.targetCount(), "global animation lines publish one shared target");
        assertEquals(1, harness.animator.pass(0L));
        for (CharacterizationHarness.Sent sent : harness.sender.sent) {
            assertEquals(2, sent.viewers().size());
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
        harness.drainDelayed();

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
    void viewerDependentAnimationFramesRouteToPerViewerMetadata() {
        harness.registerFunction("who", player -> player == null ? "console" : player.getName());
        harness.configureAnimation("fast", List.of("|who|", "still |who|"));
        PersistentHologram hologram = hologram("h-frame-viewer",
            List.of(CharacterizationHarness.FAST_CLIP_LINE));

        hologram.update();
        harness.drainDelayed();

        assertEquals(1, harness.liveSpawned(world).size(),
            "viewer-dependent clip frames must share one blank server entity");
        assertEquals(2, harness.animator.targetCount());
        assertEquals(2, harness.animator.pass(0L));
        Set<String> rendered = new HashSet<>();
        for (CharacterizationHarness.Sent sent : harness.sender.sent) {
            rendered.add(sent.text());
        }
        assertEquals(Set.of("Alice", "Bob"), rendered);
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
    void staleScheduledAnchorCannotOverwriteANewerTeleport() {
        PersistentHologram hologram = hologram("h-anchor-snapshot", List.of("hi"));
        hologram.update();
        DisplayHandle display = harness.onlySpawned(world);
        PersistentHologram.TickAnchor stale = hologram.tickAnchor();

        hologram.teleport(harness.at(world, 8.0D, 70.0D, 8.0D));
        hologram.update(new HologramTick(), stale);
        assertTrue(display.teleports.isEmpty(),
            "a queued tick may not move an entity back to a superseded anchor");

        hologram.update();
        assertEquals(1, display.teleports.size());
        assertEquals(8.0D, display.teleports.getFirst().getX());
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
