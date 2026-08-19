package art.arcane.gloss.group;

import art.arcane.gloss.Gloss;
import art.arcane.volmlib.util.math.M;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class GroupService implements Listener {
    static final long PRIMARY_GROUP_TTL_MS = 5000L;

    private final Gloss plugin;
    private final Map<UUID, CachedPrimaryGroup> primaryGroups;
    private volatile VaultPermissionHook vault;

    public GroupService(Gloss plugin) {
        this.plugin = plugin;
        this.primaryGroups = new ConcurrentHashMap<>();
    }

    public void enable() {
        if (plugin.cfg().groups().useVault()) {
            hookVault();
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void disable() {
        HandlerList.unregisterAll(this);
        vault = null;
        primaryGroups.clear();
    }

    public void reload() {
        primaryGroups.clear();
        if (plugin.cfg().groups().useVault() && vault == null) {
            hookVault();
        }
    }

    public Optional<String> primaryGroupFor(Player player) {
        if (player == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(primaryGroupName(player));
    }

    public static String normalizeGroupName(String resolved) {
        if (resolved == null || resolved.isBlank()) {
            return null;
        }
        return resolved.trim().toLowerCase(Locale.ROOT);
    }

    @EventHandler
    public void on(PlayerQuitEvent event) {
        primaryGroups.remove(event.getPlayer().getUniqueId());
    }

    private String primaryGroupName(Player player) {
        if (!plugin.cfg().groups().useVault()) {
            return null;
        }
        VaultPermissionHook hook = vault;
        if (hook == null) {
            return null;
        }
        UUID uuid = player.getUniqueId();
        long now = M.ms();
        CachedPrimaryGroup cached = primaryGroups.get(uuid);
        if (cached != null && cached.fresh(now)) {
            return cached.name();
        }
        String normalized = normalizeGroupName(hook.primaryGroup(player));
        primaryGroups.put(uuid, new CachedPrimaryGroup(normalized, now));
        return normalized;
    }

    private void hookVault() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            return;
        }
        try {
            vault = new VaultPermissionHook();
        } catch (Throwable failure) {
            vault = null;
            Gloss.warn("Vault permission hook failed to initialize: " + failure.getClass().getSimpleName()
                + (failure.getMessage() == null ? "" : ": " + failure.getMessage()));
        }
    }

    record CachedPrimaryGroup(String name, long at) {
        boolean fresh(long now) {
            return now - at < PRIMARY_GROUP_TTL_MS;
        }
    }
}
