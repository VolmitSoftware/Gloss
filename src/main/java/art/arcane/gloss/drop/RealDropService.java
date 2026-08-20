package art.arcane.gloss.drop;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.hologram.DisplayVisibility;
import art.arcane.gloss.hologram.HologramMath;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

final class RealDropService {
    private static final float DEG_TO_RAD = (float) (Math.PI / 180.0D);

    private final Gloss plugin;
    private final Supplier<GlossConfig.RealDrops> configSupplier;
    private final NamespacedKey markerKey;
    private final NamespacedKey ownerKey;
    private final NamespacedKey restoreNameKey;
    private final NamespacedKey restoreVisibilityKey;
    private final Map<UUID, State> states;
    private final Map<ChunkKey, Integer> chunkUsage;

    private volatile Set<String> disabledWorlds = Set.of();
    private volatile Set<String> materialBlacklist = Set.of();
    private volatile boolean running;
    private volatile long generation;

    RealDropService(Gloss plugin, Supplier<GlossConfig.RealDrops> configSupplier) {
        this.plugin = plugin;
        this.configSupplier = configSupplier;
        this.markerKey = new NamespacedKey(plugin, "real_drop");
        this.ownerKey = new NamespacedKey(plugin, "real_drop_owner");
        this.restoreNameKey = new NamespacedKey(plugin, "real_drop_name_visible");
        this.restoreVisibilityKey = new NamespacedKey(plugin, "real_drop_entity_visible");
        this.states = new ConcurrentHashMap<>();
        this.chunkUsage = new ConcurrentHashMap<>();
    }

