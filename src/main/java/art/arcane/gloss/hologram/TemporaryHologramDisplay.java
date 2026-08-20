package art.arcane.gloss.hologram;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.api.HologramPresentation;
import art.arcane.gloss.api.HologramViewers;
import art.arcane.gloss.api.TemporaryHologram;
import art.arcane.gloss.text.TextPipeline;
import art.arcane.volmlib.util.math.M;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

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
    private static final int NO_TELEPORT_DURATION = -1;
    private static final int RENDERED_LINE_WIDTH = 2000;

    private record LineSet(List<String> lines, int flags, boolean rendered) {
        static LineSet of(List<String> lines) {
            return new LineSet(lines, HologramMath.classify(lines), false);
        }

        static LineSet rendered(List<String> lines) {
            return new LineSet(lines, 0, true);
        }
    }

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
    private volatile LineSet lineSet;
    private volatile Location position;
    private volatile Location appliedPosition;
    private volatile Supplier<Location> binder;
    private volatile Supplier<HologramPresentation> presentationBinder;
    private volatile TextDisplay display;
    private volatile String rendered;
    private volatile HologramPresentation appliedPresentation;
    private volatile int appliedTeleportTicks;

    TemporaryHologramDisplay(HologramService service, String id, Location initial, long durationMs) {
        this.service = service;
        this.id = Objects.requireNonNull(id, "Temporary hologram requires an id.");
        this.animatorGroup = "temp:" + id + "#" + Integer.toHexString(System.identityHashCode(this));
        this.durationMs = durationMs;
        this.startedMs = M.ms();
        this.linesLock = new Object();
        this.lineSet = new LineSet(List.of(), 0, false);
        this.appliedVisibility = new ConcurrentHashMap<>();
        this.destroyed = new AtomicBoolean();
        this.spawning = new AtomicBoolean();
        this.textDirty = new AtomicBoolean(true);
        this.visibilityReset = new AtomicBoolean();
        this.viewerList = new ViewerList();
        this.appliedTeleportTicks = NO_TELEPORT_DURATION;
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
        return lineSet.lines();
    }

    @Override
    public void addLine(String line) {
        Objects.requireNonNull(line, "Hologram line may not be null.");
        synchronized (linesLock) {
            LineSet current = lineSet;
            List<String> next = new ArrayList<>(current.lines());
            next.add(line);
            List<String> copied = List.copyOf(next);
            lineSet = current.rendered() ? LineSet.rendered(copied) : LineSet.of(copied);
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
        LineSet next = LineSet.of(List.copyOf(lines));
        synchronized (linesLock) {
            this.lineSet = next;
        }

        textDirty.set(true);
    }

    @Override
    public void setRenderedLines(List<String> lines) {
        Objects.requireNonNull(lines, "Rendered hologram lines may not be null.");
        LineSet next = LineSet.rendered(List.copyOf(lines));
        synchronized (linesLock) {
            this.lineSet = next;
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
            lineSet = new LineSet(List.of(), 0, false);
        }

        textDirty.set(true);
    }

    @Override
    public void bindPosition(Supplier<Location> binder) {
        this.binder = binder;
    }

    @Override
    public void bindPresentation(Supplier<HologramPresentation> binder) {
        this.presentationBinder = binder;
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
        drive(new HologramTick(), enabled);
    }

    void drive(HologramTick tick, boolean enabled) {
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

        HologramPresentation presentation = HologramPresentation.identity();
        Supplier<HologramPresentation> activePresentationBinder = presentationBinder;
        if (activePresentationBinder != null) {
            HologramPresentation boundPresentation = safeBindPresentation(activePresentationBinder);
            if (boundPresentation != null) {
                presentation = boundPresentation;
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

            spawn(world, anchor, presentation);
            return;
        }

        moveIfNeeded(active, anchor);
        applyPresentation(active, presentation);
        applyText(active, tick, world);
        applyVisibility(active);
    }

    void onPlayerQuit(UUID playerId) {
        appliedVisibility.remove(playerId);
    }

    private void spawn(World world, Location anchor, HologramPresentation presentation) {
        if (!world.isChunkLoaded(anchor.getBlockX() >> 4, anchor.getBlockZ() >> 4)) {
            return;
        }
        if (!spawning.compareAndSet(false, true)) {
            return;
        }

        textDirty.set(false);
        LineSet snapshot = lineSet;
        String next = renderLines(snapshot);
        boolean whitelist = viewerList.isWhitelist();
        int teleportTicks = desiredTeleportTicks();
        boolean scheduled = service.plugin().scheduler().runAt(anchor, () -> {
            try {
                if (destroyed.get()) {
                    return;
                }

                Consumer<TextDisplay> configurer = spawned -> {
                    service.configureDisplay(spawned);
                    spawned.setText(next);
                    if (snapshot.rendered()) {
                        spawned.setLineWidth(RENDERED_LINE_WIDTH);
                    }
                    applyPresentationNow(spawned, presentation, teleportTicks, false);
                    if (whitelist) {
                        DisplayVisibility.setVisibleByDefault(spawned, false);
                    }
                    if (teleportTicks > 0) {
                        DisplayMotion.applyTeleportDuration(spawned, teleportTicks);
                    }
                };
                TextDisplay spawned = world.spawn(anchor, TextDisplay.class, configurer);
                if (destroyed.get()) {
                    service.despawnEntity(spawned, anchor);
                    return;
                }

                rendered = next;
                appliedPosition = anchor;
                appliedTeleportTicks = teleportTicks;
                appliedPresentation = presentation;
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
        boolean sameWorld = applied != null && applied.getWorld() == anchor.getWorld();
        if (sameWorld && applied.distanceSquared(anchor) < POSITION_EPSILON_SQUARED) {
            return;
        }

        int teleportTicks = desiredTeleportTicks();
        if (appliedTeleportTicks != teleportTicks) {
            appliedTeleportTicks = teleportTicks;
            service.plugin().scheduler().runEntity(active,
                () -> DisplayMotion.applyTeleportDuration(active, teleportTicks));
        }

        appliedPosition = anchor;
        service.plugin().scheduler().teleport(active, anchor.clone());
    }

    private int desiredTeleportTicks() {
        if (!DisplayMotion.canInterpolate() || !service.interpolatedMotion()) {
            return 0;
        }

        return DisplayMotion.clampDuration(service.temporaryUpdateIntervalTicks());
    }

    private void applyPresentation(TextDisplay active, HologramPresentation presentation) {
        if (presentation.equals(appliedPresentation)) {
            return;
        }
        appliedPresentation = presentation;
        int interpolationTicks = desiredTeleportTicks();
        service.plugin().scheduler().runEntity(active, () -> {
            if (active.isValid()) {
                applyPresentationNow(active, presentation, interpolationTicks, true);
            }
        });
    }

    private void applyPresentationNow(TextDisplay display, HologramPresentation presentation,
                                      int interpolationTicks, boolean restartInterpolation) {
        Quaternionf rotation = new Quaternionf().rotationXYZ(
            (float) Math.toRadians(presentation.rotationXDegrees()),
            (float) Math.toRadians(presentation.rotationYDegrees()),
            (float) Math.toRadians(presentation.rotationZDegrees()));
        Transformation transformation = new Transformation(
            new Vector3f(),
            rotation,
            new Vector3f((float) presentation.scaleX(), (float) presentation.scaleY(),
                (float) presentation.scaleZ()),
            new Quaternionf());
        display.setInterpolationDuration(interpolationTicks);
        if (restartInterpolation && interpolationTicks > 0) {
            display.setInterpolationDelay(-1);
        }
        display.setTransformation(transformation);
        display.setTextOpacity((byte) Math.round(presentation.opacity() * 255.0D));
    }

    private void applyText(TextDisplay active, HologramTick tick, World world) {
        LineSet snapshot = lineSet;
        if ((snapshot.flags() & TextPipeline.HAS_FUNCTION) != 0) {
            AnimationTemplate template = service.animator().compileTemplate(snapshot.lines(),
                line -> service.plugin().text().renderStatic(line));
            if (template != null) {
                service.animator().publish(animatorGroup, HologramAnimator.SHARED_SUB,
                    new HologramAnimator.Target(active.getEntityId(), template, captureViewers(tick, world)));
                return;
            }
        }

        service.animator().removeGroup(animatorGroup);
        boolean dirty = textDirty.compareAndSet(true, false);
        if (!dirty && !hasRegisteredFunction(snapshot)) {
            return;
        }

        String next = renderLines(snapshot);
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

    private void applyVisibility(TextDisplay active) {
        boolean whitelist = viewerList.isWhitelist();
        Set<UUID> members = viewerList.members();
        if (visibilityReset.compareAndSet(true, false)) {
            service.plugin().scheduler().runEntity(active, () -> DisplayVisibility.setVisibleByDefault(active, !whitelist));
            appliedVisibility.clear();
            reconcileVisibility(active, whitelist, members);
            return;
        }
        if (whitelist) {
            if (DisplayVisibility.canHideByDefault()) {
                reconcileWhitelist(active, members);
                return;
            }

            reconcileVisibility(active, true, members);
            return;
        }
        if (members.isEmpty() && appliedVisibility.isEmpty()) {
            return;
        }

        reconcileBlacklist(active, members);
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

    private void reconcileBlacklist(TextDisplay active, Set<UUID> members) {
        for (UUID member : members) {
            if (Boolean.FALSE.equals(appliedVisibility.get(member))) {
                continue;
            }

            Player viewer = Bukkit.getPlayer(member);
            if (viewer == null) {
                continue;
            }

            appliedVisibility.put(member, false);
            dispatchVisibility(active, viewer, false);
        }

        for (Map.Entry<UUID, Boolean> entry : appliedVisibility.entrySet()) {
            if (members.contains(entry.getKey())) {
                continue;
            }

            appliedVisibility.remove(entry.getKey());
            if (entry.getValue()) {
                continue;
            }

            Player watcher = Bukkit.getPlayer(entry.getKey());
            if (watcher != null) {
                dispatchVisibility(active, watcher, true);
            }
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

    private List<Player> captureViewers(HologramTick tick, World world) {
        boolean whitelist = viewerList.isWhitelist();
        Set<UUID> members = viewerList.members();
        List<HologramTick.Viewer> candidates = tick.viewers(world);
        List<Player> viewers = new ArrayList<>(candidates.size());
        for (HologramTick.Viewer candidate : candidates) {
            if (whitelist == members.contains(candidate.id())) {
                viewers.add(candidate.player());
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

    private HologramPresentation safeBindPresentation(Supplier<HologramPresentation> binder) {
        try {
            return binder.get();
        } catch (RuntimeException failure) {
            Gloss.verbose("Temporary hologram presentation binder failed for " + id + ": "
                + failure.getClass().getSimpleName());
            return null;
        }
    }

    private String renderLines(LineSet lines) {
        return lines.rendered() ? String.join("\n", lines.lines()) : service.renderStaticLines(lines.lines());
    }

    private boolean replaceLine(int index, String line) {
        synchronized (linesLock) {
            List<String> current = lineSet.lines();
            if (index < 0 || index >= current.size()) {
                return false;
            }

            List<String> next = new ArrayList<>(current);
            next.set(index, line);
            List<String> copied = List.copyOf(next);
            lineSet = lineSet.rendered() ? LineSet.rendered(copied) : LineSet.of(copied);
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
            List<String> copied = List.copyOf(next);
            lineSet = lineSet.rendered() ? LineSet.rendered(copied) : LineSet.of(copied);
            return true;
        }
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
