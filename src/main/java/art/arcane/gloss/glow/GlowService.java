package art.arcane.gloss.glow;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.service.GlossService;
import art.arcane.gloss.util.common.PacketUtils;
import art.arcane.gloss.util.common.TeamAllocator;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import org.bukkit.Location;
import org.bukkit.event.entity.EntityRemoveEvent;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Per-viewer entity outlines. A tag is a claim, not a switch: several owners may want the same
 * entity to glow for the same viewer, and the highest-priority claim is the colour that shows.
 * Clearing re-sends the entity's real flags, so an entity that was already glowing for everyone
 * keeps glowing.
 */
public final class GlowService implements GlossService, Listener {
    public static final String NAME = "glow";
    public static final String PURPOSE = "glow";

    private static final Comparator<GlowTag> STRONGEST_FIRST =
        Comparator.comparingInt(GlowTag::priority).reversed().thenComparing(GlowTag::purpose);

    private record Key(UUID viewerId, UUID targetId) {
    }

    private static final class Entry {
        private static final int NO_ENTITY = -1;

        private final Player viewer;
        private final Entity target;
        private final AtomicBoolean pending = new AtomicBoolean();
        private long revision;
        private Byte sentFlags;

        private Entry(Player viewer, Entity target) {
            this.viewer = viewer;
            this.target = target;
        }

        private final List<GlowTag> tags = new ArrayList<>(2);
        private TeamAllocator.TeamHandle handle;
        private int entityId = NO_ENTITY;
    }

    private final Gloss plugin;
    private final EntityTasks tasks;
    private final ConcurrentMap<Key, Entry> entries = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Set<UUID>> targets = new ConcurrentHashMap<>();
    private final Object[] viewerLocks = createViewerLocks();
    /**
     * The entity ids a viewer currently has tagged. {@code ENTITY_METADATA} is one of the highest
     * volume packets the server sends, and the listener runs on the Netty thread for every one of
     * them, so the question "is this pair tagged" has to be two hash lookups and the answer for a
     * viewer with nothing tagged has to be reachable without decoding the packet at all.
     */
    private final ConcurrentMap<UUID, Set<Integer>> taggedEntityIds = new ConcurrentHashMap<>();
    private final GlowMetadataListener listener = new GlowMetadataListener(this);
    private PacketListenerCommon registered;
    private int sweepTaskId = -1;

    public GlowService(Gloss plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.tasks = this::dispatchOwned;
    }

