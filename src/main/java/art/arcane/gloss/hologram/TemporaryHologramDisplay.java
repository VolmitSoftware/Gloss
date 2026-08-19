package art.arcane.gloss.hologram;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.api.HologramViewers;
import art.arcane.gloss.api.TemporaryHologram;
import art.arcane.volmlib.util.math.M;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;

final class TemporaryHologramDisplay implements TemporaryHologram {
    private static final double POSITION_EPSILON_SQUARED = 1.0E-6D;

    private final HologramService service;
    private final String id;
    private final String animatorGroup;
    private final long durationMs;
    private final long startedMs;
    private final Object linesLock;
    private final Map<UUID, Boolean> appliedVisibility;
    private final AtomicBoolean destroyed;
    private final AtomicBoolean spawning;
    private final AtomicBoolean textDirty;
    private final AtomicBoolean visibilityReset;
    private final ViewerList viewerList;
    private volatile List<String> lines;
    private volatile Location position;
    private volatile Location appliedPosition;
    private volatile Supplier<Location> binder;
    private volatile TextDisplay display;
    private volatile String rendered;

    TemporaryHologramDisplay(HologramService service, String id, Location initial, long durationMs) {
        this.service = service;
        this.id = Objects.requireNonNull(id, "Temporary hologram requires an id.");
        this.animatorGroup = "temp:" + id + "#" + Integer.toHexString(System.identityHashCode(this));
        this.durationMs = durationMs;
        this.startedMs = M.ms();
        this.linesLock = new Object();
        this.lines = List.of();
        this.appliedVisibility = new ConcurrentHashMap<>();
        this.destroyed = new AtomicBoolean();
        this.spawning = new AtomicBoolean();
        this.textDirty = new AtomicBoolean(true);
        this.visibilityReset = new AtomicBoolean();
        this.viewerList = new ViewerList();
        this.position = Objects.requireNonNull(initial, "Temporary hologram requires a location.").clone();
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public Location location() {
        return position.clone();
    }

    @Override
    public void teleport(Location location) {
        position = Objects.requireNonNull(location, "Temporary hologram teleport requires a location.").clone();
    }

    @Override
    public List<String> lines() {
        return lines;
    }

    @Override
    public void addLine(String line) {
        Objects.requireNonNull(line, "Hologram line may not be null.");
        synchronized (linesLock) {
            List<String> next = new ArrayList<>(lines);
            next.add(line);
            lines = List.copyOf(next);
        }

        textDirty.set(true);
    }

    @Override
    public void setLine(int index, String line) {
        Objects.requireNonNull(line, "Hologram line may not be null.");
        if (replaceLine(index, line)) {
            textDirty.set(true);
        }
    }

    @Override
    public void setLines(List<String> lines) {
        Objects.requireNonNull(lines, "Hologram lines may not be null.");
        List<String> next = List.copyOf(lines);
        synchronized (linesLock) {
            this.lines = next;
        }

        textDirty.set(true);
    }

    @Override
    public void removeLine(int index) {
        if (dropLine(index)) {
            textDirty.set(true);
        }
    }

    @Override
    public void clearLines() {
        synchronized (linesLock) {
            lines = List.of();
        }

        textDirty.set(true);
    }

    @Override
    public void bindPosition(Supplier<Location> binder) {
        this.binder = binder;
    }

    @Override
    public long remainingMs() {
        return durationMs - (M.ms() - startedMs);
    }

    @Override
    public HologramViewers viewers() {
        return viewerList;
    }

    @Override
    public void destroy() {
        if (!destroyed.compareAndSet(false, true)) {
            return;
        }

        service.removeTemporary(this);
        service.animator().removeGroup(animatorGroup);
        TextDisplay active = display;
        display = null;
        appliedVisibility.clear();
        if (active != null) {
            service.despawnEntity(active, position);
        }
    }

    void drive(boolean enabled) {
        if (destroyed.get()) {
            return;
        }
        if (remainingMs() <= 0L) {
            destroy();
            return;
        }
        if (!enabled) {
            service.animator().removeGroup(animatorGroup);
            TextDisplay active = display;
            if (active != null) {
                display = null;
                service.despawnEntity(active, position);
            }

            return;
        }

        Supplier<Location> activeBinder = binder;
        if (activeBinder != null) {
            Location bound = safeBind(activeBinder);
            if (bound != null && bound.getWorld() != null) {
                position = bound.clone();
            }
        }

        Location anchor = position;
        World world = anchor.getWorld();
        if (world == null) {
            return;
        }

        TextDisplay active = display;
        if (active == null || !active.isValid()) {
            service.animator().removeGroup(animatorGroup);
            if (active != null) {
                display = null;
                service.despawnEntity(active, anchor);
            }

            spawn(world, anchor);
            return;
        }

        moveIfNeeded(active, anchor);
        applyText(active);
        applyVisibility(active);
    }

    private void spawn(World world, Location anchor) {
        if (!world.isChunkLoaded(anchor.getBlockX() >> 4, anchor.getBlockZ() >> 4)) {
            return;
        }
        if (!spawning.compareAndSet(false, true)) {
            return;
        }

        String next = service.renderStaticLines(lines);
        boolean whitelist = viewerList.isWhitelist();
        boolean scheduled = service.plugin().scheduler().runAt(anchor, () -> {
            try {
                if (destroyed.get()) {
                    return;
                }

                Consumer<TextDisplay> configurer = spawned -> {
                    service.configureDisplay(spawned);
                    spawned.setText(next);
                    if (whitelist) {
                        DisplayVisibility.setVisibleByDefault(spawned, false);
                    }
                };
                TextDisplay spawned = world.spawn(anchor, TextDisplay.class, configurer);
                if (destroyed.get()) {
                    service.despawnEntity(spawned, anchor);
                    return;
                }

                rendered = next;
                appliedPosition = anchor.clone();
                appliedVisibility.clear();
                display = spawned;
                applyVisibility(spawned);
            } catch (RuntimeException failure) {
                service.plugin().getLogger().log(Level.WARNING, "Failed to spawn temporary hologram " + id, failure);
            } finally {
                spawning.set(false);
            }
        });
        if (!scheduled) {
            spawning.set(false);
        }
    }

    private void moveIfNeeded(TextDisplay active, Location anchor) {
        Location applied = appliedPosition;
        if (applied != null && applied.getWorld() == anchor.getWorld() && applied.distanceSquared(anchor) < POSITION_EPSILON_SQUARED) {
            return;
        }

        appliedPosition = anchor.clone();
        service.plugin().scheduler().teleport(active, anchor.clone());
    }

    private void applyText(TextDisplay active) {
        List<String> snapshot = lines;
        World world = position.getWorld();
        if (world != null) {
            AnimationTemplate template = service.animator().compileTemplate(snapshot,
                line -> service.plugin().text().renderStatic(line));
            if (template != null) {
                service.animator().publish(animatorGroup, HologramAnimator.SHARED_SUB,
                    new HologramAnimator.Target(active.getEntityId(), template, captureViewers(world)));
                return;
            }
        }

        service.animator().removeGroup(animatorGroup);
        boolean dirty = textDirty.compareAndSet(true, false);
        if (!dirty && !containsFunctionTokens(snapshot)) {
            return;
        }

        String next = service.renderStaticLines(snapshot);
        if (next.equals(rendered)) {
            return;
        }

        rendered = next;
        service.plugin().scheduler().runEntity(active, () -> {
            if (active.isValid()) {
                active.setText(next);
            }
        });
    }

    private void applyVisibility(TextDisplay active) {
        boolean whitelist = viewerList.isWhitelist();
        Set<UUID> members = viewerList.members();
        if (visibilityReset.compareAndSet(true, false)) {
            service.plugin().scheduler().runEntity(active, () -> DisplayVisibility.setVisibleByDefault(active, !whitelist));
            appliedVisibility.clear();
            reconcileVisibility(active, whitelist, members);
            return;
        }
        if (whitelist && DisplayVisibility.canHideByDefault()) {
            reconcileWhitelist(active, members);
            return;
        }
        if (!whitelist && members.isEmpty() && appliedVisibility.isEmpty()) {
            return;
        }

        reconcileVisibility(active, whitelist, members);
    }

    private void reconcileWhitelist(TextDisplay active, Set<UUID> members) {
        for (Map.Entry<UUID, Boolean> entry : appliedVisibility.entrySet()) {
            if (members.contains(entry.getKey())) {
                continue;
            }

            appliedVisibility.remove(entry.getKey());
            if (!entry.getValue()) {
                continue;
            }

            Player watcher = Bukkit.getPlayer(entry.getKey());
            if (watcher != null) {
                dispatchVisibility(active, watcher, false);
            }
        }

        for (UUID member : members) {
            if (Boolean.TRUE.equals(appliedVisibility.get(member))) {
                continue;
            }

            Player viewer = Bukkit.getPlayer(member);
            if (viewer == null) {
                continue;
            }

            appliedVisibility.put(member, true);
            dispatchVisibility(active, viewer, true);
        }
    }

    private void reconcileVisibility(TextDisplay active, boolean whitelist, Set<UUID> members) {
        for (Player online : Bukkit.getOnlinePlayers()) {
            UUID viewerId = online.getUniqueId();
            boolean visible = whitelist ? members.contains(viewerId) : !members.contains(viewerId);
            Boolean applied = appliedVisibility.get(viewerId);
            if (applied != null && applied == visible) {
                continue;
            }

            appliedVisibility.put(viewerId, visible);
            dispatchVisibility(active, online, visible);
        }

        appliedVisibility.keySet().removeIf(viewerId -> Bukkit.getPlayer(viewerId) == null);
    }

    private void dispatchVisibility(TextDisplay active, Player player, boolean visible) {
        service.plugin().scheduler().runEntity(player, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (visible) {
                player.showEntity(service.plugin(), active);
            } else {
                player.hideEntity(service.plugin(), active);
            }
        });
    }

