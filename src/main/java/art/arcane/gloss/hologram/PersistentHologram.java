package art.arcane.gloss.hologram;

import art.arcane.gloss.api.Hologram;
import art.arcane.gloss.doc.DocumentEnvelope;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;

final class PersistentHologram implements Hologram {
    private static final double POSITION_EPSILON_SQUARED = 1.0E-6D;

    private final HologramService service;
    private final String id;
    private final String animatorGroup;
    private final Object linesLock;
    private final Map<UUID, TextDisplay> viewerDisplays;
    private final Map<UUID, String> viewerRendered;
    private final Set<UUID> viewerSpawning;
    private final AtomicBoolean sharedSpawning;
    private volatile List<String> lines;
    private volatile String worldName;
    private volatile double x;
    private volatile double y;
    private volatile double z;
    private volatile long revision;
    private volatile Location appliedAnchor;
    private volatile TextDisplay sharedDisplay;
    private volatile String sharedRendered;

    PersistentHologram(HologramService service, String id, Location location) {
        this.service = service;
        this.id = id;
        this.animatorGroup = "holo:" + id;
        this.linesLock = new Object();
        this.lines = List.of();
        this.viewerDisplays = new ConcurrentHashMap<>();
        this.viewerRendered = new ConcurrentHashMap<>();
        this.viewerSpawning = ConcurrentHashMap.newKeySet();
        this.sharedSpawning = new AtomicBoolean();
        this.revision = 0L;
        World world = Objects.requireNonNull(location.getWorld(), "Hologram location requires a loaded world.");
        this.worldName = world.getName();
        this.x = location.getX();
        this.y = location.getY();
        this.z = location.getZ();
    }