    GlowService(Gloss plugin, EntityTasks tasks) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void enable() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        if (PacketEvents.getAPI() != null && PacketEvents.getAPI().getEventManager() != null) {
            registered = PacketEvents.getAPI().getEventManager().registerListener(listener);
        }
        if (sweepTaskId == -1) {
            sweepTaskId = plugin.scheduler().sr(() -> sweep(System.currentTimeMillis()),
                plugin.cfg().modules().glow().sweepIntervalTicks());
        }
    }

    @Override
    public void disable() {
        HandlerList.unregisterAll(this);
        if (sweepTaskId != -1) {
            plugin.scheduler().csr(sweepTaskId);
            sweepTaskId = -1;
        }
        if (registered != null && PacketEvents.getAPI() != null
            && PacketEvents.getAPI().getEventManager() != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(registered);
            registered = null;
        }
        for (Key key : List.copyOf(entries.keySet())) {
            clear(key);
        }
    }

    @Override
    public void reload() {
        if (sweepTaskId != -1) {
            plugin.scheduler().csr(sweepTaskId);
            sweepTaskId = plugin.scheduler().sr(() -> sweep(System.currentTimeMillis()),
                plugin.cfg().modules().glow().sweepIntervalTicks());
        }
        if (!enabled()) {
            for (Key key : List.copyOf(entries.keySet())) {
                clear(key);
            }
        } else {
            for (Map.Entry<Key, Entry> entry : entries.entrySet()) {
                refresh(entry.getKey(), entry.getValue());
            }
        }
    }

    @Override
    public boolean reloadOnConfigChange(GlossConfig previous, GlossConfig next) {
        return !previous.modules().glow().equals(next.modules().glow());
    }

    public boolean enabled() {
        return plugin.cfg().modules().glow().enabled();
    }

    /**
     * @param ttlTicks ticks the tag lives for, or {@code 0} to keep it until it is untagged
     */
    public void tag(Player viewer, Entity target, String color, String purpose, int priority,
                    long ttlTicks) {
        Key key = new Key(viewer.getUniqueId(), target.getUniqueId());
        GlowTag tag = new GlowTag(purpose, priority, color,
            expiresAt(ttlTicks));
        if (!enabled()) {
            throw new IllegalStateException("Glow is disabled");
        }
        Entry entry;
        synchronized (viewerLock(key.viewerId())) {
            entry = admit(key, viewer, target);
            synchronized (entry) {
                entry.tags.removeIf(existing -> existing.purpose().equals(tag.purpose()));
                entry.tags.add(tag);
                entry.tags.sort(STRONGEST_FIRST);
                entry.revision++;
            }
        }
        refresh(key, entry);
    }

    public void untag(Player viewer, Entity target, String purpose) {
        Key key = new Key(viewer.getUniqueId(), target.getUniqueId());
        Entry entry;
        synchronized (viewerLock(key.viewerId())) {
            entry = entries.get(key);
            if (entry == null) {
                return;
            }
            synchronized (entry) {
                entry.tags.removeIf(existing -> existing.purpose().equals(purpose));
                if (entry.tags.isEmpty()) {
                    entries.remove(key, entry);
                    clearEntry(key, entry);
                    return;
                }
                entry.revision++;
            }
        }
        refresh(key, entry);
    }

    /** @return the tag this viewer actually sees on this entity, or null when there is none */
    public GlowTag top(UUID viewerId, UUID targetId) {
        Entry entry = entries.get(new Key(viewerId, targetId));
        if (entry == null) {
            return null;
        }
        synchronized (entry) {
            return entry.tags.isEmpty() ? null : entry.tags.getFirst();
        }
    }

    /** @return whether this viewer has anything tagged at all; the listener's first and cheapest gate */
    public boolean hasTags(UUID viewerId) {
        return taggedEntityIds.containsKey(viewerId);
    }

    public boolean tagged(UUID viewerId, int entityId) {
        Set<Integer> ids = taggedEntityIds.get(viewerId);
        return ids != null && ids.contains(entityId);
    }

    /**
     * Puts the glow bit back into a metadata packet the server sent for a tagged pair.
     *
     * @return true when the packet was changed
     */
    public boolean reassert(UUID viewerId, int entityId, List<EntityData<?>> metadata) {
        if (!tagged(viewerId, entityId)) {
            return false;
        }
        for (int index = 0; index < metadata.size(); index++) {
            EntityData<?> data = metadata.get(index);
            if (data.getIndex() != GlowFlags.INDEX || !(data.getValue() instanceof Byte flags)) {
                continue;
            }
            if ((flags & GlowFlags.GLOWING) != 0) {
                return false;
            }
            metadata.set(index, new EntityData<>(GlowFlags.INDEX, EntityDataTypes.BYTE,
                (byte) (flags | GlowFlags.GLOWING)));
            return true;
        }
        return false;
    }

    /** Drops tags whose time ran out and restores those entities' real flags. */
    void sweep(long nowMs) {
        for (Map.Entry<Key, Entry> mapped : entries.entrySet()) {
            Entry entry = mapped.getValue();
            boolean refresh = false;
            synchronized (viewerLock(mapped.getKey().viewerId())) {
                synchronized (entry) {
                    if (entries.get(mapped.getKey()) != entry) {
                        continue;
                    }
                    boolean changed = entry.tags.removeIf(tag -> tag.expired(nowMs));
                    if (entry.tags.isEmpty()) {
                        entries.remove(mapped.getKey(), entry);
                        clearEntry(mapped.getKey(), entry);
                    } else if (changed || plugin.cfg().modules().glow().viewerRange() > 0) {
                        entry.revision++;
                        refresh = true;
                    }
                }
            }
            if (refresh) {
                refresh(mapped.getKey(), entry);
            }
        }
    }

    public void forget(UUID viewerId) {
        for (Key key : List.copyOf(entries.keySet())) {
            if (key.viewerId().equals(viewerId)) {
                clear(key);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        forget(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onRemove(EntityRemoveEvent event) {
        UUID id = event.getEntity().getUniqueId();
        for (Key key : List.copyOf(entries.keySet())) {
            if (key.targetId().equals(id)) {
                clear(key);
            }
        }
    }

    private Entry admit(Key key, Player viewer, Entity target) {
        synchronized (viewerLock(key.viewerId())) {
            Set<UUID> mine = viewerTargets(key.viewerId());
            if (!mine.contains(key.targetId()) && mine.size() >= plugin.cfg().modules().glow().maxTargetsPerViewer()) {
                pruneExpired(key.viewerId(), System.currentTimeMillis());
                mine = viewerTargets(key.viewerId());
            }
            if (!mine.contains(key.targetId()) && mine.size() >= plugin.cfg().modules().glow().maxTargetsPerViewer()) {
                throw new IllegalStateException("Glow target limit exceeded for " + key.viewerId());
            }
            mine.add(key.targetId());
            return entries.computeIfAbsent(key, ignored -> new Entry(viewer, target));
        }
    }

    private static Object[] createViewerLocks() {
        Object[] locks = new Object[256];
        for (int index = 0; index < locks.length; index++) {
            locks[index] = new Object();
        }
        return locks;
    }

    private Object viewerLock(UUID viewerId) {
        return viewerLocks[viewerId.hashCode() & (viewerLocks.length - 1)];
    }

    private static long expiresAt(long ticks) {
        if (ticks <= 0) {
            return 0;
        }
        long now = System.currentTimeMillis();
        return ticks > (Long.MAX_VALUE - now) / 50 ? Long.MAX_VALUE : now + ticks * 50;
    }

    private void pruneExpired(UUID viewerId, long now) {
        Set<UUID> mine = targets.get(viewerId);
        if (mine == null) {
            return;
        }
        for (UUID targetId : List.copyOf(mine)) {
            Key key = new Key(viewerId, targetId);
            Entry entry = entries.get(key);
            if (entry == null) {
                continue;
            }
            synchronized (entry) {
                if (entry.tags.removeIf(tag -> tag.expired(now))) {
                    entry.revision++;
                }
                if (entry.tags.isEmpty() && entries.remove(key, entry)) {
                    clearEntry(key, entry);
                }
            }
        }
    }

    private void retire(Key key, Entry expected) {
        synchronized (viewerLock(key.viewerId())) {
            if (entries.remove(key, expected)) {
                synchronized (expected) {
                    clearEntry(key, expected);
                }
            }
        }
    }

    private Set<UUID> viewerTargets(UUID viewerId) {
        return targets.computeIfAbsent(viewerId, ignored -> ConcurrentHashMap.newKeySet());
    }

    private void clear(Key key) {
        synchronized (viewerLock(key.viewerId())) {
            Entry entry = entries.remove(key);
            if (entry != null) {
                synchronized (entry) {
                    clearEntry(key, entry);
                }
            }
        }
    }

    private void clearEntry(Key key, Entry entry) {
        release(entry);
        dropEntityId(key.viewerId(), entry);
        Set<UUID> mine = targets.get(key.viewerId());
        if (mine != null) {
            synchronized (viewerLock(key.viewerId())) {
                if (!entries.containsKey(key)) {
                    mine.remove(key.targetId());
                    if (mine.isEmpty()) {
                        targets.remove(key.viewerId(), mine);
                    }
                }
            }
        }
        tasks.dispatch(entry.target, () -> {
            if (!entry.target.isValid()) {
                return;
            }
            int entityId = entry.target.getEntityId();
            byte flags = GlowFlags.of(entry.target, false);
            tasks.dispatch(entry.viewer, () -> {
                if (!entries.containsKey(key) && entry.viewer.isOnline()) {
                    send(entry.viewer, entityId, flags);
                }
            }, null);
        }, null);
    }

    private void refresh(Key key, Entry entry) {
        if (!entry.pending.compareAndSet(false, true)) {
            return;
        }
        long revision;
        synchronized (entry) {
            revision = entry.revision;
        }
        Runnable complete = () -> {
            entry.pending.set(false);
            boolean repeat;
            synchronized (entry) {
                repeat = entries.get(key) == entry && entry.revision != revision;
            }
            if (repeat) {
                refresh(key, entry);
            }
        };
        Runnable retired = () -> {
            retire(key, entry);
            complete.run();
        };
        tasks.dispatch(entry.target, () -> {
            if (entries.get(key) != entry || !entry.target.isValid()) {
                retire(key, entry);
                complete.run();
                return;
            }
            Sample sample;
            try {
                sample = new Sample(entry.target.getEntityId(), entryName(entry.target),
                    GlowFlags.of(entry.target, false), plugin.cfg().modules().glow().viewerRange() > 0
                        ? entry.target.getLocation().clone() : null);
            } catch (RuntimeException failure) {
                retired.run();
                Gloss.logExceptionStackThrottled(false, "glow-capture-" + key.targetId(), failure,
                    "Failed to capture glow state for %s.", key.targetId());
                return;
            }
            tasks.dispatch(entry.viewer, () -> {
                try {
                    synchronized (viewerLock(key.viewerId())) {
                        synchronized (entry) {
                            if (entries.get(key) != entry || entry.revision != revision || !entry.viewer.isOnline()) {
                                return;
                            }
                            apply(key, entry, sample);
                        }
                    }
                } finally {
                    complete.run();
                }
            }, retired);
        }, retired);
    }

    private void apply(Key key, Entry entry, Sample sample) {
        double range = plugin.cfg().modules().glow().viewerRange();
        Location viewerAt = range > 0 ? entry.viewer.getLocation() : null;
        boolean inRange = range <= 0 || sample.location() != null && viewerAt.getWorld() != null
            && viewerAt.getWorld().equals(sample.location().getWorld())
            && viewerAt.distanceSquared(sample.location()) <= range * range;
        if (!inRange || !entry.viewer.canSee(entry.target)) {
            release(entry);
            dropEntityId(key.viewerId(), entry);
            if (entry.sentFlags != null) {
                send(entry.viewer, sample.entityId(), sample.flags());
            }
            entry.sentFlags = null;
            return;
        }
        bindEntityId(key.viewerId(), entry, sample.entityId());
        GlowTag top = entry.tags.getFirst();
        TeamAllocator.TeamStyle style = new TeamAllocator.TeamStyle("", "", top.color(),
            TeamAllocator.NameTagVisibility.ALWAYS, TeamAllocator.CollisionRule.ALWAYS);
        if (entry.handle == null) {
            entry.handle = plugin.teams().claim(entry.viewer, PURPOSE, sample.name(), style);
        } else {
            plugin.teams().update(entry.handle, style);
        }
        byte flags = (byte) (sample.flags() | GlowFlags.GLOWING);
        if (entry.sentFlags == null || entry.sentFlags != flags) {
            send(entry.viewer, sample.entityId(), flags);
            entry.sentFlags = flags;
        }
    }

    private void dispatchOwned(Entity entity, Runnable task, Runnable retired) {
        if (FoliaScheduler.isOwnedByCurrentRegion(entity)) {
            task.run();
        } else if (!FoliaScheduler.runEntity(plugin, entity, task, 0, retired) && retired != null) {
            retired.run();
        }
    }

    @FunctionalInterface
    interface EntityTasks {
        void dispatch(Entity entity, Runnable task, Runnable retired);
    }

    private record Sample(int entityId, String name, byte flags, Location location) { }

    private void bindEntityId(UUID viewerId, Entry entry, int entityId) {
        if (entry.entityId != entityId) {
            dropEntityId(viewerId, entry);
            entry.entityId = entityId;
        }
        taggedEntityIds.computeIfAbsent(viewerId, ignored -> ConcurrentHashMap.newKeySet()).add(entityId);
    }

    private void dropEntityId(UUID viewerId, Entry entry) {
        if (entry.entityId == Entry.NO_ENTITY) {
            return;
        }
        int released = entry.entityId;
        entry.entityId = Entry.NO_ENTITY;
        taggedEntityIds.computeIfPresent(viewerId, (ignored, ids) -> {
            ids.remove(released);
            return ids.isEmpty() ? null : ids;
        });
    }

    private void release(Entry entry) {
        if (entry.handle != null) {
            plugin.teams().release(entry.handle);
            entry.handle = null;
        }
    }

    private void send(Player viewer, int entityId, byte flags) {
        List<EntityData<?>> metadata = new ArrayList<>(1);
        metadata.add(new EntityData<>(GlowFlags.INDEX, EntityDataTypes.BYTE, flags));
        PacketUtils.send(viewer, new WrapperPlayServerEntityMetadata(entityId, metadata));
    }

    /** Teams hold player names and entity uuids; a team entry is one or the other, never both. */
    private static String entryName(Entity target) {
        return target instanceof Player player ? player.getName() : target.getUniqueId().toString();
    }
}
