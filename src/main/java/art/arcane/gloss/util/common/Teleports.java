package art.arcane.gloss.util.common;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;

public final class Teleports {
  private static final Method TELEPORT_ASYNC = resolveTeleportAsync();

  private Teleports() {
  }

  @SuppressWarnings("unchecked")
  public static CompletableFuture<Boolean> teleportAsync(Player player, Location destination, PlayerTeleportEvent.TeleportCause cause) {
    if (TELEPORT_ASYNC != null) {
      try {
        return (CompletableFuture<Boolean>) TELEPORT_ASYNC.invoke(player, destination, cause);
      } catch (InvocationTargetException failure) {
        return CompletableFuture.failedFuture(failure.getCause() == null ? failure : failure.getCause());
      } catch (ReflectiveOperationException | RuntimeException failure) {
        return CompletableFuture.failedFuture(failure);
      }
    }
    try {
      return CompletableFuture.completedFuture(player.teleport(destination, cause));
    } catch (RuntimeException failure) {
      return CompletableFuture.failedFuture(failure);
    }
  }

  private static Method resolveTeleportAsync() {
    try {
      return Player.class.getMethod("teleportAsync", Location.class, PlayerTeleportEvent.TeleportCause.class);
    } catch (NoSuchMethodException absent) {
      return null;
    }
  }
}
