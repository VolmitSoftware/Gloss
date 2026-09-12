package art.arcane.gloss.camera;

import java.util.List;
import java.util.Objects;

/**
 * A Catmull-Rom path through camera nodes. Position follows the curve so a ride reads as one
 * sweep rather than a set of straight hops; yaw and pitch interpolate linearly, with yaw taking
 * the shorter arc so a turn from 350 to 10 degrees goes through zero instead of all the way round.
 */
public final class Spline {
    /**
     * @param durationTicks ticks spent travelling from this node to the next; ignored on the last
     */
    public record Node(double x, double y, double z, float yaw, float pitch, int durationTicks) {
        public Node {
            durationTicks = Math.max(0, durationTicks);
        }
    }

    public record Pose(double x, double y, double z, float yaw, float pitch) {
    }

    private final List<Node> nodes;
    private final long[] starts;
    private final long totalTicks;

    public Spline(List<Node> nodes) {
        Objects.requireNonNull(nodes, "nodes");
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("a camera path needs at least one node");
        }
        this.nodes = List.copyOf(nodes);
        this.starts = new long[this.nodes.size()];
        long cursor = 0L;
        for (int index = 0; index < this.nodes.size(); index++) {
            starts[index] = cursor;
            if (index < this.nodes.size() - 1) {
                cursor += Math.max(1, this.nodes.get(index).durationTicks());
            }
        }
        this.totalTicks = cursor;
    }

    public List<Node> nodes() {
        return nodes;
    }

    public long totalTicks() {
        return totalTicks;
    }

    public Pose at(long tick) {
        if (nodes.size() == 1 || tick <= 0L) {
            Node first = nodes.getFirst();
            return new Pose(first.x(), first.y(), first.z(), first.yaw(), first.pitch());
        }
        if (tick >= totalTicks) {
            Node last = nodes.getLast();
            return new Pose(last.x(), last.y(), last.z(), last.yaw(), last.pitch());
        }
        int segment = segmentAt(tick);
        long span = Math.max(1, nodes.get(segment).durationTicks());
        double progress = (double) (tick - starts[segment]) / span;
        Node before = nodes.get(Math.max(0, segment - 1));
        Node from = nodes.get(segment);
        Node to = nodes.get(segment + 1);
        Node after = nodes.get(Math.min(nodes.size() - 1, segment + 2));
        return new Pose(
            catmullRom(before.x(), from.x(), to.x(), after.x(), progress),
            catmullRom(before.y(), from.y(), to.y(), after.y(), progress),
            catmullRom(before.z(), from.z(), to.z(), after.z(), progress),
            interpolateYaw(from.yaw(), to.yaw(), progress),
            (float) (from.pitch() + (to.pitch() - from.pitch()) * progress));
    }

    /** The shorter of the two ways round the compass, so a wrap past north never spins the camera. */
    public static float interpolateYaw(float from, float to, double progress) {
        float delta = (to - from) % 360.0F;
        if (delta > 180.0F) {
            delta -= 360.0F;
        }
        if (delta < -180.0F) {
            delta += 360.0F;
        }
        return (float) (from + delta * progress);
    }

    private int segmentAt(long tick) {
        for (int index = nodes.size() - 2; index >= 0; index--) {
            if (tick >= starts[index]) {
                return index;
            }
        }
        return 0;
    }

    private static double catmullRom(double before, double from, double to, double after, double t) {
        double t2 = t * t;
        double t3 = t2 * t;
        return 0.5D * ((2.0D * from)
            + (-before + to) * t
            + (2.0D * before - 5.0D * from + 4.0D * to - after) * t2
            + (-before + 3.0D * from - 3.0D * to + after) * t3);
    }
}
