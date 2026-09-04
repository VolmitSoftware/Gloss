package art.arcane.gloss.hologram;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.api.AnchoredHologram;
import art.arcane.gloss.doc.DocumentEnvelope;
import art.arcane.gloss.api.ParticleLayer;
import art.arcane.gloss.condition.ShowCondition;
import art.arcane.gloss.particle.ParticleFrame;
import art.arcane.gloss.particle.ParticleRect;
import art.arcane.gloss.particle.ParticleText;
import art.arcane.gloss.particle.ParticleTextLayout;
import art.arcane.gloss.text.TextPipeline;
import art.arcane.gloss.util.common.TextUtils;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

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

final class PersistentHologram implements AnchoredHologram {
    private static final double POSITION_EPSILON_SQUARED = 1.0E-6D;
    private record LineSet(List<String> lines, int flags, long generation, boolean fastRefresh) {
    }

    private record AnchorState(String worldName, double x, double y, double z, long generation) {
    }

    record TickAnchor(long generation, Location location) {
    }

    private record DependencyMemo(long lineGeneration, long animationGeneration,
                                  boolean viewerSpecific, boolean fastDynamic) {
    }

    private record AppliedAnchor(long generation, Location location) {
    }

    private record SharedText(long generation, long emojiGeneration, long renderGeneration, String text) {
    }

    private record StaticSegments(long generation, long emojiGeneration, long renderGeneration, String[] segments) {
    }

    private record SharedAnimation(long generation, long emojiGeneration, long renderGeneration,
                                   long animationGeneration, AnimationTemplate template) {
    }

    private record ViewerAnimation(long lineGeneration, long emojiGeneration, long renderGeneration,
                                   long animationGeneration, long refreshAfterMs,
                                   AnimationTemplate template) {
    }

    private final HologramService service;
    private final String id;
    private final String animatorGroup;
    private final Object linesLock;
    private final Map<UUID, String> viewerRendered;
    private final Map<UUID, ViewerAnimation> viewerAnimations;
    private final Map<UUID, Player> activeViewers;
    private final Map<UUID, Boolean> shownViewers = new ConcurrentHashMap<>();
    private final AtomicBoolean sharedSpawning;
    private long lineGenerations;
    private volatile LineSet lineSet;
    private volatile AnchorState anchorState;
    private volatile boolean seeThrough;
    private volatile double scale;
    private volatile String billboard;
    private volatile double yaw;
    private volatile double pitch;
    private volatile List<ParticleLayer> particleLayers;
    private volatile long revision;
    private volatile AppliedAnchor appliedAnchor;
    private volatile TextDisplay sharedDisplay;
    private volatile int sharedEntityId;
    private volatile boolean personalizedDisplay;
    private volatile String sharedRendered;
    private volatile SharedText sharedTextCache;
    private volatile StaticSegments staticSegmentsCache;
    private volatile SharedAnimation sharedAnimationCache;
    private volatile DependencyMemo dependencyMemo;
    private volatile ShowCondition show = ShowCondition.ALWAYS;

    PersistentHologram(HologramService service, String id, Location location) {
        this.service = service;
        this.id = id;
        this.animatorGroup = "holo:" + id;
        this.linesLock = new Object();
        this.lineSet = new LineSet(List.of(), 0, 0L, false);
        this.viewerRendered = new ConcurrentHashMap<>();
        this.viewerAnimations = new ConcurrentHashMap<>();
        this.activeViewers = new ConcurrentHashMap<>();
        this.sharedSpawning = new AtomicBoolean();
        this.revision = 0L;
        World world = Objects.requireNonNull(location.getWorld(), "Hologram location requires a loaded world.");
        this.anchorState = new AnchorState(world.getName(), location.getX(), location.getY(),
            location.getZ(), 0L);
        this.seeThrough = true;
        this.scale = HologramDoc.DEFAULT_SCALE;
        this.billboard = HologramDoc.DEFAULT_BILLBOARD;
        this.yaw = 0.0D;
        this.pitch = 0.0D;
        this.particleLayers = List.of();
    }

