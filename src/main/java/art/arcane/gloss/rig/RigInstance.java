package art.arcane.gloss.rig;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.animation.clip.ClipSample;
import art.arcane.gloss.api.IconBillboard;
import art.arcane.gloss.api.IconDisplayStyle;
import art.arcane.gloss.condition.GlossConditionContext;
import art.arcane.gloss.condition.GlossConditionScope;
import art.arcane.gloss.config.icon.CustomItemIconData;
import art.arcane.gloss.config.icon.ItemIconData;
import art.arcane.gloss.config.icon.ItemStackIconData;
import art.arcane.gloss.config.icon.MenuIconData;
import art.arcane.gloss.exceptions.MenuIconException;
import art.arcane.gloss.hologram.TextDisplayStyle;
import art.arcane.gloss.integration.ItemProviderRegistry;
import art.arcane.gloss.interaction.InteractionHitboxService;
import art.arcane.gloss.interaction.InteractionTarget;
import art.arcane.gloss.motion.MotionClipHandle;
import art.arcane.gloss.motion.TransformFrameSource;
import art.arcane.gloss.service.AdmissionBudget;
import art.arcane.gloss.service.VisibilityGovernor;
import art.arcane.gloss.util.common.DisplayEntity;
import art.arcane.gloss.util.common.EntityIdAllocator;
import art.arcane.gloss.util.common.ItemUtils;
import art.arcane.gloss.util.common.PacketUtils;
import art.arcane.gloss.util.common.TextUtils;
import art.arcane.volmlib.util.bukkit.registry.RegistryUtil;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static io.github.retrooper.packetevents.util.SpigotConversionUtil.fromBukkitBlockData;

public final class RigInstance {
    private static final long MILLIS_PER_TICK = 50L;

    private final Gloss plugin;
    private final RigService service;
    private final String id;
    private final CompiledRig rig;
    private final RigStateMachine machine;
    private final MotionClipHandle clip;
    private final int[] entityIds;
    private final List<DisplayEntity> displays;
    private final int minimalIndex;
    private final Map<UUID, ViewerState> viewers = new ConcurrentHashMap<>();
    private final Stream fullStream = new Stream(VisibilityGovernor.Tier.FULL);
    private final Stream reducedStream = new Stream(VisibilityGovernor.Tier.REDUCED);
    private final Object poseLock = new Object();
    private final Component[] renderedText;
    private final List<InteractionHitboxService.Handle> hitboxHandles = new ArrayList<>();
    private volatile RigInstanceDoc doc;
    private volatile Location origin;
    private volatile Transform root;
    private volatile List<Player> fullViewers = List.of();
    private volatile List<Player> reducedViewers = List.of();
    private volatile boolean retired;
    private long poseMs = Long.MIN_VALUE;
    private Map<String, ClipSample> samples = Map.of();
    private Map<String, Transform> pose = Map.of();

    private record ViewerState(Player player, VisibilityGovernor.Tier tier, List<AdmissionBudget.Lease> partLeases,
                               AdmissionBudget.Lease governorLease) {
        void release() {
            for (AdmissionBudget.Lease lease : partLeases) {
                lease.close();
            }
            if (governorLease != null) {
                governorLease.close();
            }
        }
    }

    public record StreamKey(String instance, VisibilityGovernor.Tier tier) {
    }

    RigInstance(Gloss plugin, RigService service, String id, RigInstanceDoc doc, CompiledRig rig) {
        this.plugin = plugin;
        this.service = service;
        this.id = Objects.requireNonNull(id, "id");
        this.rig = Objects.requireNonNull(rig, "rig");
        this.doc = Objects.requireNonNull(doc, "doc");
        this.machine = new RigStateMachine(id, rig.graph(), doc.vars());
        if (doc.state() != null) {
            machine.setState(doc.state());
        }
        this.clip = new MotionClipHandle(service.motion());
        this.entityIds = EntityIdAllocator.global().nextBlock(rig.parts().size());
        this.minimalIndex = rig.minimalPartIndex();
        this.origin = location(doc);
        this.root = RigPose.rootTransform(doc.yaw(), doc.pitch(), (float) (double) doc.scale());
        this.renderedText = new Component[rig.parts().size()];
        this.displays = buildDisplays();
        applyPose(Long.MIN_VALUE);
        registerHitboxes();
    }

