package art.arcane.gloss.menu.action;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.config.action.TeleportActionData;
import art.arcane.gloss.util.common.Teleports;
import art.arcane.volmlib.util.bukkit.WorldIdentity;
import art.arcane.volmlib.util.scheduling.SchedulerUtils;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class TeleportMenuAction extends MenuAction<TeleportActionData> {
  private static final Set<DestinationWarning> DESTINATION_WARNINGS = ConcurrentHashMap.newKeySet();

  public TeleportMenuAction(TeleportActionData data) {
    super(data);
  }

  public boolean hasValidDestination() {
    return data.hasValidDestination();
  }

  @Override
  public ActionOutcome execute(ActionContext context) {
    Player player = context.player();
    boolean accepted = SchedulerUtils.runEntity(Gloss.instance, player, () -> teleport(context));
    if (!accepted) {
      Gloss.log(Level.WARNING,
          "Menu \"%s\" component \"%s\" could not schedule its teleport for player %s.",
          context.menuId(), context.componentId(), player.getName());
    }
    return ActionOutcome.CONTINUE;
  }

  private void teleport(ActionContext context) {
    NamespacedKey worldKey = data.resolveWorldKey();
    World world = worldKey == null ? null : WorldIdentity.resolve(worldKey).orElse(null);
    if (world == null) {
      DestinationWarning warning = new DestinationWarning(context.menuId(), context.componentId(), data.world());
      if (DESTINATION_WARNINGS.add(warning)) {
        Gloss.log(Level.WARNING,
            "Menu \"%s\" component \"%s\" cannot teleport to unloaded world \"%s\"; that action does nothing.",
            context.menuId(), context.componentId(), data.world());
      }
      return;
    }

    Player player = context.player();
    Location destination = new Location(world, data.x(), data.y(), data.z(), data.yaw(), data.pitch());
    CompletableFuture<Boolean> result = Teleports.teleportAsync(player, destination, PlayerTeleportEvent.TeleportCause.PLUGIN);
    result.whenComplete((success, failure) -> reportResult(context, player, success, failure));
  }

  private void reportResult(ActionContext context, Player player, Boolean success, Throwable failure) {
    if (failure != null) {
      Gloss.logExceptionStack(false, failure,
          "Menu \"%s\" component \"%s\" failed to teleport player %s to world %s.",
          context.menuId(), context.componentId(), player.getName(), data.world());
      return;
    }
    if (!Boolean.TRUE.equals(success)) {
      Gloss.log(Level.WARNING,
          "Menu \"%s\" component \"%s\" could not teleport player %s to world %s.",
          context.menuId(), context.componentId(), player.getName(), data.world());
    }
  }

  private record DestinationWarning(String menuId, String componentId, String worldKey) {
  }
}
