package art.arcane.gloss.bubble;

import art.arcane.gloss.api.HologramPresentation;
import art.arcane.gloss.api.HologramViewers;
import art.arcane.gloss.api.TemporaryHologram;
import org.bukkit.Location;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ChatBubblesShimmerRuntimeTest {
    @Test
    void servicePublishesSpawnAndFlyAwayFramesToTheLiveTemporaryHologram() {
        CapturingTemporaryHologram hologram = new CapturingTemporaryHologram();
        BubbleStyleDoc.Shimmer shimmer = new BubbleStyleDoc.Shimmer(
            true, true, "#ffffff", 1, 1000L, 0L, 1000L);
        ChatBubblesService.BubbleRecord record = new ChatBubblesService.BubbleRecord(
            hologram, null, new Vector(), BubbleMotionPlan.compile(BubbleStyleDoc.DEFAULTS.motion()),
            BubbleShimmerPlan.compile(shimmer), false, 1000L, 5000L, 6000L, 1, 0.5D,
            "&7", "message", 32, List.of("§7Gloss"));

        ChatBubblesService.applyBubbleText(record, 1000L);
        List<String> spawn = hologram.lines();
        ChatBubblesService.applyBubbleText(record, 3500L);
        List<String> idle = hologram.lines();
        ChatBubblesService.applyBubbleText(record, 5500L);
        List<String> departure = hologram.lines();

        assertNotEquals(List.of("§7Gloss"), spawn);
        assertEquals(List.of("§7Gloss"), idle);
        assertNotEquals(idle, departure);
        assertEquals(3, hologram.publishCount);
    }

    private static final class CapturingTemporaryHologram implements TemporaryHologram {
        private List<String> lines = List.of();
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
        public void removeLine(int index) {
            lines = List.of();
        }

        @Override
        public void clearLines() {
            lines = List.of();
        }

        @Override
        public void bindPosition(Supplier<Location> binder) {
        }

        @Override
        public void bindPresentation(Supplier<HologramPresentation> binder) {
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
