package art.arcane.gloss.emoji;

import art.arcane.gloss.service.GlossTelemetry;
import art.arcane.gloss.Gloss;
import art.arcane.gloss.condition.ShowCondition;
import art.arcane.gloss.doc.DocumentDelta;
import art.arcane.gloss.doc.DocumentRegistry;
import art.arcane.gloss.doc.GlossDocument;
import art.arcane.gloss.doc.ShippedDefaults;
import art.arcane.gloss.doc.ShippedDocumentCatalog;
import art.arcane.gloss.text.TextPipeline;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.volmlib.util.scheduling.SchedulerUtils;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

public final class EmojiService implements Listener {
    static final long SHOW_REFRESH_TICKS = 10L;
    private static final String PERMISSION_PREFIX = "gloss.emoji.";

    private final Gloss plugin;
    private final File folder;
    private final ShippedDefaults defaults;
    private final DocumentRegistry<EmojiDoc> registry;
    private volatile List<EmojiEntry> entries;
    private volatile EmojiReplacer replacer;
    private volatile Map<String, String> permissionNodes;
    private volatile EmojiVisibilityCache visibility;
    private volatile boolean enabled;
    private SchedulerUtils.TaskHandle visibilityTask;

    public EmojiService(Gloss plugin) {
        this.plugin = plugin;
        this.folder = new File(plugin.getDataFolder(), EmojiDoc.KIND);
        this.defaults = new ShippedDefaults(EmojiDoc.KIND, folder, ShippedDocumentCatalog.EMOJI.names());
        this.registry = DocumentRegistry.folder(EmojiDoc.KIND, folder, EmojiDoc::parse, EmojiDoc::revision);
        this.entries = List.of();
        this.replacer = new EmojiReplacer(List.of());
        this.permissionNodes = Map.of();
        this.visibility = new EmojiVisibilityCache(List.of());
    }

    public void enable() {
        if (!plugin.cfg().emoji().enabled()) {
            return;
        }
        enabled = true;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        defaults.extractMissing();
        registry.reload();
        rebuild(registry.snapshot());
        plugin.text().setEmojiFilter(this::apply);
        plugin.text().setViewerEmojiFilter(this::applyFor);
        plugin.watchdog().register("emoji", this::pollRegistry);
    }

    public void disable() {
        enabled = false;
        HandlerList.unregisterAll(this);
        stopVisibilityRefresh();
        visibility.close();
        plugin.watchdog().unregister("emoji");
        registry.close();
        plugin.text().setViewerEmojiFilter(null);
        plugin.text().setEmojiFilter(null);
        entries = List.of();
        replacer = new EmojiReplacer(List.of());
        permissionNodes = Map.of();
        TextPipeline.publishEmojiTriggers(List.of());
        TextPipeline.publishConditionalEmojiTokens(List.of());
    }

    public void reload() {
        disable();
        enable();
    }

    public List<EmojiEntry> all() {
        return entries;
    }

    public List<String> resetToDefault(String nameOrStar) {
        return defaults.resetToDefault(nameOrStar);
    }

    public String apply(String message) {
        return counted(message, replacer.apply(message, null, show -> show.matches(plugin, null)));
    }

    public String applyFor(Player sender, String message) {
        if (sender == null) {
            return apply(message);
        }
        Predicate<ShowCondition> visible = visibilityFor(sender);
        if (!plugin.cfg().emoji().emojiSpecificPermissions()) {
            return counted(message, replacer.apply(message, null, visible));
        }

        Map<String, String> nodes = permissionNodes;
        return counted(message, replacer.apply(message,
            id -> sender.hasPermission(nodes.getOrDefault(id, PERMISSION_PREFIX + id)), visible));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        scheduleVisibilityRefresh(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        visibility.remove(event.getPlayer().getUniqueId());
    }

    private Predicate<ShowCondition> visibilityFor(Player viewer) {
        EmojiVisibilityCache cache = visibility;
        if (!cache.isDynamic()) {
            return ShowCondition::isAlwaysVisible;
        }
        if (FoliaScheduler.isOwnedByCurrentRegion(viewer)) {
            return show -> show.matches(plugin, viewer);
        }
        Map<ShowCondition, Boolean> values = cache.snapshot(viewer.getUniqueId());
        return show -> show.isAlwaysVisible() || Boolean.TRUE.equals(values.get(show));
    }

    private synchronized void startVisibilityRefresh() {
        stopVisibilityRefresh();
        if (enabled && visibility.isDynamic()) {
            visibilityTask = SchedulerUtils.scheduleSyncTask(plugin, SHOW_REFRESH_TICKS,
                this::refreshOnlineVisibility, false);
        }
    }

    private synchronized void stopVisibilityRefresh() {
        if (visibilityTask != null) {
            visibilityTask.cancel();
            visibilityTask = null;
        }
    }

    private void refreshOnlineVisibility() {
        if (!enabled || !visibility.isDynamic()) {
            return;
        }
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            scheduleVisibilityRefresh(viewer);
        }
    }

