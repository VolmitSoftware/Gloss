package art.arcane.gloss.drop;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.api.ParticleLayer;
import art.arcane.gloss.hologram.DisplayVisibility;
import art.arcane.gloss.hologram.HologramMath;
import art.arcane.gloss.particle.ParticleFrame;
import art.arcane.gloss.particle.ParticleRect;
import art.arcane.gloss.particle.ParticleText;
import art.arcane.gloss.particle.ParticleTextLayout;
import art.arcane.gloss.service.AdmissionBudget;
import art.arcane.gloss.text.TextDisplayLayout;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

final class RealDropService {
    private static final float DEG_TO_RAD = (float) (Math.PI / 180.0D);
    private static final int MAX_VISUALS = 5;
    private static final double VANILLA_ITEM_GRAVITY = 0.04D;
    private static final double WATER_BUOYANCY_STEP = 0.02D;
    private static final double BOUNCE_MIN_APPROACH = 0.08D;
    private static final double ACTIVE_CARRIER_POSITION_EPSILON = 1.0E-7D;
    private static final double SETTLED_CARRIER_POSITION_EPSILON = 1.0D / 512.0D;
    private static final int MAX_DYNAMIC_LIGHTS_PER_CHUNK = 8;
    private static final int DYNAMIC_LIGHT_UPDATE_TICKS = 4;
    static final int MAX_ACTIVE_PRESENTATIONS = 2048;

    private final Gloss plugin;
    private final NamespacedKey markerKey;
    private final NamespacedKey ownerKey;
    private final NamespacedKey restoreNameKey;
    private final NamespacedKey restoreVisibilityKey;
    private final Map<UUID, State> states;
    private final Map<ChunkKey, Integer> chunkUsage;
    private final Map<LightKey, UUID> lightOwners;
    private final Map<ChunkKey, Integer> lightChunkUsage;
    private final AdmissionBudget admissions;
    private final boolean detachedRegionizedDisplays;

    private volatile boolean running;
    private volatile long generation;

    RealDropService(Gloss plugin) {
        this.plugin = plugin;
        this.markerKey = new NamespacedKey(plugin, "real_drop");
        this.ownerKey = new NamespacedKey(plugin, "real_drop_owner");
        this.restoreNameKey = new NamespacedKey(plugin, "real_drop_name_visible");
        this.restoreVisibilityKey = new NamespacedKey(plugin, "real_drop_entity_visible");
        this.states = new ConcurrentHashMap<>();
        this.chunkUsage = new ConcurrentHashMap<>();
        this.lightOwners = new ConcurrentHashMap<>();
        this.lightChunkUsage = new ConcurrentHashMap<>();
        this.admissions = new AdmissionBudget(MAX_ACTIVE_PRESENTATIONS);
        this.detachedRegionizedDisplays = plugin.scheduler().isFoliaThreading();
    }

    void enable() {
        generation++;
        running = true;
    }

    void disable() {
        running = false;
        generation++;
        List<State> snapshot = new ArrayList<>(states.values());
        for (State state : snapshot) {
            state.closed = true;
            if (plugin.scheduler().isOwnedByCurrentRegion(state.item)) {
                teardownOwned(state);
            } else {
                scheduleTeardown(state);
            }
        }
    }

    void present(Item item, Label label, RealDropConditionPlan.Selection selection) {
        if (item == null) {
            return;
        }
        if (!plugin.scheduler().isOwnedByCurrentRegion(item)) {
            plugin.scheduler().runEntity(item, () -> presentOwned(item, label, selection));
            return;
        }
        presentOwned(item, label, selection);
    }

    void remove(Item item) {
        if (item == null) {
            return;
        }
        State state = states.get(item.getUniqueId());
        if (state == null) {
            if (plugin.scheduler().isOwnedByCurrentRegion(item)) {
                healOwned(item);
            } else {
                plugin.scheduler().runEntity(item, () -> healOwned(item));
            }
            return;
        }
        state.closed = true;
        if (plugin.scheduler().isOwnedByCurrentRegion(item)) {
            teardownOwned(state);
        } else {
            scheduleTeardown(state);
        }
    }

    int activeCount() {
        return states.size();
    }

    private void presentOwned(Item item, Label requestedLabel,
                              RealDropConditionPlan.Selection selection) {
        State existing = states.get(item.getUniqueId());
        if (existing == null) {
            healOwned(item);
        }
        Label label = effectiveLabel(item, requestedLabel);
        GlossConfig.RealDrops config = selection.style().config();
        if (!running || !config.enabled() || selection.emptyAudience() || !eligible(item, config)) {
            if (existing != null) {
                existing.closed = true;
                teardownOwned(existing);
            } else {
                healOwned(item);
            }
            return;
        }
        if (existing != null && existing.generation == generation && !existing.closed
            && existing.selection.style() == selection.style()) {
            existing.selection = selection;
            if (!presentationOwned(existing)) {
                moveCarrier(existing, config.limits().updateIntervalTicks());
                plugin.scheduler().runEntity(item,
                    () -> presentOwned(item, requestedLabel, selection), 1);
                return;
            }
            refreshOwned(existing, label);
            return;
        }
        if (existing != null) {
            existing.closed = true;
            teardownOwned(existing);
        }
        createOwned(item, label, selection);
    }

