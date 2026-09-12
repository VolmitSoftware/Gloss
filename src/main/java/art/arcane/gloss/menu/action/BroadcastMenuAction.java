package art.arcane.gloss.menu.action;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.config.action.BroadcastActionData;
import art.arcane.gloss.util.common.TextUtils;
import art.arcane.volmlib.util.plugin.ComponentMessenger;
import art.arcane.volmlib.util.plugin.ComponentText;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.UnaryOperator;

/** Sends the message to every recipient in scope, rendered per recipient with the trigger's roles and args. */
public final class BroadcastMenuAction extends MenuAction<BroadcastActionData> {
  public BroadcastMenuAction(BroadcastActionData data) {
    super(data);
  }

  @Override
  public ActionOutcome execute(ActionContext context) {
    Gloss plugin = Gloss.instance;
    if (plugin == null) {
      return ActionOutcome.CONTINUE;
    }
    for (Player recipient : recipients(context)) {
      if (data.permission() != null && !recipient.hasPermission(data.permission())) {
        continue;
      }
      String resolved = plugin.text().renderScoped(recipient, data.message(), context.conditionScope(),
          UnaryOperator.identity());
      ComponentMessenger.send(recipient, ComponentText.component(TextUtils.parse(resolved)));
    }
    return ActionOutcome.CONTINUE;
  }

  private Collection<? extends Player> recipients(ActionContext context) {
    Location origin = ActionRoles.location(context, "viewer");
    return switch (data.scope()) {
      case "world" -> origin == null || origin.getWorld() == null ? List.of() : origin.getWorld().getPlayers();
      case "radius" -> within(origin, data.radius());
      default -> Bukkit.getOnlinePlayers();
    };
  }

  private static List<Player> within(Location origin, double radius) {
    if (origin == null || origin.getWorld() == null) {
      return List.of();
    }
    double squared = radius * radius;
    List<Player> nearby = new ArrayList<>();
    for (Player player : origin.getWorld().getPlayers()) {
      if (player.getLocation().distanceSquared(origin) <= squared) {
        nearby.add(player);
      }
    }
    return nearby;
  }

  @Override
  protected boolean requiresPlayer() {
    return false;
  }
}
