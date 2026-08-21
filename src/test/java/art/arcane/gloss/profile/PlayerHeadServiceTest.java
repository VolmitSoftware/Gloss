package art.arcane.gloss.profile;

import org.junit.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The cache contract, exercised against a fake resolver and a hand-wound clock. Nothing here
 * touches the network, a server, or {@link System#nanoTime()}.
 */
public class PlayerHeadServiceTest {

  private static final Duration HIT = Duration.ofMinutes(360L);
  private static final Duration MISS = Duration.ofMinutes(10L);

  private static PlayerHeadProfile profile(String name) {
    return new PlayerHeadProfile(UUID.nameUUIDFromBytes(name.getBytes()), name,
        "https://textures.minecraft.net/texture/" + name);
  }

  // ---------------------------------------------------------------------
  // Names that can never resolve
  // ---------------------------------------------------------------------

  @Test
  public void anUnresolvedPlaceholderIsUnknownWithoutCostingARequest() {
    FakeResolver resolver = new FakeResolver();
    PlayerHeadService service = service(resolver, new AtomicLong());

    for (String unusable : List.of("%player_name%", "{{ player.name }}", "", "   ",
        "a-name-far-too-long-for-mojang", "not.a.name", "Ünicode")) {
      assertEquals(unusable, PlayerHeadLookup.State.UNKNOWN, service.lookup(unusable).state());
    }
    assertNull(service.lookup(null).profile());
    assertEquals(0, resolver.calls.size());
    assertEquals(0, service.cachedCount());
  }

  @Test
  public void theNameRuleIsTheOneMojangIssues() {
    assertTrue(PlayerHeadService.isResolvableName("Notch"));
    assertTrue(PlayerHeadService.isResolvableName("a"));
    assertTrue(PlayerHeadService.isResolvableName("_Under_Score_1"));
    assertTrue(PlayerHeadService.isResolvableName("0123456789abcdef"));
    assertFalse(PlayerHeadService.isResolvableName("0123456789abcdefg"));
    assertFalse(PlayerHeadService.isResolvableName("has space"));
    assertFalse(PlayerHeadService.isResolvableName("has-dash"));
    assertFalse(PlayerHeadService.isResolvableName(null));
  }

  // ---------------------------------------------------------------------
  // Pending, resolved, unknown
  // ---------------------------------------------------------------------

  @Test
  public void theFirstLookupIsPendingAndTheAnswerArrivesWithoutAnotherRequest() {
    FakeResolver resolver = new FakeResolver();
    PlayerHeadService service = service(resolver, new AtomicLong());

    assertTrue(service.lookup("Notch").isPending());
    assertEquals(1, resolver.calls.size());

    resolver.complete("Notch", Optional.of(profile("Notch")));

    PlayerHeadLookup resolved = service.lookup("Notch");
    assertTrue(resolved.isResolved());
    assertEquals("Notch", resolved.profile().name());
    assertEquals(1, resolver.calls.size());
  }

  @Test
  public void aNameMojangDoesNotKnowIsUnknownRatherThanAFailure() {
    FakeResolver resolver = new FakeResolver();
    PlayerHeadService service = service(resolver, new AtomicLong());

    service.lookup("Nobody");
    resolver.complete("Nobody", Optional.empty());

    assertEquals(PlayerHeadLookup.State.UNKNOWN, service.lookup("Nobody").state());
    assertNull(service.lookup("Nobody").profile());
  }

  @Test
  public void theCacheKeyIgnoresCapitalization() {
    FakeResolver resolver = new FakeResolver();
    PlayerHeadService service = service(resolver, new AtomicLong());

    service.lookup("Notch");
    assertTrue(service.lookup("nOtCh").isPending());

    assertEquals(1, resolver.calls.size());
    assertEquals(1, service.cachedCount());
  }

  @Test
  public void theResolverSeesTheNameAsAuthoredNotAsCached() {
    FakeResolver resolver = new FakeResolver();

    service(resolver, new AtomicLong()).lookup("NoTcH");

    assertEquals(List.of("NoTcH"), resolver.calls);
  }

  // ---------------------------------------------------------------------
  // Coalescing
  // ---------------------------------------------------------------------

  @Test
  public void concurrentLookupsOfTheSameNameShareOneRequest() throws Exception {
    FakeResolver resolver = new FakeResolver();
    PlayerHeadService service = service(resolver, new AtomicLong());
    int viewers = 32;
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(viewers);
    ExecutorService pool = Executors.newFixedThreadPool(8);
    try {
      for (int viewer = 0; viewer < viewers; viewer++) {
        pool.execute(() -> {
          try {
            start.await();
            service.lookup("Notch");
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          } finally {
            done.countDown();
          }
        });
      }
      start.countDown();
      assertTrue(done.await(10L, TimeUnit.SECONDS));
    } finally {
      pool.shutdownNow();
    }

    assertEquals(1, resolver.calls.size());
    assertEquals(1, service.cachedCount());
  }

  @Test
  public void aResolvedProfileIsHandedBackAsTheSameInstanceRatherThanRebuilt() {
    FakeResolver resolver = new FakeResolver();
    PlayerHeadService service = service(resolver, new AtomicLong());
    PlayerHeadProfile answer = profile("Notch");

    service.lookup("Notch");
    resolver.complete("Notch", Optional.of(answer));

    assertSame(answer, service.lookup("Notch").profile());
    assertSame(answer, service.lookup("Notch").profile());
  }

  // ---------------------------------------------------------------------
  // Time to live
  // ---------------------------------------------------------------------

  @Test
  public void aHitIsReReadOnlyAfterItsTtl() {
    FakeResolver resolver = new FakeResolver();
    AtomicLong clock = new AtomicLong();
    PlayerHeadService service = service(resolver, clock);

    service.lookup("Notch");
    resolver.complete("Notch", Optional.of(profile("Notch")));

    clock.addAndGet(HIT.toNanos() - 1L);
    assertTrue(service.lookup("Notch").isResolved());
    assertEquals(1, resolver.calls.size());

    clock.addAndGet(2L);
    assertTrue(service.lookup("Notch").isPending());
    assertEquals(2, resolver.calls.size());
  }

  @Test
  public void aConfirmedMissIsReReadOnTheShorterMissTtl() {
    FakeResolver resolver = new FakeResolver();
    AtomicLong clock = new AtomicLong();
    PlayerHeadService service = service(resolver, clock);

    service.lookup("Nobody");
    resolver.complete("Nobody", Optional.empty());

    clock.addAndGet(MISS.toNanos() - 1L);
    assertEquals(PlayerHeadLookup.State.UNKNOWN, service.lookup("Nobody").state());
    assertEquals(1, resolver.calls.size());

    clock.addAndGet(2L);
    assertTrue(service.lookup("Nobody").isPending());
    assertEquals(2, resolver.calls.size());
  }

  @Test
  public void aFailedLookupRetriesFarSoonerThanAConfirmedMiss() {
    FakeResolver resolver = new FakeResolver();
    AtomicLong clock = new AtomicLong();
    PlayerHeadService service = service(resolver, clock);

    service.lookup("Notch");
    resolver.fail("Notch", new IllegalStateException("session server unreachable"));

    assertEquals(PlayerHeadLookup.State.UNKNOWN, service.lookup("Notch").state());

    clock.addAndGet(PlayerHeadService.FAILURE_TTL.toNanos() - 1L);
    assertEquals(1, resolver.calls.size());

    clock.addAndGet(2L);
    assertTrue(service.lookup("Notch").isPending());
    assertEquals(2, resolver.calls.size());
    assertTrue(PlayerHeadService.FAILURE_TTL.compareTo(MISS) < 0);
  }

  // ---------------------------------------------------------------------
  // Nothing here breaks a menu
  // ---------------------------------------------------------------------

  @Test
  public void aResolverThatThrowsOnTheCallingThreadIsJustAFailedLookup() {
    PlayerHeadService service = new PlayerHeadService(
        name -> {
          throw new IllegalStateException("no profile plumbing on this server");
        },
        new AtomicLong()::get, HIT, MISS, 64);

    assertEquals(PlayerHeadLookup.State.UNKNOWN, service.lookup("Notch").state());
  }

  @Test
  public void aResolverThatReturnsNothingAtAllIsAConfirmedMiss() {
    PlayerHeadService service = new PlayerHeadService(name -> null, new AtomicLong()::get, HIT, MISS, 64);

    assertEquals(PlayerHeadLookup.State.UNKNOWN, service.lookup("Notch").state());
  }

  @Test
  public void aFutureThatCompletesWithNullIsAConfirmedMiss() {
    PlayerHeadService service = new PlayerHeadService(
        name -> CompletableFuture.completedFuture(null), new AtomicLong()::get, HIT, MISS, 64);

    assertEquals(PlayerHeadLookup.State.UNKNOWN, service.lookup("Notch").state());
  }

  // ---------------------------------------------------------------------
  // Bounds and invalidation
  // ---------------------------------------------------------------------

  @Test
  public void theCacheStaysUnderItsCeilingByDroppingTheEntriesClosestToExpiry() {
    FakeResolver resolver = new FakeResolver();
    AtomicLong clock = new AtomicLong();
    PlayerHeadService service = new PlayerHeadService(resolver, clock::get, HIT, MISS, 4);

    for (int index = 0; index < 12; index++) {
      String name = "player" + index;
      service.lookup(name);
      resolver.complete(name, Optional.of(profile(name)));
      clock.addAndGet(Duration.ofSeconds(1L).toNanos());
    }

    assertTrue("cached=" + service.cachedCount(), service.cachedCount() <= 4);
    assertTrue(service.lookup("player11").isResolved());
  }

  @Test
  public void anInFlightLookupIsNeverEvicted() {
    FakeResolver resolver = new FakeResolver();
    PlayerHeadService service = new PlayerHeadService(resolver, new AtomicLong()::get, HIT, MISS, 1);

    service.lookup("first");
    service.lookup("second");
    service.lookup("third");

    assertEquals(3, service.cachedCount());
    assertTrue(service.lookup("first").isPending());
    assertEquals(3, resolver.calls.size());
  }

  @Test
  public void invalidateDropsEverythingSoAConfigFixTakesEffectAtOnce() {
    FakeResolver resolver = new FakeResolver();
    PlayerHeadService service = service(resolver, new AtomicLong());

    service.lookup("Notch");
    resolver.complete("Notch", Optional.of(profile("Notch")));
    assertTrue(service.lookup("Notch").isResolved());

    service.invalidate();

    assertEquals(0, service.cachedCount());
    assertTrue(service.lookup("Notch").isPending());
    assertEquals(2, resolver.calls.size());
  }

  @Test
  public void theServiceRefusesNonsensicalLimitsRatherThanSilentlyCachingForever() {
    FakeResolver resolver = new FakeResolver();

    assertThrowsIllegalArgument(() -> new PlayerHeadService(resolver, new AtomicLong()::get, Duration.ZERO, MISS, 8));
    assertThrowsIllegalArgument(() -> new PlayerHeadService(resolver, new AtomicLong()::get, HIT, Duration.ofMinutes(-1L), 8));
    assertThrowsIllegalArgument(() -> new PlayerHeadService(resolver, new AtomicLong()::get, HIT, MISS, 0));
  }

  private static void assertThrowsIllegalArgument(Runnable action) {
    try {
      action.run();
    } catch (IllegalArgumentException expected) {
      assertNotNull(expected);
      return;
    }
    throw new AssertionError("expected IllegalArgumentException");
  }

  private static PlayerHeadService service(FakeResolver resolver, AtomicLong clock) {
    return new PlayerHeadService(resolver, clock::get, HIT, MISS, 64);
  }

  /** Hands out futures the test completes by hand, and records every name it was asked for. */
  private static final class FakeResolver implements PlayerHeadResolver {
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final List<CompletableFuture<Optional<PlayerHeadProfile>>> pending = new CopyOnWriteArrayList<>();

    @Override
    public CompletableFuture<Optional<PlayerHeadProfile>> resolve(String name) {
      calls.add(name);
      CompletableFuture<Optional<PlayerHeadProfile>> future = new CompletableFuture<>();
      pending.add(future);
      return future;
    }

    private void complete(String name, Optional<PlayerHeadProfile> answer) {
      latest(name).complete(answer);
    }

    private void fail(String name, Throwable failure) {
      latest(name).completeExceptionally(failure);
    }

    private CompletableFuture<Optional<PlayerHeadProfile>> latest(String name) {
      for (int index = calls.size() - 1; index >= 0; index--) {
        if (calls.get(index).equalsIgnoreCase(name)) {
          return pending.get(index);
        }
      }
      throw new AssertionError("nothing was ever looked up for " + name);
    }
  }
}
