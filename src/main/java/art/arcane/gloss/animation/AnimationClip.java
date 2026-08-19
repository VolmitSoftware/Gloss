package art.arcane.gloss.animation;

import java.util.List;

public record AnimationClip(String id, double targetFramerate, AnimationMode mode, List<String> frames) {
    public AnimationClip {
        targetFramerate = clampFramerate(targetFramerate);
        frames = List.copyOf(frames);
    }

    public String frameAt(long nowMs) {
        if (frames.isEmpty()) {
            return "";
        }

        return frames.get(frameIndexAt(nowMs));
    }

    public int frameIndexAt(long nowMs) {
        int count = frames.size();
        if (count <= 1) {
            return 0;
        }

        long tick = (long) (nowMs / (1000.0D / targetFramerate));
        return switch (mode) {
            case ASCEND -> Math.floorMod(tick, count);
            case DESCEND -> count - 1 - Math.floorMod(tick, count);
            case ASCEND_DESCEND -> pingPong(tick, count);
            case RANDOM -> scrambled(tick, count);
        };
    }

    private int pingPong(long tick, int count) {
        int cycle = Math.floorMod(tick, count * 2);
        return cycle < count ? cycle : (count * 2) - 1 - cycle;
    }

    private int scrambled(long tick, int count) {
        long mixed = tick + ((long) id.hashCode() << 32);
        mixed ^= mixed >>> 33;
        mixed *= 0xFF51AFD7ED558CCDL;
        mixed ^= mixed >>> 33;
        mixed *= 0xC4CEB9FE1A85EC53L;
        mixed ^= mixed >>> 33;
        return Math.floorMod(mixed, count);
    }

    private static double clampFramerate(double value) {
        if (Double.isNaN(value) || value <= 0.0D) {
            return 1.0D;
        }

        return Math.min(value, 1000.0D);
    }
}
