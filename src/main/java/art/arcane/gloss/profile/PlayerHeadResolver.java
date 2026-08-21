package art.arcane.gloss.profile;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Turns a Minecraft username into the profile a skull renders from.
 *
 * <p>The one seam between {@link PlayerHeadService} and the network. Implementations MUST return
 * immediately: {@link PlayerHeadService} calls this from inside a
 * {@link java.util.concurrent.ConcurrentHashMap#computeIfAbsent} on whichever thread asked for the
 * head — the server thread, in every menu and panel render path — so the work belongs behind the
 * returned future, never in front of it.
 */
@FunctionalInterface
public interface PlayerHeadResolver {
  /**
   * @param name a username that already passed {@link PlayerHeadService#isResolvableName(String)},
   *             in the capitalization the menu author typed
   * @return a future completing with the account, or with an empty optional when the name is
   *         confirmed not to exist. A future that completes exceptionally is a transient failure
   *         (offline, rate limited, timed out) and is cached far more briefly than either answer
   */
  CompletableFuture<Optional<PlayerHeadProfile>> resolve(String name);
}
