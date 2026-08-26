package art.arcane.gloss.bubble;

import art.arcane.gloss.api.HologramPresentation;
import art.arcane.gloss.api.HologramViewers;
import art.arcane.gloss.api.ParticleLayer;
import art.arcane.gloss.api.ParticleTextSpan;
import art.arcane.gloss.api.TemporaryHologram;
import art.arcane.gloss.particle.ParticleText;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.function.LongFunction;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatBubblesShimmerRuntimeTest {
    private static final long BORN_AT_MS = 1000L;
    private static final long LIFETIME_MS = 5000L;

    private static ChatBubblesService.BubbleRecord record(CapturingTemporaryHologram hologram,
                                                          BubbleStyleDoc.Shimmer shimmer) {
        return new ChatBubblesService.BubbleRecord(hologram, null, new Vector(),
            BubbleMotionPlan.compile(BubbleStyleDoc.DEFAULTS.motion()), BubbleShimmerPlan.compile(shimmer),
            false, BORN_AT_MS, LIFETIME_MS, BORN_AT_MS + LIFETIME_MS, 1, 0.5D, "&7", "message", 32,
            List.of("§7abcdefgh"), new ParticleText.Rendered("§7abcdefgh", List.of()));
    }

    @Test
    void bubbleFramesWaitThenRunBothBoundedSweeps() {
        ChatBubblesService.BubbleRecord record = record(new CapturingTemporaryHologram(),
            BubbleStyleDoc.DEFAULTS.shimmer());

        assertEquals(List.of("§7abcdefgh"), record.frameAt(BORN_AT_MS));
        assertEquals(List.of("§7abcdefgh"), record.frameAt(BORN_AT_MS + 399L));
        assertTrue(record.frameAt(BORN_AT_MS + 400L).getFirst()
            .startsWith("§7§x§f§f§f§f§f§fa§7§x§f§f§f§f§f§fb"));
        assertEquals(List.of("§7abcdefgh"), record.frameAt(BORN_AT_MS + 2000L));
        assertTrue(record.frameAt(BORN_AT_MS + 4300L).getFirst()
            .startsWith("§7§x§f§f§f§f§f§fa§7§x§f§f§f§f§f§fb"));
    }

    @Test
    void framesAreMemoizedPerBandStepSoTheAnimatorLoopDoesNotRebuildEveryPass() {
        ChatBubblesService.BubbleRecord record = record(new CapturingTemporaryHologram(),
            BubbleStyleDoc.DEFAULTS.shimmer());

        List<String> first = record.frameAt(BORN_AT_MS + 400L);
        assertSame(first, record.frameAt(BORN_AT_MS + 408L));
        assertNotEquals(first, record.frameAt(BORN_AT_MS + 500L));
    }

    @Test
    void serviceHandsTheHologramAFrameBinderInsteadOfPushingTextOnTheTickPath() {
        CapturingTemporaryHologram hologram = new CapturingTemporaryHologram();
        ChatBubblesService.BubbleRecord record = record(hologram, BubbleStyleDoc.DEFAULTS.shimmer());

        ChatBubblesService.publishInitialText(record, BORN_AT_MS);

        assertEquals(1, hologram.publishCount, "spawn publishes the delayed base frame exactly once");
        assertEquals(List.of("§7abcdefgh"), hologram.lines);
        assertNotNull(hologram.frames, "the shimmer must be driven by the bound frame source");
        assertEquals(record.frameAt(BORN_AT_MS + 500L), hologram.frames.apply(BORN_AT_MS + 500L));
    }

    private static final class CapturingTemporaryHologram implements TemporaryHologram {
        private List<String> lines = List.of();
        private LongFunction<List<String>> frames;
        private int publishCount;

        @Override
        public String id() {
            return "test";
        }

        @Override
        public Location location() {
            return null;
        }

        @Override
        public void teleport(Location location) {
        }

        @Override
        public List<String> lines() {
            return lines;
        }

        @Override
        public void addLine(String line) {
            lines = List.of(line);
        }

        @Override
        public void setLine(int index, String line) {
            lines = List.of(line);
        }

        @Override
        public void setLines(List<String> lines) {
            this.lines = List.copyOf(lines);
        }

        @Override
        public void setRenderedLines(List<String> lines) {
            this.lines = List.copyOf(lines);
            publishCount++;
        }

        @Override
        public void setRenderedParticleText(String text, List<ParticleTextSpan> spans) {
        }

        @Override
        public void bindRenderedFrames(LongFunction<List<String>> frames) {
            this.frames = frames;
        }

        @Override
        public void removeLine(int index) {
            lines = List.of();
        }

        @Override
        public void clearLines() {
            lines = List.of();
        }

        @Override
        public List<ParticleLayer> particleLayers() {
            return List.of();
        }

        @Override
        public void setParticleLayers(List<ParticleLayer> particleLayers) {
        }

        @Override
        public void bindPosition(Entity owner, Supplier<Location> binder) {
        }

        @Override
        public void bindPresentation(Entity owner, Supplier<HologramPresentation> binder) {
        }

        @Override
        public long remainingMs() {
            return 0L;
        }

        @Override
        public HologramViewers viewers() {
            return new HologramViewers() {
                @Override
                public void blacklist() {
                }

                @Override
                public void whitelist() {
                }

                @Override
                public void add(UUID playerId) {
                }

                @Override
                public void remove(UUID playerId) {
                }

                @Override
                public void clear() {
                }
            };
        }

        @Override
        public void destroy() {
        }
    }
}