    private List<Player> captureViewers(World world) {
        boolean whitelist = viewerList.isWhitelist();
        Set<UUID> members = viewerList.members();
        List<Player> viewers = new ArrayList<>();
        for (Player online : world.getPlayers()) {
            boolean member = members.contains(online.getUniqueId());
            if (whitelist == member) {
                viewers.add(online);
            }
        }

        return List.copyOf(viewers);
    }

    private Location safeBind(Supplier<Location> binder) {
        try {
            return binder.get();
        } catch (RuntimeException failure) {
            Gloss.verbose("Temporary hologram binder failed for " + id + ": " + failure.getClass().getSimpleName());
            return null;
        }
    }

    private boolean replaceLine(int index, String line) {
        synchronized (linesLock) {
            if (index < 0 || index >= lines.size()) {
                return false;
            }

            List<String> next = new ArrayList<>(lines);
            next.set(index, line);
            lines = List.copyOf(next);
            return true;
        }
    }

    private boolean dropLine(int index) {
        synchronized (linesLock) {
            if (index < 0 || index >= lines.size()) {
                return false;
            }

            List<String> next = new ArrayList<>(lines);
            next.remove(index);
            lines = List.copyOf(next);
            return true;
        }
    }

    private static boolean containsFunctionTokens(List<String> snapshot) {
        for (String line : snapshot) {
            if (line.indexOf('|') >= 0) {
                return true;
            }
        }

        return false;
    }

    private final class ViewerList implements HologramViewers {
        private final Set<UUID> members = ConcurrentHashMap.newKeySet();
        private volatile boolean whitelist;

        @Override
        public void blacklist() {
            whitelist = false;
            visibilityReset.set(true);
        }

        @Override
        public void whitelist() {
            whitelist = true;
            visibilityReset.set(true);
        }

        @Override
        public void add(UUID playerId) {
            if (playerId != null) {
                members.add(playerId);
            }
        }

        @Override
        public void remove(UUID playerId) {
            if (playerId != null) {
                members.remove(playerId);
            }
        }

        @Override
        public void clear() {
            members.clear();
        }

        boolean isWhitelist() {
            return whitelist;
        }

        Set<UUID> members() {
            return members;
        }
    }
}
