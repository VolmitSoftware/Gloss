package art.arcane.gloss.hologram;

import art.arcane.gloss.hologram.CharacterizationHarness.WorldState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A temporary hologram with a bound frame source must ride the animator's async packet loop, not
 * the temporary driver's tick interval. Chat bubble shimmer depends on it: the band steps 30 times
 * a second, which a two tick driver quantizes to 10 fps.
 */
class TemporaryHologramFramePathTest {
    @TempDir
    File dataFolder;

    private CharacterizationHarness harness;
    private WorldState world;

    @BeforeEach
    void setUp() {
        harness = new CharacterizationHarness(dataFolder);
        world = harness.world("overworld");
        harness.join("Alice", world, 1.0D, 64.0D, 1.0D);
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    private TemporaryHologramDisplay bound(String id) {
        TemporaryHologramDisplay temporary = harness.temporary(id, harness.at(world, 0.5D, 64.0D, 0.5D), 60_000L);
        temporary.setRenderedLines(List.of("§7base"));
        temporary.bindRenderedFrames(nowMs -> List.of("§7frame-" + nowMs / 33L, "§7second"));
        return temporary;
    }

    @Test
    void boundFramesPublishAnAnimatorTargetInsteadOfSettingEntityText() {
        TemporaryHologramDisplay temporary = bound("t-frames");
        temporary.drive(true);
        temporary.drive(true);

        assertEquals(1, harness.animator.targetCount(),
            "a bound frame source must own the text through the animator");
        assertEquals("§7base", harness.onlySpawned(world).lastText(),
            "the tick path only carries the spawn text; frames go out as packets");
    }

    @Test
    void animatorSendsANewFrameAsSoonAsTheSourceMovesRatherThanOncePerTick() {
        TemporaryHologramDisplay temporary = bound("t-rate");
        temporary.drive(true);
        temporary.drive(true);

        // Two passes 33 ms apart: well inside one server tick, and both must land.
        assertEquals(1, harness.animator.pass(0L));
        assertEquals(0, harness.animator.pass(16L));
        assertEquals(1, harness.animator.pass(33L));

        List<CharacterizationHarness.Sent> sent = harness.sender.sent;
        assertEquals(2, sent.size());
        assertEquals("§7frame-0\n§7second", sent.getFirst().text());
        assertEquals("§7frame-1\n§7second", sent.get(1).text());
    }

    @Test
    void boundFramesAreSerializedWithSectionCodesOnlyBecauseTheyCarryPlayerText() {
        TemporaryHologramDisplay temporary = bound("t-codec");
        temporary.drive(true);
        temporary.drive(true);
        harness.animator.pass(0L);

        assertEquals(TextCodec.LEGACY, harness.sender.sent.getFirst().codec());
    }

    @Test
    void withHighFrequencyAnimationsOffTheFramesFallBackToTheTickPath() {
        harness.configure(file -> file.holograms.highFrequencyAnimations = false);
        TemporaryHologramDisplay temporary = bound("t-fallback");
        temporary.drive(true);
        temporary.drive(true);

        assertEquals(0, harness.animator.targetCount());
        assertTrue(harness.onlySpawned(world).lastText().startsWith("§7frame-"),
            "the tick path must still compose frames when the packet loop is disabled");
    }
}