    public String id() {
        return id;
    }

    public RigInstanceDoc doc() {
        return doc;
    }

    public CompiledRig rig() {
        return rig;
    }

    public RigStateMachine machine() {
        return machine;
    }

    public MotionClipHandle clip() {
        return clip;
    }

    public Location origin() {
        return origin == null ? null : origin.clone();
    }

    public String state() {
        return machine.state();
    }

    public int viewerCount() {
        return viewers.size();
    }

    public int partCount() {
        return rig.parts().size();
    }

    public int[] entityIds() {
        return entityIds.clone();
    }

    public boolean retired() {
        return retired;
    }

    public boolean hasViewer(UUID viewerId) {
        return viewers.containsKey(viewerId);
    }

    public Set<UUID> viewerIds() {
        return Set.copyOf(viewers.keySet());
    }

    public Location partLocation(String partId) {
        Location base = origin;
        int index = rig.partIndex(partId);
        if (base == null || index < 0) {
            return null;
        }
        Transform transform;
        synchronized (poseLock) {
            transform = pose.get(partId);
        }
        if (transform == null) {
            return base.clone();
        }
        return base.clone().add(transform.translation().getX(), transform.translation().getY(), transform.translation().getZ());
    }

    public boolean ensureViewer(Player player, VisibilityGovernor.Tier tier) {
        if (retired || origin == null || tier == VisibilityGovernor.Tier.CULLED) {
            despawnFor(player.getUniqueId());
            return false;
        }
        UUID viewerId = player.getUniqueId();
        ViewerState current = viewers.get(viewerId);
        if (current != null && current.player() == player) {
            if (current.tier() == tier) {
                return true;
            }
            if (sameEntitySet(current.tier(), tier)) {
                viewers.put(viewerId, new ViewerState(player, tier, current.partLeases(), current.governorLease()));
                rebuildViewerLists();
                return true;
            }
            despawnFor(viewerId);
        } else if (current != null) {
            despawnFor(viewerId);
        }
        return spawnFor(player, tier);
    }

    public void despawnFor(UUID viewerId) {
        ViewerState removed = viewers.remove(viewerId);
        if (removed == null) {
            return;
        }
        removed.release();
        Player player = removed.player();
        if (player.isOnline()) {
            PacketUtils.send(player, List.of(DisplayEntity.destroyAll(idsFor(removed.tier()))));
        }
        rebuildViewerLists();
    }

    void tick(long nowMs) {
        if (retired) {
            return;
        }
        boolean finished = clip.finished(nowMs);
        boolean changed = machine.step(conditionScope(), finished);
        String clipName = machine.clip();
        RigDoc.ClipRef reference = clipName == null ? null : rig.clips().get(clipName);
        if (changed || (!clip.playing() && reference != null)) {
            if (reference == null) {
                clip.stop();
            } else if (!clip.play(reference.motion(), nowMs, reference.loopOverride())) {
                Gloss.warnThrottled("rig-missing-motion-" + rig.id() + "-" + reference.motion(),
                    "Rig %s state %s references unknown motion %s.", rig.id(), machine.state(), reference.motion());
                clip.stop();
            }
        }
        if (changed) {
            service.persistState(this);
        }
        if (!viewers.isEmpty()) {
            refreshText();
        }
        publishStreams(nowMs);
    }

