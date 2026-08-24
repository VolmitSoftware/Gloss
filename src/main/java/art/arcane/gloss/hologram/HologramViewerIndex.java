package art.arcane.gloss.hologram;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

final class HologramViewerIndex {
    private static final double CHUNK_SIZE = 16.0D;

    private final Map<UUID, HologramTick.Viewer> viewers = new ConcurrentHashMap<>();
    private final Map<UUID, Map<Long, Set<UUID>>> chunks = new ConcurrentHashMap<>();
    private final Queue<UUID> reconciliationOrder = new ConcurrentLinkedQueue<>();
    private final Set<UUID> reconciliationActive = ConcurrentHashMap.newKeySet();
    private final Set<UUID> reconciliationQueued = ConcurrentHashMap.newKeySet();
    private final Object reconciliationLock = new Object();

    boolean update(Player player, Location location) {
        World world = location.getWorld();
        UUID playerId = player.getUniqueId();
        if (world == null) {
            remove(playerId);
            return true;
        }

        HologramTick.Viewer next = new HologramTick.Viewer(
            player, playerId, world.getUID(), location.getX(), location.getY(), location.getZ());
        HologramTick.Viewer observed = viewers.get(playerId);
        long nextChunk = chunkKey(next.x(), next.z());
        boolean transitioned = observed == null || !observed.worldId().equals(next.worldId())
            || chunkKey(observed.x(), observed.z()) != nextChunk;
        viewers.compute(playerId, (ignored, previous) -> {
            if (previous == null) {
                activateReconciliation(playerId);
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

    void remove(UUID playerId) {
        viewers.computeIfPresent(playerId, (ignored, previous) -> {
            removeFromChunk(previous.worldId(), chunkKey(previous.x(), previous.z()), playerId);
            deactivateReconciliation(playerId);
            return null;
        });
    }

    List<HologramTick.Viewer> nearby(Location anchor, double range) {
        World world = anchor.getWorld();
        if (world == null || range < 0.0D || !Double.isFinite(range)) {
            return List.of();
        }
        Map<Long, Set<UUID>> worldChunks = chunks.get(world.getUID());
        if (worldChunks == null || worldChunks.isEmpty()) {
            return List.of();
        }

        double rangeSquared = range * range;
        int minimumChunkX = chunkCoordinate(anchor.getX() - range);
        int maximumChunkX = chunkCoordinate(anchor.getX() + range);
        int minimumChunkZ = chunkCoordinate(anchor.getZ() - range);
        int maximumChunkZ = chunkCoordinate(anchor.getZ() + range);
        List<HologramTick.Viewer> matches = new ArrayList<>();
        UUID worldId = world.getUID();
        for (long chunkX = minimumChunkX; chunkX <= maximumChunkX; chunkX++) {
            for (long chunkZ = minimumChunkZ; chunkZ <= maximumChunkZ; chunkZ++) {
                Set<UUID> bucket = worldChunks.get(chunkKey((int) chunkX, (int) chunkZ));
                if (bucket == null) {
                    continue;
                }
                for (UUID playerId : bucket) {
                    HologramTick.Viewer viewer = viewers.get(playerId);
                    if (viewer == null || !worldId.equals(viewer.worldId())) {
                        continue;
                    }
                    double dx = viewer.x() - anchor.getX();
                    double dy = viewer.y() - anchor.getY();
                    double dz = viewer.z() - anchor.getZ();
                    if (dx * dx + dy * dy + dz * dz <= rangeSquared) {
                        matches.add(viewer);
                    }
                }
            }
        }
        return List.copyOf(matches);
    }

    boolean anyNearby(Location anchor, double range) {
        World world = anchor.getWorld();
        if (world == null || range < 0.0D || !Double.isFinite(range)) {
            return false;
        }
        Map<Long, Set<UUID>> worldChunks = chunks.get(world.getUID());
        if (worldChunks == null || worldChunks.isEmpty()) {
            return false;
        }

        double rangeSquared = range * range;
        int minimumChunkX = chunkCoordinate(anchor.getX() - range);
        int maximumChunkX = chunkCoordinate(anchor.getX() + range);
        int minimumChunkZ = chunkCoordinate(anchor.getZ() - range);
        int maximumChunkZ = chunkCoordinate(anchor.getZ() + range);
        UUID worldId = world.getUID();
        for (long chunkX = minimumChunkX; chunkX <= maximumChunkX; chunkX++) {
            for (long chunkZ = minimumChunkZ; chunkZ <= maximumChunkZ; chunkZ++) {
                Set<UUID> bucket = worldChunks.get(chunkKey((int) chunkX, (int) chunkZ));
                if (bucket == null) {
                    continue;
                }
                for (UUID playerId : bucket) {
                    HologramTick.Viewer viewer = viewers.get(playerId);
                    if (viewer == null || !worldId.equals(viewer.worldId())) {
                        continue;
                    }
                    double dx = viewer.x() - anchor.getX();
                    double dy = viewer.y() - anchor.getY();
                    double dz = viewer.z() - anchor.getZ();
                    if (dx * dx + dy * dy + dz * dz <= rangeSquared) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    void clear() {
        viewers.clear();
        chunks.clear();
        reconciliationOrder.clear();
        reconciliationActive.clear();
        reconciliationQueued.clear();
    }

    void reconcileBatch(int maximum, Consumer<Player> action) {
        int batchSize;
        synchronized (reconciliationLock) {
            batchSize = Math.min(Math.max(0, maximum), reconciliationOrder.size());
        }
        for (int index = 0; index < batchSize; index++) {
            UUID playerId;
            synchronized (reconciliationLock) {
                playerId = reconciliationOrder.poll();
                if (playerId != null) {
                    reconciliationQueued.remove(playerId);
                }
            }
            if (playerId == null) {
                return;
            }
            HologramTick.Viewer viewer = viewers.get(playerId);
            if (viewer == null) {
                continue;
            }
            action.accept(viewer.player());
            synchronized (reconciliationLock) {
                if (reconciliationActive.contains(playerId)
                    && reconciliationQueued.add(playerId)) {
                    reconciliationOrder.add(playerId);
                }
            }
        }
    }

    int reconciliationQueueSize() {
        synchronized (reconciliationLock) {
            return reconciliationOrder.size();
        }
    }

    private void activateReconciliation(UUID playerId) {
        synchronized (reconciliationLock) {
            reconciliationActive.add(playerId);
            reconciliationOrder.removeIf(playerId::equals);
            reconciliationQueued.remove(playerId);
            reconciliationQueued.add(playerId);
            reconciliationOrder.add(playerId);
        }
    }

    private void deactivateReconciliation(UUID playerId) {
        synchronized (reconciliationLock) {
            reconciliationActive.remove(playerId);
            reconciliationQueued.remove(playerId);
            reconciliationOrder.removeIf(playerId::equals);
        }
    }

    private void addToChunk(UUID worldId, long chunkKey, UUID playerId) {
        Map<Long, Set<UUID>> worldChunks = chunks.computeIfAbsent(worldId,
            ignored -> new ConcurrentHashMap<>());
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
