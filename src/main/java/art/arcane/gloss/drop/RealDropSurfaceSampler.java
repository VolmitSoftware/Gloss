package art.arcane.gloss.drop;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.BoundingBox;

final class RealDropSurfaceSampler {
    private static final int MAX_PROBE_BLOCKS = 32;
    private static final double POINT_EPSILON = 1.0E-7D;

    private RealDropSurfaceSampler() {
    }

    static double surfaceY(Location location) {
        World world = location.getWorld();
        int blockX = location.getBlockX();
        int blockZ = location.getBlockZ();
        int startY = location.getBlockY();
        int minimumY = Math.max(world.getMinHeight(), startY - MAX_PROBE_BLOCKS);
        double pointX = location.getX();
        double pointZ = location.getZ();
        double maximumY = location.getY() + POINT_EPSILON;
        for (int blockY = startY; blockY >= minimumY; blockY--) {
            Block block = world.getBlockAt(blockX, blockY, blockZ);
            double support = highestSupport(block, pointX, pointZ, maximumY);
            if (Double.isFinite(support)) {
                return support;
            }
        }
        return minimumY;
    }

    private static double highestSupport(Block block, double pointX, double pointZ, double maximumY) {
        double highest = Double.NEGATIVE_INFINITY;
        for (BoundingBox box : block.getCollisionShape().getBoundingBoxes()) {
            if (pointX + POINT_EPSILON < box.getMinX() || pointX - POINT_EPSILON > box.getMaxX()
                || pointZ + POINT_EPSILON < box.getMinZ() || pointZ - POINT_EPSILON > box.getMaxZ()) {
                continue;
            }
            double top = box.getMaxY();
            if (top <= maximumY && top > highest) {
                highest = top;
            }
        }
        return highest;
    }
}