    void updateDoc(RigInstanceDoc next) {
        RigInstanceDoc previous = doc;
        doc = next;
        boolean moved = !previous.world().equals(next.world()) || previous.x() != next.x() || previous.y() != next.y()
            || previous.z() != next.z();
        boolean turned = previous.yaw() != next.yaw() || previous.pitch() != next.pitch()
            || previous.scale() != next.scale();
        if (turned) {
            root = RigPose.rootTransform(next.yaw(), next.pitch(), (float) (double) next.scale());
            invalidatePose();
        }
        if (moved) {
            Location target = location(next);
            boolean worldChanged = !previous.world().equals(next.world());
            origin = target;
            if (worldChanged || target == null) {
                despawnAll();
            } else {
                List<PacketWrapper<?>> teleports = new ArrayList<>(displays.size());
                for (DisplayEntity display : displays) {
                    teleports.add(display.goTo(target));
                }
                for (ViewerState state : viewers.values()) {
                    if (state.player().isOnline()) {
                        PacketUtils.send(state.player(), teleports);
                    }
                }
            }
        }
        Map<String, Object> vars = next.vars();
        for (String name : machine.vars().keySet()) {
            if (!vars.containsKey(name)) {
                machine.setVar(name, null);
            }
        }
        for (Map.Entry<String, Object> entry : vars.entrySet()) {
            machine.setVar(entry.getKey(), entry.getValue());
        }
        if (next.state() != null) {
            machine.setState(next.state());
        }
    }

    void retire() {
        retired = true;
        despawnAll();
        InteractionHitboxService interaction = service.interaction();
        if (interaction != null) {
            for (InteractionHitboxService.Handle handle : hitboxHandles) {
                interaction.unregister(handle);
            }
        }
        hitboxHandles.clear();
        service.streamer().remove(new StreamKey(id, VisibilityGovernor.Tier.FULL));
        service.streamer().remove(new StreamKey(id, VisibilityGovernor.Tier.REDUCED));
        fullStream.published = false;
        reducedStream.published = false;
        clip.stop();
    }

    private void despawnAll() {
        for (UUID viewerId : List.copyOf(viewers.keySet())) {
            despawnFor(viewerId);
        }
    }

    private void registerHitboxes() {
        InteractionHitboxService interaction = service.interaction();
        if (interaction == null) {
            return;
        }
        for (CompiledRig.CompiledHitbox hitbox : rig.hitboxes()) {
            float width = Math.max(hitbox.size().getX(), hitbox.size().getZ());
            float height = hitbox.size().getY();
            String componentId = "hitbox:" + hitbox.part();
            InteractionTarget target = new InteractionTarget("rig:" + rig.id(), id + "/" + hitbox.part(),
                () -> hitboxOrigin(hitbox.part(), height), width, height, hitbox.actions(),
                (player, trigger) -> new RigClickContext(plugin, this, componentId, player, trigger), () -> !retired);
            hitboxHandles.add(interaction.register(target));
        }
    }

    private Location hitboxOrigin(String partId, float height) {
        Location center = partLocation(partId);
        return center == null ? null : center.subtract(0.0D, height / 2.0D, 0.0D);
    }

    private boolean spawnFor(Player player, VisibilityGovernor.Tier tier) {
        int[] ids = idsFor(tier);
        List<AdmissionBudget.Lease> partLeases = new ArrayList<>(ids.length);
        AdmissionBudget budget = service.viewerBudget(player.getUniqueId());
        for (int index = 0; index < ids.length; index++) {
            AdmissionBudget.Lease lease = budget.tryAcquire();
            if (lease == null) {
                for (AdmissionBudget.Lease acquired : partLeases) {
                    acquired.close();
                }
                return false;
            }
            partLeases.add(lease);
        }
        AdmissionBudget.Lease governorLease = plugin.governor().admit(player, VisibilityGovernor.Surface.RIG, ids.length);
        if (governorLease == null) {
            for (AdmissionBudget.Lease acquired : partLeases) {
                acquired.close();
            }
            return false;
        }
        if (viewers.isEmpty()) {
            refreshText();
        }
        List<PacketWrapper<?>> packets = new ArrayList<>(ids.length * 2);
        // Under the pose lock: the motion thread writes these same display fields, and a spawn
        // built across a write would hand the new viewer some parts from one frame and some from
        // the next, which the transform diff would then never correct.
        synchronized (poseLock) {
            applyPose(System.currentTimeMillis());
            for (int index : partIndicesFor(tier)) {
                packets.addAll(displays.get(index).spawn());
            }
        }
        viewers.put(player.getUniqueId(), new ViewerState(player, tier, List.copyOf(partLeases), governorLease));
        PacketUtils.send(player, packets);
        rebuildViewerLists();
        return true;
    }

