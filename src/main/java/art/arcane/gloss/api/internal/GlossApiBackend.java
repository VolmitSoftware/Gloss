package art.arcane.gloss.api.internal;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.api.HoloCloseReason;
import art.arcane.gloss.config.MenuDefinitionData;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.volmlib.util.scheduling.SchedulerUtils;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

final class GlossApiBackend implements ApiBackend {
  private final Gloss plugin;

  GlossApiBackend(Gloss plugin) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
  }

  @Override
  public boolean schedule(Player player, Runnable task, Runnable retired) {
    if (player == null || task == null) {
      return false;
    }

    if (FoliaScheduler.runEntity(plugin, player, task, 0L, retired)) {
      return true;
    }

    if (FoliaScheduler.isFolia(plugin)) {
      plugin.getLogger().warning("Failed to run entity task on Folia for plugin " + plugin.getName()
          + "; refusing unsafe global fallback.");
      return false;
    }

    return SchedulerUtils.runGlobal(plugin, task);
  }

  @Override
  public boolean hasSession(Player player) {
    return plugin.getSessionManager().hasMenuSession(player);
  }

  @Override
  public boolean openSession(Player player, MenuDefinitionData definition, ApiMenuHandle handle) {
    return plugin.getSessionManager().createNewSession(player, definition, handle);
  }

  @Override
  public boolean closeSession(Player player, HoloCloseReason reason) {
    return plugin.getSessionManager().destroySession(player, false, reason);
  }

  @Override
  public boolean closeSessionOf(Player player, ApiMenuHandle handle, HoloCloseReason reason) {
    return plugin.getSessionManager().destroySessionFor(player, handle, reason);
  }

  @Override
  public void forgetClickGuard(String ownerName) {
    plugin.getSessionManager().getClickGuard().forget(ownerName);
  }

  @Override
  public MenuDefinitionData definition(String menuId) {
    return plugin.getMenuCatalog().definition(menuId).orElse(null);
  }

  @Override
  public Set<String> menuIds() {
    return Set.copyOf(plugin.getMenuCatalog().keys());
  }

  @Override
  public Player online(UUID playerId) {
    return Bukkit.getPlayer(playerId);
  }
}