    private void createOwned(Item item, Label label, RealDropConditionPlan.Selection selection) {
        if (!item.isValid() || item.isDead()) {
            return;
        }
        long createGeneration = generation;
        if (!running) {
            healOwned(item);
            return;
        }
        AdmissionBudget.Lease admission = admissions.tryAcquire();
        if (admission == null) {
            healOwned(item);
            return;
        }
        ChunkKey reservedChunk = null;
        int reserved = 0;
        boolean chunkReserved = false;
        State state = null;
        try {
            GlossConfig.RealDrops config = selection.style().config();
            ItemStack stack = item.getItemStack();
            int visualCount = desiredVisualCount(stack, config);
            boolean createLabel = config.labels().enabled() && !label.lines().isEmpty();
            reserved = visualCount + (createLabel ? 1 : 0);
            reservedChunk = chunkKey(item.getLocation());
            if (!reserve(reservedChunk, reserved, config.limits().maxVisualsPerChunk())) {
                admission.close();
                healOwned(item);
                return;
            }
            chunkReserved = true;

            Boolean visibleByDefault = DisplayVisibility.isVisibleByDefault(item);
            boolean restoreVisibility = visibleByDefault == null || visibleByDefault;
            boolean restoreName = item.isCustomNameVisible();
            state = new State(
                item, createGeneration, reservedChunk, reserved, restoreVisibility, restoreName,
                item.hasGravity(), admission, selection);
            state.modelKind = RealDropModel.modelKind(stack.getType());
            state.lastPollDelayTicks = config.limits().updateIntervalTicks();
            Quaternionf initialRotation = RealDropModel.baseRotation(state.modelKind);
            if (item.isOnGround()) {
                initialRotation.set(landingRotation(state, config));
            }
            state.animation = new RealDropAnimationState(
                initialRotation, RealDropModel.spin(item.getUniqueId(), 0, config.motion()), item.isOnGround());
            state.velocity = item.getVelocity();
            state.lastVelocityY = state.velocity.getY();
            state.lastItemX = item.getLocation().getX();
            state.lastItemZ = item.getLocation().getZ();
            state.onGround = item.isOnGround();
            state.inWater = item.isInWater();
            state.inLava = inLava(item);
            refreshEnvironment(state, selection.style().script());
            state.authoredSample = authoredAnimationSample(state);
            applyAuthoredPhysics(state, state.authoredSample.physics());
            updateAuthoredLight(state, state.authoredSample.lightLevel());
            states.put(item.getUniqueId(), state);
            if (!presentationStillCurrent(running, generation, createGeneration, config.enabled())) {
                state.closed = true;
                teardownOwned(state);
                return;
            }

            for (int index = 0; index < visualCount; index++) {
                state.visuals.add(spawnVisual(state, stack, index, visualCount, config));
            }
            if (createLabel) {
                state.label = spawnLabel(state, label, config);
                state.labelText = label.text();
                state.labelAuthoredText = label.authoredText();
            }
            item.getPersistentDataContainer().set(markerKey, PersistentDataType.BOOLEAN, true);
            item.getPersistentDataContainer().set(restoreNameKey, PersistentDataType.BOOLEAN, restoreName);
            item.getPersistentDataContainer().set(restoreVisibilityKey, PersistentDataType.BOOLEAN, restoreVisibility);
            DisplayVisibility.setVisibleByDefault(item,
                selection.universalAudience() ? false : restoreVisibility);
            if (createLabel && restoreName) {
                item.setCustomNameVisible(false);
            }
            state.stackHash = stack.hashCode();
            applyPose(state, state.animation.rotation(), state.onGround
                ? config.landing().transitionTicks()
                : config.limits().updateIntervalTicks());
            if (!presentationStillCurrent(running, generation, createGeneration, config.enabled())) {
                state.closed = true;
                teardownOwned(state);
                return;
            }
            reconcileAudience(state);
            emitParticles(state, config);
            scheduleTick(state, config.limits().updateIntervalTicks());
        } catch (RuntimeException | Error failure) {
            try {
                if (state == null) {
                    if (chunkReserved) {
                        release(reservedChunk, reserved);
                    }
                    admission.close();
                    healOwned(item);
                } else {
                    state.closed = true;
                    teardownOwned(state);
                }
            } catch (RuntimeException | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            Gloss.logExceptionStackThrottled(false, "real-drop-create", failure,
                "Could not create a real-drop presentation for item %s.", item.getUniqueId());
            if (failure instanceof Error error) {
                throw error;
            }
        }
    }

    private Display spawnVisual(State state, ItemStack stack, int index, int count,
                                GlossConfig.RealDrops config) {
        if (index < MAX_VISUALS) {
            state.appliedGlow[index] = 0;
            state.appliedViewRange[index] = -1.0F;
        }
        PresentationSample sample = presentationSample(state, index, count);
        World world = state.item.getWorld();
        Display display;
        if (state.modelKind == RealDropModel.ModelKind.FLAT) {
            ItemDisplay itemDisplay = world.spawn(state.item.getLocation(), ItemDisplay.class);
            itemDisplay.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            setDisplayContent(itemDisplay, stack);
            display = itemDisplay;
        } else {
            BlockDisplay blockDisplay = world.spawn(state.item.getLocation(), BlockDisplay.class);
            setDisplayContent(blockDisplay, stack);
            display = blockDisplay;
        }
        display.setPersistent(false);
        display.setViewRange(HologramMath.viewRangeMultiplier(config.limits().viewRange()));
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(config.limits().updateIntervalTicks());
        display.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING,
            state.item.getUniqueId().toString());
        if (!state.selection.universalAudience()) {
            DisplayVisibility.setVisibleByDefault(display, false);
        }
        if (index > 0 && usesPassengerCarrier(detachedRegionizedDisplays)
            && !carrier(state).addPassenger(display)) {
            display.remove();
            throw new IllegalStateException("Display carrier refused an additional dropped-item model");
        }
        applyVisualTransformation(display, state, index, state.animation.rotation(), config, sample);
        applyPresentation(state, index, display, sample, config);
        return display;
    }

