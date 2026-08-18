package art.arcane.gloss.tab;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.group.GlossGroup;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class TablistService implements Listener {
    private final Gloss plugin;
    private final Map<UUID, TabOverride> overrides;
    private final Map<UUID, String> appliedListNames;
    private final Map<UUID, HeaderFooter> appliedHeaderFooters;
    private volatile int driverTaskId;

    public TablistService(Gloss plugin) {
        this.plugin = plugin;
        this.overrides = new ConcurrentHashMap<>();
        this.appliedListNames = new ConcurrentHashMap<>();
        this.appliedHeaderFooters = new ConcurrentHashMap<>();
        this.driverTaskId = -1;
    }

    public static String substituteTokens(String raw, String playerName, String groupName) {
        if (raw == null) {
            return "";
        }
        return raw.replace("$player", playerName == null ? "" : playerName)
            .replace("$group", groupName == null ? "" : groupName);
    }

    public void enable() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        startDriver();
    }

    public void disable() {
        HandlerList.unregisterAll(this);
        stopDriver();
        overrides.clear();
        resetAppliedHeaderFooters();
        resetAppliedListNames();
    }

    public void reload() {
        stopDriver();
        GlossConfig.Tablist settings = plugin.cfg().tablist();
        if (!settings.enabled()) {
            resetAppliedHeaderFooters();
            resetAppliedListNames();
        } else {
            if (!settings.useHeaderFooters()) {
                resetAppliedHeaderFooters();
            }
            if (!settings.groupListNames()) {
                resetAppliedListNames();
            }
        }
        startDriver();
    }

    public void setTab(Player player, String header, String footer) {
        overrides.put(player.getUniqueId(), new TabOverride(header == null ? "" : header, footer == null ? "" : footer));
        pushNow(player);
    }

    public void resetTab(Player player) {
        overrides.remove(player.getUniqueId());
        pushNow(player);
    }

    @EventHandler
    public void on(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        overrides.remove(uuid);
        appliedListNames.remove(uuid);
        appliedHeaderFooters.remove(uuid);
    }

    private void startDriver() {
        GlossConfig.Tablist settings = plugin.cfg().tablist();
        if (!settings.enabled()) {
            return;
        }
        driverTaskId = plugin.scheduler().sr(this::tick, settings.updateIntervalTicks());
    }

    private void stopDriver() {
        if (driverTaskId != -1) {
            plugin.scheduler().csr(driverTaskId);
            driverTaskId = -1;
        }
    }

    private void tick() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            plugin.scheduler().runEntity(player, () -> apply(player));
        }
    }

    private void pushNow(Player player) {
        if (!plugin.cfg().tablist().enabled()) {
            return;
        }
        plugin.scheduler().runEntity(player, () -> apply(player));
    }

    private void apply(Player player) {
        if (!player.isOnline()) {
            return;
        }
        GlossConfig.Tablist settings = plugin.cfg().tablist();
        if (settings.useHeaderFooters()) {
            TabOverride override = overrides.get(player.getUniqueId());
            String header = renderSafe(player, override != null ? override.header() : settings.header());
            String footer = renderSafe(player, override != null ? override.footer() : settings.footer());
            HeaderFooter rendered = new HeaderFooter(header, footer);
            if (!rendered.equals(appliedHeaderFooters.get(player.getUniqueId()))) {
                appliedHeaderFooters.put(player.getUniqueId(), rendered);
                player.setPlayerListHeaderFooter(header, footer);
            }
        }
        applyListName(player);
    }

    private void applyListName(Player player) {
        if (!plugin.cfg().tablist().groupListNames()) {
            if (appliedListNames.remove(player.getUniqueId()) != null) {
                player.setPlayerListName(null);
            }
            return;
        }
        Optional<GlossGroup> group = plugin.groups().groupFor(player);
        String template = group.map(GlossGroup::tablistName).orElse("");
        if (template.isBlank()) {
            if (appliedListNames.remove(player.getUniqueId()) != null) {
                player.setPlayerListName(null);
            }
            return;
        }
        String substituted = substituteTokens(template, player.getName(), group.get().name());
        String rendered = renderSafe(player, substituted);
        String previous = appliedListNames.get(player.getUniqueId());
        if (rendered.equals(previous)) {
            return;
        }
        appliedListNames.put(player.getUniqueId(), rendered);
        player.setPlayerListName(rendered);
    }

    private String renderSafe(Player player, String raw) {
        String rendered = plugin.text().render(player, raw == null ? "" : raw);
        return rendered == null ? "" : rendered;
    }

    private void resetAppliedHeaderFooters() {
        for (UUID uuid : appliedHeaderFooters.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) {
                continue;
            }
            mutateOnEntityThread(player, () -> player.setPlayerListHeaderFooter("", ""));
        }
        appliedHeaderFooters.clear();
    }

    private void resetAppliedListNames() {
        for (UUID uuid : appliedListNames.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) {
                continue;
            }
            mutateOnEntityThread(player, () -> player.setPlayerListName(null));
        }
        appliedListNames.clear();
    }

    private void mutateOnEntityThread(Player player, Runnable action) {
        if (plugin.scheduler().runEntity(player, action)) {
            return;
        }
        if (FoliaScheduler.isOwnedByCurrentRegion(player)) {
            action.run();
        }
    }

    private record TabOverride(String header, String footer) {
    }

    private record HeaderFooter(String header, String footer) {
    }
}
