package art.arcane.gloss.motd;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.doc.DocumentDelta;
import art.arcane.gloss.doc.DocumentRegistry;
import art.arcane.gloss.doc.GlossDocument;
import art.arcane.gloss.doc.RegistryOwner;
import art.arcane.gloss.doc.ShippedDefaults;
import art.arcane.gloss.doc.ShippedDocumentCatalog;
import art.arcane.gloss.service.PaperBridges;
import art.arcane.gloss.text.TextPipeline;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerListPingEvent;
import org.bukkit.util.CachedServerIcon;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

public final class MotdService implements RegistryOwner {
    private final Gloss plugin;
    private final ShippedDefaults defaults;
    private final DocumentRegistry<MotdDoc> registry;
    private final AtomicLong docGeneration;
    private final FaviconCache favicons;
    private PingDecorator pingDecorator;
    private ServerLinksPublisher serverLinks;
    private PingListener listener;
    private volatile boolean failureLogged;
    private volatile MotdMemo memo;

    public MotdService(Gloss plugin) {
        this.plugin = plugin;
        this.docGeneration = new AtomicLong();
        this.defaults = new ShippedDefaults(MotdDoc.KIND, plugin.getDataFolder(),
            ShippedDocumentCatalog.MOTD.names());
        this.registry = DocumentRegistry.singleFile(MotdDoc.KIND,
            new File(plugin.getDataFolder(), MotdDoc.KIND + ".json"), MotdDoc::parse, MotdDoc::revision);
        this.favicons = new FaviconCache(plugin.getImageAssets());
    }

    /**
     * MOTD ships off, so the shipped {@code motd.json} is only written once the feature is turned
     * on. Until then {@link #doc()} falls back to {@link MotdDoc#DEFAULTS} and nothing lands on disk.
     */
    public void enable() {
        boolean enabled = plugin.cfg().motd().enabled();
        if (enabled) {
            defaults.extractMissing();
        }
        registry.reload();
        docGeneration.incrementAndGet();
        plugin.watchdog().register(MotdDoc.KIND, this::pollRegistry);
        if (!enabled) {
            return;
        }

        pingDecorator = PaperBridges.load("com.destroystokyo.paper.event.server.PaperServerListPingEvent",
            "art.arcane.gloss.paper.PaperServerListPingBridge", PingDecorator.class).orElse(null);
        serverLinks = PaperBridges.load("org.bukkit.ServerLinks",
            "art.arcane.gloss.paper.PaperServerLinksBridge", ServerLinksPublisher.class).orElse(null);
        publishLinks();
        listener = new PingListener();
        Bukkit.getPluginManager().registerEvents(listener, plugin);
    }

    public void disable() {
        plugin.watchdog().unregister(MotdDoc.KIND);
        registry.close();
        favicons.clear();
        if (serverLinks != null) {
            serverLinks.clear();
            serverLinks = null;
        }
        pingDecorator = null;
        if (listener == null) {
            return;
        }

        HandlerList.unregisterAll(listener);
        listener = null;
    }

    public void reload() {
        disable();
        failureLogged = false;
        enable();
    }

    public List<String> resetToDefault(String nameOrStar) {
        return defaults.resetToDefault(nameOrStar);
    }

    private MotdDoc doc() {
        GlossDocument<MotdDoc> document = registry.get(MotdDoc.KIND);
        return document == null ? MotdDoc.DEFAULTS : document.value();
    }

    private void pollRegistry() {
        DocumentDelta delta = registry.poll();
        if (delta.isEmpty() || !registry.acknowledge(delta)) {
            return;
        }
        docGeneration.incrementAndGet();
        publishLinks();
    }

    private void publishLinks() {
        ServerLinksPublisher publisher = serverLinks;
        if (publisher != null) {
            publisher.publish(doc().links());
        }
    }

    private void handlePing(ServerListPingEvent event) {
        if (plugin.proxyOwnership() != null && plugin.proxyOwnership().ownsMotd()) {
            return;
        }
        try {
            MotdDoc document = doc();
            if (!document.show().matches(plugin, null)) {
                return;
            }
            MotdMemo current = memo(document);
            int index = ThreadLocalRandom.current().nextInt(current.entries().size());
            String cached = current.rendered()[index];
            MotdDoc.MotdEntry entry = current.entries().get(index);
            event.setMotd(cached == null
                ? plugin.text().renderStatic(entry.joined())
                : cached);
            applyExtras(event, entry, current, index);
        } catch (Throwable failure) {
            if (!failureLogged) {
                failureLogged = true;
                Gloss.logExceptionStack(false, failure, "MOTD render failed; keeping the server default.");
            }
        }
    }