    private TextDisplay spawnLabel(State state, Label label, GlossConfig.RealDrops config) {
        GlossConfig.RealDrops.Labels labels = config.labels();
        TextDisplay display = state.item.getWorld().spawn(state.item.getLocation(), TextDisplay.class);
        display.setPersistent(false);
        display.setBillboard(Display.Billboard.valueOf(labels.billboard()));
        display.setSeeThrough(labels.seeThrough());
        display.setShadowed(labels.shadow());
        display.setViewRange(HologramMath.viewRangeMultiplier(labels.viewRange()));
        display.setLineWidth(TextDisplayLayout.FULL_WIDTH);
        display.setTextOpacity((byte) -1);
        display.setBackgroundColor(labels.background()
            ? Color.fromARGB(labels.backgroundAlpha(), labels.backgroundRed(), labels.backgroundGreen(), labels.backgroundBlue())
            : Color.fromARGB(0, 0, 0, 0));
        display.setText(label.text());
        display.setTransformation(new Transformation(
            new Vector3f(0.0F, labels.yOffset(), 0.0F),
            new Quaternionf(),
            new Vector3f(labels.scale(), labels.scale(), labels.scale()),
            new Quaternionf()));
        display.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING,
            state.item.getUniqueId().toString());
        if (!state.selection.universalAudience()) {
            DisplayVisibility.setVisibleByDefault(display, false);
        }
        if (usesPassengerCarrier(detachedRegionizedDisplays) && !carrier(state).addPassenger(display)) {
            display.remove();
            throw new IllegalStateException("Display carrier refused the dropped-item label");
        }
        return display;
    }

    private void refreshOwned(State state, Label label) {
        if (!state.item.isValid() || state.item.isDead()) {
            retire(state);
            return;
        }
        try {
            GlossConfig.RealDrops config = state.selection.style().config();
            refreshVisuals(state, config);
            refreshLabel(state, label, config);
            reconcileAudience(state);
        } catch (RuntimeException failure) {
            failState(state, failure);
        }
    }

    private void refreshVisuals(State state, GlossConfig.RealDrops config) {
        boolean poseRefreshRequired = false;
        for (int index = state.visuals.size() - 1; index >= 0; index--) {
            Display display = state.visuals.get(index);
            if (!display.isValid()) {
                state.visuals.remove(index);
                release(state.chunkKey, 1);
                state.reserved--;
                poseRefreshRequired = true;
            }
        }
        ItemStack stack = state.item.getItemStack();
        int stackHash = stack.hashCode();
        int desired = desiredVisualCount(stack, config);
        if (stackHash != state.stackHash) {
            RealDropModel.ModelKind nextKind = RealDropModel.modelKind(stack.getType());
            if (usesBlockDisplay(state.modelKind) != usesBlockDisplay(nextKind)) {
                replaceVisuals(state, stack, nextKind, config);
            } else {
                state.modelKind = nextKind;
                for (Display display : state.visuals) {
                    setDisplayContent(display, stack);
                }
            }
            state.stackHash = stackHash;
            poseRefreshRequired = true;
        }
        int delta = desired - state.visuals.size();
        if (delta > 0 && reserve(state.chunkKey, delta, config.limits().maxVisualsPerChunk())) {
            state.reserved += delta;
            for (int index = state.visuals.size(); index < desired; index++) {
                state.visuals.add(spawnVisual(state, stack, index, desired, config));
            }
            poseRefreshRequired = true;
        } else if (delta < 0) {
            for (int index = state.visuals.size() - 1; index >= desired; index--) {
                removeEntity(state.visuals.remove(index));
                release(state.chunkKey, 1);
                state.reserved--;
            }
            poseRefreshRequired = true;
        }
        if (poseRefreshRequired) {
            applyPose(state, state.animation.rotation(), config.limits().updateIntervalTicks());
        }
    }

    private void replaceVisuals(State state, ItemStack stack, RealDropModel.ModelKind nextKind,
                                GlossConfig.RealDrops config) {
        int count = state.visuals.size();
        for (Display display : state.visuals) {
            removeEntity(display);
        }
        state.visuals.clear();
        state.modelKind = nextKind;
        for (int index = 0; index < count; index++) {
            state.visuals.add(spawnVisual(state, stack, index, count, config));
        }
        if (usesPassengerCarrier(detachedRegionizedDisplays)
            && state.label != null && state.label.isValid()
            && !carrier(state).addPassenger(state.label)) {
            throw new IllegalStateException("Display carrier refused the dropped-item label after model replacement");
        }
    }

    private void refreshLabel(State state, Label label, GlossConfig.RealDrops config) {
        boolean wanted = config.labels().enabled() && !label.lines().isEmpty();
        if (!wanted) {
            if (state.label != null) {
                removeEntity(state.label);
                state.label = null;
                state.labelText = "";
                state.labelAuthoredText = "";
                release(state.chunkKey, 1);
                state.reserved--;
            }
            state.item.setCustomNameVisible(state.restoreNameVisible && state.item.getCustomName() != null);
            return;
        }
        state.item.setCustomNameVisible(false);
        if (state.label == null || !state.label.isValid()) {
            if (state.label != null) {
                release(state.chunkKey, 1);
                state.reserved--;
            }
            if (!reserve(state.chunkKey, 1, config.limits().maxVisualsPerChunk())) {
                state.label = null;
                return;
            }
            state.reserved++;
            state.label = spawnLabel(state, label, config);
            state.labelText = label.text();
            state.labelAuthoredText = label.authoredText();
            return;
        }
        String text = label.text();
        if (!text.equals(state.labelText)) {
            state.label.setText(text);
            state.labelText = text;
        }
        state.labelAuthoredText = label.authoredText();
    }

    private void tick(State state) {
        try {
            tickOwned(state);
        } catch (RuntimeException failure) {
            failState(state, failure);
        }
    }

    private void tickOwned(State state) {
        if (state.closed || state.generation != generation || states.get(state.item.getUniqueId()) != state) {
            return;
        }
        GlossConfig.RealDrops config = state.selection.style().config();
        if (!running || !config.enabled() || !state.item.isValid() || state.item.isDead()) {
            state.closed = true;
            teardownOwned(state);
            return;
        }
        if (!eligible(state.item, config)) {
            state.closed = true;
            teardownOwned(state);
            return;
        }

        RealDropScriptPlan scriptPlan = state.selection.style().script();
        int elapsedTicks = Math.max(1, state.lastPollDelayTicks);
        state.animationAgeTicks += elapsedTicks;
        RealDropAnimationPlan.AnimationSample sampledAnimation = authoredAnimationSample(state);
        boolean authoredChanged = !sampledAnimation.equals(state.authoredSample);
        state.authoredSample = sampledAnimation;
        applyAuthoredPhysics(state, state.authoredSample.physics());
        boolean onGround = state.item.isOnGround();
        Location itemLocation = state.item.getLocation();
        double deltaX = itemLocation.getX() - state.lastItemX;
        double deltaZ = itemLocation.getZ() - state.lastItemZ;
        boolean previousInWater = state.inWater;
        boolean inWater = state.item.isInWater();
        state.inWater = inWater;
        state.inLava = inLava(state.item);
        PhysicsResult physics = state.authoredSample.physics()
            ? applyPhysics(state, config, onGround, inWater, elapsedTicks)
            : new PhysicsResult(state.item.getVelocity(), false);
        Vector velocity = physics.velocity();
        state.velocity = velocity;
        refreshEnvironment(state, scriptPlan);
        double velocityY = velocity.getY();
        boolean bounced = physics.bounced() || (!onGround && state.lastVelocityY < -0.02D && velocityY > 0.02D);
        if (bounced) {
            state.bounceRevision++;
            if (config.motion().changeOnBounce()) {
                state.animation.angularVelocity(
                    RealDropModel.spin(state.item.getUniqueId(), state.bounceRevision, config.motion()));
            }
        }
        boolean supported = onGround && !bounced;
        double impactSpeed = onGround && !state.onGround ? Math.max(0.0D, -state.lastVelocityY) : 0.0D;
        RealDropAnimationState.Phase previousPhase = state.animation.phase();
        RealDropAnimationFrame frame = RealDropAnimationEngine.advance(
            state.item.getUniqueId(), state.modelKind, state.animation,
            new RealDropAnimationInput(
                elapsedTicks, supported, inWater, state.inLava, bounced,
                state.onGround ? deltaX : 0.0D, state.onGround ? deltaZ : 0.0D,
                velocity.getX(), velocityY, velocity.getZ(), impactSpeed),
            config);
        recordAnimationEvents(state, previousPhase, frame.phase(), previousInWater, inWater,
            onGround && !state.onGround, bounced);
        sampledAnimation = authoredAnimationSample(state);
        authoredChanged |= !sampledAnimation.equals(state.authoredSample);
        state.authoredSample = sampledAnimation;
        updateAuthoredLight(state, state.authoredSample.lightLevel());
        state.settled = frame.settled();
        boolean authoredContinuous = authoredAnimationRequiresContinuousUpdates(state);
        int pollDelayTicks = frame.settled()
            && ((scriptPlan != null && scriptPlan.continuousUpdatesRequired()) || authoredContinuous)
            ? config.limits().updateIntervalTicks()
            : frame.pollDelayTicks();
        pollDelayTicks = particlePollDelay(config, pollDelayTicks);
        if (!presentationOwned(state)) {
            moveCarrier(state, frame.interpolationTicks());
            state.animation.markPoseDirty();
            state.onGround = supported;
            state.lastVelocityY = velocityY;
            state.lastItemX = itemLocation.getX();
            state.lastItemZ = itemLocation.getZ();
            state.lastPollDelayTicks = pollDelayTicks;
            reconcileAudience(state);
            emitParticles(state, config);
            scheduleTick(state, pollDelayTicks);
            return;
        }
        if (!moveReservation(state, chunkKey(state.item.getLocation()), config.limits().maxVisualsPerChunk())) {
            state.closed = true;
            teardownOwned(state);
            return;
        }
        refreshVisuals(state, config);
        moveCarrier(state, frame.interpolationTicks());

        state.onGround = supported;
        if (frame.poseChanged() || authoredChanged
            || (scriptPlan != null && scriptPlan.continuousUpdatesRequired())) {
            applyPose(state, frame.rotation(), frame.interpolationTicks());
        }
        state.lastVelocityY = velocityY;
        state.lastItemX = itemLocation.getX();
        state.lastItemZ = itemLocation.getZ();
        state.lastPollDelayTicks = pollDelayTicks;
        reconcileAudience(state);
        emitParticles(state, config);
        scheduleTick(state, pollDelayTicks);
    }

    private void failState(State state, RuntimeException failure) {
        state.closed = true;
        teardownOwned(state);
        Gloss.logExceptionStackThrottled(false, "real-drop-update", failure,
            "Could not update a real-drop presentation for item %s.", state.item.getUniqueId());
    }

    private void applyPose(State state, Quaternionf rotation, int interpolationTicks) {
        GlossConfig.RealDrops config = state.selection.style().config();
        RealDropScriptPlan plan = state.selection.style().script();
        int count = state.visuals.size();
        RealDropScriptPlan.RealDropScriptSample sharedSample = plan != null && !plan.perModelRequired()
            ? sample(state, plan, 0, count)
            : null;
        for (int index = 0; index < count; index++) {
            Display display = state.visuals.get(index);
            if (!display.isValid()) {
                continue;
            }
            RealDropScriptPlan.RealDropScriptSample scriptSample = sharedSample != null
                ? sharedSample
                : sample(state, plan, index, count);
            PresentationSample presentation = composePresentation(state.authoredSample, scriptSample);
            Transformation transformation = visualTransformation(state, index, rotation, config, presentation);
            applyInterpolatedTransformation(display, transformation, interpolationTicks);
            applyPresentation(state, index, display, presentation, config);
        }
    }

    private void applyVisualTransformation(Display display, State state, int index,
                                           Quaternionf rotation, GlossConfig.RealDrops config,
                                           PresentationSample sample) {
        display.setTransformation(visualTransformation(state, index, rotation, config, sample));
    }

    private Transformation visualTransformation(State state, int index, Quaternionf rotation,
                                                GlossConfig.RealDrops config,
                                                PresentationSample sample) {
        RealDropModel.Offset offset = RealDropModel.offset(index, config.limits().spread());
        float scale = RealDropModel.scale(state.modelKind, config.scale());
        Quaternionf indexedRotation = RealDropModel.indexedRotation(rotation, index);
        Material material = state.item.getItemStack().getType();
        boolean grounded = state.item.isOnGround();
        if (sample.neutralTransform()) {
            if (state.modelKind != RealDropModel.ModelKind.FLAT) {
                return blockTransformation(material, indexedRotation, scale, scale, scale,
                    offset.x(), offset.y(), offset.z(), grounded);
            }
            return new Transformation(
                new Vector3f(offset.x(), offset.y() + RealDropModel.yOffset(
                    material, state.modelKind, scale, indexedRotation, grounded), offset.z()),
                indexedRotation,
                new Vector3f(scale, scale, scale),
                new Quaternionf());
        }
        Quaternionf posed = indexedRotation.rotateXYZ(
            (float) sample.rotationX() * DEG_TO_RAD,
            (float) sample.rotationY() * DEG_TO_RAD,
            (float) sample.rotationZ() * DEG_TO_RAD);
        float scaleX = scale * (float) sample.scaleX();
        float scaleY = scale * (float) sample.scaleY();
        float scaleZ = scale * (float) sample.scaleZ();
        if (state.modelKind != RealDropModel.ModelKind.FLAT) {
            return blockTransformation(material, posed, scaleX, scaleY, scaleZ,
                offset.x() + (float) sample.offsetX(),
                offset.y() + (float) sample.offsetY(),
                offset.z() + (float) sample.offsetZ(), grounded);
        }
        float baseY = RealDropModel.yOffset(material, state.modelKind, scale, posed, grounded);
        return new Transformation(
            new Vector3f(
                offset.x() + (float) sample.offsetX(),
                offset.y() + baseY + (float) sample.offsetY(),
                offset.z() + (float) sample.offsetZ()),
            posed,
            new Vector3f(
                scaleX,
                scaleY,
                scaleZ),
            new Quaternionf());
    }

    private static Transformation blockTransformation(Material material, Quaternionf rotation,
                                                      float scaleX, float scaleY, float scaleZ,
                                                      float offsetX, float offsetY, float offsetZ,
                                                      boolean grounded) {
        RealDropModel.BlockGeometry geometry = RealDropModel.blockGeometry(material);
        Vector3f transformedCenter = new Vector3f(
            geometry.centerX() * scaleX,
            geometry.centerY() * scaleY,
            geometry.centerZ() * scaleZ);
        rotation.transform(transformedCenter);
        float support = grounded
            ? RealDropModel.verticalHalfExtent(geometry, scaleX, scaleY, scaleZ, rotation)
            : 0.0F;
        return new Transformation(
            new Vector3f(
                offsetX - transformedCenter.x(),
                offsetY + support - transformedCenter.y(),
                offsetZ - transformedCenter.z()),
            rotation,
            new Vector3f(scaleX, scaleY, scaleZ),
            new Quaternionf());
    }

    private RealDropAnimationPlan.AnimationSample authoredAnimationSample(State state) {
        RealDropAnimationPlan plan = state.selection.style().animation();
        if (plan == null || !plan.enabled()) {
            return RealDropAnimationPlan.AnimationSample.neutral("");
        }
        String material = state.item.getItemStack().getType().name();
        List<RealDropAnimationPlan.ActiveClip> active = activeAnimationClips(state, plan, material);
        return plan.sample(material, active);
    }

    private boolean authoredAnimationRequiresContinuousUpdates(State state) {
        RealDropAnimationPlan plan = state.selection.style().animation();
        if (plan == null || !plan.enabled()) {
            return false;
        }
        String material = state.item.getItemStack().getType().name();
        for (RealDropAnimationPlan.ActiveClip clip : activeAnimationClips(state, plan, material)) {
            if (plan.requiresContinuousUpdates(material, clip.trigger(), clip.elapsedTicks())) {
                return true;
            }
        }
        return false;
    }

    private static List<RealDropAnimationPlan.ActiveClip> activeAnimationClips(
        State state,
        RealDropAnimationPlan plan,
        String material
    ) {
        List<RealDropAnimationPlan.ActiveClip> active = new ArrayList<>(2 + state.eventTicks.size());
        active.add(new RealDropAnimationPlan.ActiveClip(
            GlossConfig.RealDrops.AnimationTrigger.SPAWN, state.animationAgeTicks));
        GlossConfig.RealDrops.AnimationTrigger phase = GlossConfig.RealDrops.AnimationTrigger.valueOf(
            state.animation.phase().name());
        active.add(new RealDropAnimationPlan.ActiveClip(phase, state.animation.phaseTicks()));
        for (Map.Entry<GlossConfig.RealDrops.AnimationTrigger, Long> entry : state.eventTicks.entrySet()) {
            double elapsed = state.animationAgeTicks - entry.getValue();
            double duration = plan.clipDurationTicks(material, entry.getKey());
            if (duration >= 0.0D && elapsed <= duration) {
                active.add(new RealDropAnimationPlan.ActiveClip(entry.getKey(), elapsed));
            }
        }
        return active;
    }

    private static void recordAnimationEvents(
        State state,
        RealDropAnimationState.Phase previousPhase,
        RealDropAnimationState.Phase phase,
        boolean previousInWater,
        boolean inWater,
        boolean impact,
        boolean bounced
    ) {
        if (impact) {
            state.eventTicks.put(GlossConfig.RealDrops.AnimationTrigger.IMPACT, state.animationAgeTicks);
        }
        if (bounced) {
            state.eventTicks.put(GlossConfig.RealDrops.AnimationTrigger.BOUNCE, state.animationAgeTicks);
        }
        if (!previousInWater && inWater) {
            state.eventTicks.put(GlossConfig.RealDrops.AnimationTrigger.ENTER_FLUID, state.animationAgeTicks);
        } else if (previousInWater && !inWater) {
            state.eventTicks.put(GlossConfig.RealDrops.AnimationTrigger.EXIT_FLUID, state.animationAgeTicks);
        }
        if (phase == RealDropAnimationState.Phase.ROLLING && previousPhase != phase) {
            state.eventTicks.put(GlossConfig.RealDrops.AnimationTrigger.START_ROLL, state.animationAgeTicks);
        }
        if (phase == RealDropAnimationState.Phase.SETTLED && previousPhase != phase) {
            state.eventTicks.put(GlossConfig.RealDrops.AnimationTrigger.SETTLE, state.animationAgeTicks);
        }
        if (previousPhase == RealDropAnimationState.Phase.SETTLED && phase != previousPhase) {
            state.eventTicks.put(GlossConfig.RealDrops.AnimationTrigger.WAKE, state.animationAgeTicks);
        }
    }

    private static void applyAuthoredPhysics(State state, boolean physics) {
        if (!physics) {
            if (!state.animationPhysicsHeld) {
                state.animationPhysicsHeld = true;
                state.heldVelocity = state.item.getVelocity();
            }
            if (state.item.hasGravity()) {
                state.item.setGravity(false);
            }
            if (!state.item.getVelocity().isZero()) {
                state.item.setVelocity(new Vector());
            }
            return;
        }
        releaseAuthoredPhysics(state, true);
    }

    private static void releaseAuthoredPhysics(State state, boolean restoreVelocity) {
        if (!state.animationPhysicsHeld) {
            return;
        }
        state.animationPhysicsHeld = false;
        if (!state.item.isValid()) {
            return;
        }
        state.item.setGravity(state.restoreGravity);
        if (restoreVelocity && state.heldVelocity != null) {
            state.item.setVelocity(state.heldVelocity);
        }
        state.heldVelocity = null;
    }

    private void updateAuthoredLight(State state, int requestedLevel) {
        int lightLevel = Math.max(0, Math.min(15, requestedLevel));
        if (lightLevel <= 0) {
            removeAuthoredLight(state);
            return;
        }
        Location location = state.item.getLocation();
        LightKey next = new LightKey(
            location.getWorld().getUID(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
        if (next.equals(state.lightBlock)) {
            if (state.lightLevel != lightLevel) {
                Block block = location.getBlock();
                if (block.getType() != Material.LIGHT) {
                    lightOwners.remove(next, state.item.getUniqueId());
                    releaseLight(state.lightChunk);
                    state.lightBlock = null;
                    state.lightChunk = null;
                    state.lightLevel = 0;
                    return;
                }
                placeOwnedLight(state, block, lightLevel);
            }
            return;
        }
        if (state.animationAgeTicks - state.lastLightUpdateTick < DYNAMIC_LIGHT_UPDATE_TICKS) {
            return;
        }
        state.lastLightUpdateTick = state.animationAgeTicks;
        removeAuthoredLight(state);
        ChunkKey chunk = new ChunkKey(next.worldId(), next.x() >> 4, next.z() >> 4);
        if (!reserveLight(chunk)) {
            return;
        }
        UUID previous = lightOwners.putIfAbsent(next, state.item.getUniqueId());
        if (previous != null) {
            releaseLight(chunk);
            return;
        }
        Block block = location.getWorld().getBlockAt(next.x(), next.y(), next.z());
        if (!block.getType().isAir()) {
            lightOwners.remove(next, state.item.getUniqueId());
            releaseLight(chunk);
            return;
        }
        state.lightBlock = next;
        state.lightChunk = chunk;
        placeOwnedLight(state, block, lightLevel);
    }

    private static void placeOwnedLight(State state, Block block, int lightLevel) {
        BlockData data = Material.LIGHT.createBlockData();
        if (!(data instanceof Levelled levelled)) {
            throw new IllegalStateException("LIGHT block data does not expose a level");
        }
        levelled.setLevel(lightLevel);
        block.setBlockData(levelled, false);
        state.lightLevel = lightLevel;
    }

    private void removeAuthoredLight(State state) {
        LightKey key = state.lightBlock;
        ChunkKey chunk = state.lightChunk;
        if (key == null || !lightOwners.remove(key, state.itemId)) {
            state.lightBlock = null;
            state.lightChunk = null;
            state.lightLevel = 0;
            return;
        }
        state.lightBlock = null;
        state.lightChunk = null;
        state.lightLevel = 0;
        releaseLight(chunk);
        World world = plugin.getServer().getWorld(key.worldId());
        if (world == null) {
            return;
        }
        Location location = new Location(world, key.x(), key.y(), key.z());
        Runnable removal = () -> {
            Block block = world.getBlockAt(key.x(), key.y(), key.z());
            if (!lightOwners.containsKey(key) && block.getType() == Material.LIGHT) {
                block.setType(Material.AIR, false);
            }
        };
        if (FoliaScheduler.isOwnedByCurrentRegion(location)) {
            removal.run();
        } else {
            FoliaScheduler.runRegion(plugin, location, removal);
        }
    }

    private boolean reserveLight(ChunkKey chunk) {
        AtomicBoolean accepted = new AtomicBoolean();
        lightChunkUsage.compute(chunk, (ignored, used) -> {
            int current = used == null ? 0 : used;
            if (current >= MAX_DYNAMIC_LIGHTS_PER_CHUNK) {
                return used;
            }
            accepted.set(true);
            return current + 1;
        });
        return accepted.get();
    }

    private void releaseLight(ChunkKey chunk) {
        if (chunk == null) {
            return;
        }
        lightChunkUsage.computeIfPresent(chunk, (ignored, used) -> used <= 1 ? null : used - 1);
    }

    private RealDropScriptPlan.RealDropScriptSample sample(State state, RealDropScriptPlan plan,
                                                           int index, int count) {
        if (plan == null) {
            return null;
        }
        ItemStack stack = state.item.getItemStack();
        return plan.sample(new RealDropScriptPlan.RealDropScriptContext(
            (System.nanoTime() - state.spawnNanos) / 1.0E9D,
            state.item.getTicksLived(),
            index,
            Math.max(1, count),
            stack.getAmount(),
            state.onGround,
            state.settled,
            state.animation.phase().name(),
            state.animation.phaseTicks() / 20.0D,
            state.animation.impactSpeed(),
            state.inWater,
            state.inLava,
            state.bounceRevision,
            state.velocity.getX(),
            state.velocity.getY(),
            state.velocity.getZ(),
            state.height,
            state.blockLight,
            state.skyLight,
            state.random,
            stack.getType().name(),
            state.modelKind));
    }

    private PresentationSample presentationSample(State state, int index, int count) {
        return composePresentation(state.authoredSample,
            sample(state, state.selection.style().script(), index, count));
    }

    private static PresentationSample composePresentation(
        RealDropAnimationPlan.AnimationSample authored,
        RealDropScriptPlan.RealDropScriptSample script
    ) {
        RealDropAnimationPlan.AnimationSample timeline = authored == null
            ? RealDropAnimationPlan.AnimationSample.neutral("")
            : authored;
        if (script == null) {
            return new PresentationSample(
                timeline.offsetX(), timeline.offsetY(), timeline.offsetZ(),
                timeline.rotationX(), timeline.rotationY(), timeline.rotationZ(),
                timeline.scaleX(), timeline.scaleY(), timeline.scaleZ(),
                (int) timeline.glowArgb(), timeline.visible());
        }
        return new PresentationSample(
            timeline.offsetX() + script.offsetX(),
            timeline.offsetY() + script.offsetY(),
            timeline.offsetZ() + script.offsetZ(),
            timeline.rotationX() + script.rotationX(),
            timeline.rotationY() + script.rotationY(),
            timeline.rotationZ() + script.rotationZ(),
            timeline.scaleX() * script.scaleX(),
            timeline.scaleY() * script.scaleY(),
            timeline.scaleZ() * script.scaleZ(),
            script.glowArgb() == 0 ? (int) timeline.glowArgb() : script.glowArgb(),
            timeline.visible() && script.visible());
    }

    private void applyPresentation(State state, int index, Display display,
                                   PresentationSample sample,
                                   GlossConfig.RealDrops config) {
        if (index >= MAX_VISUALS) {
            return;
        }
        int glow = sample.glowArgb();
        if (state.appliedGlow[index] != glow) {
            state.appliedGlow[index] = glow;
            if (glow == 0) {
                display.setGlowing(false);
                display.setGlowColorOverride(null);
            } else {
                display.setGlowColorOverride(Color.fromRGB((glow >> 16) & 0xFF, (glow >> 8) & 0xFF, glow & 0xFF));
                display.setGlowing(true);
            }
        }
        float viewRange = sample.visible()
            ? HologramMath.viewRangeMultiplier(config.limits().viewRange())
            : 0.0F;
        if (state.appliedViewRange[index] != viewRange) {
            state.appliedViewRange[index] = viewRange;
            display.setViewRange(viewRange);
        }
        int lightLevel = state.authoredSample == null ? 0 : state.authoredSample.lightLevel();
        if (state.appliedLightLevel[index] != lightLevel) {
            state.appliedLightLevel[index] = lightLevel;
            display.setBrightness(lightLevel <= 0 ? null : new Display.Brightness(lightLevel, lightLevel));
        }
    }

    private PhysicsResult applyPhysics(State state, GlossConfig.RealDrops config, boolean onGround,
                                       boolean inWater, int elapsedTicks) {
        GlossConfig.RealDrops.Physics physics = config.physics();
        Vector velocity = state.item.getVelocity();
        if (!physics.enabled()) {
            restoreGravity(state);
            return new PhysicsResult(velocity, false);
        }

        boolean floating = physics.gravityMultiplier() <= 0.0F;
        boolean desiredGravity = !floating;
        if (state.item.hasGravity() != desiredGravity) {
            state.item.setGravity(desiredGravity);
            state.gravityOverridden = true;
        }

        int intervalTicks = Math.max(1, elapsedTicks);
        double x = velocity.getX();
        double y = velocity.getY();
        double z = velocity.getZ();
        boolean changed = false;
        boolean bounced = false;

        if (floating && y != 0.0D) {
            y = 0.0D;
            changed = true;
        }
        if (!floating && physics.gravityMultiplier() != 1.0F && !onGround) {
            y -= VANILLA_ITEM_GRAVITY * (physics.gravityMultiplier() - 1.0F) * intervalTicks;
            changed = true;
        }
        if (inWater && physics.waterBuoyancy() > 0.0F) {
            y += WATER_BUOYANCY_STEP * physics.waterBuoyancy() * intervalTicks;
            changed = true;
        }
        if (inWater && physics.waterDrag() > 0.0F) {
            double retained = Math.pow(1.0D - physics.waterDrag(), intervalTicks);
            x *= retained;
            y *= retained;
            z *= retained;
            changed = true;
        }
        if (physics.bounce() > 0.0F && onGround && !state.onGround
            && state.lastVelocityY < -BOUNCE_MIN_APPROACH) {
            y = -state.lastVelocityY * physics.bounce();
            bounced = true;
            changed = true;
        }
        if (!changed) {
            return new PhysicsResult(velocity, false);
        }
        Vector applied = new Vector(x, y, z);
        state.item.setVelocity(applied);
        return new PhysicsResult(applied, bounced);
    }

    private void restoreGravity(State state) {
        if (!state.gravityOverridden) {
            return;
        }
        state.gravityOverridden = false;
        if (state.item.isValid()) {
            state.item.setGravity(state.restoreGravity);
        }
    }

    private static void refreshEnvironment(State state, RealDropScriptPlan plan) {
        if (plan == null || !plan.environmentRequired()) {
            return;
        }
        Location location = state.item.getLocation();
        int blockX = location.getBlockX();
        int blockY = location.getBlockY();
        int blockZ = location.getBlockZ();
        if (blockX != state.environmentBlockX || blockY != state.environmentBlockY
            || blockZ != state.environmentBlockZ) {
            state.environmentBlockX = blockX;
            state.environmentBlockY = blockY;
            state.environmentBlockZ = blockZ;
            state.blockLight = location.getBlock().getLightFromBlocks();
            state.skyLight = location.getBlock().getLightFromSky();
            state.surfaceY = RealDropSurfaceSampler.surfaceY(location);
        }
        state.height = Math.max(0.0D, location.getY() - state.surfaceY);
    }

    private static boolean inLava(Item item) {
        return item.getLocation().getBlock().getType() == Material.LAVA;
    }

    private Quaternionf landingRotation(State state, GlossConfig.RealDrops config) {
        return RealDropModel.landingRotation(state.item.getUniqueId(), state.modelKind, config.landing());
    }

    static void applyInterpolatedTransformation(Display display, Transformation transformation,
                                                int interpolationTicks) {
        int duration = Math.max(0, interpolationTicks);
        display.setInterpolationDuration(duration);
        if (duration > 0) {
            display.setInterpolationDelay(-1);
        }
        display.setTransformation(transformation);
    }

    private void scheduleTick(State state, int delayTicks) {
        if (state.closed || !running) {
            return;
        }
        boolean accepted = FoliaScheduler.runEntity(plugin, state.item,
            () -> tick(state), Math.max(1, delayTicks), () -> retire(state));
        if (!accepted) {
            retire(state);
        }
    }

    private int particlePollDelay(GlossConfig.RealDrops config, int pollDelayTicks) {
        if (!plugin.cfg().particles().enabled() || config.particleLayers().isEmpty()) {
            return pollDelayTicks;
        }
        int delay = pollDelayTicks;
        for (ParticleLayer layer : config.particleLayers()) {
            delay = Math.min(delay, layer.emission().intervalTicks());
        }
        return Math.max(1, delay);
    }

    private void emitParticles(State state, GlossConfig.RealDrops config) {
        if (!plugin.cfg().particles().enabled() || config.particleLayers().isEmpty()) {
            return;
        }
        Location itemOrigin = state.item.getLocation().clone();
        double range = Math.max(config.limits().viewRange(), config.labels().viewRange());
        String authoredLabel = state.labelAuthoredText;
        String renderedLabel = state.labelText;
        boolean hasLabel = state.label != null && state.label.isValid() && !renderedLabel.isEmpty();
        RealDropConditionPlan.Selection selection = state.selection;
        for (Entity nearby : state.item.getNearbyEntities(range, range, range)) {
            if (!(nearby instanceof Player viewer)) {
                continue;
            }
            plugin.scheduler().runEntity(viewer, () -> emitParticlesForViewer(
                state, selection, viewer, itemOrigin, authoredLabel, renderedLabel, hasLabel, config));
        }
    }

    private void emitParticlesForViewer(State state, RealDropConditionPlan.Selection selection,
                                        Player viewer, Location itemOrigin, String authoredLabel,
                                        String renderedLabel, boolean hasLabel,
                                        GlossConfig.RealDrops config) {
        if (state.closed || !viewer.isOnline() || !selection.visibleTo(plugin, viewer)) {
            return;
        }
        ParticleText.Rendered label = authoredLabel.isEmpty()
            ? new ParticleText.Rendered(renderedLabel, List.of())
            : plugin.text().renderParticleText(viewer, authoredLabel);
        long tick = System.currentTimeMillis() / 50L;
        for (ParticleLayer layer : config.particleLayers()) {
            String scope = layer.target().scope();
            boolean labelScope = scope.equals("label") || scope.equals("text")
                || scope.equals("line") || scope.equals("span");
            if (labelScope && !hasLabel) {
                continue;
            }
            Location origin = labelScope
                ? itemOrigin.clone().add(0.0D, config.labels().yOffset(), 0.0D)
                : itemOrigin;
            ParticleFrame particleFrame = billboardFrame(viewer, origin);
            List<ParticleRect> targets = realDropTargets(layer, label, config, hasLabel);
            if (!scope.equals("local") && targets.isEmpty()) {
                continue;
            }
            plugin.particles().emit(viewer, particleFrame, layer, targets, tick);
        }
    }

    private List<ParticleRect> realDropTargets(ParticleLayer layer, ParticleText.Rendered label,
                                                GlossConfig.RealDrops config, boolean hasLabel) {
        String scope = layer.target().scope();
        float modelScale = Math.max(config.scale().defaultScale(),
            Math.max(config.scale().flatItems(), config.scale().thinBlocks()));
        if (scope.equals("projection")) {
            double labelHeight = hasLabel ? config.labels().yOffset() + config.labels().scale() * 0.26D : 0.0D;
            return List.of(new ParticleRect(0.0D, labelHeight / 2.0D, 0.0D,
                Math.max(modelScale, hasLabel
                    ? ParticleTextLayout.textBounds(label.text(), config.labels().scale()).width()
                    : 0.0D),
                Math.max(modelScale, labelHeight), modelScale));
        }
        if (scope.equals("model")) {
            return List.of(new ParticleRect(0.0D, modelScale / 2.0D, 0.0D,
                modelScale, modelScale, modelScale));
        }
        if (scope.equals("label") || scope.equals("text")) {
            return List.of(ParticleTextLayout.textBounds(label.text(), config.labels().scale()));
        }
        if (scope.equals("line")) {
            List<ParticleRect> lines = ParticleTextLayout.lineBounds(label.text(), config.labels().scale());
            int index = layer.target().line() - 1;
            return index < lines.size() ? List.of(lines.get(index)) : List.of();
        }
        if (scope.equals("span")) {
            boolean perLetter = layer.geometry().type().equals("letterBounds")
                || layer.geometry().type().equals("glyphOutline")
                || layer.geometry().type().equals("glyphFill");
            return ParticleTextLayout.bounds(label, layer.target().name(), config.labels().scale(), perLetter);
        }
        return List.of();
    }

    private static ParticleFrame billboardFrame(Player viewer, Location origin) {
        Vector front = viewer.getEyeLocation().toVector().subtract(origin.toVector());
        if (front.lengthSquared() < 1.0E-12D) {
            front = new Vector(0.0D, 0.0D, 1.0D);
        }
        front.normalize();
        Vector referenceUp = Math.abs(front.getY()) > 0.999D
            ? new Vector(0.0D, 0.0D, 1.0D)
            : new Vector(0.0D, 1.0D, 0.0D);
        Vector right = front.clone().crossProduct(referenceUp).normalize();
        Vector up = right.clone().crossProduct(front).normalize();
        return new ParticleFrame(origin, right, up, front.clone().multiply(-1.0D));
    }

    private void reconcileAudience(State state) {
        RealDropConditionPlan.Selection selection = state.selection;
        if (selection.universalAudience() || state.closed) {
            return;
        }
        GlossConfig.RealDrops config = selection.style().config();
        double range = Math.max(config.limits().viewRange(), config.labels().viewRange());
        List<Display> displays = visibleDisplays(state);
        for (Entity nearby : state.item.getNearbyEntities(range, range, range)) {
            if (!(nearby instanceof Player viewer)) {
                continue;
            }
            state.audienceViewers.add(viewer.getUniqueId());
            dispatchAudience(state, selection, viewer, displays);
        }
    }

    private List<Display> visibleDisplays(State state) {
        List<Display> displays = new ArrayList<>(state.visuals.size() + 1);
        displays.addAll(state.visuals);
        if (state.label != null) {
            displays.add(state.label);
        }
        return List.copyOf(displays);
    }

    private void dispatchAudience(State state, RealDropConditionPlan.Selection selection,
                                  Player viewer, List<Display> displays) {
        plugin.scheduler().runEntity(viewer, () -> {
            if (state.closed || !viewer.isOnline()) {
                return;
            }
            boolean visible = selection.visibleTo(plugin, viewer);
            if (visible) {
                viewer.hideEntity(plugin, state.item);
            } else if (state.restoreVisibleByDefault) {
                viewer.showEntity(plugin, state.item);
            } else {
                viewer.hideEntity(plugin, state.item);
            }
            for (Display display : displays) {
                if (visible) {
                    viewer.showEntity(plugin, display);
                } else {
                    viewer.hideEntity(plugin, display);
                }
            }
        });
    }

    private void restoreAudience(State state) {
        if (state.selection.universalAudience() || state.audienceViewers.isEmpty()) {
            return;
        }
        for (UUID viewerId : state.audienceViewers) {
            Player viewer = plugin.getServer().getPlayer(viewerId);
            if (viewer == null) {
                continue;
            }
            plugin.scheduler().runEntity(viewer, () -> {
                if (!viewer.isOnline()) {
                    return;
                }
                if (state.restoreVisibleByDefault) {
                    viewer.showEntity(plugin, state.item);
                } else {
                    viewer.hideEntity(plugin, state.item);
                }
            });
        }
        state.audienceViewers.clear();
    }

    private void scheduleTeardown(State state) {
        boolean accepted = FoliaScheduler.runEntity(plugin, state.item,
            () -> teardownOwned(state), 0L, () -> retire(state));
        if (!accepted) {
            retire(state);
        }
    }

    private void teardownOwned(State state) {
        if (!state.destroyed.compareAndSet(false, true)) {
            return;
        }
        boolean current = states.remove(state.item.getUniqueId(), state);
        state.closed = true;
        restoreAudience(state);
        release(state.chunkKey, state.reserved);
        state.reserved = 0;
        state.admission.close();
        removeAuthoredLight(state);
        releaseAuthoredPhysics(state, false);
        restoreGravity(state);
        for (Display display : state.visuals) {
            removeEntity(display);
        }
        state.visuals.clear();
        if (state.label != null) {
            removeEntity(state.label);
            state.label = null;
        }
        if (current && state.item.isValid()) {
            DisplayVisibility.setVisibleByDefault(state.item, state.restoreVisibleByDefault);
            state.item.setCustomNameVisible(state.restoreNameVisible && state.item.getCustomName() != null);
            clearMarkers(state.item);
        }
    }

    private void retire(State state) {
        if (!state.destroyed.compareAndSet(false, true)) {
            return;
        }
        states.remove(state.itemId, state);
        state.closed = true;
        restoreAudience(state);
        release(state.chunkKey, state.reserved);
        state.reserved = 0;
        state.admission.close();
        removeAuthoredLight(state);
        for (Display display : state.visuals) {
            removeEntity(display);
        }
        if (state.label != null) {
            removeEntity(state.label);
        }
    }

    private void healOwned(Item item) {
        removeOwnedPassengers(item);
        if (!item.getPersistentDataContainer().has(markerKey, PersistentDataType.BOOLEAN)) {
            return;
        }
        Boolean restoreVisibility = item.getPersistentDataContainer().get(restoreVisibilityKey, PersistentDataType.BOOLEAN);
        Boolean restoreName = item.getPersistentDataContainer().get(restoreNameKey, PersistentDataType.BOOLEAN);
        DisplayVisibility.setVisibleByDefault(item, restoreVisibility == null || restoreVisibility);
        item.setCustomNameVisible(Boolean.TRUE.equals(restoreName) && item.getCustomName() != null);
        clearMarkers(item);
    }

    private void removeOwnedPassengers(Item item) {
        String ownerId = item.getUniqueId().toString();
        for (Entity passenger : List.copyOf(item.getPassengers())) {
            String markedOwner = passenger.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
            if (!ownerId.equals(markedOwner)) {
                continue;
            }
            item.removePassenger(passenger);
            removeEntity(passenger);
        }
    }

    private void clearMarkers(Item item) {
        item.getPersistentDataContainer().remove(markerKey);
        item.getPersistentDataContainer().remove(restoreNameKey);
        item.getPersistentDataContainer().remove(restoreVisibilityKey);
    }

    private boolean eligible(Item item, GlossConfig.RealDrops config) {
        if (!item.isValid() || item.isDead()) {
            return false;
        }
        if (containsIgnoreCase(config.filters().disabledWorlds(), item.getWorld().getName())) {
            return false;
        }
        Material material = item.getItemStack().getType();
        if (material == Material.AIR || containsIgnoreCase(
            config.filters().materialBlacklist(), material.name())) {
            return false;
        }
        return !config.filters().onlyPlayerDrops() || item.getThrower() != null;
    }

    private Label effectiveLabel(Item item, Label requested) {
        Label supplied = requested == null ? Label.none() : requested;
        if (!supplied.lines().isEmpty()) {
            return supplied;
        }
        String customName = item.getCustomName();
        if (customName != null && item.isCustomNameVisible()) {
            return Label.single(customName);
        }
        return Label.none();
    }

    private int desiredVisualCount(ItemStack stack, GlossConfig.RealDrops config) {
        return RealDropModel.visualCount(stack.getAmount(), stack.getMaxStackSize(),
            config.limits().maxVisualsPerStack());
    }

    private static void setDisplayContent(Display display, ItemStack stack) {
        if (display instanceof ItemDisplay itemDisplay) {
            ItemStack shown = stack.clone();
            shown.setAmount(1);
            itemDisplay.setItemStack(shown);
            return;
        }
        if (display instanceof BlockDisplay blockDisplay) {
            blockDisplay.setBlock(stack.getType().createBlockData());
            return;
        }
        throw new IllegalArgumentException("Unsupported real-drop display type " + display.getType());
    }

    static boolean usesBlockDisplay(RealDropModel.ModelKind kind) {
        return kind != RealDropModel.ModelKind.FLAT;
    }

    private boolean presentationOwned(State state) {
        Display carrier = carrierOrNull(state);
        if (carrier == null || !plugin.scheduler().isOwnedByCurrentRegion(carrier)) {
            return false;
        }
        if (!detachedRegionizedDisplays) {
            return true;
        }
        for (Display display : state.visuals) {
            if (display.isValid() && !plugin.scheduler().isOwnedByCurrentRegion(display)) {
                return false;
            }
        }
        return state.label == null || !state.label.isValid()
            || plugin.scheduler().isOwnedByCurrentRegion(state.label);
    }

    private void moveCarrier(State state, int interpolationTicks) {
        Display carrier = carrierOrNull(state);
        if (carrier == null) {
            return;
        }
        Location destination = state.item.getLocation().clone();
        if (!carrierPositionChanged(state.carrierPositionKnown, state.settled,
            destination.getX(), destination.getY(), destination.getZ(),
            state.lastCarrierX, state.lastCarrierY, state.lastCarrierZ)) {
            return;
        }
        state.carrierPositionKnown = true;
        state.lastCarrierX = destination.getX();
        state.lastCarrierY = destination.getY();
        state.lastCarrierZ = destination.getZ();
        int duration = Math.max(0, Math.min(interpolationTicks, 59));
        if (detachedRegionizedDisplays) {
            for (Display display : state.visuals) {
                moveDisplay(display, destination, duration);
            }
            moveDisplay(state.label, destination, duration);
            return;
        }
        moveDisplay(carrier, destination, duration);
    }

    private void moveDisplay(Display display, Location destination, int duration) {
        if (display == null) {
            return;
        }
        plugin.scheduler().runEntity(display, () -> {
            if (!display.isValid()) {
                return;
            }
            display.setTeleportDuration(duration);
            plugin.scheduler().teleport(display, destination);
        });
    }

    static boolean carrierPositionChanged(boolean positionKnown, boolean settled,
                                          double x, double y, double z,
                                          double lastX, double lastY, double lastZ) {
        if (!positionKnown) {
            return true;
        }
        double epsilon = settled
            ? SETTLED_CARRIER_POSITION_EPSILON
            : ACTIVE_CARRIER_POSITION_EPSILON;
        return Math.abs(x - lastX) > epsilon
            || Math.abs(y - lastY) > epsilon
            || Math.abs(z - lastZ) > epsilon;
    }

    static boolean usesPassengerCarrier(boolean detachedRegionizedDisplays) {
        return !detachedRegionizedDisplays;
    }

    static boolean presentationStillCurrent(
        boolean running,
        long currentGeneration,
        long createGeneration,
        boolean enabled
    ) {
        return running && enabled && currentGeneration == createGeneration;
    }

    private static Display carrier(State state) {
        Display carrier = carrierOrNull(state);
        if (carrier == null) {
            throw new IllegalStateException("Real-drop presentation has no display carrier");
        }
        return carrier;
    }

    private static Display carrierOrNull(State state) {
        return state.visuals.isEmpty() ? null : state.visuals.get(0);
    }

    private boolean moveReservation(State state, ChunkKey next, int maximum) {
        if (next.equals(state.chunkKey)) {
            return true;
        }
        if (!reserve(next, state.reserved, maximum)) {
            return false;
        }
        release(state.chunkKey, state.reserved);
        state.chunkKey = next;
        return true;
    }

    private boolean reserve(ChunkKey key, int count, int maximum) {
        if (count <= 0) {
            return true;
        }
        AtomicBoolean accepted = new AtomicBoolean();
        chunkUsage.compute(key, (ignored, used) -> {
            int current = used == null ? 0 : used;
            if (current + count > maximum) {
                return used;
            }
            accepted.set(true);
            return current + count;
        });
        return accepted.get();
    }

    private void release(ChunkKey key, int count) {
        if (key == null || count <= 0) {
            return;
        }
        chunkUsage.computeIfPresent(key, (ignored, used) -> used <= count ? null : used - count);
    }

    private static ChunkKey chunkKey(Location location) {
        World world = location.getWorld();
        return new ChunkKey(world.getUID(), location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    private void removeEntity(Entity entity) {
        if (entity == null) {
            return;
        }
        if (plugin.scheduler().isOwnedByCurrentRegion(entity)) {
            if (entity.isValid()) {
                entity.remove();
            }
            return;
        }
        FoliaScheduler.runEntity(plugin, entity, () -> {
            if (entity.isValid()) {
                entity.remove();
            }
        }, 0L, () -> {
        });
    }

    private static boolean containsIgnoreCase(List<String> values, String expected) {
        for (String value : values) {
            if (value.equalsIgnoreCase(expected)) {
                return true;
            }
        }
        return false;
    }

    record Label(List<String> authoredLines, List<String> lines) {
        Label {
            authoredLines = authoredLines == null ? List.of() : List.copyOf(authoredLines);
            lines = lines == null ? List.of() : List.copyOf(lines);
        }

        static Label none() {
            return new Label(List.of(), List.of());
        }

        static Label single(String text) {
            return text == null || text.isEmpty() ? none() : new Label(List.of(text), List.of(text));
        }

        static Label rendered(String text) {
            return text == null || text.isEmpty() ? none() : new Label(List.of(), List.of(text));
        }

        String text() {
            return String.join("\n", lines);
        }

        String authoredText() {
            return String.join("\n", authoredLines);
        }
    }

    private static final class State {
        private final Item item;
        private final UUID itemId;
        private final long generation;
        private final List<Display> visuals;
        private final boolean restoreVisibleByDefault;
        private final boolean restoreGravity;
        private final long spawnNanos;
        private final double random;
        private final int[] appliedGlow;
        private final float[] appliedViewRange;
        private final int[] appliedLightLevel;
        private final Map<GlossConfig.RealDrops.AnimationTrigger, Long> eventTicks;
        private final Set<UUID> audienceViewers;
        private final AdmissionBudget.Lease admission;
        private final AtomicBoolean destroyed = new AtomicBoolean();

        private ChunkKey chunkKey;
        private TextDisplay label;
        private String labelText = "";
        private String labelAuthoredText = "";
        private RealDropModel.ModelKind modelKind;
        private RealDropAnimationState animation;
        private RealDropAnimationPlan.AnimationSample authoredSample;
        private Vector velocity;
        private Vector heldVelocity;
        private long animationAgeTicks;
        private long lastLightUpdateTick = Long.MIN_VALUE / 2L;
        private int reserved;
        private int stackHash;
        private int lastPollDelayTicks;
        private int bounceRevision;
        private int blockLight;
        private int skyLight;
        private int environmentBlockX = Integer.MIN_VALUE;
        private int environmentBlockY = Integer.MIN_VALUE;
        private int environmentBlockZ = Integer.MIN_VALUE;
        private double lastItemX;
        private double lastItemZ;
        private double lastVelocityY;
        private double lastCarrierX;
        private double lastCarrierY;
        private double lastCarrierZ;
        private double height;
        private double surfaceY;
        private boolean restoreNameVisible;
        private boolean onGround;
        private boolean settled;
        private boolean inWater;
        private boolean inLava;
        private boolean carrierPositionKnown;
        private boolean gravityOverridden;
        private boolean animationPhysicsHeld;
        private LightKey lightBlock;
        private ChunkKey lightChunk;
        private int lightLevel;
        private volatile RealDropConditionPlan.Selection selection;
        private volatile boolean closed;

        private State(Item item, long generation, ChunkKey chunkKey, int reserved,
                      boolean restoreVisibleByDefault, boolean restoreNameVisible, boolean restoreGravity,
                      AdmissionBudget.Lease admission, RealDropConditionPlan.Selection selection) {
            this.item = item;
            this.itemId = item.getUniqueId();
            this.generation = generation;
            this.chunkKey = chunkKey;
            this.reserved = reserved;
            this.restoreVisibleByDefault = restoreVisibleByDefault;
            this.restoreGravity = restoreGravity;
            this.restoreNameVisible = restoreNameVisible;
            this.admission = admission;
            this.selection = selection;
            this.visuals = new ArrayList<>();
            this.spawnNanos = System.nanoTime();
            this.random = RealDropScriptPlan.RealDropScriptContext.stableRandom(item.getUniqueId());
            this.appliedGlow = new int[MAX_VISUALS];
            this.appliedViewRange = new float[MAX_VISUALS];
            this.appliedLightLevel = new int[MAX_VISUALS];
            this.eventTicks = new EnumMap<>(GlossConfig.RealDrops.AnimationTrigger.class);
            this.audienceViewers = ConcurrentHashMap.newKeySet();
            Arrays.fill(this.appliedViewRange, -1.0F);
            Arrays.fill(this.appliedLightLevel, -1);
            this.velocity = new Vector();
            this.lastPollDelayTicks = 1;
        }
    }

    private record PhysicsResult(Vector velocity, boolean bounced) {
    }

    private record PresentationSample(
        double offsetX,
        double offsetY,
        double offsetZ,
        double rotationX,
        double rotationY,
        double rotationZ,
        double scaleX,
        double scaleY,
        double scaleZ,
        int glowArgb,
        boolean visible
    ) {
        boolean neutralTransform() {
            return offsetX == 0.0D && offsetY == 0.0D && offsetZ == 0.0D
                && rotationX == 0.0D && rotationY == 0.0D && rotationZ == 0.0D
                && scaleX == 1.0D && scaleY == 1.0D && scaleZ == 1.0D;
        }
    }

    private record ChunkKey(UUID worldId, int x, int z) {
    }

    private record LightKey(UUID worldId, int x, int y, int z) {
    }
}