    PersistentHologram(HologramService service, String id, HologramDoc doc) {
        this.service = service;
        this.id = id;
        this.animatorGroup = "holo:" + id;
        this.linesLock = new Object();
        this.lineSet = new LineSet(List.of(), 0, 0L, false);
        this.viewerRendered = new ConcurrentHashMap<>();
        this.viewerAnimations = new ConcurrentHashMap<>();
        this.activeViewers = new ConcurrentHashMap<>();
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
        TickAnchor snapshot = tickAnchor();
        return snapshot == null ? null : snapshot.location().clone();
    }

    @Override
    public void teleport(Location location) {
        Objects.requireNonNull(location, "Hologram teleport requires a location.");
        World world = Objects.requireNonNull(location.getWorld(), "Hologram teleport requires a loaded world.");
        AnchorState previous = anchorState;
        anchorState = new AnchorState(world.getName(), location.getX(), location.getY(), location.getZ(),
            previous == null ? 0L : previous.generation() + 1L);
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
        service.persistentTextChanged();
    }

    @Override
    public void setLine(int index, String line) {
        Objects.requireNonNull(line, "Hologram line may not be null.");
        if (!replaceLine(index, line)) {
            return;
        }

        service.persist(this);
        service.persistentTextChanged();
    }

    @Override
    public void setLines(List<String> lines) {
        Objects.requireNonNull(lines, "Hologram lines may not be null.");
        List<String> next = List.copyOf(lines);
        synchronized (linesLock) {
            publishLines(next);
        }

        service.persist(this);
        service.persistentTextChanged();
    }

    @Override
    public void removeLine(int index) {
        if (!dropLine(index)) {
            return;
        }

        service.persist(this);
        service.persistentTextChanged();
    }

    @Override
    public void clearLines() {
        synchronized (linesLock) {
            publishLines(List.of());
        }

        service.persist(this);
        service.persistentTextChanged();
    }

    @Override
    public List<ParticleLayer> particleLayers() {
        return particleLayers;
    }

    @Override
    public void setParticleLayers(List<ParticleLayer> particleLayers) {
        this.particleLayers = ParticleLayer.copyLayers(particleLayers, "hologram");
        service.persist(this);
        service.requestDriverIntervalReconcile();
    }

    @Override
    public String billboard() {
        return billboard;
    }

    @Override
    public double scale() {
        return scale;
    }

    @Override
    public double yaw() {
        return yaw;
    }

    @Override
    public double pitch() {
        return pitch;
    }

    @Override
    public void setScale(double scale) {
        this.scale = HologramDoc.requireScale(scale);
        applyScale();
        service.persist(this);
    }

    @Override
    public void setOrientation(String billboard, double yaw, double pitch) {
        String validatedBillboard = HologramDoc.requireBillboard(billboard);
        double validatedYaw = HologramDoc.requireYaw(yaw);
        double validatedPitch = HologramDoc.requirePitch(pitch);
        this.billboard = validatedBillboard;
        this.yaw = validatedYaw;
        this.pitch = validatedPitch;
        applyOrientation();
        service.persist(this);
    }

    void apply(HologramDoc doc) {
        if (!show.equals(doc.show())) {
            despawnAll();
        }
        show = doc.show();
        HologramDoc.Anchor anchor = doc.anchor();
        AnchorState previousAnchor = anchorState;
        anchorState = new AnchorState(anchor.world(), anchor.position().getX(), anchor.position().getY(),
            anchor.position().getZ(), previousAnchor == null ? 0L : previousAnchor.generation() + 1L);
        boolean visibilityChanged = seeThrough != doc.seeThrough();
        boolean scaleChanged = scale != doc.scale();
        boolean orientationChanged = !doc.billboard().equals(billboard)
            || doc.yaw() != yaw
            || doc.pitch() != pitch;
        seeThrough = doc.seeThrough();
        scale = doc.scale();
        billboard = doc.billboard();
        yaw = doc.yaw();
        pitch = doc.pitch();
        particleLayers = doc.particleLayers();
        revision = doc.revision();
        synchronized (linesLock) {
            publishLines(doc.lines());
        }
        if (visibilityChanged) {
            applySeeThrough();
        }
        if (scaleChanged) {
            applyScale();
        }
        if (orientationChanged) {
            applyOrientation();
        }
    }

