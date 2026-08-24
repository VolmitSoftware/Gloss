package art.arcane.gloss.menu;

import art.arcane.gloss.Gloss;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class PreviewDiscoveryCadenceTest {

  private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-00000000cad0");

  private Gloss previousInstance;
  private MenuSessionManager manager;

  @Before
  public void installBarePlugin() throws ReflectiveOperationException {
    Gloss gloss = CharacterizationSupport.bareGloss(CharacterizationSupport.server(Map.of()));
    previousInstance = CharacterizationSupport.installGloss(gloss);
    manager = new MenuSessionManager();
  }

  @After
  public void restore() {
    CharacterizationSupport.restoreGloss(previousInstance);
  }

  @Test
  public void unchangedMissSkipsRaycastUntilMovementOrForcedFallback() {
    AtomicInteger blockTraces = new AtomicInteger();
    World world = world(blockTraces);
    Location eye = new Location(world, 4.0D, 65.62D, -2.0D, 12.5F, 5.0F);
    AtomicReference<Location> playerEye = new AtomicReference<>(eye);
    Player player = player(playerEye);

    manager.managePreviewEvents(player, false);
    manager.managePreviewEvents(player, false);
    assertEquals(1, blockTraces.get());

    Location turned = eye.clone();
    turned.setYaw(12.6F);
    playerEye.set(turned);
    manager.managePreviewEvents(player, false);
    assertEquals(2, blockTraces.get());

    manager.managePreviewEvents(player, true);
    assertEquals(3, blockTraces.get());
  }

  @Test
  public void fallbackSweepCapsOneThousandPlayersAtTenScansPerTick() {
    MenuSessionManager.PreviewFallbackSweep sweep = new MenuSessionManager.PreviewFallbackSweep();
    List<Player> players = new ArrayList<>(1000);
    Player player = player(new AtomicReference<>(
        new Location(world(new AtomicInteger()), 0.0D, 65.62D, 0.0D)));
    for (int index = 0; index < 1000; index++) {
      players.add(player);
    }

    sweep.begin(players);
    assertEquals(10, sweep.batchSize());

    AtomicInteger total = new AtomicInteger();
    for (int tick = 0; tick < MenuSessionManager.PREVIEW_FALLBACK_INTERVAL_TICKS; tick++) {
      assertEquals(10, sweep.drainBatch(ignored -> total.incrementAndGet()));
    }
    assertEquals(1000, total.get());
    assertEquals(0, sweep.drainBatch(ignored -> total.incrementAndGet()));
  }

  @Test
  public void emptyFallbackSweepSchedulesNothing() {
    MenuSessionManager.PreviewFallbackSweep sweep = new MenuSessionManager.PreviewFallbackSweep();

    sweep.begin(List.of());

    assertEquals(0, sweep.batchSize());
    assertEquals(0, sweep.drainBatch(ignored -> {
    }));
  }

  @Test
  public void discoveryQueueCapsAndDeduplicatesOneThousandConcurrentMovers() throws Exception {
    int playerCount = 1000;
    int producerCount = 8;
    MenuSessionManager.PreviewDiscoveryQueue queue = new MenuSessionManager.PreviewDiscoveryQueue();
    List<Player> players = new ArrayList<>(playerCount);
    World world = world(new AtomicInteger());
    for (int index = 0; index < playerCount; index++) {
      UUID playerId = new UUID(0L, index + 1L);
      players.add(player(playerId, new AtomicReference<>(
          new Location(world, index, 65.62D, 0.0D))));
    }

    ExecutorService executor = Executors.newFixedThreadPool(producerCount);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Void>> producers = new ArrayList<>(producerCount);
    try {
      for (int producerIndex = 0; producerIndex < producerCount; producerIndex++) {
        boolean forceRescan = producerIndex == producerCount - 1;
        Future<Void> producer = executor.submit(() -> {
          start.await();
          for (Player player : players) {
            queue.offer(player, forceRescan);
          }
          return null;
        });
        producers.add(producer);
      }
      start.countDown();
      for (Future<Void> producer : producers) {
        producer.get(10L, TimeUnit.SECONDS);
      }
    } finally {
      executor.shutdownNow();
    }

    assertEquals(playerCount, queue.size());
    Set<UUID> discovered = new HashSet<>(playerCount);
    int ticks = 0;
    while (queue.size() > 0) {
      int drained = queue.drain(MenuSessionManager.PREVIEW_DISCOVERY_LIMIT_PER_TICK, discovery -> {
        assertTrue(discovery.forceRescan());
        assertTrue(discovered.add(discovery.playerId()));
        queue.complete(discovery);
      });
      assertTrue(drained <= MenuSessionManager.PREVIEW_DISCOVERY_LIMIT_PER_TICK);
      ticks++;
    }

    assertEquals(playerCount, discovered.size());
    assertEquals(100, ticks);
  }

  @Test
  public void repeatedMoverReturnsAtTailWithoutDisplacingWaitingPlayers() {
    MenuSessionManager.PreviewDiscoveryQueue queue = new MenuSessionManager.PreviewDiscoveryQueue();
    World world = world(new AtomicInteger());
    Player first = player(new UUID(0L, 1L), eye(world));
    Player second = player(new UUID(0L, 2L), eye(world));
    Player third = player(new UUID(0L, 3L), eye(world));
    queue.offer(first, false);
    queue.offer(second, false);
    queue.offer(third, false);

    List<UUID> order = new ArrayList<>();
    List<Boolean> forced = new ArrayList<>();
    assertEquals(1, queue.drain(1, discovery -> {
      order.add(discovery.playerId());
      forced.add(discovery.forceRescan());
      assertFalse(queue.offer(first, true));
      queue.complete(discovery);
    }));
    assertEquals(3, queue.drain(3, discovery -> {
      order.add(discovery.playerId());
      forced.add(discovery.forceRescan());
      queue.complete(discovery);
    }));

    assertEquals(List.of(first.getUniqueId(), second.getUniqueId(), third.getUniqueId(), first.getUniqueId()), order);
    assertEquals(List.of(false, false, false, true), forced);
  }

  @Test
  public void staleCompletionCannotRemoveRejoinedPlayerDiscovery() {
    MenuSessionManager.PreviewDiscoveryQueue queue = new MenuSessionManager.PreviewDiscoveryQueue();
    UUID playerId = new UUID(0L, 1L);
    World world = world(new AtomicInteger());
    Player departed = player(playerId, eye(world));
    Player rejoined = player(playerId, eye(world));
    AtomicReference<MenuSessionManager.PreviewDiscovery> departedDiscovery = new AtomicReference<>();
    queue.offer(departed, false);
    assertEquals(1, queue.drain(1, departedDiscovery::set));

    assertTrue(queue.remove(playerId));
    assertTrue(queue.offer(rejoined, false));
    queue.complete(departedDiscovery.get());
    assertEquals(1, queue.size());

    assertEquals(1, queue.drain(1, discovery -> {
      assertSame(rejoined, discovery.player());
      queue.complete(discovery);
    }));
    assertEquals(0, queue.size());

    queue.offer(rejoined, false);
    queue.clear();
    assertEquals(0, queue.size());
    assertEquals(0, queue.drain(MenuSessionManager.PREVIEW_DISCOVERY_LIMIT_PER_TICK, discovery -> {
    }));
  }

  @Test
  public void failedDiscoveryActionDiscardsOnlyItsClaim() {
    MenuSessionManager.PreviewDiscoveryQueue queue = new MenuSessionManager.PreviewDiscoveryQueue();
    World world = world(new AtomicInteger());
    Player failed = player(new UUID(0L, 1L), eye(world));
    Player waiting = player(new UUID(0L, 2L), eye(world));
    queue.offer(failed, false);
    queue.offer(waiting, false);

    IllegalStateException failure = assertThrows(IllegalStateException.class,
        () -> queue.drain(MenuSessionManager.PREVIEW_DISCOVERY_LIMIT_PER_TICK, discovery -> {
          throw new IllegalStateException("expected");
        }));

    assertEquals("expected", failure.getMessage());
    assertEquals(1, queue.size());
    assertEquals(1, queue.drain(MenuSessionManager.PREVIEW_DISCOVERY_LIMIT_PER_TICK, discovery -> {
      assertSame(waiting, discovery.player());
      queue.complete(discovery);
    }));
    assertEquals(0, queue.size());
  }

  private static World world(AtomicInteger blockTraces) {
    return (World) CharacterizationSupport.proxy(new Class<?>[]{World.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "rayTraceBlocks" -> {
            blockTraces.incrementAndGet();
            yield null;
          }
          default -> CharacterizationSupport.identity(proxy, method, args);
        });
  }

  private static Player player(AtomicReference<Location> eye) {
    return player(PLAYER_ID, eye);
  }

  private static AtomicReference<Location> eye(World world) {
    return new AtomicReference<>(new Location(world, 0.0D, 65.62D, 0.0D));
  }

  private static Player player(UUID playerId, AtomicReference<Location> eye) {
    return (Player) CharacterizationSupport.proxy(new Class<?>[]{Player.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "getUniqueId" -> playerId;
          case "getEyeLocation" -> eye.get().clone();
          case "isOnline" -> true;
          case "getName" -> "cadence";
          default -> CharacterizationSupport.identity(proxy, method, args);
        });
  }
}