    private static boolean sameEntitySet(VisibilityGovernor.Tier first, VisibilityGovernor.Tier second) {
        boolean firstMinimal = first == VisibilityGovernor.Tier.MINIMAL;
        boolean secondMinimal = second == VisibilityGovernor.Tier.MINIMAL;
        return firstMinimal == secondMinimal;
    }

    private int[] idsFor(VisibilityGovernor.Tier tier) {
        if (tier == VisibilityGovernor.Tier.MINIMAL) {
            return new int[]{entityIds[minimalIndex]};
        }
        return entityIds;
    }

    private int[] partIndicesFor(VisibilityGovernor.Tier tier) {
        if (tier == VisibilityGovernor.Tier.MINIMAL) {
            return new int[]{minimalIndex};
        }
        int[] all = new int[rig.parts().size()];
        for (int index = 0; index < all.length; index++) {
            all[index] = index;
        }
        return all;
    }

    private void rebuildViewerLists() {
        List<Player> full = new ArrayList<>();
        List<Player> reduced = new ArrayList<>();
        for (ViewerState state : viewers.values()) {
            if (state.tier() == VisibilityGovernor.Tier.FULL) {
                full.add(state.player());
            } else if (state.tier() == VisibilityGovernor.Tier.REDUCED) {
                reduced.add(state.player());
            }
        }
        fullViewers = List.copyOf(full);
        reducedViewers = List.copyOf(reduced);
        publishStreams(System.currentTimeMillis());
    }

    private void publishStreams(long nowMs) {
        fullStream.publishIfActive(nowMs);
        reducedStream.publishIfActive(nowMs);
    }

    private GlossConditionScope conditionScope() {
        return new GlossConditionScope(plugin, GlossConditionContext.subject(null));
    }

    /**
     * Only for an instance somebody can see. Rendering and parsing every text part of every
     * instance on a ten-tick driver with the server empty is pure cost; a viewer being admitted to
     * an instance that had none refreshes first, so they never spawn on stale text.
     */
    private void refreshText() {
        List<Part> parts = rig.parts();
        List<Player> audience = allViewers();
        for (int index = 0; index < parts.size(); index++) {
            Part part = parts.get(index);
            if (part.type() != PartType.TEXT) {
                continue;
            }
            Component rendered = renderText(part);
            if (rendered.equals(renderedText[index])) {
                continue;
            }
            renderedText[index] = rendered;
            displays.get(index).text(rendered);
            if (!audience.isEmpty()) {
                PacketUtils.broadcast(audience, DisplayEntity.textUpdate(entityIds[index], rendered), () -> !retired);
            }
        }
    }

    private List<Player> allViewers() {
        List<Player> all = new ArrayList<>(viewers.size());
        for (ViewerState state : viewers.values()) {
            all.add(state.player());
        }
        return all;
    }

    private Component renderText(Part part) {
        String raw = part.text();
        String rendered = RigNamespace.with(machine, () -> plugin.text().render(null, raw));
        return TextUtils.parse(rendered);
    }

    private void invalidatePose() {
        synchronized (poseLock) {
            poseMs = Long.MIN_VALUE;
        }
    }