    HologramDoc toDoc(long revision) {
        AnchorState anchor = anchorState;
        return new HologramDoc(HologramDoc.CURRENT_SCHEMA_VERSION, revision,
            new HologramDoc.Anchor(anchor.worldName(), new Vector(anchor.x(), anchor.y(), anchor.z())), lineSet.lines(), seeThrough,
            scale, billboard, yaw, pitch, particleLayers, show);
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
        boolean fastRefresh = false;
        for (String line : next) {
            if (TextPipeline.requiresFastRefresh(line)) {
                fastRefresh = true;
                break;
            }
        }
        lineSet = new LineSet(next, HologramMath.classify(next), lineGenerations, fastRefresh);
        dependencyMemo = null;
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
        TickAnchor anchor = tickAnchor();
        if (anchor == null) {
            despawnAll();
            return;
        }
        update(new HologramTick(), anchor);
    }

    void update(HologramTick tick) {
        TickAnchor anchor = tickAnchor();
        if (anchor == null) {
            despawnAll();
            return;
        }
        update(tick, anchor);
    }

    void update(HologramTick tick, TickAnchor tickAnchor) {
        LineSet snapshot = lineSet;
        if (!isCurrent(tickAnchor)) {
            return;
        }
        if (snapshot.lines().isEmpty() || !show.isDynamic() && !show.isAlwaysVisible()) {
            despawnAll();
            return;
        }

        Location anchor = tickAnchor.location();
        anchor.setYaw((float) yaw);
        anchor.setPitch((float) pitch);
        World world = anchor.getWorld();
        if (world == null) {
            despawnAll();
            return;
        }
        reconcilePosition(tickAnchor, anchor);
        List<HologramTick.Viewer> viewers = tick.viewers(world, anchor, service.viewRange());
        if (show.isDynamic() || viewerSpecific(snapshot) && service.perViewerPlaceholders()) {
            updatePersonalized(world, tickAnchor, anchor, snapshot, viewers);
        } else {
            updateShared(world, tickAnchor, anchor, snapshot, viewers);
        }
        emitParticles(anchor, snapshot, viewers);
    }

    TickAnchor tickAnchor() {
        AnchorState snapshot = anchorState;
        if (snapshot == null) {
            return null;
        }
        World world = Bukkit.getWorld(snapshot.worldName());
        if (world == null) {
            return null;
        }
        return new TickAnchor(snapshot.generation(), new Location(world, snapshot.x(), snapshot.y(), snapshot.z()));
    }

    boolean isCurrent(TickAnchor anchor) {
        AnchorState current = anchorState;
        return current != null && current.generation() == anchor.generation();
    }

    private boolean viewerSpecific(LineSet snapshot) {
        return dependencies(snapshot).viewerSpecific();
    }

    private DependencyMemo dependencies(LineSet snapshot) {
        long animationGeneration = service.animationGeneration();
        DependencyMemo cached = dependencyMemo;
        if (cached != null && cached.lineGeneration() == snapshot.generation()
            && cached.animationGeneration() == animationGeneration) {
            return cached;
        }
        boolean dependent = false;
        for (String line : snapshot.lines()) {
            if (TextPipeline.viewerSpecific(line)
                || service.animationFramesViewerSpecific(line)) {
                dependent = true;
                break;
            }
        }
        DependencyMemo resolved = new DependencyMemo(snapshot.generation(), animationGeneration,
            dependent, service.hasFastDynamicAnimationContent(snapshot.lines()));
        dependencyMemo = resolved;
        return resolved;
    }

    void despawnAll() {
        if (sharedDisplay == null && activeViewers.isEmpty()) {
            return;
        }
        despawnShared();
    }

    void onPlayerQuit(UUID playerId) {
        invalidateViewer(playerId, false);
    }

    private void reconcilePosition(TickAnchor tickAnchor, Location anchor) {
        AppliedAnchor applied = appliedAnchor;
        if (applied != null && applied.location().getWorld() == anchor.getWorld()
            && applied.location().distanceSquared(anchor) < POSITION_EPSILON_SQUARED) {
            if (applied.generation() != tickAnchor.generation()) {
                appliedAnchor = new AppliedAnchor(tickAnchor.generation(), anchor.clone());
            }
            return;
        }

        appliedAnchor = new AppliedAnchor(tickAnchor.generation(), anchor.clone());
        TextDisplay shared = sharedDisplay;
        if (shared != null) {
            service.runEntity(shared, () -> {
                if (isCurrent(tickAnchor)) {
                    service.plugin().scheduler().teleport(shared, anchor.clone());
                }
            }, () -> clearShared(shared));
        }
    }

