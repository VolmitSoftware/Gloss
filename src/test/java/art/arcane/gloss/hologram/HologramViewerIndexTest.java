package art.arcane.gloss.hologram;

import art.arcane.gloss.menu.CharacterizationSupport;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HologramViewerIndexTest {
    @Test
    void movementWorldChangesAndRemovalUpdateSpatialMembership() {
        UUID firstWorldId = UUID.randomUUID();
        UUID secondWorldId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        World firstWorld = world(firstWorldId);
        World secondWorld = world(secondWorldId);
        Player player = player(playerId);
        HologramViewerIndex index = new HologramViewerIndex();

        assertTrue(index.update(player, new Location(firstWorld, 1.0D, 64.0D, 1.0D)));
        assertTrue(!index.update(player, new Location(firstWorld, 2.0D, 64.0D, 2.0D)),
            "movement inside one chunk must not report a tracking transition");
        assertEquals(List.of(playerId), ids(index.nearby(new Location(firstWorld, 0.0D, 64.0D, 0.0D), 8.0D)));

        assertTrue(index.update(player, new Location(firstWorld, 40.0D, 64.0D, 0.0D)));
        assertTrue(index.nearby(new Location(firstWorld, 0.0D, 64.0D, 0.0D), 8.0D).isEmpty());
        assertEquals(List.of(playerId), ids(index.nearby(new Location(firstWorld, 40.0D, 64.0D, 0.0D), 8.0D)));

        assertTrue(index.update(player, new Location(secondWorld, -17.0D, 64.0D, -17.0D)));
        assertTrue(index.nearby(new Location(firstWorld, 40.0D, 64.0D, 0.0D), 8.0D).isEmpty());
        assertEquals(List.of(playerId), ids(index.nearby(new Location(secondWorld, -17.0D, 64.0D, -17.0D), 1.0D)));

        index.remove(playerId);
        assertTrue(index.nearby(new Location(secondWorld, -17.0D, 64.0D, -17.0D), 8.0D).isEmpty());
    }

    @Test
    void clearRemovesEveryViewer() {
        World world = world(UUID.randomUUID());
        HologramViewerIndex index = new HologramViewerIndex();
        index.update(player(UUID.randomUUID()), new Location(world, 0.0D, 64.0D, 0.0D));
        index.update(player(UUID.randomUUID()), new Location(world, 16.0D, 64.0D, 0.0D));

        index.clear();

        assertTrue(index.nearby(new Location(world, 0.0D, 64.0D, 0.0D), 64.0D).isEmpty());
    }

    @Test
    void concurrentRemovalCannotDetachANewViewerBucket() throws InterruptedException {
        World world = world(UUID.randomUUID());
        Player retiring = player(UUID.randomUUID());
        Player arriving = player(UUID.randomUUID());
        Location anchor = new Location(world, 1.0D, 64.0D, 1.0D);
        HologramViewerIndex index = new HologramViewerIndex();
        index.update(retiring, anchor);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            workers.execute(() -> {
                await(start);
                for (int iteration = 0; iteration < 100_000; iteration++) {
                    index.update(retiring, anchor);
                    index.remove(retiring.getUniqueId());
                }
            });
            workers.execute(() -> {
                await(start);
                for (int iteration = 0; iteration < 100_000; iteration++) {
                    index.update(arriving, anchor);
                }
            });
            start.countDown();
            workers.shutdown();
            assertTrue(workers.awaitTermination(10L, TimeUnit.SECONDS));
        } finally {
            workers.shutdownNow();
        }

        index.update(arriving, anchor);
        assertEquals(List.of(arriving.getUniqueId()), ids(index.nearby(anchor, 1.0D)));
    }

    @Test
    void reconnectKeepsOneFairReconciliationEntry() {
        World world = world(UUID.randomUUID());
        Player player = player(UUID.randomUUID());
        Location location = new Location(world, 1.0D, 64.0D, 1.0D);
        HologramViewerIndex index = new HologramViewerIndex();

        for (int reconnect = 0; reconnect < 100; reconnect++) {
            index.update(player, location);
            index.remove(player.getUniqueId());
        }
        index.update(player, location);

        assertEquals(1, index.reconciliationQueueSize());
        AtomicInteger reconciled = new AtomicInteger();
        index.reconcileBatch(32, ignored -> reconciled.incrementAndGet());
        assertEquals(1, reconciled.get(), "one active viewer must be reconciled at most once per sweep");
        assertEquals(1, index.reconciliationQueueSize());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static List<UUID> ids(List<HologramTick.Viewer> viewers) {
        return viewers.stream().map(HologramTick.Viewer::id).toList();
    }

    private static World world(UUID worldId) {
        return (World) CharacterizationSupport.proxy(new Class<?>[]{World.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getUID" -> worldId;
                default -> CharacterizationSupport.identity(proxy, method, args);
            });
    }

    private static Player player(UUID playerId) {
        return (Player) CharacterizationSupport.proxy(new Class<?>[]{Player.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> playerId;
                default -> CharacterizationSupport.identity(proxy, method, args);
            });
    }
}