    void enable() {
        generation++;
        running = true;
        GlossConfig.RealDrops.Filters filters = config().filters();
        disabledWorlds = normalized(filters.disabledWorlds(), false);
        materialBlacklist = normalized(filters.materialBlacklist(), true);
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

    void present(Item item, Label label) {
        if (item == null) {
            return;
        }
        if (!plugin.scheduler().isOwnedByCurrentRegion(item)) {
            plugin.scheduler().runEntity(item, () -> presentOwned(item, label));
            return;
        }
        presentOwned(item, label);
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

    private void presentOwned(Item item, Label requestedLabel) {
        State existing = states.get(item.getUniqueId());
        if (existing == null) {
            healOwned(item);
        }
        Label label = effectiveLabel(item, requestedLabel);
        if (!running || !config().enabled() || !eligible(item)) {
            if (existing != null) {
                existing.closed = true;
                teardownOwned(existing);
            } else {
                healOwned(item);
            }
            return;
        }
        if (existing != null && existing.generation == generation && !existing.closed) {
            if (!presentationOwned(existing)) {
                moveCarrier(existing, config().limits().updateIntervalTicks());
                plugin.scheduler().runEntity(item, () -> presentOwned(item, requestedLabel), 1);
                return;
            }
            refreshOwned(existing, label);
            return;
        }
        if (existing != null) {
            existing.closed = true;
            teardownOwned(existing);
        }
        createOwned(item, label);
    }

    private void createOwned(Item item, Label label) {
        if (!item.isValid() || item.isDead()) {
            return;
        }
        GlossConfig.RealDrops config = config();
        ItemStack stack = item.getItemStack();
        int visualCount = desiredVisualCount(stack, config);
        boolean createLabel = config.labels().enabled() && !label.lines().isEmpty();
        int reserved = visualCount + (createLabel ? 1 : 0);
        ChunkKey chunkKey = chunkKey(item.getLocation());
        if (!reserve(chunkKey, reserved, config.limits().maxVisualsPerChunk())) {
            healOwned(item);
            return;
        }

        Boolean visibleByDefault = DisplayVisibility.isVisibleByDefault(item);
        boolean restoreVisibility = visibleByDefault == null || visibleByDefault;
        boolean restoreName = item.isCustomNameVisible();
        State state = new State(item, generation, chunkKey, reserved, restoreVisibility, restoreName);
        state.modelKind = RealDropModel.modelKind(stack.getType());
        state.rotation.set(baseRotation(state.modelKind));
        state.spin = RealDropModel.spin(item.getUniqueId(), 0, config.motion());
        state.lastVelocityY = item.getVelocity().getY();
        state.onGround = item.isOnGround();
        states.put(item.getUniqueId(), state);

        try {
            for (int index = 0; index < visualCount; index++) {
                state.visuals.add(spawnVisual(state, stack, index, config));
            }
            if (createLabel) {
                state.label = spawnLabel(state, label, config);
                state.labelText = label.text();
            }
            item.getPersistentDataContainer().set(markerKey, PersistentDataType.BOOLEAN, true);
            item.getPersistentDataContainer().set(restoreNameKey, PersistentDataType.BOOLEAN, restoreName);
            item.getPersistentDataContainer().set(restoreVisibilityKey, PersistentDataType.BOOLEAN, restoreVisibility);
            DisplayVisibility.setVisibleByDefault(item, false);
            if (createLabel && restoreName) {
                item.setCustomNameVisible(false);
            }
            state.stackHash = stack.hashCode();
            if (state.onGround) {
                state.rotation.set(landingRotation(state, config));
            }
            applyPose(state, state.rotation, state.onGround
                ? config.landing().transitionTicks()
                : config.limits().updateIntervalTicks());
            scheduleTick(state, config.limits().updateIntervalTicks());
        } catch (RuntimeException failure) {
            state.closed = true;
            teardownOwned(state);
            Gloss.logExceptionStack(false, failure,
                "Could not create a real-drop presentation for item %s.", item.getUniqueId());
        }
    }

    private ItemDisplay spawnVisual(State state, ItemStack stack, int index, GlossConfig.RealDrops config) {
        World world = state.item.getWorld();
        ItemDisplay display = world.spawn(state.item.getLocation(), ItemDisplay.class);
        display.setPersistent(false);
        display.setViewRange(HologramMath.viewRangeMultiplier(config.limits().viewRange()));
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(config.limits().updateIntervalTicks());
        display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
        display.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING,
            state.item.getUniqueId().toString());
        setDisplayStack(display, stack);
        if (index > 0 && !carrier(state).addPassenger(display)) {
            display.remove();
            throw new IllegalStateException("ItemDisplay carrier refused an additional dropped-item model");
        }
        applyVisualTransformation(display, state, index, state.rotation, config);
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
        display.setLineWidth(1000);
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
        if (!carrier(state).addPassenger(display)) {
            display.remove();
            throw new IllegalStateException("ItemDisplay carrier refused the dropped-item label");
        }
        return display;
    }

    private void refreshOwned(State state, Label label) {
        if (!state.item.isValid() || state.item.isDead()) {
            retire(state);
            return;
        }
        try {
            GlossConfig.RealDrops config = config();
            refreshVisuals(state, config);
            refreshLabel(state, label, config);
        } catch (RuntimeException failure) {
            failState(state, failure);
        }
    }

    private void refreshVisuals(State state, GlossConfig.RealDrops config) {
        for (int index = state.visuals.size() - 1; index >= 0; index--) {
            ItemDisplay display = state.visuals.get(index);
            if (!display.isValid()) {
                state.visuals.remove(index);
                release(state.chunkKey, 1);
                state.reserved--;
            }
        }
        ItemStack stack = state.item.getItemStack();
        int stackHash = stack.hashCode();
        int desired = desiredVisualCount(stack, config);
        int delta = desired - state.visuals.size();
        if (delta > 0 && reserve(state.chunkKey, delta, config.limits().maxVisualsPerChunk())) {
            state.reserved += delta;
            for (int index = state.visuals.size(); index < desired; index++) {
                state.visuals.add(spawnVisual(state, stack, index, config));
            }
        } else if (delta < 0) {
            for (int index = state.visuals.size() - 1; index >= desired; index--) {
                removeEntity(state.visuals.remove(index));
                release(state.chunkKey, 1);
                state.reserved--;
            }
        }
        if (stackHash == state.stackHash) {
            return;
        }
        state.stackHash = stackHash;
        state.modelKind = RealDropModel.modelKind(stack.getType());
        for (ItemDisplay display : state.visuals) {
            setDisplayStack(display, stack);
        }
        if (state.onGround) {
            state.rotation.set(landingRotation(state, config));
        }
        applyPose(state, state.rotation, config.limits().updateIntervalTicks());
    }

    private void refreshLabel(State state, Label label, GlossConfig.RealDrops config) {
        boolean wanted = config.labels().enabled() && !label.lines().isEmpty();
        if (!wanted) {
            if (state.label != null) {
                removeEntity(state.label);
                state.label = null;
                state.labelText = "";
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
            return;
        }
        String text = label.text();
        if (!text.equals(state.labelText)) {
            state.label.setText(text);
            state.labelText = text;
        }
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
        if (!running || !config().enabled() || !state.item.isValid() || state.item.isDead()) {
            state.closed = true;
            teardownOwned(state);
            return;
        }
        if (!eligible(state.item)) {
            state.closed = true;
            teardownOwned(state);
            return;
        }

        GlossConfig.RealDrops config = config();
        boolean onGround = state.item.isOnGround();
        int nextDelay = onGround
            ? config.limits().settledPollIntervalTicks()
            : config.limits().updateIntervalTicks();
        if (!presentationOwned(state)) {
            moveCarrier(state, nextDelay);
            state.onGround = onGround;
            state.lastVelocityY = state.item.getVelocity().getY();
            scheduleTick(state, nextDelay);
            return;
        }
        if (!moveReservation(state, chunkKey(state.item.getLocation()), config.limits().maxVisualsPerChunk())) {
            state.closed = true;
            teardownOwned(state);
            return;
        }
        refreshVisuals(state, config);
        moveCarrier(state, nextDelay);

        Vector velocity = state.item.getVelocity();
        double velocityY = velocity.getY();
        boolean bounced = !onGround && state.lastVelocityY < -0.02D && velocityY > 0.02D;
        if (bounced && config.motion().changeOnBounce()) {
            state.bounceRevision++;
            state.spin = RealDropModel.spin(state.item.getUniqueId(), state.bounceRevision, config.motion());
        }
        if (!onGround && config.motion().tumble()) {
            float seconds = config.limits().updateIntervalTicks() / 20.0F;
            state.rotation.rotateXYZ(
                state.spin.x() * seconds * DEG_TO_RAD,
                state.spin.y() * seconds * DEG_TO_RAD,
                state.spin.z() * seconds * DEG_TO_RAD);
            applyPose(state, state.rotation, config.limits().updateIntervalTicks());
        } else if (onGround && !state.onGround) {
            state.rotation.set(landingRotation(state, config));
            applyPose(state, state.rotation, config.landing().transitionTicks());
        }
        state.onGround = onGround;
        state.lastVelocityY = velocityY;
        scheduleTick(state, nextDelay);
    }

    private void failState(State state, RuntimeException failure) {
        state.closed = true;
        teardownOwned(state);
        Gloss.logExceptionStack(false, failure,
            "Could not update a real-drop presentation for item %s.", state.item.getUniqueId());
    }

    private void applyPose(State state, Quaternionf rotation, int interpolationTicks) {
        GlossConfig.RealDrops config = config();
        for (int index = 0; index < state.visuals.size(); index++) {
            ItemDisplay display = state.visuals.get(index);
            if (!display.isValid()) {
                continue;
            }
            display.setInterpolationDuration(Math.max(0, interpolationTicks));
            applyVisualTransformation(display, state, index, rotation, config);
        }
    }

    private void applyVisualTransformation(ItemDisplay display, State state, int index,
                                           Quaternionf rotation, GlossConfig.RealDrops config) {
        RealDropModel.Offset offset = RealDropModel.offset(index, config.limits().spread());
        float scale = RealDropModel.scale(state.modelKind, config.scale());
        Quaternionf indexedRotation = new Quaternionf(rotation);
        if (index > 0) {
            indexedRotation.rotateY(index * 0.41F);
        }
        display.setTransformation(new Transformation(
            new Vector3f(offset.x(), offset.y() + RealDropModel.yOffset(state.item.getItemStack().getType()), offset.z()),
            indexedRotation,
            new Vector3f(scale, scale, scale),
            new Quaternionf()));
    }

    private Quaternionf landingRotation(State state, GlossConfig.RealDrops config) {
        RealDropModel.Angles angles = RealDropModel.landing(state.item.getUniqueId(), state.modelKind, config.landing());
        return quaternion(angles);
    }

    private static Quaternionf baseRotation(RealDropModel.ModelKind kind) {
        return kind == RealDropModel.ModelKind.FLAT
            ? new Quaternionf().rotateX(90.0F * DEG_TO_RAD)
            : new Quaternionf();
    }

    private static Quaternionf quaternion(RealDropModel.Angles angles) {
        return new Quaternionf().rotateXYZ(
            angles.x() * DEG_TO_RAD,
            angles.y() * DEG_TO_RAD,
            angles.z() * DEG_TO_RAD);
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

    private void scheduleTeardown(State state) {
        boolean accepted = FoliaScheduler.runEntity(plugin, state.item,
            () -> teardownOwned(state), 0L, () -> retire(state));
        if (!accepted) {
            retire(state);
        }
    }

    private void teardownOwned(State state) {
        if (state.destroyed) {
            return;
        }
        state.destroyed = true;
        boolean current = states.remove(state.item.getUniqueId(), state);
        state.closed = true;
        for (ItemDisplay display : state.visuals) {
            removeEntity(display);
        }
        state.visuals.clear();
        if (state.label != null) {
            removeEntity(state.label);
            state.label = null;
        }
        release(state.chunkKey, state.reserved);
        state.reserved = 0;
        if (current && state.item.isValid()) {
            DisplayVisibility.setVisibleByDefault(state.item, state.restoreVisibleByDefault);
            state.item.setCustomNameVisible(state.restoreNameVisible && state.item.getCustomName() != null);
            clearMarkers(state.item);
        }
    }

    private void retire(State state) {
        if (state.destroyed) {
            return;
        }
        state.destroyed = true;
        states.remove(state.item.getUniqueId(), state);
        state.closed = true;
        release(state.chunkKey, state.reserved);
        state.reserved = 0;
        for (ItemDisplay display : state.visuals) {
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

    private boolean eligible(Item item) {
        if (!item.isValid() || item.isDead()) {
            return false;
        }
        if (disabledWorlds.contains(item.getWorld().getName().toLowerCase(Locale.ROOT))) {
            return false;
        }
        Material material = item.getItemStack().getType();
        if (material == Material.AIR || materialBlacklist.contains(material.name())) {
            return false;
        }
        return !config().filters().onlyPlayerDrops() || item.getThrower() != null;
    }

    private GlossConfig.RealDrops config() {
        return configSupplier.get();
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

    private static void setDisplayStack(ItemDisplay display, ItemStack stack) {
        ItemStack shown = stack.clone();
        shown.setAmount(1);
        display.setItemStack(shown);
    }

    private boolean presentationOwned(State state) {
        ItemDisplay carrier = carrierOrNull(state);
        return carrier != null && plugin.scheduler().isOwnedByCurrentRegion(carrier);
    }

    private void moveCarrier(State state, int interpolationTicks) {
        ItemDisplay carrier = carrierOrNull(state);
        if (carrier == null) {
            return;
        }
        Location destination = state.item.getLocation().clone();
        int duration = Math.max(0, Math.min(interpolationTicks, 59));
        plugin.scheduler().runEntity(carrier, () -> {
            if (!carrier.isValid()) {
                return;
            }
            carrier.setTeleportDuration(duration);
            plugin.scheduler().teleport(carrier, destination);
        });
    }

    private static ItemDisplay carrier(State state) {
        ItemDisplay carrier = carrierOrNull(state);
        if (carrier == null) {
            throw new IllegalStateException("Real-drop presentation has no ItemDisplay carrier");
        }
        return carrier;
    }

    private static ItemDisplay carrierOrNull(State state) {
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

    private static Set<String> normalized(List<String> values, boolean uppercase) {
        Set<String> normalized = new HashSet<>(values.size());
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            String clean = value.trim();
            normalized.add(uppercase ? clean.toUpperCase(Locale.ROOT) : clean.toLowerCase(Locale.ROOT));
        }
        return Set.copyOf(normalized);
    }

    record Label(List<String> lines) {
        Label {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }

        static Label none() {
            return new Label(List.of());
        }

        static Label single(String text) {
            return text == null || text.isEmpty() ? none() : new Label(List.of(text));
        }

        String text() {
            return String.join("\n", lines);
        }
    }

    private static final class State {
        private final Item item;
        private final long generation;
        private final List<ItemDisplay> visuals;
        private final boolean restoreVisibleByDefault;
        private final Quaternionf rotation;

        private ChunkKey chunkKey;
        private TextDisplay label;
        private String labelText = "";
        private RealDropModel.ModelKind modelKind;
        private RealDropModel.Angles spin;
        private int reserved;
        private int stackHash;
        private int bounceRevision;
        private double lastVelocityY;
        private boolean restoreNameVisible;
        private boolean onGround;
        private boolean closed;
        private boolean destroyed;

        private State(Item item, long generation, ChunkKey chunkKey, int reserved,
                      boolean restoreVisibleByDefault, boolean restoreNameVisible) {
            this.item = item;
            this.generation = generation;
            this.chunkKey = chunkKey;
            this.reserved = reserved;
            this.restoreVisibleByDefault = restoreVisibleByDefault;
            this.restoreNameVisible = restoreNameVisible;
            this.visuals = new ArrayList<>();
            this.rotation = new Quaternionf();
        }
    }

    private record ChunkKey(UUID worldId, int x, int z) {
    }
}