    private void updateShared(World world, TickAnchor tickAnchor, Location anchor, LineSet snapshot,
                              List<HologramTick.Viewer> viewers) {
        TextDisplay display = sharedDisplay;
        boolean anyInRange = anyInRange(viewers);
        if (display == null) {
            if (anyInRange) {
                spawnDisplay(world, tickAnchor, anchor, snapshot, false, viewers);
            }

            return;
        }
        if (!anyInRange) {
            despawnAll();
            return;
        }
        if (personalizedDisplay) {
            personalizedDisplay = false;
            clearPersonalizedState();
            sharedRendered = null;
        }
        if ((snapshot.flags() & TextPipeline.HAS_FUNCTION) != 0) {
            AnimationTemplate template = sharedAnimation(snapshot);
            if (template != null) {
                sharedRendered = null;
                TextDisplay target = display;
                List<Player> audience = captureViewers(viewers);
                service.runEntity(target, () -> service.animator().publish(animatorGroup,
                    HologramAnimator.SHARED_SUB,
                    new HologramAnimator.Target(target.getEntityId(), template, audience)),
                    () -> clearShared(target));
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
        service.runEntity(target, () -> target.setText(rendered), () -> clearShared(target));
    }

    private String sharedText(LineSet snapshot) {
        long emojiGeneration = TextPipeline.emojiGeneration();
        long renderGeneration = service.plugin().text().renderGeneration();
        SharedText cached = sharedTextCache;
        if (cached != null && cached.generation() == snapshot.generation()
            && cached.emojiGeneration() == emojiGeneration
            && cached.renderGeneration() == renderGeneration && !hasDynamicText(snapshot)) {
            return cached.text();
        }

        String rendered = service.renderStaticLines(snapshot.lines());
        sharedTextCache = new SharedText(snapshot.generation(), emojiGeneration, renderGeneration, rendered);
        return rendered;
    }

    private AnimationTemplate sharedAnimation(LineSet snapshot) {
        long emojiGeneration = TextPipeline.emojiGeneration();
        long renderGeneration = service.plugin().text().renderGeneration();
        long animationGeneration = service.animationGeneration();
        boolean dynamic = service.hasDynamicAnimationContent(snapshot.lines());
        SharedAnimation cached = sharedAnimationCache;
        if (!dynamic && cached != null && cached.generation() == snapshot.generation()
            && cached.emojiGeneration() == emojiGeneration
            && cached.renderGeneration() == renderGeneration
            && cached.animationGeneration() == animationGeneration) {
            return cached.template();
        }
        AnimationTemplate template = service.animator().compileTemplate(snapshot.lines(),
                line -> service.plugin().text().renderParticleText(null, line).text());
        if (template != null && !dynamic) {
            sharedAnimationCache = new SharedAnimation(snapshot.generation(), emojiGeneration,
                renderGeneration, animationGeneration, template);
        }
        return template;
    }

    private boolean hasDynamicText(LineSet snapshot) {
        if ((snapshot.flags() & TextPipeline.HAS_FUNCTION) == 0) {
            return false;
        }

        TextPipeline text = service.plugin().text();
        for (String line : snapshot.lines()) {
            if (TextPipeline.containsExpression(line)
                || HologramMath.containsRegisteredFunction(line, text::hasFunction)) {
                return true;
            }
        }

        return false;
    }

    boolean requiresFastRefresh() {
        return lineSet.fastRefresh() || !particleLayers.isEmpty();
    }

    private void emitParticles(Location anchor, LineSet snapshot, List<HologramTick.Viewer> viewers) {
        if (particleLayers.isEmpty() || sharedDisplay == null || viewers.isEmpty()) {
            return;
        }
        List<String> authored = snapshot.lines();
        for (HologramTick.Viewer viewer : viewers) {
            Player player = viewer.player();
            UUID playerId = viewer.id();
            service.runViewerWork(player, playerId, id + "#particles",
                () -> emitParticlesFor(player, anchor, authored));
        }
    }

    private void emitParticlesFor(Player viewer, Location anchor, List<String> authored) {
        if (!viewer.isOnline() || particleLayers.isEmpty() || !show.matches(service.plugin(), viewer)) {
            return;
        }
        String source = String.join("\n", authored);
        ParticleText.Rendered rendered = service.plugin().text().renderParticleText(viewer, source);
        ParticleFrame frame = particleFrame(viewer, anchor);
        long tick = System.currentTimeMillis() / 50L;
        for (ParticleLayer layer : particleLayers) {
            List<ParticleRect> targets = particleTargets(layer, rendered);
            if (!layer.target().scope().equals("local") && targets.isEmpty()) {
                continue;
            }
            service.plugin().particles().emit(viewer, frame, layer, targets, tick);
        }
    }

    private List<ParticleRect> particleTargets(ParticleLayer layer, ParticleText.Rendered rendered) {
        String scope = layer.target().scope();
        if (scope.equals("projection") || scope.equals("text")) {
            return List.of(ParticleTextLayout.textBounds(rendered.text(), scale));
        }
        if (scope.equals("line")) {
            List<ParticleRect> lines = ParticleTextLayout.lineBounds(rendered.text(), scale);
            int index = layer.target().line() - 1;
            return index < lines.size() ? List.of(lines.get(index)) : List.of();
        }
        if (scope.equals("span")) {
            boolean perLetter = layer.geometry().type().equals("letterBounds")
                || layer.geometry().type().equals("glyphOutline")
                || layer.geometry().type().equals("glyphFill");
            return ParticleTextLayout.bounds(rendered, layer.target().name(), scale, perLetter);
        }
        if (scope.equals("local")) {
            return List.of();
        }
        return List.of();
    }

    private ParticleFrame particleFrame(Player viewer, Location anchor) {
        Vector front;
        if (billboard.equals("FIXED")) {
            front = anchor.getDirection().multiply(-1.0D);
        } else {
            front = viewer.getEyeLocation().toVector().subtract(anchor.toVector());
            if (billboard.equals("VERTICAL")) {
                front.setY(0.0D);
            }
            if (front.lengthSquared() < 1.0E-12D) {
                front = new Vector(0.0D, 0.0D, 1.0D);
            }
            front.normalize();
        }
        Vector referenceUp = Math.abs(front.getY()) > 0.999D
            ? new Vector(0.0D, 0.0D, 1.0D)
            : new Vector(0.0D, 1.0D, 0.0D);
        Vector right = front.clone().crossProduct(referenceUp).normalize();
        Vector up = right.clone().crossProduct(front).normalize();
        return new ParticleFrame(anchor, right, up, front.clone().multiply(-1.0D));
    }

    private void spawnDisplay(World world, TickAnchor tickAnchor, Location anchor, LineSet snapshot,
                              boolean personalized, List<HologramTick.Viewer> viewers) {
        if (!isChunkLoaded(world, anchor)) {
            return;
        }
        if (!sharedSpawning.compareAndSet(false, true)) {
            return;
        }

        String rendered = personalized ? "" : sharedText(snapshot);
        boolean scheduled = service.plugin().scheduler().runAt(anchor, () -> {
            try {
                if (!service.isActive(this) || !service.plugin().cfg().holograms().enabled()
                    || !isCurrent(tickAnchor)) {
                    return;
                }

                Consumer<TextDisplay> configurer = spawned -> {
                    service.configureDisplay(spawned, seeThrough, billboardMode());
                    if (!show.isAlwaysVisible()) {
                        DisplayVisibility.setVisibleByDefault(spawned, false);
                    }
                    spawned.setTransformation(scaleTransformation());
                    spawned.setText(rendered);
                };
                TextDisplay spawned = world.spawn(anchor, TextDisplay.class, configurer);
                if (!service.isActive(this) || !service.plugin().cfg().holograms().enabled()
                    || !isCurrent(tickAnchor)) {
                    service.despawnEntity(spawned, anchor);
                    return;
                }

                sharedDisplay = spawned;
                sharedEntityId = spawned.getEntityId();
                personalizedDisplay = personalized;
                sharedRendered = rendered;
                if (!service.isActive(this) || !service.plugin().cfg().holograms().enabled()
                    || !isCurrent(tickAnchor)) {
                    clearShared(spawned);
                    service.despawnEntity(spawned, anchor);
                    return;
                }
                if (personalized) {
                    refreshPersonalizedViewers(spawned.getEntityId(), snapshot, viewers, 1L);
                } else {
                    publishSpawnedSharedAnimation(spawned, snapshot, viewers);
                }
            } catch (RuntimeException failure) {
                Gloss.logExceptionStackThrottled(false, "persistent-hologram-spawn", failure,
                    "Failed to spawn hologram %s.", id);
            } finally {
                sharedSpawning.set(false);
            }
        });
        if (!scheduled) {
            sharedSpawning.set(false);
        }
    }

    private void publishSpawnedSharedAnimation(TextDisplay spawned, LineSet snapshot,
                                               List<HologramTick.Viewer> viewers) {
        if ((snapshot.flags() & TextPipeline.HAS_FUNCTION) == 0) {
            return;
        }
        AnimationTemplate template = sharedAnimation(snapshot);
        if (template == null) {
            return;
        }

        sharedRendered = null;
        service.animator().publish(animatorGroup, HologramAnimator.SHARED_SUB,
            new HologramAnimator.Target(spawned.getEntityId(), template, captureViewers(viewers)));
    }

    private void updatePersonalized(World world, TickAnchor tickAnchor, Location anchor, LineSet snapshot,
                                    List<HologramTick.Viewer> viewers) {
        if (viewers.isEmpty()) {
            despawnAll();
            return;
        }
        TextDisplay display = sharedDisplay;
        if (display == null) {
            spawnDisplay(world, tickAnchor, anchor, snapshot, true, viewers);
            return;
        }

        long delayTicks = 0L;
        if (!personalizedDisplay) {
            personalizedDisplay = true;
            service.animator().removeGroup(animatorGroup);
            clearPersonalizedState();
            sharedRendered = "";
            TextDisplay target = display;
            service.runEntity(target, () -> target.setText(""), () -> clearShared(target));
            delayTicks = 1L;
        }
        refreshPersonalizedViewers(display.getEntityId(), snapshot, viewers, delayTicks);
    }

    private void refreshPersonalizedViewers(int entityId, LineSet snapshot,
                                            List<HologramTick.Viewer> viewers, long delayTicks) {
        Set<UUID> current = new HashSet<>(viewers.size());
        for (HologramTick.Viewer viewer : viewers) {
            current.add(viewer.id());
            activeViewers.put(viewer.id(), viewer.player());
            refreshViewerText(viewer.id(), viewer.player(), entityId, snapshot, delayTicks);
        }
        for (UUID viewerId : activeViewers.keySet()) {
            if (!current.contains(viewerId)) {
                invalidateViewer(viewerId, true);
            }
        }
    }

    void invalidateTrackingFor(Player player, boolean clearClientText) {
        if (personalizedDisplay) {
            invalidateViewer(player.getUniqueId(), clearClientText);
        }
    }

    void refreshTrackingFor(Player player) {
        if (!personalizedDisplay) {
            return;
        }
        UUID viewerId = player.getUniqueId();
        if (!activeViewers.containsKey(viewerId)) {
            return;
        }

        int entityId = sharedEntityId;
        shownViewers.remove(viewerId);
        viewerRendered.remove(viewerId);
        viewerAnimations.remove(viewerId);
        service.animator().remove(animatorGroup, viewerId.toString());
        service.animator().discardText(viewerId, entityId);
    }

    int activeViewerCount() {
        return activeViewers.size();
    }

    private void refreshViewerText(UUID viewerId, Player player, int entityId, LineSet snapshot,
                                   long delayTicks) {
        if (!show.isDynamic() && (snapshot.flags() & TextPipeline.HAS_FUNCTION) != 0
            && viewerAnimationFresh(viewerId, snapshot, System.currentTimeMillis())) {
            return;
        }
        service.runViewerWork(player, viewerId, id,
            () -> refreshViewerTextOnOwner(viewerId, player, entityId, snapshot), delayTicks);
    }

    private void refreshViewerTextOnOwner(UUID viewerId, Player player, int entityId,
                                          LineSet snapshot) {
        if (!player.isOnline()) {
            return;
        }
        if (!personalizedDisplay || !activeViewers.containsKey(viewerId) || sharedEntityId != entityId) {
            return;
        }
        TextDisplay expectedDisplay = sharedDisplay;
        Boolean visible = refreshShow(player, viewerId, expectedDisplay, snapshot);
        if (visible == null) {
            return;
        }
        if (!visible) {
            viewerRendered.remove(viewerId);
            viewerAnimations.remove(viewerId);
            service.animator().remove(animatorGroup, viewerId.toString());
            service.animator().discardText(viewerId, entityId);
            return;
        }
        if ((snapshot.flags() & TextPipeline.HAS_FUNCTION) != 0) {
            long nowMs = System.currentTimeMillis();
            if (viewerAnimationFresh(viewerId, snapshot, nowMs)) {
                return;
            }
            long emojiGeneration = TextPipeline.emojiGeneration();
            long renderGeneration = service.plugin().text().renderGeneration();
            long animationGeneration = service.animationGeneration();
            AnimationTemplate template = service.animator().compileTemplate(snapshot.lines(),
                line -> service.plugin().text().renderParticleText(player, line).text());
            if (template != null) {
                long intervalMs = viewerRefreshIntervalMs(snapshot);
                viewerAnimations.put(viewerId, new ViewerAnimation(snapshot.generation(),
                    emojiGeneration, renderGeneration, animationGeneration, nowMs + intervalMs, template));
                viewerRendered.remove(viewerId);
                service.animator().discardText(viewerId, entityId);
                service.animator().publish(animatorGroup,
                    viewerId.toString(),
                    new HologramAnimator.Target(entityId, template, List.of(player)));
                return;
            }
        }

        viewerAnimations.remove(viewerId);
        service.animator().remove(animatorGroup, viewerId.toString());
        String rendered = composeViewerText(player, snapshot);
        if (rendered.equals(viewerRendered.get(viewerId))) {
            return;
        }

        viewerRendered.put(viewerId, rendered);
        service.animator().sendText(player, viewerId, entityId, rendered);
    }

    private long viewerRefreshIntervalMs(LineSet snapshot) {
        return dependencies(snapshot).fastDynamic()
            ? 50L
            : service.persistentUpdateIntervalTicks() * 50L;
    }

    private Boolean refreshShow(Player player, UUID viewerId, TextDisplay expectedDisplay, LineSet snapshot) {
        ShowCondition condition = show;
        if (condition.isAlwaysVisible()) {
            return true;
        }
        boolean visible = condition.matches(service.plugin(), player);
        if (sharedDisplay != expectedDisplay || expectedDisplay == null || lineSet != snapshot
            || show != condition || !personalizedDisplay || !activeViewers.containsKey(viewerId)) {
            return null;
        }
        Boolean previous = shownViewers.put(viewerId, visible);
        if (previous == null || previous != visible) {
            if (visible) {
                player.showEntity(service.plugin(), expectedDisplay);
            } else {
                player.hideEntity(service.plugin(), expectedDisplay);
            }
        }
        return visible;
    }

    private boolean viewerAnimationFresh(UUID viewerId, LineSet snapshot, long nowMs) {
        ViewerAnimation cached = viewerAnimations.get(viewerId);
        return cached != null && cached.lineGeneration() == snapshot.generation()
            && cached.emojiGeneration() == TextPipeline.emojiGeneration()
            && cached.renderGeneration() == service.plugin().text().renderGeneration()
            && cached.animationGeneration() == service.animationGeneration()
            && nowMs < cached.refreshAfterMs();
    }

    private String composeViewerText(Player player, LineSet snapshot) {
        String[] segments = staticSegments(snapshot);
        List<String> values = snapshot.lines();
        TextPipeline text = service.plugin().text();
        List<String> rendered = new ArrayList<>(values.size());
        for (int index = 0; index < values.size(); index++) {
            String cached = segments[index];
            rendered.add(cached != null ? cached : text.render(player, values.get(index)));
        }

        return TextUtils.joinLegacyLines(rendered);
    }

    private String[] staticSegments(LineSet snapshot) {
        long emojiGeneration = TextPipeline.emojiGeneration();
        long renderGeneration = service.plugin().text().renderGeneration();
        StaticSegments cached = staticSegmentsCache;
        if (cached != null && cached.generation() == snapshot.generation()
            && cached.emojiGeneration() == emojiGeneration
            && cached.renderGeneration() == renderGeneration) {
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

        staticSegmentsCache = new StaticSegments(snapshot.generation(), emojiGeneration,
            renderGeneration, segments);
        return segments;
    }

    private void despawnShared() {
        TextDisplay display = sharedDisplay;
        service.animator().removeGroup(animatorGroup);
        clearPersonalizedState();
        sharedDisplay = null;
        sharedEntityId = 0;
        personalizedDisplay = false;
        sharedRendered = null;
        if (display == null) {
            return;
        }

        service.despawnEntity(display, location());
    }

    private void applySeeThrough() {
        TextDisplay shared = sharedDisplay;
        if (shared != null) {
            service.runEntity(shared, () -> shared.setSeeThrough(seeThrough),
                () -> clearShared(shared));
        }
    }

    private void applyScale() {
        TextDisplay shared = sharedDisplay;
        if (shared != null) {
            service.runEntity(shared, () -> shared.setTransformation(scaleTransformation()),
                () -> clearShared(shared));
        }
    }

    private Transformation scaleTransformation() {
        float displayScale = (float) scale;
        return new Transformation(new Vector3f(), new Quaternionf(),
            new Vector3f(displayScale, displayScale, displayScale), new Quaternionf());
    }

    private Display.Billboard billboardMode() {
        return Display.Billboard.valueOf(billboard);
    }

    private void applyOrientation() {
        Display.Billboard mode = billboardMode();
        float entityYaw = (float) yaw;
        float entityPitch = (float) pitch;
        TextDisplay shared = sharedDisplay;
        if (shared != null) {
            service.runEntity(shared, () -> {
                shared.setBillboard(mode);
                shared.setRotation(entityYaw, entityPitch);
            }, () -> clearShared(shared));
        }
    }

    private List<Player> captureViewers(List<HologramTick.Viewer> viewers) {
        List<Player> captured = new ArrayList<>(viewers.size());
        for (HologramTick.Viewer viewer : viewers) {
            captured.add(viewer.player());
        }

        return List.copyOf(captured);
    }

    private boolean anyInRange(List<HologramTick.Viewer> viewers) {
        return !viewers.isEmpty();
    }

    private void clearShared(TextDisplay display) {
        if (sharedDisplay != display) {
            return;
        }
        service.animator().removeGroup(animatorGroup);
        clearPersonalizedState();
        sharedDisplay = null;
        sharedEntityId = 0;
        personalizedDisplay = false;
        sharedRendered = null;
    }

    private void invalidateViewer(UUID viewerId, boolean clearClientText) {
        Player viewer = activeViewers.remove(viewerId);
        shownViewers.remove(viewerId);
        viewerRendered.remove(viewerId);
        viewerAnimations.remove(viewerId);
        service.animator().remove(animatorGroup, viewerId.toString());
        int entityId = sharedEntityId;
        service.animator().discardText(viewerId, entityId);
        if (!clearClientText || viewer == null) {
            return;
        }

        service.runViewerWork(viewer, viewerId, id, () -> {
            if (personalizedDisplay && sharedEntityId == entityId && !activeViewers.containsKey(viewerId)) {
                TextDisplay display = sharedDisplay;
                if (!show.isAlwaysVisible() && display != null) {
                    viewer.hideEntity(service.plugin(), display);
                }
                service.animator().sendText(viewer, viewerId, entityId, "");
            }
        });
    }

    private void clearPersonalizedState() {
        int entityId = sharedEntityId;
        for (UUID viewerId : activeViewers.keySet()) {
            service.animator().remove(animatorGroup, viewerId.toString());
            service.animator().discardText(viewerId, entityId);
        }
        activeViewers.clear();
        shownViewers.clear();
        viewerRendered.clear();
        viewerAnimations.clear();
    }

    private static boolean isChunkLoaded(World world, Location anchor) {
        return world.isChunkLoaded(anchor.getBlockX() >> 4, anchor.getBlockZ() >> 4);
    }
}
