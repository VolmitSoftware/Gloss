package art.arcane.gloss.hologram;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.api.HologramPresentation;
import art.arcane.gloss.api.HologramViewers;
import art.arcane.gloss.api.TemporaryHologram;
import art.arcane.gloss.api.ParticleLayer;
import art.arcane.gloss.api.ParticleTextSpan;
import art.arcane.gloss.particle.ParticleFrame;
import art.arcane.gloss.particle.ParticleRect;
import art.arcane.gloss.particle.ParticleText;
import art.arcane.gloss.particle.ParticleTextLayout;
import art.arcane.gloss.text.TextPipeline;
import art.arcane.gloss.text.TextDisplayLayout;
import art.arcane.gloss.util.common.TextUtils;
import art.arcane.volmlib.util.math.M;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
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
import java.util.function.LongFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;

final class TemporaryHologramDisplay implements TemporaryHologram {
    private static final double POSITION_EPSILON_SQUARED = 1.0E-6D;
    private static final int NO_TELEPORT_DURATION = -1;
    private static final int RENDERED_LINE_WIDTH = TextDisplayLayout.FULL_WIDTH;

    private record LineSet(List<String> lines, int flags, boolean rendered) {
        static LineSet of(List<String> lines) {
            return new LineSet(lines, HologramMath.classify(lines), false);
        }

        static LineSet rendered(List<String> lines) {
            return new LineSet(lines, 0, true);
        }
    }

    private record AnimationMemo(LineSet lines, long emojiGeneration, long renderGeneration,
                                 long animationGeneration, AnimationTemplate template) {
    }

    private record PositionBinding(Entity owner, Supplier<Location> binder) {
    }

    private record PresentationBinding(Entity owner, Supplier<HologramPresentation> binder) {
    }

    private final HologramService service;
    private final String id;
    private final String animatorGroup;
    private final long durationMs;
    private final long startedMs;
    private final Object linesLock;
    private final Map<UUID, Boolean> appliedVisibility;
    private final AtomicBoolean destroyed;
    private final AtomicBoolean driving;
    private final AtomicBoolean animationPublished;
    private final AtomicBoolean spawning;
    private final AtomicBoolean textDirty;
    private final AtomicBoolean visibilityReset;
    private final ViewerList viewerList;
    private volatile LineSet lineSet;
    private volatile ParticleText.Rendered renderedParticleText;
    private volatile Location position;
    private volatile Location appliedPosition;
    private volatile PositionBinding positionBinding;
    private volatile PresentationBinding presentationBinding;
    private volatile HologramPresentation boundPresentation;
    private volatile FrameComposer frameComposer;
    private volatile TextDisplay display;
    private volatile String rendered;
    private volatile HologramPresentation appliedPresentation;
    private volatile int appliedTeleportTicks;
    private volatile AnimationMemo animationMemo;
    private volatile List<ParticleLayer> particleLayers;
    private volatile Predicate<Player> viewerCondition;

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
        this.driving = new AtomicBoolean();
        this.animationPublished = new AtomicBoolean();
        this.spawning = new AtomicBoolean();
        this.textDirty = new AtomicBoolean(true);
        this.visibilityReset = new AtomicBoolean();
        this.viewerList = new ViewerList();
        this.appliedTeleportTicks = NO_TELEPORT_DURATION;
        Location startingPosition = Objects.requireNonNull(initial,
            "Temporary hologram requires a location.").clone();
        Objects.requireNonNull(startingPosition.getWorld(),
            "Temporary hologram requires a loaded world.");
        this.position = startingPosition;
        this.boundPresentation = HologramPresentation.identity();
        this.particleLayers = List.of();
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
        Location destination = Objects.requireNonNull(location,
            "Temporary hologram teleport requires a location.").clone();
        Objects.requireNonNull(destination.getWorld(), "Temporary hologram teleport requires a loaded world.");
        position = destination;
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
        renderedParticleText = null;
        LineSet next = LineSet.rendered(List.copyOf(lines));
        synchronized (linesLock) {
            this.lineSet = next;
        }