    private void applyExtras(ServerListPingEvent event, MotdDoc.MotdEntry entry, MotdMemo memo, int index) {
        CachedServerIcon icon = memo.icons()[index];
        if (icon != null) {
            event.setServerIcon(icon);
        }
        Extras extras = memo.extras()[index] == null ? render(entry) : memo.extras()[index];
        if (extras.max() != null) {
            event.setMaxPlayers(extras.max());
        }
        PingDecorator decorator = pingDecorator;
        if (decorator == null) {
            return;
        }
        decorator.decorate(event,
            new PingDecorator.RenderedPing(extras.sample(), extras.online(), extras.max(), extras.version()));
    }

    /** Everything a ping needs besides the MOTD line itself; memoised unless something varies. */
    private Extras render(MotdDoc.MotdEntry entry) {
        return new Extras(renderSample(entry), number(entry.online()), number(entry.max()),
            renderStatic(entry.version()));
    }

    /**
     * Whether an entry's sample, counts or version carry a text function, which is the one thing
     * that can produce different output on the next ping and so must not be memoised.
     */
    static boolean extrasVary(MotdDoc.MotdEntry entry) {
        if (varies(entry.online()) || varies(entry.max()) || varies(entry.version())) {
            return true;
        }
        for (String line : entry.sample()) {
            if (varies(line)) {
                return true;
            }
        }
        return false;
    }

    private static boolean varies(String raw) {
        return raw != null && (TextPipeline.classify(raw) & TextPipeline.HAS_FUNCTION) != 0;
    }

    private List<String> renderSample(MotdDoc.MotdEntry entry) {
        if (entry.sample().isEmpty()) {
            return List.of();
        }
        List<String> rendered = new ArrayList<>(entry.sample().size());
        for (String line : entry.sample()) {
            rendered.add(plugin.text().renderStatic(line));
        }
        return rendered;
    }

    private String renderStatic(String raw) {
        return raw == null ? null : plugin.text().renderStatic(raw);
    }

    private Integer number(String raw) {
        String rendered = renderStatic(raw);
        if (rendered == null || rendered.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf((int) Double.parseDouble(rendered.trim()));
        } catch (NumberFormatException notANumber) {
            Gloss.warnThrottled("motd-count-" + raw, "MOTD count \"%s\" did not render to a number.", raw);
            return null;
        }
    }

    /**
     * Entries are rendered once per document revision and emoji table. An entry carrying a text
     * function ({@code |name|}) is left out of the memo and re-rendered on every ping, so a
     * time-varying function still produces fresh output.
     */
    private MotdMemo memo(MotdDoc document) {
        long generation = docGeneration.get();
        long emojiGeneration = TextPipeline.emojiGeneration();
        MotdMemo current = memo;
        if (current != null && current.docGeneration() == generation && current.emojiGeneration() == emojiGeneration) {
            return current;
        }

        List<MotdDoc.MotdEntry> entries = document.entries();
        String[] rendered = new String[entries.size()];
        for (int index = 0; index < rendered.length; index++) {
            String joined = entries.get(index).joined();
            if ((TextPipeline.classify(joined) & TextPipeline.HAS_FUNCTION) == 0) {
                rendered[index] = plugin.text().renderStatic(joined);
            }
        }
        CachedServerIcon[] icons = new CachedServerIcon[entries.size()];
        Extras[] extras = new Extras[entries.size()];
        for (int index = 0; index < icons.length; index++) {
            MotdDoc.MotdEntry entry = entries.get(index);
            icons[index] = favicons.iconFor(entry.favicon(), generation);
            extras[index] = extrasVary(entry) ? null : render(entry);
        }
        MotdMemo built = new MotdMemo(generation, emojiGeneration, entries, rendered, icons, extras);
        memo = built;
        return built;
    }

    private record MotdMemo(long docGeneration, long emojiGeneration, List<MotdDoc.MotdEntry> entries,
                            String[] rendered, CachedServerIcon[] icons, Extras[] extras) {
    }

    /** A ping's sample, counts and version, rendered once per document revision and emoji table. */
    private record Extras(List<String> sample, Integer online, Integer max, String version) {
    }

    private final class PingListener implements Listener {
        @EventHandler(priority = EventPriority.LOWEST)
        public void on(ServerListPingEvent event) {
            handlePing(event);
        }
    }

    @Override
    public Map<String, DocumentRegistry<?>> registries() {
        return Map.of("motd", registry);
    }
}
