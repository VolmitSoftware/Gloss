package art.arcane.gloss.tab;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.doc.DocumentDelta;
import art.arcane.gloss.doc.DocumentRegistry;
import art.arcane.gloss.doc.GlossDocument;
import art.arcane.gloss.doc.ShippedDefaults;
import art.arcane.gloss.doc.ShippedDocumentCatalog;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class TablistService implements Listener {
    private final Gloss plugin;
    private final ShippedDefaults defaults;
    private final DocumentRegistry<TablistDoc> registry;
    private final Map<UUID, TabOverride> overrides;
    private final Map<UUID, String> appliedListNames;
    private final Map<UUID, HeaderFooter> appliedHeaderFooters;
    private volatile int driverTaskId;

    public TablistService(Gloss plugin) {
        this.plugin = plugin;
        this.defaults = new ShippedDefaults(TablistDoc.KIND, plugin.getDataFolder(),
            ShippedDocumentCatalog.TABLIST.names());
        this.registry = DocumentRegistry.singleFile(TablistDoc.KIND,
            new File(plugin.getDataFolder(), TablistDoc.KIND + ".json"), TablistDoc::parse, TablistDoc::revision);
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

    public static ListNameChoice chooseListName(boolean op, String primaryGroup, Map<String, String> nameFormats) {
        if (op && nameFormats.containsKey(TablistDoc.OP_GROUP_KEY)) {
            return new ListNameChoice(nameFormats.get(TablistDoc.OP_GROUP_KEY), TablistDoc.OP_GROUP_KEY);
        }
        if (primaryGroup != null && !primaryGroup.isBlank()) {
            String groupKey = primaryGroup.trim().toLowerCase(Locale.ROOT);
            if (nameFormats.containsKey(groupKey)) {
                return new ListNameChoice(nameFormats.get(groupKey), primaryGroup);
            }
        }
        if (nameFormats.containsKey(TablistDoc.DEFAULT_GROUP_KEY)) {
            return new ListNameChoice(nameFormats.get(TablistDoc.DEFAULT_GROUP_KEY),
                primaryGroup == null ? "" : primaryGroup);
        }
        return new ListNameChoice(TablistDoc.FALLBACK_FORMAT, primaryGroup == null ? "" : primaryGroup);
    }

    public void enable() {
        defaults.extractMissing();
        registry.reload();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        plugin.watchdog().register(TablistDoc.KIND, this::pollRegistry);
        startDriver();
    }

    public void disable() {
        plugin.watchdog().unregister(TablistDoc.KIND);
        HandlerList.unregisterAll(this);
        stopDriver();
        overrides.clear();
        resetAppliedHeaderFooters();
        resetAppliedListNames();
    }

    public void reload() {
        stopDriver();
        registry.reload();
        if (!plugin.cfg().tablist().enabled()) {
            resetAppliedHeaderFooters();
            resetAppliedListNames();
        } else {
            TablistDoc doc = doc();
            if (!doc.useHeaderFooter()) {
                resetAppliedHeaderFooters();
            }
            if (!doc.groupListNames()) {
                resetAppliedListNames();
            }
        }
        startDriver();
    }

    public List<String> resetToDefault(String nameOrStar) {
        return defaults.resetToDefault(nameOrStar);
    }

    /** Players Gloss currently manages: an applied list name, header/footer, or plugin override. */
    public int managedPlayerCount() {
        Set<UUID> managed = new HashSet<>(appliedListNames.keySet());
        managed.addAll(appliedHeaderFooters.keySet());
        managed.addAll(overrides.keySet());
        return managed.size();
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

    private TablistDoc doc() {
        GlossDocument<TablistDoc> document = registry.get(TablistDoc.KIND);
        return document == null ? TablistDoc.DEFAULTS : document.value();
    }

    private void pollRegistry() {
        DocumentDelta delta = registry.poll();
        if (delta.isEmpty()) {
            return;
        }
        TablistDoc doc = doc();
        if (doc.useHeaderFooter()) {
            appliedHeaderFooters.clear();
        } else {
            resetAppliedHeaderFooters();
        }
        if (doc.groupListNames()) {
            appliedListNames.clear();
        } else {
            resetAppliedListNames();
        }
    }

    private void startDriver() {
        if (!plugin.cfg().tablist().enabled()) {
            return;
        }
        driverTaskId = plugin.scheduler().sr(this::tick, plugin.cfg().tablist().updateIntervalTicks());
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
        TablistDoc doc = doc();
        if (doc.useHeaderFooter()) {
            TabOverride override = overrides.get(player.getUniqueId());
            String header = renderSafe(player, override != null ? override.header() : doc.header());
            String footer = renderSafe(player, override != null ? override.footer() : doc.footer());
            HeaderFooter rendered = new HeaderFooter(header, footer);
            if (!rendered.equals(appliedHeaderFooters.get(player.getUniqueId()))) {
                appliedHeaderFooters.put(player.getUniqueId(), rendered);
                player.setPlayerListHeaderFooter(header, footer);
            }
        }
        applyListName(player, doc);
    }

    private void applyListName(Player player, TablistDoc doc) {
        if (!doc.groupListNames()) {
            if (appliedListNames.remove(player.getUniqueId()) != null) {
                player.setPlayerListName(null);
            }
            return;
        }
        String primaryGroup = plugin.groups().primaryGroupFor(player).orElse(null);
        ListNameChoice choice = chooseListName(player.isOp(), primaryGroup, doc.nameFormats());
        if (choice.template().isBlank()) {
            if (appliedListNames.remove(player.getUniqueId()) != null) {
                player.setPlayerListName(null);
            }
            return;
        }
        String substituted = substituteTokens(choice.template(), player.getName(), choice.groupName());
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

    public record ListNameChoice(String template, String groupName) {
    }

    private record TabOverride(String header, String footer) {
    }

    private record HeaderFooter(String header, String footer) {
    }
}
