package art.arcane.gloss.rig;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.doc.DocumentDelta;
import art.arcane.gloss.doc.DocumentParsers;
import art.arcane.gloss.doc.DocumentRegistry;
import art.arcane.gloss.doc.DocumentReviser;
import art.arcane.gloss.doc.DocumentStore;
import art.arcane.gloss.doc.GlossDocument;
import art.arcane.gloss.doc.ShippedDefaults;
import art.arcane.gloss.doc.ShippedDocumentCatalog;
import art.arcane.gloss.expr.ExprVariableNamespaces;
import art.arcane.gloss.interaction.InteractionHitboxService;
import art.arcane.gloss.motion.MotionService;
import art.arcane.gloss.motion.TransformStreamer;
import art.arcane.gloss.service.AdmissionBudget;
import art.arcane.gloss.service.GlossService;
import art.arcane.gloss.service.VisibilityGovernor;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.volmlib.util.scheduling.SchedulerUtils;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

public final class RigService implements GlossService, Listener {
    public static final String NAME = "rigs";
    public static final int DRIVER_INTERVAL_TICKS = 10;
    public static final int VIEWER_PART_CAP = 256;
    private static final int NO_TASK = -1;
    private static final DocumentReviser<RigDoc> RIG_REVISER = new DocumentReviser<>() {
        @Override
        public long revisionOf(RigDoc value) {
            return value.revision();
        }

        @Override
        public RigDoc withRevision(RigDoc value, long revision) {
            return value.withRevision(revision);
        }
    };
    private static final DocumentReviser<RigInstanceDoc> INSTANCE_REVISER = new DocumentReviser<>() {
        @Override
        public long revisionOf(RigInstanceDoc value) {
            return value.revision();
        }

        @Override
        public RigInstanceDoc withRevision(RigInstanceDoc value, long revision) {
            return value.withRevision(revision);
        }
    };

    private final Gloss plugin;
    private final ShippedDefaults rigDefaults;
    private final DocumentStore<RigDoc> rigStore;
    private final DocumentRegistry<RigDoc> rigRegistry;
    private final DocumentStore<RigInstanceDoc> instanceStore;
    private final DocumentRegistry<RigInstanceDoc> instanceRegistry;
    private final RigViewerIndex viewerIndex;
    private final Map<String, CompiledRig> rigs = new ConcurrentHashMap<>();
    private final Map<String, RigInstance> instances = new ConcurrentHashMap<>();
    private final Map<UUID, AdmissionBudget> viewerBudgets = new ConcurrentHashMap<>();
    private volatile boolean started;
    private int driverTaskId = NO_TASK;

    private record Candidate(RigInstance instance, double distanceSquared) {
    }