    PersistentHologram(HologramService service, String id, HologramDoc doc) {
        this.service = service;
        this.id = id;
        this.animatorGroup = "holo:" + id;
        this.linesLock = new Object();
        this.lines = List.of();
        this.viewerDisplays = new ConcurrentHashMap<>();
        this.viewerRendered = new ConcurrentHashMap<>();
        this.viewerSpawning = ConcurrentHashMap.newKeySet();
        this.sharedSpawning = new AtomicBoolean();
        this.revision = 0L;
        apply(doc);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public Location location() {
        World world = Bukkit.getWorld(worldName);
        return world == null ? null : new Location(world, x, y, z);
    }

    @Override
    public void teleport(Location location) {
        Objects.requireNonNull(location, "Hologram teleport requires a location.");
        World world = Objects.requireNonNull(location.getWorld(), "Hologram teleport requires a loaded world.");
        worldName = world.getName();
        x = location.getX();
        y = location.getY();
        z = location.getZ();
        service.persist(this);
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

        service.persist(this);
    }

    @Override
    public void setLine(int index, String line) {
        Objects.requireNonNull(line, "Hologram line may not be null.");
        if (!replaceLine(index, line)) {
            return;
        }

        service.persist(this);
    }

    @Override
    public void setLines(List<String> lines) {
        Objects.requireNonNull(lines, "Hologram lines may not be null.");
        List<String> next = List.copyOf(lines);
        synchronized (linesLock) {
            this.lines = next;
        }

        service.persist(this);
    }

    @Override
    public void removeLine(int index) {
        if (!dropLine(index)) {
            return;
        }

        service.persist(this);
    }

    @Override
    public void clearLines() {
        synchronized (linesLock) {
            lines = List.of();
        }

        service.persist(this);
    }

    void apply(HologramDoc doc) {
        HologramDoc.Anchor anchor = doc.anchor();
        worldName = anchor.world();
        x = anchor.position().getX();
        y = anchor.position().getY();
        z = anchor.position().getZ();
        revision = doc.revision();
        synchronized (linesLock) {
            lines = doc.lines();
        }
    }

    HologramDoc toDoc(long revision) {
        return new HologramDoc(HologramDoc.CURRENT_SCHEMA_VERSION, revision,
            new HologramDoc.Anchor(worldName, new Vector(x, y, z)), lines);
    }

    long nextRevision() {
        long next = revision >= DocumentEnvelope.MAX_SAFE_REVISION
            ? DocumentEnvelope.MAX_SAFE_REVISION
            : revision + 1L;
        revision = next;
        return next;
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

    void update() {
        List<String> snapshot = lines;
        World world = Bukkit.getWorld(worldName);
        if (world == null || snapshot.isEmpty()) {
            despawnAll();
            return;
        }

        Location anchor = new Location(world, x, y, z);
        reconcilePosition(world, anchor);
        if (HologramMath.requiresViewerRendering(snapshot) && service.perViewerPlaceholders()) {
            despawnShared();
            updatePerViewer(world, anchor, snapshot);
        } else {
            despawnViewers();
            updateShared(world, anchor, snapshot);
        }
    }

    void despawnAll() {
        service.animator().removeGroup(animatorGroup);
        despawnShared();
        despawnViewers();
    }

    private void reconcilePosition(World world, Location anchor) {
        Location applied = appliedAnchor;
        if (applied != null && applied.getWorld() == world && applied.distanceSquared(anchor) < POSITION_EPSILON_SQUARED) {
            return;
        }

        appliedAnchor = anchor.clone();
        TextDisplay shared = sharedDisplay;
        if (shared != null && shared.isValid()) {
            service.plugin().scheduler().teleport(shared, anchor.clone());
        }

        for (TextDisplay display : viewerDisplays.values()) {
            if (display.isValid()) {
                service.plugin().scheduler().teleport(display, anchor.clone());
            }
        }
    }

    private void updateShared(World world, Location anchor, List<String> snapshot) {
        TextDisplay display = sharedDisplay;
        if (display == null || !display.isValid()) {
            sharedDisplay = null;
            sharedRendered = null;
            service.animator().remove(animatorGroup, HologramAnimator.SHARED_SUB);
            if (display != null) {
                service.despawnEntity(display, anchor);
            }

            spawnShared(world, anchor, snapshot);
            return;
        }

        AnimationTemplate template = service.animator().compileTemplate(snapshot,
            line -> service.plugin().text().renderStatic(line));
        if (template != null) {
            sharedRendered = null;
            service.animator().publish(animatorGroup, HologramAnimator.SHARED_SUB,
                new HologramAnimator.Target(display.getEntityId(), template, captureViewers(world)));
            return;
        }

        service.animator().remove(animatorGroup, HologramAnimator.SHARED_SUB);
        String rendered = service.renderStaticLines(snapshot);
        if (rendered.equals(sharedRendered)) {
            return;
        }

        sharedRendered = rendered;
        service.plugin().scheduler().runEntity(display, () -> {
            if (display.isValid()) {
                display.setText(rendered);
            }
        });
    }

    private void spawnShared(World world, Location anchor, List<String> snapshot) {
        if (!isChunkLoaded(world, anchor)) {
            return;
        }
        if (!sharedSpawning.compareAndSet(false, true)) {
            return;
        }

        String rendered = service.renderStaticLines(snapshot);
        boolean scheduled = service.plugin().scheduler().runAt(anchor, () -> {
            try {
                if (!service.isActive(this)) {
                    return;
                }

                Consumer<TextDisplay> configurer = spawned -> {
                    service.configureDisplay(spawned);
                    spawned.setText(rendered);
                };
                TextDisplay spawned = world.spawn(anchor, TextDisplay.class, configurer);
                if (!service.isActive(this)) {
                    service.despawnEntity(spawned, anchor);
                    return;
                }

                sharedDisplay = spawned;
                sharedRendered = rendered;
            } catch (RuntimeException failure) {
                service.plugin().getLogger().log(Level.WARNING, "Failed to spawn hologram " + id, failure);
            } finally {
                sharedSpawning.set(false);
            }
        });
        if (!scheduled) {
            sharedSpawning.set(false);
        }
    }

    private void updatePerViewer(World world, Location anchor, List<String> snapshot) {
        double range = service.viewRange();
        double rangeSquared = range * range;
        Set<UUID> active = new HashSet<>();
        for (Player player : world.getPlayers()) {
            Location playerLocation = player.getLocation();
            if (playerLocation.getWorld() != world || distanceSquared(playerLocation) > rangeSquared) {
                continue;
            }

            UUID viewerId = player.getUniqueId();
            active.add(viewerId);
            TextDisplay display = viewerDisplays.get(viewerId);
            if (display == null || !display.isValid()) {
                service.animator().remove(animatorGroup, viewerId.toString());
                if (display != null) {
                    viewerDisplays.remove(viewerId);
                    viewerRendered.remove(viewerId);
                    service.despawnEntity(display, anchor);
                }

                spawnViewer(world, anchor, snapshot, player);
                continue;
            }

            refreshViewerText(player, display, snapshot);
        }

        for (Map.Entry<UUID, TextDisplay> entry : viewerDisplays.entrySet()) {
            if (active.contains(entry.getKey())) {
                continue;
            }

            service.animator().remove(animatorGroup, entry.getKey().toString());
            viewerDisplays.remove(entry.getKey());
            viewerRendered.remove(entry.getKey());
            service.despawnEntity(entry.getValue(), anchor);
        }
    }

    private void spawnViewer(World world, Location anchor, List<String> snapshot, Player player) {
        if (!isChunkLoaded(world, anchor)) {
            return;
        }

        UUID viewerId = player.getUniqueId();
        if (!viewerSpawning.add(viewerId)) {
            return;
        }

        boolean scheduled = service.plugin().scheduler().runAt(anchor, () -> {
            try {
                if (!service.isActive(this) || !player.isOnline()) {
                    return;
                }

                Consumer<TextDisplay> configurer = spawned -> {
                    service.configureDisplay(spawned);
                    DisplayVisibility.setVisibleByDefault(spawned, false);
                };
                TextDisplay spawned = world.spawn(anchor, TextDisplay.class, configurer);
                if (!service.isActive(this)) {
                    service.despawnEntity(spawned, anchor);
                    return;
                }

                if (!DisplayVisibility.canHideByDefault()) {
                    hideFromOthers(spawned, viewerId);
                }

                viewerDisplays.put(viewerId, spawned);
                showToViewer(spawned, player);
                refreshViewerText(player, spawned, snapshot);
            } catch (RuntimeException failure) {
                service.plugin().getLogger().log(Level.WARNING, "Failed to spawn viewer hologram " + id, failure);
            } finally {
                viewerSpawning.remove(viewerId);
            }
        });
        if (!scheduled) {
            viewerSpawning.remove(viewerId);
        }
    }

    private void hideFromOthers(TextDisplay display, UUID viewerId) {
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!online.getUniqueId().equals(viewerId)) {
                online.hideEntity(service.plugin(), display);
            }
        }
    }

