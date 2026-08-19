package art.arcane.gloss.group;

import art.arcane.gloss.Gloss;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public final class GroupService implements Listener {
    static final long PRIMARY_GROUP_TTL_MS = 5000L;
    static final long PRIMARY_GROUP_TTL_JITTER_MS = 1500L;

    private final Gloss plugin;
    private final Map<UUID, CachedPrimaryGroup> primaryGroups;
    private final Set<UUID> refreshing;
    private volatile VaultPermissionHook vault;

    public GroupService(Gloss plugin) {
        this.plugin = plugin;
        this.primaryGroups = new ConcurrentHashMap<>();
        this.refreshing = ConcurrentHashMap.newKeySet();
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
        refreshing.clear();
    }

    public void reload() {
        primaryGroups.clear();
        refreshing.clear();
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

    public Optional<String> cachedPrimaryGroupFor(Player player) {
        if (player == null || !plugin.cfg().groups().useVault() || vault == null) {
            return Optional.empty();
        }
        CachedPrimaryGroup cached = primaryGroups.get(player.getUniqueId());
        if (cached != null && cached.fresh(nowMs())) {
            return Optional.ofNullable(cached.name());
        }
        scheduleRefresh(player);
        return Optional.empty();
    }

    public static String normalizeGroupName(String resolved) {
        if (resolved == null || resolved.isBlank()) {
            return null;
        }
        return resolved.trim().toLowerCase(Locale.ROOT);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void on(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        primaryGroups.remove(uuid);
        refreshing.remove(uuid);
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
        long now = nowMs();
        CachedPrimaryGroup cached = primaryGroups.get(uuid);
        if (cached != null && cached.fresh(now)) {
            return cached.name();
        }
        return refresh(primaryGroups, uuid, now, key -> hook.primaryGroup(player)).name();
    }

    static CachedPrimaryGroup refresh(Map<UUID, CachedPrimaryGroup> cache, UUID uuid, long now,
                                      Function<UUID, String> resolver) {
        return cache.compute(uuid, (key, existing) -> existing != null && existing.fresh(now)
            ? existing
            : new CachedPrimaryGroup(normalizeGroupName(resolver.apply(key)), now + jitterMs(key)));
    }

    private void scheduleRefresh(Player player) {
        UUID uuid = player.getUniqueId();
        if (!refreshing.add(uuid)) {
            return;
        }
        plugin.scheduler().a(() -> {
            try {
                primaryGroupName(player);
            } finally {
                refreshing.remove(uuid);
            }
        }, 0);
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

    static long jitterMs(UUID uuid) {
        return Math.floorMod((long) uuid.hashCode(), PRIMARY_GROUP_TTL_JITTER_MS);
    }

    private static long nowMs() {
        return System.nanoTime() / 1_000_000L;
    }

    record CachedPrimaryGroup(String name, long at) {
        boolean fresh(long now) {
            return now - at < PRIMARY_GROUP_TTL_MS;
        }
    }
}
