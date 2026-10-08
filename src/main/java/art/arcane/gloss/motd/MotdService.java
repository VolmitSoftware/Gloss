package art.arcane.gloss.motd;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.condition.GlossConditionScope;
import art.arcane.gloss.doc.DocumentDelta;
import art.arcane.gloss.doc.DocumentRegistry;
import art.arcane.gloss.doc.GlossDocument;
import art.arcane.gloss.doc.RegistryOwner;
import art.arcane.gloss.doc.ShippedDefaults;
import art.arcane.gloss.doc.ShippedDocumentCatalog;
import art.arcane.gloss.expr.ExprScope;
import art.arcane.gloss.service.PaperBridges;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerListPingEvent;
import org.bukkit.util.CachedServerIcon;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.random.RandomGenerator;

public final class MotdService implements RegistryOwner {
    private final Gloss plugin;
    private final ShippedDefaults defaults;
    private final DocumentRegistry<MotdDoc> registry;
    private final AtomicLong lifecycle = new AtomicLong();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicReference<Prepared> pending = new AtomicReference<>();
    private final FaviconCache favicons;
    private volatile PingDecorator pingDecorator;
    private ServerLinksPublisher serverLinks;
    private PingListener listener;
    private int refreshTask = -1;
    private volatile boolean failureLogged;
    private volatile boolean linksDirty;
    private volatile Prepared prepared;
    private volatile Snapshot snapshot;

    public MotdService(Gloss plugin) {
        this.plugin = plugin;
        this.defaults = new ShippedDefaults(MotdDoc.KIND, plugin.getDataFolder(), ShippedDocumentCatalog.MOTD.names());
        this.registry = DocumentRegistry.singleFile(MotdDoc.KIND,
            new File(plugin.getDataFolder(), MotdDoc.KIND + ".json"), MotdDoc::parse, MotdDoc::revision);
        this.favicons = new FaviconCache(plugin.getImageAssets());
    }

    public void enable() {
        long generation = lifecycle.incrementAndGet();
        if (plugin.cfg().motd().enabled()) {
            defaults.extractMissing();
        }
        registry.reload();
        prepared = prepare(doc());
        plugin.watchdog().register(MotdDoc.KIND, () -> pollRegistry(generation));
        serverLinks = PaperBridges.load("org.bukkit.ServerLinks",
            "art.arcane.gloss.paper.PaperServerLinksBridge", ServerLinksPublisher.class).orElse(null);
        publishLinks();
        if (!plugin.cfg().motd().enabled()) {
            return;
        }
        pingDecorator = PaperBridges.load("com.destroystokyo.paper.event.server.PaperServerListPingEvent",
            "art.arcane.gloss.paper.PaperServerListPingBridge", PingDecorator.class).orElse(null);
        refreshSnapshot();
        refreshTask = plugin.scheduler().sr(this::refreshSnapshot, plugin.cfg().motd().snapshotRefreshTicks());
        listener = new PingListener();
        Bukkit.getPluginManager().registerEvents(listener, plugin);
    }

    public void disable() {
        lifecycle.incrementAndGet();
        plugin.watchdog().unregister(MotdDoc.KIND);
        if (refreshTask != -1) {
            plugin.scheduler().csr(refreshTask);
            refreshTask = -1;
        }
        registry.close();
        favicons.clear();
        pending.set(null);
        linksDirty = false;
        prepared = null;
        snapshot = null;
        if (serverLinks != null) {
            if (plugin.proxyOwnership() == null || !plugin.proxyOwnership().ownsServerLinks()) {
                serverLinks.publish(List.of());
            } else {
                serverLinks.clear();
            }
            serverLinks = null;
        }
        pingDecorator = null;
        if (listener != null) {
            HandlerList.unregisterAll(listener);
            listener = null;
        }
    }

    public void reload() {
        disable();
        failureLogged = false;
        enable();
    }

    public List<String> resetToDefault(String nameOrStar) {
        return defaults.resetToDefault(nameOrStar);
    }

    public void refreshProxyOwnership() {
        if (!FoliaScheduler.isFoliaThreading(plugin.getServer()) && Bukkit.isPrimaryThread()) {
            publishLinks();
            return;
        }
        long generation = lifecycle.get();
        if (!FoliaScheduler.runGlobal(plugin, () -> {
            if (lifecycle.get() == generation) {
                publishLinks();
            }
        })) {
            linksDirty = true;
            logFailure(new IllegalStateException("Cannot schedule server-link ownership publication"));
        }
    }

    @Override
    public Map<String, DocumentRegistry<?>> registries() {
        return Map.of("motd", registry);
    }

    private MotdDoc doc() {
        GlossDocument<MotdDoc> document = registry.get(MotdDoc.KIND);
        return document == null ? MotdDoc.DEFAULTS : document.value();
    }

    private void pollRegistry(long generation) {
        DocumentDelta delta = registry.poll();
        if (!delta.isEmpty() && registry.acknowledge(delta)) {
            Prepared next = prepare(doc());
            if (lifecycle.get() == generation) {
                pending.set(next);
            }
        }
        if (pending.get() == null && !linksDirty || lifecycle.get() != generation) {
            return;
        }
        if (!FoliaScheduler.runGlobal(plugin, () -> {
            if (lifecycle.get() != generation) {
                return;
            }
            Prepared next = pending.getAndSet(null);
            if (next != null) {
                prepared = next;
                refreshSnapshot();
            }
            publishLinks();
        })) {
            logFailure(new IllegalStateException("Cannot schedule MOTD document publication; retrying on the next scan"));
        }
    }