    private Map<String, Transform> applyPose(long nowMs) {
        synchronized (poseLock) {
            if (poseMs == nowMs && nowMs != Long.MIN_VALUE) {
                return pose;
            }
            samples = nowMs == Long.MIN_VALUE || !clip.playing()
                ? Map.of()
                : clip.sampleBones(rig.model().bones().keySet(), nowMs);
            pose = RigPose.partTransforms(rig.model(), rig.parts(), root, samples);
            poseMs = nowMs;
            for (int index = 0; index < displays.size(); index++) {
                Transform transform = pose.get(rig.parts().get(index).id());
                DisplayEntity display = displays.get(index);
                display.translation(transform.translation()).scale(transform.scale()).leftRotation(transform.rotation());
            }
            return pose;
        }
    }

    private List<PacketWrapper<?>> frame(long nowMs, Stream stream) {
        Map<String, Transform> current;
        Map<String, ClipSample> currentSamples;
        synchronized (poseLock) {
            applyPose(nowMs);
            current = pose;
            currentSamples = samples;
        }
        int frameTicks = (int) Math.max(1L, Math.round(1000.0D / stream.fps() / MILLIS_PER_TICK));
        List<PacketWrapper<?>> packets = stream.transforms(current, frameTicks);
        stream.appendPresentation(packets, currentSamples);
        return packets;
    }

    private List<DisplayEntity> buildDisplays() {
        List<Part> parts = rig.parts();
        List<DisplayEntity> built = new ArrayList<>(parts.size());
        Location base = origin == null ? new Location(null, doc.x(), doc.y(), doc.z()) : origin;
        for (int index = 0; index < parts.size(); index++) {
            built.add(buildDisplay(parts.get(index), entityIds[index], base, index));
        }
        return List.copyOf(built);
    }

    private DisplayEntity buildDisplay(Part part, int entityId, Location base, int index) {
        DisplayEntity display = switch (part.type()) {
            case BLOCK -> new DisplayEntity(entityId, UUID.randomUUID(), EntityTypes.BLOCK_DISPLAY)
                .displayKind(DisplayEntity.DisplayKind.BLOCK)
                .blockState(blockState(part));
            case ITEM -> new DisplayEntity(entityId, UUID.randomUUID(), EntityTypes.ITEM_DISPLAY)
                .displayKind(DisplayEntity.DisplayKind.ITEM)
                .item(resolveItem(part));
            case TEXT -> {
                Component rendered = renderText(part);
                renderedText[index] = rendered;
                yield new DisplayEntity(entityId, UUID.randomUUID(), EntityTypes.TEXT_DISPLAY)
                    .displayKind(DisplayEntity.DisplayKind.TEXT)
                    .text(rendered);
            }
        };
        display.noGravity(true).location(PacketUtils.vector3d(base.toVector()));
        IconDisplayStyle style = part.style() == null
            ? (part.type() == PartType.TEXT ? IconDisplayStyle.hologramDefaults() : IconDisplayStyle.defaults())
            : part.style();
        TextDisplayStyle.apply(display, style);
        display.billboard(billboard(part, style).metadataValue());
        if (part.brightness() != null) {
            display.brightness(packedBrightness(part.brightness()));
        }
        return display;
    }

    private static IconBillboard billboard(Part part, IconDisplayStyle style) {
        if (part.billboard() != null) {
            return IconBillboard.valueOf(part.billboard().toUpperCase(Locale.ROOT));
        }
        if (part.style() != null) {
            return style.billboard();
        }
        return part.type() == PartType.TEXT ? IconBillboard.CENTER : IconBillboard.FIXED;
    }

    static int packedBrightness(int level) {
        return (level << 4) | (level << 20);
    }

    private int blockState(Part part) {
        Material material = material(part.block());
        if (material == null || !material.isBlock()) {
            Gloss.warnThrottled("rig-block-" + rig.id() + "-" + part.id(),
                "Rig %s part %s names unknown block %s; using stone.", rig.id(), part.id(), part.block());
            material = Material.STONE;
        }
        BlockData data = material.createBlockData();
        return fromBukkitBlockData(data).getGlobalId();
    }

