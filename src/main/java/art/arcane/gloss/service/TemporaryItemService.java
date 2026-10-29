package art.arcane.gloss.service;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.api.TemporaryItem;
import art.arcane.gloss.api.TemporaryItemSpec;
import art.arcane.gloss.menu.DisplayEntityGroup;
import art.arcane.gloss.menu.DisplayEntityManager;
import art.arcane.gloss.util.common.DisplayEntity;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TemporaryItemService implements Listener, AutoCloseable {
    private static final int MAX_ITEMS = 256;

    private final Gloss plugin;
    private final Set<Presentation> active = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    public TemporaryItemService(Gloss plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public synchronized TemporaryItem create(Plugin owner, Player viewer, TemporaryItemSpec spec) {
        if (closed || !owner.isEnabled() || active.size() >= MAX_ITEMS) {
            throw new IllegalStateException("Temporary item presentations are unavailable or at capacity");
        }
        Presentation presentation = new Presentation(owner, viewer, spec);
        active.add(presentation);
        if (!FoliaScheduler.runEntity(plugin, viewer, presentation::show, 0L, presentation::destroy)) {
            presentation.destroy();
        }
        return presentation;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        for (Presentation presentation : active) {
            if (presentation.viewer.getUniqueId().equals(event.getPlayer().getUniqueId())) {
                presentation.destroy();
            }
        }
    }

    @EventHandler
    public void onDisable(PluginDisableEvent event) {
        for (Presentation presentation : active) {
            if (presentation.owner == event.getPlugin()) {
                presentation.destroy();
            }
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        for (Presentation presentation : active) {
            presentation.destroy();
        }
    }

    private final class Presentation implements TemporaryItem {
        private final Plugin owner;
        private final Player viewer;
        private final TemporaryItemSpec spec;
        private final long expiresAt;
        private final AtomicBoolean destroyed = new AtomicBoolean();
        private DisplayEntityGroup group;

        private Presentation(Plugin owner, Player viewer, TemporaryItemSpec spec) {
            this.owner = owner;
            this.viewer = viewer;
            this.spec = spec;
            expiresAt = System.nanoTime() + spec.durationMs() * 1_000_000L;
        }

        private synchronized void show() {
            if (isClosed() || !visible()) {
                destroy();
                return;
            }
            try {
                group = DisplayEntityManager.group(viewer, VisibilityGovernor.Surface.HOLOGRAM);
                DisplayEntity display = DisplayEntity.Builder.itemDisplay(spec.item(), spec.location(), spec.scale());
                UUID handle = DisplayEntityManager.add(group, display);
                DisplayEntityManager.spawn(handle, viewer);
                schedule();
            } catch (RuntimeException | LinkageError exception) {
                destroy();
                Gloss.logExceptionStack(false, exception, "Could not display a temporary item for %s", viewer.getUniqueId());
            }
        }

        private void schedule() {
            if (!FoliaScheduler.runEntity(plugin, viewer, this::tick, 5L, this::destroy)) {
                destroy();
            }
        }

        private synchronized void tick() {
            if (isClosed() || !visible()) {
                destroy();
                return;
            }
            try {
                group.refresh();
                schedule();
            } catch (RuntimeException | LinkageError exception) {
                destroy();
                Gloss.logExceptionStack(false, exception, "Could not refresh a temporary item for %s", viewer.getUniqueId());
            }
        }

        private boolean visible() {
            Location current = viewer.getLocation();
            Location target = spec.location();
            return !closed && owner.isEnabled() && viewer.isOnline() && !viewer.isDead()
                    && System.nanoTime() < expiresAt && current.getWorld().equals(target.getWorld())
                    && current.distanceSquared(target) <= spec.range() * spec.range();
        }

        @Override
        public boolean isClosed() {
            return destroyed.get();
        }

        @Override
        public synchronized void destroy() {
            if (!destroyed.compareAndSet(false, true)) {
                return;
            }
            active.remove(this);
            if (group != null) {
                DisplayEntityManager.retire(plugin, group);
                group = null;
            }
        }
    }
}