    private void showToViewer(TextDisplay display, Player player) {
        service.plugin().scheduler().runEntity(player, () -> {
            if (player.isOnline()) {
                player.showEntity(service.plugin(), display);
            }
        });
    }

    private void refreshViewerText(Player player, TextDisplay display, List<String> snapshot) {
        service.plugin().scheduler().runEntity(player, () -> {
            if (!player.isOnline()) {
                return;
            }

            UUID animatorSub = player.getUniqueId();
            AnimationTemplate template = service.animator().compileTemplate(snapshot,
                line -> service.plugin().text().render(player, line));
            if (template != null) {
                viewerRendered.remove(animatorSub);
                service.animator().publish(animatorGroup, animatorSub.toString(),
                    new HologramAnimator.Target(display.getEntityId(), template, List.of(player)));
                return;
            }

            service.animator().remove(animatorGroup, animatorSub.toString());
            StringBuilder builder = new StringBuilder();
            for (int index = 0; index < snapshot.size(); index++) {
                if (index > 0) {
                    builder.append('\n');
                }

                builder.append(service.plugin().text().render(player, snapshot.get(index)));
            }

            String rendered = builder.toString();
            UUID viewerId = player.getUniqueId();
            if (rendered.equals(viewerRendered.get(viewerId))) {
                return;
            }

            viewerRendered.put(viewerId, rendered);
            service.plugin().scheduler().runEntity(display, () -> {
                if (display.isValid()) {
                    display.setText(rendered);
                }
            });
        });
    }

    private void despawnShared() {
        TextDisplay display = sharedDisplay;
        if (display == null) {
            return;
        }

        service.animator().remove(animatorGroup, HologramAnimator.SHARED_SUB);
        sharedDisplay = null;
        sharedRendered = null;
        service.despawnEntity(display, location());
    }

    private void despawnViewers() {
        if (viewerDisplays.isEmpty()) {
            return;
        }

        Location anchor = location();
        for (Map.Entry<UUID, TextDisplay> entry : viewerDisplays.entrySet()) {
            service.animator().remove(animatorGroup, entry.getKey().toString());
            viewerDisplays.remove(entry.getKey());
            service.despawnEntity(entry.getValue(), anchor);
        }

        viewerRendered.clear();
    }

    private List<Player> captureViewers(World world) {
        double range = service.viewRange();
        double rangeSquared = range * range;
        List<Player> viewers = new ArrayList<>();
        for (Player player : world.getPlayers()) {
            Location playerLocation = player.getLocation();
            if (playerLocation.getWorld() == world && distanceSquared(playerLocation) <= rangeSquared) {
                viewers.add(player);
            }
        }

        return List.copyOf(viewers);
    }

    private double distanceSquared(Location playerLocation) {
        double dx = playerLocation.getX() - x;
        double dy = playerLocation.getY() - y;
        double dz = playerLocation.getZ() - z;
        return dx * dx + dy * dy + dz * dz;
    }

    private static boolean isChunkLoaded(World world, Location anchor) {
        return world.isChunkLoaded(anchor.getBlockX() >> 4, anchor.getBlockZ() >> 4);
    }
}