    public RigService(Gloss plugin) {
        this.plugin = plugin;
        File rigFolder = new File(plugin.getDataFolder(), RigDoc.KIND);
        File instanceFolder = new File(plugin.getDataFolder(), RigInstanceDoc.KIND);
        this.rigDefaults = new ShippedDefaults(RigDoc.KIND, rigFolder, ShippedDocumentCatalog.RIGS_ENTRY.names());
        this.rigStore = new DocumentStore<>(RigDoc.KIND, rigFolder, RIG_REVISER);
        this.rigRegistry = DocumentRegistry.folder(RigDoc.KIND, rigFolder, RigDoc::parse, RigDoc::revision,
            rigStore::isOwnWrite);
        this.instanceStore = new DocumentStore<>(RigInstanceDoc.KIND, instanceFolder, INSTANCE_REVISER);
        this.instanceRegistry = DocumentRegistry.folder(RigInstanceDoc.KIND, instanceFolder, RigInstanceDoc::parse,
            RigInstanceDoc::revision, instanceStore::isOwnWrite);
        this.viewerIndex = new RigViewerIndex();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void contribute() {
        ExprVariableNamespaces.global().register(new RigNamespace());
    }

    @Override
    public void enable() {
        if (!plugin.cfg().modules().rigs().enabled()) {
            return;
        }
        started = true;
        reload();
        plugin.watchdog().register(RigDoc.KIND, this::pollRigs);
        plugin.watchdog().register(RigInstanceDoc.KIND, this::pollInstances);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        for (Player player : Bukkit.getOnlinePlayers()) {
            captureViewer(player);
        }
        driverTaskId = plugin.scheduler().sr(this::drive, DRIVER_INTERVAL_TICKS);
    }

    @Override
    public void disable() {
        started = false;
        plugin.watchdog().unregister(RigDoc.KIND);
        plugin.watchdog().unregister(RigInstanceDoc.KIND);
        HandlerList.unregisterAll(this);
        if (driverTaskId != NO_TASK) {
            plugin.scheduler().csr(driverTaskId);
            driverTaskId = NO_TASK;
        }
        retireAll();
        rigRegistry.close();
        instanceRegistry.close();
        rigStore.forgetAll();
        instanceStore.forgetAll();
        rigs.clear();
        viewerIndex.clear();
        viewerBudgets.clear();
    }

    @Override
    public void reload() {
        rigDefaults.extractMissing();
        rigRegistry.reload();
        instanceRegistry.reload();
        rebuild();
    }

    @Override
    public boolean reloadOnConfigChange(GlossConfig previous, GlossConfig next) {
        return !previous.modules().rigs().equals(next.modules().rigs());
    }

    public MotionService motion() {
        return plugin.service(MotionService.class);
    }

    public InteractionHitboxService interaction() {
        return plugin.service(InteractionHitboxService.class);
    }

    public TransformStreamer streamer() {
        return motion().streamer();
    }

    public RigViewerIndex viewerIndex() {
        return viewerIndex;
    }

    public AdmissionBudget viewerBudget(UUID viewerId) {
        return viewerBudgets.computeIfAbsent(viewerId, ignored -> new AdmissionBudget(VIEWER_PART_CAP));
    }

    public Set<String> rigIds() {
        return Set.copyOf(rigs.keySet());
    }

    public Optional<CompiledRig> rig(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(rigs.get(id));
    }

    public Collection<RigInstance> instances() {
        return List.copyOf(instances.values());
    }

    public Optional<RigInstance> instance(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(instances.get(id));
    }

    public DocumentStore<RigDoc> rigStore() {
        return rigStore;
    }

    public List<String> resetToDefault(String name) {
        List<String> restored = rigDefaults.resetToDefault(name);
        if (!restored.isEmpty()) {
            reload();
        }
        return restored;
    }

    public RigInstanceDoc place(String rigId, Location location, String requestedId) throws IOException {
        CompiledRig rig = rigs.get(rigId);
        if (rig == null) {
            throw new IllegalArgumentException("unknown rig " + rigId);
        }
        if (location.getWorld() == null) {
            throw new IllegalArgumentException("rig placement needs a world");
        }
        String id = requestedId == null || requestedId.isBlank() ? nextInstanceId(rigId) : requestedId.trim();
        if (instances.containsKey(id)) {
            throw new IllegalArgumentException("rig instance " + id + " already exists");
        }
        int cap = plugin.cfg().modules().rigs().maxInstancesPerChunk();
        if (instancesInChunk(location) >= cap) {
            throw new IllegalArgumentException("chunk already holds " + cap + " rig instances");
        }
        RigInstanceDoc doc = RigInstanceDoc.placed(rigId, location.getWorld().getName(), location.getX(), location.getY(),
            location.getZ(), location.getYaw(), location.getPitch());
        instanceStore.write(id, doc);
        instanceRegistry.publish(id, DocumentParsers.GSON.toJson(doc), doc);
        instances.put(id, new RigInstance(plugin, this, id, doc, rig));
        return doc;
    }

    public boolean move(String id, Location location) throws IOException {
        if (location.getWorld() == null) {
            return false;
        }
        return mutate(id, current -> current.withPosition(location.getWorld().getName(), location.getX(), location.getY(),
            location.getZ(), current.yaw(), current.pitch()));
    }

    public boolean rotate(String id, float yaw, float pitch) throws IOException {
        return mutate(id, current -> current.withPosition(current.world(), current.x(), current.y(), current.z(), yaw, pitch));
    }

    public boolean scale(String id, double scale) throws IOException {
        return mutate(id, current -> current.withScale(scale));
    }

    public boolean setState(String id, String state) {
        RigInstance instance = instances.get(id);
        if (instance == null || !instance.machine().setState(state)) {
            return false;
        }
        persistState(instance);
        return true;
    }

    public boolean setVar(String id, String name, Object value) {
        RigInstance instance = instances.get(id);
        if (instance == null) {
            return false;
        }
        instance.machine().setVar(name, value);
        Map<String, Object> vars = instance.machine().vars();
        try {
            mutate(id, current -> current.withVars(vars));
        } catch (IOException failure) {
            Gloss.logExceptionStack(false, failure, "Failed to save rig instance %s.", id);
        }
        return true;
    }

    public boolean remove(String id) throws IOException {
        RigInstance removed = instances.remove(id);
        if (removed == null) {
            return false;
        }
        removed.retire();
        instanceStore.delete(id);
        instanceRegistry.remove(id);
        return true;
    }

    void persistState(RigInstance instance) {
        String state = instance.machine().state();
        try {
            mutate(instance.id(), current -> current.withState(state));
        } catch (IOException failure) {
            Gloss.logExceptionStack(false, failure, "Failed to save rig instance %s.", instance.id());
        }
    }

    private boolean mutate(String id, UnaryOperator<RigInstanceDoc> update) throws IOException {
        RigInstance instance = instances.get(id);
        if (instance == null) {
            return false;
        }
        RigInstanceDoc current = instance.doc();
        RigInstanceDoc next = instanceStore.mutate(id, current, current.revision(), update);
        instanceRegistry.publish(id, DocumentParsers.GSON.toJson(next), next);
        instance.updateDoc(next);
        return true;
    }

    private String nextInstanceId(String rigId) {
        int index = 1;
        while (instances.containsKey(rigId + "-" + index)) {
            index++;
        }
        return rigId + "-" + index;
    }

    private int instancesInChunk(Location location) {
        int count = 0;
        int chunkX = location.getBlockX() >> 4;
        int chunkZ = location.getBlockZ() >> 4;
        for (RigInstance instance : instances.values()) {
            Location origin = instance.origin();
            if (origin == null || origin.getWorld() != location.getWorld()) {
                continue;
            }
            if (origin.getBlockX() >> 4 == chunkX && origin.getBlockZ() >> 4 == chunkZ) {
                count++;
            }
        }
        return count;
    }

    private void rebuild() {
        retireAll();
        rigs.clear();
        int partCap = plugin.cfg().modules().rigs().maxPartsPerRig();
        for (Map.Entry<String, GlossDocument<RigDoc>> entry : rigRegistry.snapshot().entrySet()) {
            compileRig(entry.getKey(), entry.getValue().value(), partCap);
        }
        for (Map.Entry<String, GlossDocument<RigInstanceDoc>> entry : instanceRegistry.snapshot().entrySet()) {
            instantiate(entry.getKey(), entry.getValue().value());
        }
        if (!rigs.isEmpty() || !instances.isEmpty()) {
            Gloss.info("Loaded " + rigs.size() + " rigs and " + instances.size() + " rig instances.");
        }
    }

    private void compileRig(String id, RigDoc doc, int partCap) {
        try {
            rigs.put(id, CompiledRig.compile(id, doc, partCap));
        } catch (IllegalArgumentException failure) {
            Gloss.warn("rigs/%s.json refused: %s", id, failure.getMessage());
        }
    }

    private void instantiate(String id, RigInstanceDoc doc) {
        CompiledRig rig = rigs.get(doc.rig());
        if (rig == null) {
            Gloss.warnThrottled("rig-instance-missing-rig-" + id,
                "rig-instances/%s.json references unknown rig %s; not placed.", id, doc.rig());
            return;
        }
        RigInstance previous = instances.remove(id);
        if (previous != null) {
            previous.retire();
        }
        instances.put(id, new RigInstance(plugin, this, id, doc, rig));
    }

    private void retireAll() {
        for (RigInstance instance : List.copyOf(instances.values())) {
            instance.retire();
        }
        instances.clear();
    }

    private void pollRigs() {
        DocumentDelta delta = rigRegistry.poll();
        if (delta.isEmpty()) {
            return;
        }
        if (!rigRegistry.dispatch(delta, task -> SchedulerUtils.runGlobal(plugin, task), () -> applyRigDelta(delta))) {
            Gloss.warnThrottled("rig-hotload-scheduling",
                "Rig hot reload could not reach the server thread; the change will be retried.");
        }
    }

    private void applyRigDelta(DocumentDelta delta) {
        int partCap = plugin.cfg().modules().rigs().maxPartsPerRig();
        for (String id : delta.loaded()) {
            GlossDocument<RigDoc> document = rigRegistry.get(delta, id);
            if (document == null) {
                continue;
            }
            compileRig(id, document.value(), partCap);
            Gloss.info("Hotloaded rig " + id + ".json");
            reinstantiate(id);
        }
        for (String id : delta.removed()) {
            rigs.remove(id);
            reinstantiate(id);
        }
    }

    private void reinstantiate(String rigId) {
        for (RigInstance instance : List.copyOf(instances.values())) {
            if (!instance.doc().rig().equals(rigId)) {
                continue;
            }
            instance.retire();
            instances.remove(instance.id());
            instantiate(instance.id(), instance.doc());
        }
    }

    private void pollInstances() {
        DocumentDelta delta = instanceRegistry.poll();
        if (delta.isEmpty()) {
            return;
        }
        if (!instanceRegistry.dispatch(delta, task -> SchedulerUtils.runGlobal(plugin, task),
            () -> applyInstanceDelta(delta))) {
            Gloss.warnThrottled("rig-instance-hotload-scheduling",
                "Rig instance hot reload could not reach the server thread; the change will be retried.");
        }
    }

    private void applyInstanceDelta(DocumentDelta delta) {
        for (String id : delta.loaded()) {
            GlossDocument<RigInstanceDoc> document = instanceRegistry.get(delta, id);
            if (document == null) {
                continue;
            }
            RigInstance existing = instances.get(id);
            if (existing != null && existing.doc().rig().equals(document.value().rig())) {
                existing.updateDoc(document.value());
            } else {
                instantiate(id, document.value());
            }
            Gloss.info("Hotloaded rig instance " + id + ".json");
        }
        for (String id : delta.removed()) {
            RigInstance removed = instances.remove(id);
            if (removed != null) {
                removed.retire();
            }
        }
    }

    private void drive() {
        if (!started) {
            return;
        }
        long nowMs = System.currentTimeMillis();
        reconcileAudiences();
        for (RigInstance instance : instances.values()) {
            instance.tick(nowMs);
        }
    }

    private void reconcileAudiences() {
        Map<UUID, List<Candidate>> perViewer = new HashMap<>();
        Map<UUID, Player> players = new HashMap<>();
        for (RigInstance instance : instances.values()) {
            Location origin = instance.origin();
            if (origin == null) {
                continue;
            }
            RigDoc.Lod lod = instance.rig().doc().lod();
            Set<UUID> keep = new java.util.HashSet<>();
            for (RigViewerIndex.Viewer viewer : viewerIndex.nearby(origin, lod.cullAt())) {
                if (plugin.bedrock().isBedrock(viewer.id()) || !admits(instance, viewer.player())) {
                    continue;
                }
                keep.add(viewer.id());
                players.putIfAbsent(viewer.id(), viewer.player());
                perViewer.computeIfAbsent(viewer.id(), ignored -> new ArrayList<>())
                    .add(new Candidate(instance, viewer.distanceSquared(origin)));
            }
            for (UUID viewerId : instance.viewerIds()) {
                if (!keep.contains(viewerId)) {
                    instance.despawnFor(viewerId);
                }
            }
        }
        for (Map.Entry<UUID, List<Candidate>> entry : perViewer.entrySet()) {
            Player player = players.get(entry.getKey());
            List<Candidate> candidates = entry.getValue();
            candidates.sort(Comparator.comparingDouble(Candidate::distanceSquared));
            for (Candidate candidate : candidates) {
                VisibilityGovernor.Tier tier = tier(candidate, player);
                candidate.instance().ensureViewer(player, tier);
            }
        }
    }

    private boolean admits(RigInstance instance, Player player) {
        RigDoc.Audience rigAudience = instance.rig().doc().audience();
        RigDoc.Audience instanceAudience = instance.doc().audience();
        return RigNamespace.with(instance.machine(), () -> rigAudience.when().matches(plugin, player)
            && instanceAudience.when().matches(plugin, player));
    }

    private VisibilityGovernor.Tier tier(Candidate candidate, Player player) {
        RigDoc.Lod lod = candidate.instance().rig().doc().lod();
        double distance = Math.sqrt(candidate.distanceSquared());
        VisibilityGovernor.Tier byDistance = distance < lod.reducedAt() ? VisibilityGovernor.Tier.FULL
            : distance < lod.minimalAt() ? VisibilityGovernor.Tier.REDUCED
            : VisibilityGovernor.Tier.MINIMAL;
        VisibilityGovernor.Tier governed = plugin.governor().tier(player, VisibilityGovernor.Surface.RIG,
            candidate.distanceSquared());
        return governed.ordinal() > byDistance.ordinal() ? governed : byDistance;
    }

    private void captureViewer(Player player) {
        FoliaScheduler.runEntity(plugin, player, () -> {
            if (player.isOnline()) {
                viewerIndex.update(player, player.getLocation());
            }
        }, 0L, () -> viewerIndex.remove(player.getUniqueId()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        viewerIndex.update(event.getPlayer(), event.getPlayer().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID viewerId = event.getPlayer().getUniqueId();
        viewerIndex.remove(viewerId);
        for (RigInstance instance : instances.values()) {
            instance.despawnFor(viewerId);
        }
        viewerBudgets.remove(viewerId);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!(event instanceof PlayerTeleportEvent) && event.getTo() != null) {
            viewerIndex.update(event.getPlayer(), event.getTo());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (event.getTo() != null) {
            viewerIndex.update(event.getPlayer(), event.getTo());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        viewerIndex.update(event.getPlayer(), event.getPlayer().getLocation());
    }
}
