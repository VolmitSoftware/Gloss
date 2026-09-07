package art.arcane.gloss.entity;

import art.arcane.gloss.api.TemporaryHologram;
import art.arcane.gloss.particle.ParticleText;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

final class EntityOverlayTarget {
    record Sample(EntityOverlayText.Snapshot snapshot, Location anchor, double x, double y, double z) {
    }

    static final class Render {
        final Set<UUID> whitelist = ConcurrentHashMap.newKeySet();
        TemporaryHologram display;
        ParticleText.Rendered frame;
        EntityOverlayText.Prepared prepared;
        EntityOverlayText.Snapshot snapshot;
        List<String> details = List.of();
        long renderGeneration = -1;
        long emojiGeneration = -1;
        long animationGeneration = -1;

        void hide() {
            if (display != null) {
                display.destroy();
                display = null;
            }
            frame = null;
            prepared = null;
            snapshot = null;
            details = List.of();
            whitelist.clear();
        }
    }

    final Render shared = new Render();
    final ConcurrentMap<UUID, Render> personal = new ConcurrentHashMap<>();
    final ConcurrentMap<UUID, Long> audience = new ConcurrentHashMap<>();
    final Set<UUID> personalViewers = ConcurrentHashMap.newKeySet();
    final AtomicBoolean rendering = new AtomicBoolean();
    volatile LivingEntity target;
    volatile Sample sample;
    volatile boolean dirty = true;
    volatile boolean retired;

    private final Object lifecycle = new Object();
    private final UUID targetId;

    EntityOverlayTarget(UUID targetId) {
        this.targetId = targetId;
    }

    UUID targetId() {
        return targetId;
    }

    boolean awaiting(UUID viewerId, boolean personalised) {
        return personalised ? !personal.containsKey(viewerId) : !shared.whitelist.contains(viewerId);
    }

    boolean attach(Render render, Supplier<TemporaryHologram> factory) {
        synchronized (lifecycle) {
            if (retired) {
                return false;
            }
            if (render.display == null) {
                render.display = factory.get();
            }
            return true;
        }
    }

    void publish(EntityOverlayText.Snapshot snapshot, Location anchor, double x, double y, double z) {
        Sample previous = sample;
        sample = new Sample(snapshot, anchor, x, y, z);
        if (previous == null || !previous.snapshot().equals(snapshot)) {
            dirty = true;
        }
    }

    Render retirePersonal(UUID viewerId) {
        Render stale = personal.remove(viewerId);
        if (stale != null) {
            synchronized (stale) {
                stale.hide();
            }
        }
        return stale;
    }

    void destroy() {
        synchronized (lifecycle) {
            retired = true;
            synchronized (shared) {
                shared.hide();
            }
            for (UUID viewerId : List.copyOf(personal.keySet())) {
                retirePersonal(viewerId);
            }
        }
        audience.clear();
        personalViewers.clear();
        target = null;
    }
}