    private void scheduleVisibilityRefresh(Player viewer) {
        if (!enabled) {
            return;
        }
        EmojiVisibilityCache cache = visibility;
        UUID playerId = viewer.getUniqueId();
        EmojiVisibilityCache.Sample sample = cache.begin(playerId);
        if (sample == null) {
            return;
        }
        if (!FoliaScheduler.runEntity(plugin, viewer,
            () -> cache.capture(sample, show -> show.matches(plugin, viewer)), 0L, () -> cache.discard(playerId, sample))) {
            cache.discard(playerId, sample);
        }
    }

    private static String counted(String input, String output) {
        if (output != null && output != input && !output.equals(input)) {
            GlossTelemetry.countEmojiReplacement();
        }
        return output;
    }

    public List<String> suggestions(String prefix) {
        List<EmojiEntry> snapshot = entries;
        String needle = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>(snapshot.size());
        for (EmojiEntry entry : snapshot) {
            if (!entry.enabled()) {
                continue;
            }

            String token = entry.token();
            if (needle.isEmpty() || token.toLowerCase(Locale.ROOT).startsWith(needle)) {
                out.add(token);
            }
        }

        return out;
    }

    private void pollRegistry() {
        DocumentDelta delta = registry.poll();
        if (delta.isEmpty()) {
            return;
        }
        registry.apply(delta, () -> rebuild(registry.snapshot(delta)));
    }

    private void rebuild(Map<String, GlossDocument<EmojiDoc>> documents) {
        List<EmojiEntry> loaded = new ArrayList<>(documents.size());
        for (GlossDocument<EmojiDoc> document : documents.values()) {
            EmojiDoc doc = document.value();
            loaded.add(new EmojiEntry(document.id(), doc.trigger(), UnicodeText.parse(doc.emoji()), doc.enabled(), doc.show()));
        }

        loaded.sort(Comparator.comparing(EmojiEntry::id));
        entries = List.copyOf(loaded);
        replacer = new EmojiReplacer(entries);

        Map<String, String> nodes = new HashMap<>(loaded.size() * 2);
        List<String> triggers = new ArrayList<>();
        for (EmojiEntry entry : entries) {
            nodes.put(entry.id(), PERMISSION_PREFIX + entry.id());
            if (entry.enabled() && entry.hasTrigger()) {
                triggers.add(entry.trigger());
            }
        }
        permissionNodes = Map.copyOf(nodes);
        TextPipeline.publishEmojiTriggers(triggers);
        List<String> conditionalTokens = new ArrayList<>();
        Set<ShowCondition> dynamicConditions = new HashSet<>();
        for (EmojiEntry entry : entries) {
            if (entry.enabled() && entry.show().isDynamic()) {
                dynamicConditions.add(entry.show());
            }
        }
        EmojiVisibilityCache previous = visibility;
        visibility = new EmojiVisibilityCache(dynamicConditions);
        previous.close();
        startVisibilityRefresh();
        boolean hasConditional = !dynamicConditions.isEmpty();
        if (hasConditional) {
            for (EmojiEntry entry : entries) {
                if (entry.enabled() && (entry.show().isAlwaysVisible() || entry.show().isDynamic())) {
                    conditionalTokens.add(entry.token());
                    if (entry.hasTrigger()) {
                        conditionalTokens.add(entry.trigger());
                    }
                }
            }
        }
        TextPipeline.publishConditionalEmojiTokens(conditionalTokens);
    }
}
