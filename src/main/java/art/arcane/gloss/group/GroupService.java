package art.arcane.gloss.group;

import art.arcane.gloss.Gloss;
import art.arcane.volmlib.util.io.FolderWatcher;
import art.arcane.volmlib.util.math.M;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class GroupService implements Listener {
    private static final long PRIMARY_GROUP_TTL_MS = 5000L;
    private static final String OP_GROUP_NAME = "_op";
    private static final String GROUP_FILE_SUFFIX = ".yml";
    private static final String KEY_TABLIST_NAME = "tablist-name";
    private static final String KEY_DEFAULT_BOARD = "default-board";
    private static final String DEFAULT_OP_TABLIST_NAME = "&6$player";

    private final Gloss plugin;
    private final Map<String, GlossGroup> groups;
    private final Map<UUID, CachedPrimaryGroup> primaryGroups;
    private volatile VaultPermissionHook vault;
    private volatile FolderWatcher watcher;
    private volatile int watcherTaskId;

    public GroupService(Gloss plugin) {
        this.plugin = plugin;
        this.groups = new ConcurrentHashMap<>();
        this.primaryGroups = new ConcurrentHashMap<>();
        this.watcherTaskId = -1;
    }

    public void enable() {
        File folder = groupsFolder();
        generateDefaultOpGroup(folder);
        loadGroups(folder);
        watcher = new FolderWatcher(folder);
        if (plugin.cfg().groups().useVault()) {
            hookVault();
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        startWatcherTask();
    }

    public void disable() {
        HandlerList.unregisterAll(this);
        stopWatcherTask();
        watcher = null;
        vault = null;
        groups.clear();
        primaryGroups.clear();
    }

    public void reload() {
        File folder = groupsFolder();
        loadGroups(folder);
        watcher = new FolderWatcher(folder);
        primaryGroups.clear();
        stopWatcherTask();
        startWatcherTask();
        if (plugin.cfg().groups().useVault() && vault == null) {
            hookVault();
        }
    }

    public Optional<GlossGroup> groupFor(Player player) {
        if (player == null) {
            return Optional.empty();
        }
        if (player.isOp()) {
            GlossGroup opGroup = groups.get(OP_GROUP_NAME);
            if (opGroup != null) {
                return Optional.of(opGroup);
            }
        }
        String primary = primaryGroupName(player);
        return primary == null ? Optional.empty() : Optional.ofNullable(groups.get(primary));
    }

    public List<String> groupNames() {
        List<String> names = new ArrayList<>(groups.keySet());
        Collections.sort(names);
        return names;
    }

    public GlossGroup group(String name) {
        return name == null ? null : groups.get(name.toLowerCase(Locale.ROOT));
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
        if (cached != null && now - cached.at() < PRIMARY_GROUP_TTL_MS) {
            return cached.name();
        }
        String resolved = hook.primaryGroup(player);
        String normalized = resolved == null ? null : resolved.toLowerCase(Locale.ROOT);
        primaryGroups.put(uuid, new CachedPrimaryGroup(normalized, now));
        return normalized;
    }

    private void startWatcherTask() {
        watcherTaskId = plugin.scheduler().ar(this::pollWatcher, plugin.cfg().hotload().watchIntervalTicks());
    }

    private void stopWatcherTask() {
        if (watcherTaskId != -1) {
            plugin.scheduler().car(watcherTaskId);
            watcherTaskId = -1;
        }
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

    private void generateDefaultOpGroup(File folder) {
        File[] files = folder.listFiles((directory, name) -> name.endsWith(GROUP_FILE_SUFFIX));
        if (files != null && files.length > 0) {
            return;
        }
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set(KEY_TABLIST_NAME, DEFAULT_OP_TABLIST_NAME);
        configuration.set(KEY_DEFAULT_BOARD, "");
        File file = new File(folder, OP_GROUP_NAME + GROUP_FILE_SUFFIX);
        try {
            configuration.save(file);
        } catch (IOException failure) {
            Gloss.warn("Unable to write default _op group file: " + failure.getMessage());
        }
    }

    private void loadGroups(File folder) {
        Map<String, GlossGroup> loaded = new HashMap<>();
        File[] files = folder.listFiles((directory, name) -> name.endsWith(GROUP_FILE_SUFFIX));
        if (files != null) {
            for (File file : files) {
                GlossGroup group = readGroupFile(file);
                if (group != null) {
                    loaded.put(group.name(), group);
                }
            }
        }
        groups.putAll(loaded);
        groups.keySet().retainAll(loaded.keySet());
    }

    private GlossGroup readGroupFile(File file) {
        String name = file.getName();
        String groupName = name.substring(0, name.length() - GROUP_FILE_SUFFIX.length()).toLowerCase(Locale.ROOT);
        if (groupName.isEmpty()) {
            return null;
        }
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(file);
        return new GlossGroup(
            groupName,
            configuration.getString(KEY_TABLIST_NAME, ""),
            configuration.getString(KEY_DEFAULT_BOARD, "")
        );
    }

    private void pollWatcher() {
        FolderWatcher activeWatcher = watcher;
        if (activeWatcher == null || !activeWatcher.checkModified()) {
            return;
        }
        loadGroups(groupsFolder());
        primaryGroups.clear();
        plugin.boards().reselectAll();
    }

    private File groupsFolder() {
        File folder = new File(plugin.getDataFolder(), "groups");
        if (!folder.exists() && !folder.mkdirs()) {
            Gloss.warn("Unable to create groups folder at " + folder.getAbsolutePath());
        }
        return folder;
    }

    private record CachedPrimaryGroup(String name, long at) {
    }
}
