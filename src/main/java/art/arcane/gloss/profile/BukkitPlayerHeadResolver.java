package art.arcane.gloss.profile;

import art.arcane.gloss.Gloss;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;

import java.net.URL;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Resolves usernames through the server's own profile plumbing.
 *
 * <p>Everything here is {@code org.bukkit.profile}, which Spigot and Paper both ship, so this class
 * survives the {@code compileSpigotCompatibility} pass that strips {@code paper-api}
 * (build.gradle:104-112). {@link PlayerProfile#update()} is the only outbound call and it is
 * already asynchronous — CraftBukkit runs it on the server's background executor and hands back a
 * future — so this method returns without doing any work, which is the contract
 * {@link PlayerHeadResolver} requires.
 */
public final class BukkitPlayerHeadResolver implements PlayerHeadResolver {

  /**
   * Turns a session server that never answers into a failed lookup instead of an entry that stays
   * {@link PlayerHeadLookup.State#PENDING} forever and can never be evicted.
   */
  static final long TIMEOUT_SECONDS = 15L;

  @Override
  public CompletableFuture<Optional<PlayerHeadProfile>> resolve(String name) {
    Player online = Bukkit.getPlayerExact(name);
    if (online != null) {
      Gloss plugin = Gloss.instance;
      if (plugin == null) {
        return CompletableFuture.completedFuture(snapshot(online.getPlayerProfile()));
      }
      CompletableFuture<Optional<PlayerHeadProfile>> answer = new CompletableFuture<>();
      AtomicBoolean fallbackStarted = new AtomicBoolean();
      Runnable fallback = () -> {
        if (!fallbackStarted.compareAndSet(false, true)) {
          return;
        }
        if (!plugin.isEnabled()) {
          answer.complete(Optional.empty());
          return;
        }
        resolveRemote(name).whenComplete((resolved, failure) -> {
          if (failure == null) {
            answer.complete(resolved);
          } else {
            answer.completeExceptionally(failure);
          }
        });
      };
      Runnable capture = () -> {
        try {
          answer.complete(snapshot(online.getPlayerProfile()));
        } catch (RuntimeException | LinkageError failure) {
          fallback.run();
        }
      };
      if (!FoliaScheduler.runEntity(plugin, online, capture, 0L, fallback)) {
        fallback.run();
      }
      return answer.orTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
    return resolveRemote(name);
  }

  private CompletableFuture<Optional<PlayerHeadProfile>> resolveRemote(String name) {
    PlayerProfile profile = Bukkit.createPlayerProfile(name);
    return profile.update()
        .orTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .thenApply(BukkitPlayerHeadResolver::snapshot);
  }

  /**
   * A profile with no id is Mojang saying the name does not exist. A profile with an id but no skin
   * is a real account that never uploaded one: still a resolved head, because the id alone picks
   * the default skin the client draws.
   */
  private static Optional<PlayerHeadProfile> snapshot(PlayerProfile updated) {
    if (updated == null || updated.getUniqueId() == null) {
      return Optional.empty();
    }
    String name = updated.getName();
    if (name == null || name.isBlank()) {
      return Optional.empty();
    }
    PlayerTextures textures = updated.getTextures();
    URL skin = textures == null ? null : textures.getSkin();
    return Optional.of(new PlayerHeadProfile(updated.getUniqueId(), name, skin == null ? null : skin.toString()));
  }
}
