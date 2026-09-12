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
import org.bukkit.Bukkit;
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
    static final int SWEEP_INTERVAL_TICKS = 20;

    private static final Comparator<GlowTag> STRONGEST_FIRST =
        Comparator.comparingInt(GlowTag::priority).reversed().thenComparing(GlowTag::purpose);

    private record Key(UUID viewerId, UUID targetId) {
    }

    private static final class Entry {
        private static final int NO_ENTITY = -1;

        private final List<GlowTag> tags = new ArrayList<>(2);
        private TeamAllocator.TeamHandle handle;
        private int entityId = NO_ENTITY;
    }

    private final Gloss plugin;
    private final ConcurrentMap<Key, Entry> entries = new ConcurrentHashMap<>();
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
                SWEEP_INTERVAL_TICKS);
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
            ttlTicks <= 0L ? 0L : System.currentTimeMillis() + ttlTicks * 50L);
        Entry entry = entries.computeIfAbsent(key, ignored -> new Entry());
        synchronized (entry) {
            bindEntityId(key.viewerId(), entry, target.getEntityId());
            entry.tags.removeIf(existing -> existing.purpose().equals(tag.purpose()));
            entry.tags.add(tag);
            entry.tags.sort(STRONGEST_FIRST);
            apply(viewer, target, entry);
        }
    }

    public void untag(Player viewer, Entity target, String purpose) {
        Key key = new Key(viewer.getUniqueId(), target.getUniqueId());
        Entry entry = entries.get(key);
        if (entry == null) {
            return;
        }
        synchronized (entry) {
            entry.tags.removeIf(existing -> existing.purpose().equals(purpose));
            if (entry.tags.isEmpty()) {
                entries.remove(key, entry);
                release(entry);
                dropEntityId(key.viewerId(), entry);
                send(viewer, target, false);
                return;
            }
            apply(viewer, target, entry);
        }
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
            synchronized (entry) {
                if (!entry.tags.removeIf(tag -> tag.expired(nowMs))) {
                    continue;
                }
                if (entry.tags.isEmpty()) {
                    entries.remove(mapped.getKey(), entry);
                    clearEntry(mapped.getKey(), entry);
                    continue;
                }
                Player viewer = Bukkit.getPlayer(mapped.getKey().viewerId());
                Entity target = Bukkit.getEntity(mapped.getKey().targetId());
                if (viewer != null && target != null) {
                    apply(viewer, target, entry);
                }
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

    private void clear(Key key) {
        Entry entry = entries.remove(key);
        if (entry != null) {
            clearEntry(key, entry);
        }
    }

    private void clearEntry(Key key, Entry entry) {
        release(entry);
        dropEntityId(key.viewerId(), entry);
        Player viewer = Bukkit.getPlayer(key.viewerId());
        Entity target = Bukkit.getEntity(key.targetId());
        if (viewer != null && target != null) {
            send(viewer, target, false);
        }
    }

    private void apply(Player viewer, Entity target, Entry entry) {
        GlowTag top = entry.tags.getFirst();
        TeamAllocator.TeamStyle style = new TeamAllocator.TeamStyle("", "", top.color(),
            TeamAllocator.NameTagVisibility.ALWAYS, TeamAllocator.CollisionRule.ALWAYS);
        if (entry.handle == null) {
            entry.handle = plugin.teams().claim(viewer, PURPOSE, entryName(target), style);
        } else {
            plugin.teams().update(entry.handle, style);
        }
        send(viewer, target, true);
    }

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

    private void send(Player viewer, Entity target, boolean glowing) {
        List<EntityData<?>> metadata = new ArrayList<>(1);
        metadata.add(new EntityData<>(GlowFlags.INDEX, EntityDataTypes.BYTE,
            GlowFlags.of(target, glowing)));
        PacketUtils.send(viewer, new WrapperPlayServerEntityMetadata(target.getEntityId(), metadata));
    }

    /** Teams hold player names and entity uuids; a team entry is one or the other, never both. */
    private static String entryName(Entity target) {
        return target instanceof Player player ? player.getName() : target.getUniqueId().toString();
    }
}