        textDirty.set(true);
    }

    @Override
    public void setRenderedParticleText(String text, List<ParticleTextSpan> spans) {
        String rendered = text == null ? "" : text;
        List<ParticleText.Span> converted = new ArrayList<>(spans == null ? 0 : spans.size());
        if (spans != null) {
            for (ParticleTextSpan span : spans) {
                ParticleTextSpan value = Objects.requireNonNull(span,
                    "rendered particle spans must not contain null entries");
                if (value.end() > rendered.length()) {
                    throw new IllegalArgumentException("rendered particle span exceeds the rendered text length");
                }
                converted.add(new ParticleText.Span(value.name(), value.start(), value.end()));
            }
        }
        renderedParticleText = new ParticleText.Rendered(rendered, converted);
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
    public List<ParticleLayer> particleLayers() {
        return particleLayers;
    }

    @Override
    public void setParticleLayers(List<ParticleLayer> particleLayers) {
        this.particleLayers = ParticleLayer.copyLayers(particleLayers, "temporary hologram");
    }

    @Override
    public void bindRenderedFrames(LongFunction<List<String>> frames) {
        this.frameComposer = frames == null ? null : new FrameComposer(frames);
    }

    @Override
    public void bindPosition(Entity owner, Supplier<Location> binder) {
        this.positionBinding = binder == null ? null : new PositionBinding(
            Objects.requireNonNull(owner, "Position binding requires an owning entity."), binder);
    }

    @Override
    public void bindPresentation(Entity owner, Supplier<HologramPresentation> binder) {
        this.presentationBinding = binder == null ? null : new PresentationBinding(
            Objects.requireNonNull(owner, "Presentation binding requires an owning entity."), binder);
    }

    @Override
    public long remainingMs() {
        return durationMs - (M.ms() - startedMs);
    }

    @Override
    public HologramViewers viewers() {
        return viewerList;
    }

    void setViewerCondition(Predicate<Player> condition) {
        viewerCondition = condition;
        visibilityReset.set(true);
    }

    @Override
    public void destroy() {
        if (!destroyed.compareAndSet(false, true)) {
            return;
        }

        service.removeTemporary(this);
        retractAnimation();
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

    void scheduleDrive(HologramTick tick, boolean enabled) {
        if (!driving.compareAndSet(false, true)) {
            return;
        }
        if (destroyed.get()) {
            driving.set(false);
            return;
        }
        if (remainingMs() <= 0L) {
            try {
                destroy();
            } finally {
                driving.set(false);
            }
            return;
        }
        sampleBindings(tick, enabled);
    }

    private void sampleBindings(HologramTick tick, boolean enabled) {
        PositionBinding position = positionBinding;
        PresentationBinding presentation = presentationBinding;
        if (position == null && presentation == null) {
            scheduleDisplayDrive(tick, enabled);
            return;
        }
        if (position != null && presentation != null && position.owner() == presentation.owner()) {
            Runnable sample = () -> {
                samplePositionNow(position);
                samplePresentationNow(presentation);
                scheduleDisplayDrive(tick, enabled);
            };
            Runnable retirement = once(() -> scheduleDisplayDrive(tick, enabled));
            if (!FoliaScheduler.runEntity(service.plugin(), position.owner(), sample, 0L, retirement)) {
                retirement.run();
            }
            return;
        }
        samplePosition(position, presentation, tick, enabled);
    }

    private void samplePosition(PositionBinding binding, PresentationBinding presentation,
                                HologramTick tick, boolean enabled) {
        if (binding == null) {
            samplePresentation(presentation, tick, enabled);
            return;
        }
        Runnable sample = () -> {
            samplePositionNow(binding);
            samplePresentation(presentation, tick, enabled);
        };
        Runnable retirement = once(() -> samplePresentation(presentation, tick, enabled));
        if (!FoliaScheduler.runEntity(service.plugin(), binding.owner(), sample, 0L, retirement)) {
            retirement.run();
        }
    }

    private void samplePresentation(PresentationBinding binding, HologramTick tick, boolean enabled) {
        if (binding == null) {
            scheduleDisplayDrive(tick, enabled);
            return;
        }
        Runnable sample = () -> {
            samplePresentationNow(binding);
            scheduleDisplayDrive(tick, enabled);
        };
        Runnable retirement = once(() -> scheduleDisplayDrive(tick, enabled));
        if (!FoliaScheduler.runEntity(service.plugin(), binding.owner(), sample, 0L, retirement)) {
            retirement.run();
        }
    }

    private void samplePositionNow(PositionBinding binding) {
        Location bound = safeBind(binding.binder());
        if (bound != null && bound.getWorld() != null) {
            position = bound.clone();
        }
    }

    private void samplePresentationNow(PresentationBinding binding) {
        HologramPresentation presentation = safeBindPresentation(binding.binder());
        if (presentation != null) {
            boundPresentation = presentation;
        }
    }

    private void scheduleDisplayDrive(HologramTick tick, boolean enabled) {
        Runnable driveTask = () -> {
            try {
                drive(tick, enabled);
            } finally {
                driving.set(false);
            }
        };
        TextDisplay active = display;
        if (active != null) {
            if (FoliaScheduler.isOwnedByCurrentRegion(active)) {
                driveTask.run();
                return;
            }
            Runnable retirement = once(() -> scheduleAfterRetirement(active, driveTask));
            if (FoliaScheduler.runEntity(service.plugin(), active, driveTask, 0L, retirement)) {
                return;
            }
            retirement.run();
            return;
        }
        scheduleAtPosition(driveTask);
    }

    private void scheduleAfterRetirement(TextDisplay retired, Runnable driveTask) {
        if (display == retired) {
            display = null;
            retractAnimation();
            service.despawnEntity(retired, position);
        }
        scheduleAtPosition(driveTask);
    }

    private void scheduleAtPosition(Runnable driveTask) {
        if (!service.plugin().scheduler().runAt(position.clone(), driveTask)) {
            driving.set(false);
        }
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
            retractAnimation();
            TextDisplay active = display;
            if (active != null) {
                display = null;
                service.despawnEntity(active, position);
            }

            return;
        }

        HologramPresentation presentation = boundPresentation;

        Location anchor = position;
        World world = anchor.getWorld();
        if (world == null) {
            return;
        }

        TextDisplay active = display;
        if (active == null || !active.isValid()) {
            retractAnimation();
            if (active != null) {
                display = null;
                service.despawnEntity(active, anchor);
            }

            if (captureViewers(tick, world).isEmpty()) {
                return;
            }

            spawn(world, anchor, presentation);
            return;
        }

        moveIfNeeded(active, anchor);
        applyPresentation(active, presentation);
        applyVisibility(active);
        applyText(active, tick, world);
    }

    void emitParticles(HologramTick tick) {
        if (destroyed.get() || display == null || particleLayers.isEmpty()) {
            return;
        }
        World world = position.getWorld();
        if (world != null) {
            emitParticles(tick, world, boundPresentation);
        }
    }

    private void emitParticles(HologramTick tick, World world, HologramPresentation presentation) {
        if (particleLayers.isEmpty()) {
            return;
        }
        List<Player> viewers = captureViewers(tick, world);
        LineSet snapshot = lineSet;
        Location anchor = position.clone();
        for (Player viewer : viewers) {
            UUID viewerId = viewer.getUniqueId();
            service.runViewerWork(viewer, viewerId, animatorGroup + "#particles",
                () -> emitParticlesFor(viewer, anchor, snapshot, presentation));
        }
    }

    private void emitParticlesFor(Player viewer, Location anchor, LineSet snapshot,
                                  HologramPresentation presentation) {
        if (!viewer.isOnline() || destroyed.get() || particleLayers.isEmpty()
            || !conditionMatches(viewer)) {
            return;
        }
        ParticleText.Rendered override = renderedParticleText;
        ParticleText.Rendered particleText = snapshot.rendered()
            ? override == null
                ? new ParticleText.Rendered(TextUtils.joinLegacyLines(snapshot.lines()), List.of())
                : override
            : service.plugin().text().renderParticleText(viewer, String.join("\n", snapshot.lines()));
        ParticleFrame frame = particleFrame(viewer, anchor, presentation);
        long tick = System.currentTimeMillis() / 50L;
        double scale = Math.max(presentation.scaleX(), presentation.scaleY());
        for (ParticleLayer layer : particleLayers) {
            List<ParticleRect> targets = particleTargets(layer, particleText, scale);
            if (!layer.target().scope().equals("local") && targets.isEmpty()) {
                continue;
            }
            service.plugin().particles().emit(viewer, frame, layer, targets, tick);
        }
    }

    private List<ParticleRect> particleTargets(ParticleLayer layer, ParticleText.Rendered rendered,
                                                double scale) {
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
        return List.of();
    }

    private ParticleFrame particleFrame(Player viewer, Location anchor,
                                        HologramPresentation presentation) {
        Vector front = viewer.getEyeLocation().toVector().subtract(anchor.toVector());
        if (front.lengthSquared() < 1.0E-12D) {
            front = new Vector(0.0D, 0.0D, 1.0D);
        }
        front.normalize();
        Vector referenceUp = Math.abs(front.getY()) > 0.999D
            ? new Vector(0.0D, 0.0D, 1.0D)
            : new Vector(0.0D, 1.0D, 0.0D);
        Vector right = front.clone().crossProduct(referenceUp).normalize();
        Vector up = right.clone().crossProduct(front).normalize();
        return new ParticleFrame(anchor, right, up, front.clone().multiply(-1.0D));
    }

    void onPlayerQuit(UUID playerId) {
        appliedVisibility.remove(playerId);
    }

    private void spawn(World world, Location anchor, HologramPresentation presentation) {
        if (!spawning.compareAndSet(false, true)) {
            return;
        }

        textDirty.set(false);
        LineSet snapshot = lineSet;
        String next = renderLines(snapshot);
        boolean whitelist = viewerList.isWhitelist() || viewerCondition != null;
        AtomicBoolean defaultVisibilityApplied = new AtomicBoolean(!whitelist);
        int teleportTicks = desiredTeleportTicks();
        boolean scheduled = service.plugin().scheduler().runAt(anchor, () -> {
            try {
                if (destroyed.get()) {
                    return;
                }
                if (!world.isChunkLoaded(anchor.getBlockX() >> 4, anchor.getBlockZ() >> 4)) {
                    return;
                }

                Consumer<TextDisplay> configurer = spawned -> {
                    service.configureDisplay(spawned, false, Display.Billboard.CENTER);
                    spawned.setText(next);
                    if (snapshot.rendered()) {
                        spawned.setLineWidth(RENDERED_LINE_WIDTH);
                        spawned.setAlignment(TextDisplay.TextAlignment.LEFT);
                    }
                    applyPresentationNow(spawned, presentation, teleportTicks, false);
                    if (whitelist) {
                        defaultVisibilityApplied.set(DisplayVisibility.setVisibleByDefault(spawned, false));
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
                if (viewerList.isWhitelist() == whitelist) {
                    visibilityReset.set(whitelist && !defaultVisibilityApplied.get());
                }
                display = spawned;
                applyVisibility(spawned);
            } catch (RuntimeException failure) {
                Gloss.logExceptionStackThrottled(false, "temporary-hologram-spawn", failure,
                    "Failed to spawn temporary hologram %s.", id);
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
        if (FoliaScheduler.isOwnedByCurrentRegion(active)) {
            applyPresentationNow(active, presentation, interpolationTicks, true);
            return;
        }
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
        FrameComposer frames = frameComposer;
        if (frames != null) {
            applyFrames(active, tick, world, frames);
            return;
        }

        LineSet snapshot = lineSet;
        if ((snapshot.flags() & TextPipeline.HAS_FUNCTION) != 0) {
            AnimationTemplate template = animationTemplate(snapshot);
            if (template != null) {
                publishAnimation(HologramAnimator.SHARED_SUB,
                    new HologramAnimator.Target(active.getEntityId(), template, captureViewers(tick, world)));
                return;
            }
        }

        retractAnimation();
        boolean dirty = textDirty.compareAndSet(true, false);
        if (!dirty && !hasDynamicText(snapshot)) {
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

    /**
     * A bound frame source owns the text. It rides the animator's async packet loop so a sub tick
     * effect keeps its own rate instead of being quantized to the temporary driver's tick interval;
     * with high frequency animations switched off it falls back to one composed frame per drive.
     */
    private void applyFrames(TextDisplay active, HologramTick tick, World world, FrameComposer frames) {
        if (service.highFrequencyAnimations()) {
            publishAnimation(HologramAnimator.SHARED_SUB,
                new HologramAnimator.Target(active.getEntityId(), frames, captureViewers(tick, world),
                    TextCodec.LEGACY));
            return;
        }

        retractAnimation();
        textDirty.set(false);
        String next = frames.compose(M.ms());
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

    private void publishAnimation(String sub, HologramAnimator.Target target) {
        animationPublished.set(true);
        service.animator().publish(animatorGroup, sub, target);
    }

    private void retractAnimation() {
        if (animationPublished.compareAndSet(true, false)) {
            service.animator().removeGroup(animatorGroup);
        }
    }

    boolean hasPublishedAnimation() {
        return animationPublished.get();
    }

    private AnimationTemplate animationTemplate(LineSet snapshot) {
        long emojiGeneration = TextPipeline.emojiGeneration();
        long renderGeneration = service.plugin().text().renderGeneration();
        long animationGeneration = service.animationGeneration();
        boolean dynamic = service.hasDynamicAnimationContent(snapshot.lines());
        AnimationMemo cached = animationMemo;
        if (!dynamic && cached != null && cached.lines() == snapshot
            && cached.emojiGeneration() == emojiGeneration
            && cached.renderGeneration() == renderGeneration
            && cached.animationGeneration() == animationGeneration) {
            return cached.template();
        }
        AnimationTemplate template = service.animator().compileTemplate(snapshot.lines(),
            line -> service.plugin().text().renderParticleText(null, line).text());
        if (template != null && !dynamic) {
            animationMemo = new AnimationMemo(snapshot, emojiGeneration, renderGeneration,
                animationGeneration, template);
        }
        return template;
    }

    private void applyVisibility(TextDisplay active) {
        if (viewerCondition != null) {
            if (visibilityReset.compareAndSet(true, false)) {
                DisplayVisibility.setVisibleByDefault(active, false);
                appliedVisibility.clear();
            }
            for (UUID viewerId : appliedVisibility.keySet()) {
                Player viewer = Bukkit.getPlayer(viewerId);
                if (viewer == null) {
                    appliedVisibility.remove(viewerId);
                } else {
                    dispatchConditionalVisibility(active, viewer);
                }
            }
            service.forEachNearbyViewer(position, service.viewRange() * service.viewRange(), viewer -> {
                if (!appliedVisibility.containsKey(viewer.getUniqueId())) {
                    dispatchConditionalVisibility(active, viewer);
                }
            });
            return;
        }
        boolean whitelist = viewerList.isWhitelist();
        Set<UUID> members = viewerList.members();
        if (visibilityReset.compareAndSet(true, false)) {
            service.plugin().scheduler().runEntity(active, () -> DisplayVisibility.setVisibleByDefault(active, !whitelist));
            appliedVisibility.clear();
            reconcileVisibility(active, whitelist, members);
            return;
        }
        if (whitelist) {
            reconcileWhitelist(active, members);
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

    void reconcileVisibilityFor(Player player) {
        TextDisplay active = display;
        if (active == null || !player.isOnline()) {
            return;
        }
        if (viewerCondition != null) {
            dispatchConditionalVisibility(active, player);
            return;
        }
        UUID viewerId = player.getUniqueId();
        boolean whitelist = viewerList.isWhitelist();
        boolean visible = whitelist == viewerList.members().contains(viewerId);
        Boolean previous = appliedVisibility.put(viewerId, visible);
        if (previous == null || previous != visible) {
            dispatchVisibility(active, player, visible);
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

    private void dispatchConditionalVisibility(TextDisplay active, Player player) {
        service.runViewerWork(player, player.getUniqueId(), animatorGroup + "#show", () -> {
            if (destroyed.get() || display != active || !player.isOnline()) {
                return;
            }
            UUID viewerId = player.getUniqueId();
            Location viewerLocation = player.getLocation();
            Location anchor = position;
            boolean nearby = viewerLocation.getWorld() == anchor.getWorld()
                && viewerLocation.distanceSquared(anchor) <= service.viewRange() * service.viewRange();
            boolean visible = nearby && viewerList.isWhitelist() == viewerList.members().contains(viewerId)
                && conditionMatches(player);
            Boolean previous = appliedVisibility.put(viewerId, visible);
            if (previous == null || previous != visible) {
                retractAnimation();
                service.animator().discardText(viewerId, active.getEntityId());
                if (visible) {
                    player.showEntity(service.plugin(), active);
                } else {
                    player.hideEntity(service.plugin(), active);
                }
            }
            if (!nearby) {
                appliedVisibility.remove(viewerId);
            }
        });
    }

    private boolean conditionMatches(Player viewer) {
        Predicate<Player> condition = viewerCondition;
        return condition == null || condition.test(viewer);
    }

    private List<Player> captureViewers(HologramTick tick, World world) {
        boolean whitelist = viewerList.isWhitelist();
        Set<UUID> members = viewerList.members();
        boolean conditional = viewerCondition != null && display != null;
        if (members.isEmpty() && !conditional) {
            return whitelist ? List.of() : tick.temporaryPlayers(world, position, service.viewRange());
        }
        List<HologramTick.Viewer> candidates = tick.temporaryViewers(world, position, service.viewRange());
        List<Player> viewers = new ArrayList<>(candidates.size());
        for (HologramTick.Viewer candidate : candidates) {
            if (whitelist == members.contains(candidate.id())
                && (!conditional || Boolean.TRUE.equals(appliedVisibility.get(candidate.id())))) {
                viewers.add(candidate.player());
            }
        }

        return List.copyOf(viewers);
    }

    static Runnable once(Runnable action) {
        AtomicBoolean executed = new AtomicBoolean();
        return () -> {
            if (executed.compareAndSet(false, true)) {
                action.run();
            }
        };
    }

    private Location safeBind(Supplier<Location> binder) {
        try {
            return binder.get();
        } catch (RuntimeException failure) {
            Gloss.verbose("Temporary hologram binder failed for %s: %s.", id,
                failure.getClass().getSimpleName());
            return null;
        }
    }

    private HologramPresentation safeBindPresentation(Supplier<HologramPresentation> binder) {
        try {
            return binder.get();
        } catch (RuntimeException failure) {
            Gloss.verbose("Temporary hologram presentation binder failed for %s: %s.", id,
                failure.getClass().getSimpleName());
            return null;
        }
    }

    private String renderLines(LineSet lines) {
        return lines.rendered() ? TextUtils.joinLegacyLines(lines.lines()) : service.renderStaticLines(lines.lines());
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

    /**
     * Joins the bound frame's lines once per band step rather than once per animator pass. The
     * binder memoizes on its own effect clock and hands back the same list until the effect moves,
     * so identity is enough to skip the join.
     */
    private static final class FrameComposer implements TextFrameSource {
        private final LongFunction<List<String>> frames;
        private volatile List<String> lastLines;
        private volatile String lastText;

        private FrameComposer(LongFunction<List<String>> frames) {
            this.frames = frames;
        }

        @Override
        public String compose(long nowMs) {
            List<String> lines = frames.apply(nowMs);
            if (lines == lastLines) {
                return lastText;
            }

            String text = String.join("\n", lines);
            lastLines = lines;
            lastText = text;
            return text;
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
