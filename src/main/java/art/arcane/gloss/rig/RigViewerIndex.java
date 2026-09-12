package art.arcane.gloss.rig;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RigViewerIndex {
    private static final double CHUNK_SIZE = 16.0D;

    public record Viewer(Player player, UUID id, UUID worldId, double x, double y, double z) {
        public double distanceSquared(Location anchor) {
            double dx = x - anchor.getX();
            double dy = y - anchor.getY();
            double dz = z - anchor.getZ();
            return dx * dx + dy * dy + dz * dz;
        }
    }

    private final Map<UUID, Viewer> viewers = new ConcurrentHashMap<>();
    private final Map<UUID, Map<Long, Set<UUID>>> chunks = new ConcurrentHashMap<>();

    public boolean update(Player player, Location location) {
        World world = location.getWorld();
        UUID playerId = player.getUniqueId();
        if (world == null) {
            remove(playerId);
            return true;
        }

        Viewer observed = viewers.get(playerId);
        if (observed != null && observed.player() == player
            && observed.worldId().equals(world.getUID())
            && observed.x() == location.getX() && observed.y() == location.getY()
            && observed.z() == location.getZ()) {
            return false;
        }

        Viewer next = new Viewer(player, playerId, world.getUID(), location.getX(), location.getY(), location.getZ());
        long nextChunk = chunkKey(next.x(), next.z());
        boolean transitioned = observed == null || !observed.worldId().equals(next.worldId())
            || chunkKey(observed.x(), observed.z()) != nextChunk;
        viewers.compute(playerId, (ignored, previous) -> {
            if (previous == null) {
                addToChunk(next.worldId(), nextChunk, playerId);
                return next;
            }
            if (previous.worldId().equals(next.worldId())
                && chunkKey(previous.x(), previous.z()) == nextChunk) {
                return next;
            }
            removeFromChunk(previous.worldId(), chunkKey(previous.x(), previous.z()), playerId);
            addToChunk(next.worldId(), nextChunk, playerId);
            return next;
        });
        return transitioned;
    }

    public void remove(UUID playerId) {
        viewers.computeIfPresent(playerId, (ignored, previous) -> {
            removeFromChunk(previous.worldId(), chunkKey(previous.x(), previous.z()), playerId);
            return null;
        });
    }

    public Viewer viewer(UUID playerId) {
        return viewers.get(playerId);
    }

    public int size() {
        return viewers.size();
    }

    public List<Viewer> nearby(Location anchor, double range) {
        return nearby(anchor, range, false);
    }

    public List<Viewer> withinBox(Location anchor, double range) {
        return nearby(anchor, range, true);
    }

    public boolean anyNearby(Location anchor, double range) {
        return !nearby(anchor, range, false).isEmpty();
    }

    public void clear() {
        viewers.clear();
        chunks.clear();
    }

    private List<Viewer> nearby(Location anchor, double range, boolean box) {
        World world = anchor.getWorld();
        if (world == null || range < 0.0D || !Double.isFinite(range)) {
            return List.of();
        }
        Map<Long, Set<UUID>> worldChunks = chunks.get(world.getUID());
        if (worldChunks == null || worldChunks.isEmpty()) {
            return List.of();
        }

        int minimumChunkX = chunkCoordinate(anchor.getX() - range);
        int maximumChunkX = chunkCoordinate(anchor.getX() + range);
        int minimumChunkZ = chunkCoordinate(anchor.getZ() - range);
        int maximumChunkZ = chunkCoordinate(anchor.getZ() + range);
        List<Viewer> matches = new ArrayList<>();
        UUID worldId = world.getUID();
        double chunkCount = ((long) maximumChunkX - minimumChunkX + 1D)
            * ((long) maximumChunkZ - minimumChunkZ + 1D);
        if (chunkCount > worldChunks.size()) {
            for (Set<UUID> bucket : worldChunks.values()) {
                appendMatches(matches, bucket, worldId, anchor, range, box);
            }
            return List.copyOf(matches);
        }
        for (long chunkX = minimumChunkX; chunkX <= maximumChunkX; chunkX++) {
            for (long chunkZ = minimumChunkZ; chunkZ <= maximumChunkZ; chunkZ++) {
                Set<UUID> bucket = worldChunks.get(chunkKey((int) chunkX, (int) chunkZ));
                if (bucket == null) {
                    continue;
                }
                appendMatches(matches, bucket, worldId, anchor, range, box);
            }
        }
        return List.copyOf(matches);
    }

    private void appendMatches(List<Viewer> matches, Set<UUID> bucket, UUID worldId,
                               Location anchor, double range, boolean box) {
        double rangeSquared = range * range;
        for (UUID playerId : bucket) {
            Viewer viewer = viewers.get(playerId);
            if (viewer == null || !worldId.equals(viewer.worldId())) {
                continue;
            }
            double dx = viewer.x() - anchor.getX();
            double dy = viewer.y() - anchor.getY();
            double dz = viewer.z() - anchor.getZ();
            boolean inside = box
                ? Math.abs(dx) <= range && Math.abs(dy) <= range && Math.abs(dz) <= range
                : dx * dx + dy * dy + dz * dz <= rangeSquared;
            if (inside) {
                matches.add(viewer);
            }
        }
    }

    private void addToChunk(UUID worldId, long chunkKey, UUID playerId) {
        Map<Long, Set<UUID>> worldChunks = chunks.computeIfAbsent(worldId, ignored -> new ConcurrentHashMap<>());
        worldChunks.compute(chunkKey, (ignored, bucket) -> {
            Set<UUID> active = bucket == null ? ConcurrentHashMap.newKeySet() : bucket;
            active.add(playerId);
            return active;
        });
    }

    private void removeFromChunk(UUID worldId, long chunkKey, UUID playerId) {
        Map<Long, Set<UUID>> worldChunks = chunks.get(worldId);
        if (worldChunks == null) {
            return;
        }
        worldChunks.computeIfPresent(chunkKey, (ignored, bucket) -> {
            bucket.remove(playerId);
            return bucket.isEmpty() ? null : bucket;
        });
    }

    private static long chunkKey(double x, double z) {
        return chunkKey(chunkCoordinate(x), chunkCoordinate(z));
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    private static int chunkCoordinate(double coordinate) {
        if (coordinate <= (double) Integer.MIN_VALUE * CHUNK_SIZE) {
            return Integer.MIN_VALUE;
        }
        if (coordinate >= (double) Integer.MAX_VALUE * CHUNK_SIZE) {
            return Integer.MAX_VALUE;
        }
        return (int) Math.floor(coordinate / CHUNK_SIZE);
    }
}
