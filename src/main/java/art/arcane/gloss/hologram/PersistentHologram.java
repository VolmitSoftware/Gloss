package art.arcane.gloss.hologram;

import art.arcane.gloss.api.Hologram;
import art.arcane.gloss.doc.DocumentEnvelope;
import art.arcane.gloss.text.TextPipeline;
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
    private record LineSet(List<String> lines, int flags, boolean viewerDependent, long generation) {
    }

    private record SharedText(long generation, long emojiGeneration, String text) {
    }

    private record StaticSegments(long generation, long emojiGeneration, String[] segments) {
    }

    private final HologramService service;
    private final String id;
    private final String animatorGroup;
    private final Object linesLock;
    private final Map<UUID, TextDisplay> viewerDisplays;
    private final Map<UUID, String> viewerRendered;
    private final Set<UUID> viewerSpawning;
    private final AtomicBoolean sharedSpawning;
    private long lineGenerations;
    private volatile LineSet lineSet;
    private volatile String worldName;
    private volatile double x;
    private volatile double y;
    private volatile double z;
    private volatile long revision;
    private volatile Location appliedAnchor;
    private volatile TextDisplay sharedDisplay;
    private volatile String sharedRendered;
    private volatile SharedText sharedTextCache;
    private volatile StaticSegments staticSegmentsCache;

    PersistentHologram(HologramService service, String id, Location location) {
        this.service = service;
        this.id = id;
        this.animatorGroup = "holo:" + id;
        this.linesLock = new Object();
        this.lineSet = new LineSet(List.of(), 0, false, 0L);
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
        this.lineSet = new LineSet(List.of(), 0, false, 0L);
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
        return lineSet.lines();
    }

    @Override
    public void addLine(String line) {
        Objects.requireNonNull(line, "Hologram line may not be null.");
        synchronized (linesLock) {
            List<String> next = new ArrayList<>(lineSet.lines());
            next.add(line);
            publishLines(List.copyOf(next));
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
            publishLines(next);
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
            publishLines(List.of());
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
            publishLines(doc.lines());
        }
    }

    HologramDoc toDoc(long revision) {
        return new HologramDoc(HologramDoc.CURRENT_SCHEMA_VERSION, revision,
            new HologramDoc.Anchor(worldName, new Vector(x, y, z)), lineSet.lines());
    }

    long nextRevision() {
        long next = revision >= DocumentEnvelope.MAX_SAFE_REVISION
            ? DocumentEnvelope.MAX_SAFE_REVISION
            : revision + 1L;
        revision = next;
        return next;
    }

    private void publishLines(List<String> next) {
        lineGenerations++;
        boolean viewerDependent = false;
        for (String line : next) {
            if (TextPipeline.viewerDependent(line)) {
                viewerDependent = true;
                break;
            }
        }
        lineSet = new LineSet(next, HologramMath.classify(next), viewerDependent, lineGenerations);
    }

    private boolean replaceLine(int index, String line) {
        synchronized (linesLock) {
            List<String> current = lineSet.lines();
            if (index < 0 || index >= current.size()) {
                return false;
            }

            List<String> next = new ArrayList<>(current);
            next.set(index, line);
            publishLines(List.copyOf(next));
            return true;
        }
    }

    private boolean dropLine(int index) {
        synchronized (linesLock) {
            List<String> current = lineSet.lines();
            if (index < 0 || index >= current.size()) {
                return false;
            }

            List<String> next = new ArrayList<>(current);
            next.remove(index);
            publishLines(List.copyOf(next));
            return true;
        }
    }

    void update() {
        update(new HologramTick());
    }

    void update(HologramTick tick) {
        LineSet snapshot = lineSet;
        World world = Bukkit.getWorld(worldName);
        if (world == null || snapshot.lines().isEmpty()) {
            despawnAll();
            return;
        }

        Location anchor = new Location(world, x, y, z);
        reconcilePosition(world, anchor);
        List<HologramTick.Viewer> viewers = tick.viewers(world);
        if (snapshot.viewerDependent() && service.perViewerPlaceholders()) {
            despawnShared();
            updatePerViewer(world, anchor, snapshot, viewers);
        } else {
            despawnViewers();
            updateShared(world, anchor, snapshot, viewers);
        }
    }

    void despawnAll() {
        service.animator().removeGroup(animatorGroup);
        despawnShared();
        despawnViewers();
    }

    void onPlayerQuit(UUID playerId) {
        viewerRendered.remove(playerId);
        viewerSpawning.remove(playerId);
        TextDisplay display = viewerDisplays.remove(playerId);
        if (display == null) {
            return;
        }

        service.animator().remove(animatorGroup, playerId.toString());
        service.despawnEntity(display, location());
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

    private void updateShared(World world, Location anchor, LineSet snapshot, List<HologramTick.Viewer> viewers) {
        TextDisplay display = sharedDisplay;
        if (display != null && !display.isValid()) {
            sharedDisplay = null;
            sharedRendered = null;
            service.animator().remove(animatorGroup, HologramAnimator.SHARED_SUB);
            service.despawnEntity(display, anchor);
            display = null;
        }

        boolean anyInRange = anyInRange(viewers);
        if (display == null) {
            if (anyInRange) {
                spawnShared(world, anchor, snapshot);
            }

            return;
        }
        if (!anyInRange) {
            service.animator().remove(animatorGroup, HologramAnimator.SHARED_SUB);
            return;
        }
        if ((snapshot.flags() & TextPipeline.HAS_FUNCTION) != 0) {
            AnimationTemplate template = service.animator().compileTemplate(snapshot.lines(),
                line -> service.plugin().text().renderStatic(line));
            if (template != null) {
                sharedRendered = null;
                service.animator().publish(animatorGroup, HologramAnimator.SHARED_SUB,
                    new HologramAnimator.Target(display.getEntityId(), template, captureViewers(viewers)));
                return;
            }
        }

        service.animator().remove(animatorGroup, HologramAnimator.SHARED_SUB);
        String rendered = sharedText(snapshot);
        if (rendered.equals(sharedRendered)) {
            return;
        }

        sharedRendered = rendered;
        TextDisplay target = display;
        service.plugin().scheduler().runEntity(target, () -> {
            if (target.isValid()) {
                target.setText(rendered);
            }
        });
    }

    private String sharedText(LineSet snapshot) {
        long emojiGeneration = TextPipeline.emojiGeneration();
        SharedText cached = sharedTextCache;
        if (cached != null && cached.generation() == snapshot.generation()
            && cached.emojiGeneration() == emojiGeneration && !hasRegisteredFunction(snapshot)) {
            return cached.text();
        }

        String rendered = service.renderStaticLines(snapshot.lines());
        sharedTextCache = new SharedText(snapshot.generation(), emojiGeneration, rendered);
        return rendered;
    }

    private boolean hasRegisteredFunction(LineSet snapshot) {
        if ((snapshot.flags() & TextPipeline.HAS_FUNCTION) == 0) {
            return false;
        }

        TextPipeline text = service.plugin().text();
        for (String line : snapshot.lines()) {
            if (HologramMath.containsRegisteredFunction(line, text::hasFunction)) {
                return true;
            }
        }

        return false;
    }

    private void spawnShared(World world, Location anchor, LineSet snapshot) {
        if (!isChunkLoaded(world, anchor)) {
            return;
        }
        if (!sharedSpawning.compareAndSet(false, true)) {
            return;
        }

        String rendered = sharedText(snapshot);
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

    private void updatePerViewer(World world, Location anchor, LineSet snapshot, List<HologramTick.Viewer> viewers) {
        double range = service.viewRange();
        double rangeSquared = range * range;
        Set<UUID> active = null;
        for (HologramTick.Viewer viewer : viewers) {
            if (distanceSquared(viewer) > rangeSquared) {
                continue;
            }

            UUID viewerId = viewer.id();
            if (active == null) {
                active = new HashSet<>();
            }

            active.add(viewerId);
            TextDisplay display = viewerDisplays.get(viewerId);
            if (display == null || !display.isValid()) {
                service.animator().remove(animatorGroup, viewerId.toString());
                if (display != null) {
                    viewerDisplays.remove(viewerId);
                    viewerRendered.remove(viewerId);
                    service.despawnEntity(display, anchor);
                }

                spawnViewer(world, anchor, snapshot, viewer.player());
                continue;
            }

            refreshViewerText(viewer.player(), display, snapshot);
        }
        if (viewerDisplays.isEmpty()) {
            return;
        }

        for (Map.Entry<UUID, TextDisplay> entry : viewerDisplays.entrySet()) {
            if (active != null && active.contains(entry.getKey())) {
                continue;
            }

            service.animator().remove(animatorGroup, entry.getKey().toString());
            viewerDisplays.remove(entry.getKey());
            viewerRendered.remove(entry.getKey());
            service.despawnEntity(entry.getValue(), anchor);
        }
    }

    private void spawnViewer(World world, Location anchor, LineSet snapshot, Player player) {
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
                    hideFromOthers(world, spawned, viewerId);
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

    private void hideFromOthers(World world, TextDisplay display, UUID viewerId) {
        for (Player online : world.getPlayers()) {
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

    private void refreshViewerText(Player player, TextDisplay display, LineSet snapshot) {
        service.plugin().scheduler().runEntity(player, () -> {
            if (!player.isOnline()) {
                return;
            }

            UUID viewerId = player.getUniqueId();
            if ((snapshot.flags() & TextPipeline.HAS_FUNCTION) != 0) {
                AnimationTemplate template = service.animator().compileTemplate(snapshot.lines(),
                    line -> service.plugin().text().render(player, line));
                if (template != null) {
                    viewerRendered.remove(viewerId);
                    service.animator().publish(animatorGroup, viewerId.toString(),
                        new HologramAnimator.Target(display.getEntityId(), template, List.of(player)));
                    return;
                }
            }

            service.animator().remove(animatorGroup, viewerId.toString());
            String rendered = composeViewerText(player, snapshot);
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

    private String composeViewerText(Player player, LineSet snapshot) {
        String[] segments = staticSegments(snapshot);
        List<String> values = snapshot.lines();
        TextPipeline text = service.plugin().text();
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                builder.append('\n');
            }

            String cached = segments[index];
            builder.append(cached != null ? cached : text.render(player, values.get(index)));
        }

        return builder.toString();
    }

    private String[] staticSegments(LineSet snapshot) {
        long emojiGeneration = TextPipeline.emojiGeneration();
        StaticSegments cached = staticSegmentsCache;
        if (cached != null && cached.generation() == snapshot.generation()
            && cached.emojiGeneration() == emojiGeneration) {
            return cached.segments();
        }

        List<String> values = snapshot.lines();
        String[] segments = new String[values.size()];
        TextPipeline text = service.plugin().text();
        for (int index = 0; index < values.size(); index++) {
            String line = values.get(index);
            if (TextPipeline.viewerDependent(line)) {
                continue;
            }

            segments[index] = text.renderStatic(line);
        }

        staticSegmentsCache = new StaticSegments(snapshot.generation(), emojiGeneration, segments);
        return segments;
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

    private List<Player> captureViewers(List<HologramTick.Viewer> viewers) {
        double range = service.viewRange();
        double rangeSquared = range * range;
        List<Player> captured = new ArrayList<>(viewers.size());
        for (HologramTick.Viewer viewer : viewers) {
            if (distanceSquared(viewer) <= rangeSquared) {
                captured.add(viewer.player());
            }
        }

        return List.copyOf(captured);
    }

    private boolean anyInRange(List<HologramTick.Viewer> viewers) {
        double range = service.viewRange();
        double rangeSquared = range * range;
        for (HologramTick.Viewer viewer : viewers) {
            if (distanceSquared(viewer) <= rangeSquared) {
                return true;
            }
        }

        return false;
    }

    private double distanceSquared(HologramTick.Viewer viewer) {
        double dx = viewer.x() - x;
        double dy = viewer.y() - y;
        double dz = viewer.z() - z;
        return dx * dx + dy * dy + dz * dz;
    }

    private static boolean isChunkLoaded(World world, Location anchor) {
        return world.isChunkLoaded(anchor.getBlockX() >> 4, anchor.getBlockZ() >> 4);
    }
}
