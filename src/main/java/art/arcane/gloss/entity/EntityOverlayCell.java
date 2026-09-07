package art.arcane.gloss.entity;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class EntityOverlayCell {
    private static final int CELL_SHIFT = 4;

    record Anchor(UUID viewerId, Player player, World world, double x, double y, double z, long sequence) {
        double distanceSquared(double otherX, double otherY, double otherZ) {
            double dx = x - otherX;
            double dy = y - otherY;
            double dz = z - otherZ;
            return dx * dx + dy * dy + dz * dz;
        }

        Location location() {
            return new Location(world, x, y, z);
        }

        BoundingBox box(double range) {
            return new BoundingBox(x - range, y - range, z - range, x + range, y + range, z + range);
        }
    }

    private record Key(UUID worldId, int cellX, int cellY, int cellZ) {
    }

    private final World world;
    private final List<Anchor> anchors = new ArrayList<>(2);
    private double minX = Double.MAX_VALUE;
    private double minY = Double.MAX_VALUE;
    private double minZ = Double.MAX_VALUE;
    private double maxX = -Double.MAX_VALUE;
    private double maxY = -Double.MAX_VALUE;
    private double maxZ = -Double.MAX_VALUE;

    private EntityOverlayCell(World world) {
        this.world = world;
    }

    static Collection<EntityOverlayCell> bucket(Collection<Anchor> anchors, long sequence, long graceDrives) {
        Map<Key, EntityOverlayCell> cells = new HashMap<>();
        for (Anchor anchor : anchors) {
            if (sequence - anchor.sequence() > graceDrives) {
                continue;
            }
            Key key = new Key(anchor.world().getUID(), cell(anchor.x()), cell(anchor.y()), cell(anchor.z()));
            cells.computeIfAbsent(key, ignored -> new EntityOverlayCell(anchor.world())).add(anchor);
        }
        return cells.values();
    }

    World world() {
        return world;
    }

    int size() {
        return anchors.size();
    }

    Anchor anchor(int index) {
        return anchors.get(index);
    }

    Location center() {
        return new Location(world, (minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2);
    }

    BoundingBox box(double range) {
        return new BoundingBox(minX - range, minY - range, minZ - range,
            maxX + range, maxY + range, maxZ + range);
    }

    private void add(Anchor anchor) {
        anchors.add(anchor);
        minX = Math.min(minX, anchor.x());
        minY = Math.min(minY, anchor.y());
        minZ = Math.min(minZ, anchor.z());
        maxX = Math.max(maxX, anchor.x());
        maxY = Math.max(maxY, anchor.y());
        maxZ = Math.max(maxZ, anchor.z());
    }

    static boolean owned(World world, BoundingBox box, boolean folia) {
        if (!folia) {
            return true;
        }
        int minimumX = chunkOf(box.getMinX());
        int maximumX = chunkOf(box.getMaxX());
        int minimumZ = chunkOf(box.getMinZ());
        int maximumZ = chunkOf(box.getMaxZ());
        for (int chunkX = minimumX; chunkX <= maximumX; chunkX++) {
            for (int chunkZ = minimumZ; chunkZ <= maximumZ; chunkZ++) {
                if (!FoliaScheduler.isOwnedByCurrentRegion(world, chunkX, chunkZ)) {
                    return false;
                }
            }
        }
        return true;
    }

    static BoundingBox ownedPortion(World world, BoundingBox box, double centerX, double centerZ, boolean folia) {
        if (!folia) {
            return box;
        }
        int chunkX = chunkOf(centerX);
        int chunkZ = chunkOf(centerZ);
        if (!FoliaScheduler.isOwnedByCurrentRegion(world, chunkX, chunkZ)) {
            return null;
        }
        BoundingBox clamped = clamp(box, expand(world, chunkX, chunkZ, chunkOf(box.getMinX()), -1, true),
            expand(world, chunkX, chunkZ, chunkOf(box.getMaxX()), 1, true),
            expand(world, chunkX, chunkZ, chunkOf(box.getMinZ()), -1, false),
            expand(world, chunkX, chunkZ, chunkOf(box.getMaxZ()), 1, false));
        return owned(world, clamped, true) ? clamped : clamp(box, chunkX, chunkX, chunkZ, chunkZ);
    }

    private static int expand(World world, int chunkX, int chunkZ, int limit, int step, boolean alongX) {
        int reached = alongX ? chunkX : chunkZ;
        while (reached != limit) {
            int next = reached + step;
            boolean isOwned = alongX
                ? FoliaScheduler.isOwnedByCurrentRegion(world, next, chunkZ)
                : FoliaScheduler.isOwnedByCurrentRegion(world, chunkX, next);
            if (!isOwned) {
                break;
            }
            reached = next;
        }
        return reached;
    }

    private static BoundingBox clamp(BoundingBox box, int minimumChunkX, int maximumChunkX,
                                     int minimumChunkZ, int maximumChunkZ) {
        return new BoundingBox(
            Math.max(box.getMinX(), minimumChunkX << 4), box.getMinY(),
            Math.max(box.getMinZ(), minimumChunkZ << 4),
            Math.min(box.getMaxX(), (maximumChunkX << 4) + 16), box.getMaxY(),
            Math.min(box.getMaxZ(), (maximumChunkZ << 4) + 16));
    }

    private static int chunkOf(double coordinate) {
        return (int) Math.floor(coordinate) >> 4;
    }

    private static int cell(double coordinate) {
        return (int) Math.floor(coordinate) >> CELL_SHIFT;
    }
}