    private Prepared prepare(MotdDoc document) {
        Map<String, CachedServerIcon> icons = new HashMap<>();
        for (MotdDoc.MotdEntry entry : document.entries()) {
            for (String path : document.iconsFor(entry)) {
                if (!icons.containsKey(path)) {
                    CachedServerIcon icon = favicons.iconFor(path, document.revision());
                    if (icon != null) {
                        icons.put(path, icon);
                    }
                }
            }
        }
        return new Prepared(document, Map.copyOf(icons));
    }

    private void publishLinks() {
        linksDirty = false;
        ServerLinksPublisher publisher = serverLinks;
        if (publisher == null) {
            return;
        }
        if (plugin.proxyOwnership() != null && plugin.proxyOwnership().ownsServerLinks()) {
            publisher.clear();
            return;
        }
        publisher.publish(doc().enabledLinks(plugin.cfg().motd().enabled()));
    }

    private void refreshSnapshot() {
        Prepared source = prepared;
        if (source == null) {
            return;
        }
        try {
            long now = System.currentTimeMillis();
            MotdDoc document = source.document();
            source = prepare(document);
            prepared = source;
            ExprScope scope = GlossConditionScope.viewer(plugin, null);
            List<Response> responses = new ArrayList<>(document.entries().size());
            List<MotdPolicy.Candidate> candidates = new ArrayList<>(document.entries().size());
            if (document.show().matches(scope)) {
                int online = Bukkit.getOnlinePlayers().size();
                for (MotdDoc.MotdEntry entry : document.entries()) {
                    if (!entry.show().matches(scope) || !entry.select().matchesSnapshot(now, document.state(), online)) {
                        continue;
                    }
                    int index = responses.size();
                    candidates.add(new MotdPolicy.Candidate(index, entry.weight(), entry.select()));
                    List<String> sample = new ArrayList<>(entry.sample().size());
                    for (String line : entry.sample()) {
                        sample.add(plugin.text().renderStatic(line));
                    }
                    responses.add(new Response(plugin.text().renderStatic(entry.joined()), document.iconsFor(entry),
                        new PingDecorator.RenderedPing(List.copyOf(sample), number(entry.online()), number(entry.max()),
                            renderStatic(entry.version()), entry.sampleMode(), entry.counts())));
                }
            }
            snapshot = new Snapshot(document.rotation(), List.copyOf(candidates), List.copyOf(responses), source.icons());
        } catch (RuntimeException failure) {
            logFailure(failure);
        }
    }

    private void handlePing(ServerListPingEvent event) {
        if (plugin.proxyOwnership() != null && plugin.proxyOwnership().ownsMotd()) {
            return;
        }
        Snapshot current = snapshot;
        if (current == null) {
            return;
        }
        try {
            PingDecorator decorator = pingDecorator;
            MotdPolicy.Request request = decorator == null
                ? new MotdPolicy.Request(event.getHostname(), null) : decorator.request(event);
            RandomGenerator random = ThreadLocalRandom.current();
            long position = current.rotation().position(sequence, System.currentTimeMillis());
            int index = current.rotation().choose(current.candidates(), request, position, random);
            if (index < 0) {
                return;
            }
            Response response = current.responses().get(index);
            event.setMotd(response.text());
            int iconIndex = current.rotation().icon(response.icons().size(), position, random);
            if (iconIndex >= 0) {
                CachedServerIcon icon = current.icons().get(response.icons().get(iconIndex));
                if (icon != null) {
                    event.setServerIcon(icon);
                }
            }
            PingDecorator.RenderedPing extras = response.extras();
            MotdPolicy.Counts counts = extras.counts();
            if (!counts.maximumMode().equals("inherit")) {
                event.setMaxPlayers(MotdPolicy.count(counts.maximumMode(), counts.maximumValue(), event.getMaxPlayers()));
            } else if (extras.max() != null) {
                event.setMaxPlayers(extras.max());
            }
            if (decorator != null) {
                decorator.decorate(event, extras);
            }
        } catch (RuntimeException failure) {
            logFailure(failure);
        }
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
            double value = Double.parseDouble(rendered.trim());
            if (Double.isFinite(value)) {
                return (int) Math.clamp(value, 0D, Integer.MAX_VALUE);
            }
        } catch (NumberFormatException ignored) {
        }
        Gloss.warnThrottled("motd-count-" + raw, "MOTD count \"%s\" did not render to a finite number.", raw);
        return null;
    }

    private void logFailure(RuntimeException failure) {
        if (!failureLogged) {
            failureLogged = true;
            Gloss.logExceptionStack(false, failure, "MOTD update failed; retaining the previous response snapshot.");
        }
    }

    private record Prepared(MotdDoc document, Map<String, CachedServerIcon> icons) {
    }

    private record Snapshot(MotdPolicy.Rotation rotation, List<MotdPolicy.Candidate> candidates,
                            List<Response> responses, Map<String, CachedServerIcon> icons) {
    }

    private record Response(String text, List<String> icons, PingDecorator.RenderedPing extras) {
    }

    private final class PingListener implements Listener {
        @EventHandler(priority = EventPriority.LOWEST)
        public void on(ServerListPingEvent event) {
            handlePing(event);
        }
    }
}