    private ItemStack resolveItem(Part part) {
        MenuIconData icon = part.item();
        ItemStack resolved = null;
        try {
            if (icon instanceof ItemIconData item) {
                Material material = item.requireMaterial();
                resolved = new ItemUtils.Builder(material, item.count() > 0 ? item.count() : 1)
                    .modelData(item.customModelValue()).get();
            } else if (icon instanceof ItemStackIconData stack) {
                resolved = stack.stack().clone();
            } else if (icon instanceof CustomItemIconData custom) {
                ItemProviderRegistry registry = plugin.getItemProviders();
                resolved = registry == null ? null : registry.resolve(custom.provider(), custom.item());
            }
        } catch (MenuIconException | RuntimeException failure) {
            resolved = null;
        }
        if (resolved == null) {
            Gloss.warnThrottled("rig-item-" + rig.id() + "-" + part.id(),
                "Rig %s part %s could not resolve its item; using a barrier.", rig.id(), part.id());
            return new ItemStack(Material.BARRIER);
        }
        return resolved;
    }

    private static Material material(String key) {
        try {
            NamespacedKey namespaced = NamespacedKey.fromString(key);
            return namespaced == null ? null : RegistryUtil.find(Material.class, namespaced);
        } catch (RuntimeException | LinkageError failure) {
            return Material.matchMaterial(key);
        }
    }

    private static Location location(RigInstanceDoc doc) {
        World world = Bukkit.getWorld(doc.world());
        return world == null ? null : new Location(world, doc.x(), doc.y(), doc.z());
    }

    private final class Stream implements TransformFrameSource {
        private final VisibilityGovernor.Tier tier;
        private final Map<String, RigFrames.Presentation> sent = new HashMap<>();
        private final Map<String, Transform> sentTransforms = new HashMap<>();
        private volatile boolean published;

        private Stream(VisibilityGovernor.Tier tier) {
            this.tier = tier;
        }

        @Override
        public List<PacketWrapper<?>> compose(long nowMs) {
            return frame(nowMs, this);
        }

        @Override
        public List<Player> viewers() {
            return tier == VisibilityGovernor.Tier.FULL ? fullViewers : reducedViewers;
        }

        @Override
        public boolean live() {
            return !retired && clip.streaming(System.currentTimeMillis());
        }

        @Override
        public int fps() {
            int base = Math.max(1, clip.fps());
            return tier == VisibilityGovernor.Tier.FULL ? base : Math.max(1, base / 2);
        }

        private void publishIfActive(long nowMs) {
            boolean active = !retired && clip.streaming(nowMs) && !viewers().isEmpty();
            StreamKey key = new StreamKey(id, tier);
            if (active && !published) {
                published = true;
                synchronized (sentTransforms) {
                    sentTransforms.clear();
                }
                service.streamer().publish(key, this);
            } else if (!active && published) {
                published = false;
                service.streamer().remove(key);
            }
        }

        /** The transform of each part as this stream last sent it, so a still pose costs nothing. */
        private List<PacketWrapper<?>> transforms(Map<String, Transform> pose, int frameTicks) {
            synchronized (sentTransforms) {
                return RigFrames.transformPackets(rig.parts(), entityIds, pose, frameTicks, sentTransforms);
            }
        }

        private void appendPresentation(List<PacketWrapper<?>> packets, Map<String, ClipSample> currentSamples) {
            List<Part> parts = rig.parts();
            synchronized (sent) {
                for (int index = 0; index < parts.size(); index++) {
                    Part part = parts.get(index);
                    ClipSample sample = currentSamples.get(part.bone());
                    RigFrames.Presentation next = RigFrames.presentation(sample);
                    RigFrames.Presentation previous = sent.get(part.id());
                    if (next.equals(previous)) {
                        continue;
                    }
                    sent.put(part.id(), next);
                    packets.add(RigFrames.presentationUpdate(entityIds[index], displays.get(index), next));
                }
            }
        }
    }
}
