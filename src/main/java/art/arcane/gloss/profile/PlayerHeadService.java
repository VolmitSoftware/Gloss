package art.arcane.gloss.profile;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * The non-blocking profile cache behind {@code playerHead} icons.
 *
 * <p>Three rules shape it, in this order:
 *
 * <ol>
 *   <li><b>Never block the server thread.</b> {@link #lookup(String)} is called from
 *       {@code MenuComponent.tick} (see {@code MenuComponent.java:60}) and from every icon spawn,
 *       both on the server thread. It only ever reads a map and hands back what it already has, so
 *       the first render of a fresh name is {@link PlayerHeadLookup.State#PENDING} by design.</li>
 *   <li><b>Never ask Mojang twice for the same thing.</b> One entry per lowercase name holds the
 *       in-flight future itself, so N concurrent viewers of {@code %player_name%}-free menus that
 *       name the same player share a single request. Both answers are cached: a hit for
 *       {@code cacheMinutes}, a confirmed-missing name for {@code unknownCacheMinutes}, and a
 *       transient failure for {@link #FAILURE_TTL} so an outage retries soon without hammering.</li>
 *   <li><b>Never break a menu.</b> Nothing here throws. An unusable name never reaches the
 *       resolver, a resolver that throws synchronously is treated as a failed lookup, and every
 *       failure path answers {@link PlayerHeadLookup.State#UNKNOWN}, which the icon renders as the
 *       configured fallback head.</li>
 * </ol>
 */
public final class PlayerHeadService {

  /**
   * The current username ceiling. Anything longer, empty, or carrying a character Mojang never
   * issued — a placeholder the pipeline could not resolve, a UUID, an email — is answered UNKNOWN
   * without a request. There is no lower bound beyond non-empty: legacy accounts shorter than the
   * three characters signup enforces today still exist.
   */
  public static final int MAX_NAME_LENGTH = 16;

  /**
   * How long a lookup that failed rather than answered stays cached. Deliberately far shorter than
   * a confirmed miss: the name may well exist and the session server may well be back in a minute.
   */
  public static final Duration FAILURE_TTL = Duration.ofMinutes(1L);

  static final int MAX_CONCURRENT_RESOLUTIONS = 16;
  static final int MAX_QUEUED_RESOLUTIONS = 1024;

  private final PlayerHeadResolver resolver;
  private final LongSupplier nanoClock;
  private final Duration hitTtl;
  private final Duration unknownTtl;
  private final int maxEntries;
  private final Map<String, Entry> entries = new ConcurrentHashMap<>();
  private final Set<String> failureLogged = ConcurrentHashMap.newKeySet();
  private final Object evictionLock = new Object();
  private final Object resolutionLock = new Object();
  private final ArrayDeque<ResolutionRequest> queuedResolutions = new ArrayDeque<>();
  private int activeResolutions;
  private boolean drainingResolutions;

  public PlayerHeadService(PlayerHeadResolver resolver, LongSupplier nanoClock, Duration hitTtl,
                           Duration unknownTtl, int maxEntries) {
    this.resolver = Objects.requireNonNull(resolver, "resolver");
    this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
    this.hitTtl = requirePositive(hitTtl, "hitTtl");
    this.unknownTtl = requirePositive(unknownTtl, "unknownTtl");
    if (maxEntries < 1) {
      throw new IllegalArgumentException("maxEntries must be at least 1");
    }
    this.maxEntries = maxEntries;
  }

  /** The live service, or null before {@code onEnable} and after {@code onDisable}. */
  public static PlayerHeadService active() {
    Gloss plugin = Gloss.instance;
    return plugin == null ? null : plugin.playerHeads();
  }

  /**
   * True for a string Mojang could have issued as a username. An unresolved placeholder still
   * carrying its {@code %} or {@code {{ }}} markers fails this, which is exactly why an icon whose
   * placeholder did not resolve shows the fallback head instead of costing a request.
   */
  public static boolean isResolvableName(String name) {
    if (name == null || name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
      return false;
    }
    for (int index = 0; index < name.length(); index++) {
      char c = name.charAt(index);
      boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
      if (!allowed) {
        return false;
      }
    }
    return true;
  }

  /**
   * Answers from cache, starting a lookup when there is nothing cached and the name is usable.
   * Never blocks, never throws, never touches the network on this thread.
   */
  public PlayerHeadLookup lookup(String name) {
    if (!isResolvableName(name)) {
      return PlayerHeadLookup.unknown();
    }
    String key = name.toLowerCase(Locale.ROOT);
    long now = nanoClock.getAsLong();
    Entry cached = entries.get(key);
    if (cached != null && cached.isExpired(now)) {
      if (entries.remove(key, cached)) {
        failureLogged.remove(key);
      }
      cached = null;
    }
    if (cached == null) {
      cached = entries.computeIfAbsent(key, ignored -> new Entry(name));
      evictIfOversized(now);
    }
    return cached.snapshot();
  }

  /**
   * Drops everything cached. Called when {@code gloss.toml} changes the head settings, so an
   * operator who fixed a name or turned resolution back on sees it on the next refresh instead of
   * waiting out a six-hour TTL.
   */
  public void invalidate() {
    entries.clear();
    failureLogged.clear();
    List<ResolutionRequest> abandoned;
    synchronized (resolutionLock) {
      abandoned = new ArrayList<>(queuedResolutions);
      queuedResolutions.clear();
    }
    for (ResolutionRequest request : abandoned) {
      request.answer().cancel(false);
    }
  }

  /** Entry count, including in-flight lookups. Test and metrics seam. */
  public int cachedCount() {
    return entries.size();
  }

  /** Builds the service the running plugin uses, from the current {@code gloss.toml} values. */
  public static PlayerHeadService fromConfig(GlossConfig.PlayerHeads settings) {
    return new PlayerHeadService(
        new BukkitPlayerHeadResolver(),
        System::nanoTime,
        Duration.ofMinutes(settings.cacheMinutes()),
        Duration.ofMinutes(settings.unknownCacheMinutes()),
        settings.maxCachedProfiles());
  }

  private static Duration requirePositive(Duration value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(name + " must be positive");
    }
    return value;
  }

  /**
   * Keeps the map bounded without a scheduled sweep. Expired entries go first; only if the cache is
   * still over its limit does it drop the entries closest to expiry, and never an in-flight one —
   * those carry {@link Long#MAX_VALUE} until they complete.
   */
  private void evictIfOversized(long now) {
    if (entries.size() <= maxEntries) {
      return;
    }
    synchronized (evictionLock) {
      if (entries.size() <= maxEntries) {
        return;
      }
      for (Map.Entry<String, Entry> candidate : entries.entrySet()) {
        if (candidate.getValue().isExpired(now)
            && entries.remove(candidate.getKey(), candidate.getValue())) {
          failureLogged.remove(candidate.getKey());
        }
      }
      int excess = entries.size() - maxEntries;
      if (excess <= 0) {
        return;
      }
      List<Map.Entry<String, Entry>> byExpiry = new ArrayList<>();
      for (Map.Entry<String, Entry> candidate : entries.entrySet()) {
        if (candidate.getValue().expiresAt != Long.MAX_VALUE) {
          byExpiry.add(candidate);
        }
      }
      byExpiry.sort(Comparator.comparingLong(candidate -> candidate.getValue().expiresAt));
      for (Map.Entry<String, Entry> candidate : byExpiry) {
        if (excess <= 0) {
          return;
        }
        if (entries.remove(candidate.getKey(), candidate.getValue())) {
          failureLogged.remove(candidate.getKey());
          excess--;
        }
      }
    }
  }

  private boolean enqueueResolution(String name,
                                    CompletableFuture<Optional<PlayerHeadProfile>> answer) {
    synchronized (resolutionLock) {
      if (queuedResolutions.size() >= MAX_QUEUED_RESOLUTIONS) {
        return false;
      }
      queuedResolutions.addLast(new ResolutionRequest(name, answer));
    }
    drainResolutionQueue();
    return true;
  }

  private void drainResolutionQueue() {
    synchronized (resolutionLock) {
      if (drainingResolutions) {
        return;
      }
      drainingResolutions = true;
    }

    try {
      while (true) {
        ResolutionRequest request;
        synchronized (resolutionLock) {
          if (activeResolutions >= MAX_CONCURRENT_RESOLUTIONS || queuedResolutions.isEmpty()) {
            return;
          }
          request = queuedResolutions.removeFirst();
          activeResolutions++;
        }
        startResolution(request);
      }
    } finally {
      boolean continueDraining;
      synchronized (resolutionLock) {
        drainingResolutions = false;
        continueDraining = activeResolutions < MAX_CONCURRENT_RESOLUTIONS
            && !queuedResolutions.isEmpty();
      }
      if (continueDraining) {
        drainResolutionQueue();
      }
    }
  }

  private void startResolution(ResolutionRequest request) {
    CompletableFuture<Optional<PlayerHeadProfile>> pending;
    try {
      pending = resolver.resolve(request.name());
      if (pending == null) {
        pending = CompletableFuture.completedFuture(Optional.empty());
      }
    } catch (RuntimeException | LinkageError immediateFailure) {
      completeResolution(request, null, immediateFailure);
      return;
    }
    pending.whenComplete((result, failure) -> completeResolution(request, result, failure));
  }

  private void completeResolution(ResolutionRequest request, Optional<PlayerHeadProfile> result,
                                  Throwable failure) {
    try {
      if (failure == null) {
        request.answer().complete(result);
      } else {
        request.answer().completeExceptionally(failure);
      }
    } finally {
      synchronized (resolutionLock) {
        activeResolutions--;
      }
      drainResolutionQueue();
    }
  }

  private void logFailureOnce(String name, Throwable failure) {
    if (!failureLogged.add(name.toLowerCase(Locale.ROOT))) {
      return;
    }
    Gloss.logExceptionStack(false, failure,
        "Player head lookup for \"%s\" failed; showing the fallback head and retrying in %d s.",
        name, FAILURE_TTL.toSeconds());
  }

  /**
   * One cached name. {@code expiresAt} stays {@link Long#MAX_VALUE} while the lookup is in flight,
   * which is what makes an in-flight entry both un-evictable and un-expirable — the next caller
   * joins it instead of starting a second request.
   */
  private final class Entry {
    private final CompletableFuture<Optional<PlayerHeadProfile>> future;
    private volatile long expiresAt = Long.MAX_VALUE;

    private Entry(String name) {
      this.future = new CompletableFuture<>();
      future.whenComplete((result, failure) -> settle(name, result, failure));
      if (!enqueueResolution(name, future)) {
        future.completeExceptionally(ResolutionQueueFullException.INSTANCE);
      }
    }

    private void settle(String name, Optional<PlayerHeadProfile> result, Throwable failure) {
      Duration ttl;
      if (failure != null) {
        ttl = FAILURE_TTL;
        if (!(failure instanceof CancellationException)
            && !(failure instanceof ResolutionQueueFullException)) {
          logFailureOnce(name, failure);
        }
      } else {
        ttl = result != null && result.isPresent() ? hitTtl : unknownTtl;
      }
      long now = nanoClock.getAsLong();
      expiresAt = now + ttl.toNanos();
      evictIfOversized(now);
    }

    private boolean isExpired(long now) {
      long deadline = expiresAt;
      return deadline != Long.MAX_VALUE && now - deadline >= 0L;
    }

    private PlayerHeadLookup snapshot() {
      if (!future.isDone()) {
        return PlayerHeadLookup.pending();
      }
      Optional<PlayerHeadProfile> result;
      try {
        result = future.getNow(Optional.empty());
      } catch (RuntimeException failed) {
        return PlayerHeadLookup.unknown();
      }
      return result != null && result.isPresent()
          ? PlayerHeadLookup.resolved(result.get())
          : PlayerHeadLookup.unknown();
    }
  }

  private record ResolutionRequest(String name,
                                   CompletableFuture<Optional<PlayerHeadProfile>> answer) {
  }

  private static final class ResolutionQueueFullException extends RuntimeException {
    private static final ResolutionQueueFullException INSTANCE = new ResolutionQueueFullException();

    private ResolutionQueueFullException() {
      super("player head resolution queue is full", null, false, false);
    }
  }
}
