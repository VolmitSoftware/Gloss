package art.arcane.gloss.motd;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.doc.DocumentDelta;
import art.arcane.gloss.doc.DocumentRegistry;
import art.arcane.gloss.doc.GlossDocument;
import art.arcane.gloss.doc.ShippedDefaults;
import art.arcane.gloss.doc.ShippedDocumentCatalog;
import art.arcane.gloss.text.TextPipeline;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerListPingEvent;

import java.io.File;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

public final class MotdService {
    private final Gloss plugin;
    private final ShippedDefaults defaults;
    private final DocumentRegistry<MotdDoc> registry;
    private final AtomicLong docGeneration;
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

        listener = new PingListener();
        Bukkit.getPluginManager().registerEvents(listener, plugin);
    }

    public void disable() {
        plugin.watchdog().unregister(MotdDoc.KIND);
        registry.close();
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
    }

    private void handlePing(ServerListPingEvent event) {
        try {
            MotdMemo current = memo();
            int index = ThreadLocalRandom.current().nextInt(current.entries().size());
            String cached = current.rendered()[index];
            event.setMotd(cached == null
                ? plugin.text().renderStatic(current.entries().get(index).joined())
                : cached);
        } catch (Throwable failure) {
            if (!failureLogged) {
                failureLogged = true;
                Gloss.warn("MOTD render failed: " + failure.getClass().getSimpleName()
                    + (failure.getMessage() == null ? "" : ": " + failure.getMessage()));
            }
        }
    }

    /**
     * Entries are rendered once per document revision and emoji table. An entry carrying a text
     * function ({@code |name|}) is left out of the memo and re-rendered on every ping, so a
     * time-varying function still produces fresh output.
     */
    private MotdMemo memo() {
        long generation = docGeneration.get();
        long emojiGeneration = TextPipeline.emojiGeneration();
        MotdMemo current = memo;
        if (current != null && current.docGeneration() == generation && current.emojiGeneration() == emojiGeneration) {
            return current;
        }

        List<MotdDoc.MotdEntry> entries = doc().entries();
        String[] rendered = new String[entries.size()];
        for (int index = 0; index < rendered.length; index++) {
            String joined = entries.get(index).joined();
            if ((TextPipeline.classify(joined) & TextPipeline.HAS_FUNCTION) == 0) {
                rendered[index] = plugin.text().renderStatic(joined);
            }
        }
        MotdMemo built = new MotdMemo(generation, emojiGeneration, entries, rendered);
        memo = built;
        return built;
    }

    private record MotdMemo(long docGeneration, long emojiGeneration, List<MotdDoc.MotdEntry> entries,
                            String[] rendered) {
    }

    private final class PingListener implements Listener {
        @EventHandler(priority = EventPriority.LOWEST)
        public void on(ServerListPingEvent event) {
            handlePing(event);
        }
    }
}
