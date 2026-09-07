package art.arcane.gloss.board;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.condition.BoundedConditionErrorCallback;
import art.arcane.gloss.condition.GlossConditionScope;
import art.arcane.gloss.doc.DocumentDelta;
import art.arcane.gloss.doc.ExecutorStorageTaskRunner;
import art.arcane.gloss.doc.DocumentRegistry;
import art.arcane.gloss.doc.DocumentReviser;
import art.arcane.gloss.doc.DocumentStore;
import art.arcane.gloss.doc.GlossDocument;
import art.arcane.gloss.doc.ShippedDefaults;
import art.arcane.gloss.doc.ShippedDocumentCatalog;
import art.arcane.gloss.expr.ExprScope;
import art.arcane.volmlib.util.scheduling.SchedulerUtils;
import art.arcane.gloss.text.TextPipeline;
import art.arcane.volmlib.util.board.Board;
import art.arcane.volmlib.util.board.BoardManager;
import art.arcane.volmlib.util.board.BoardProvider;
import art.arcane.volmlib.util.board.BoardSettings;
import art.arcane.volmlib.util.board.ScoreDirection;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import java.util.logging.Level;

public final class BoardService implements Listener {
    private static final int MAX_LINES = 15;
    private static final int ANIMATION_REFRESH_INTERVAL_TICKS = 1;
    private static final int SELECTION_STRIPE_PERIOD_TICKS = 1;
    private static final long TICK_NANOS = 50_000_000L;

    private static final DocumentReviser<BoardDoc> REVISER = new DocumentReviser<>() {
        @Override
        public long revisionOf(BoardDoc value) {
            return value.revision();
        }

        @Override
        public BoardDoc withRevision(BoardDoc value, long revision) {
            return value.withRevision(revision);
        }
    };

    private final Gloss plugin;
    private final ShippedDefaults defaults;
    private final DocumentRegistry<BoardDoc> registry;
    private final DocumentStore<BoardDoc> store;
    private final BoardStorageQueue storage;
    private final Map<String, GlossBoardMeta> metas;
    private final Map<UUID, String> selections;
    private final Map<UUID, GlossBoardMeta.ActiveProfile> profiles;
    private final Set<UUID> sticky;
    private final UnaryOperator<String> staticRender;
    private final BoundedConditionErrorCallback conditionErrors;
    private final BoardRenderCache renderCache;
    private final List<UUID> selectionOrder;
    private int selectionStripeIndex;
    private int selectionCursor;
    private volatile BoardManager<Board> ordinaryManager;
    private volatile BoardManager<Board> animationManager;
    private volatile int ordinaryManagerIntervalTicks;
    private volatile int selectionTaskId;
    private volatile List<GlossBoardMeta> boardSnapshot;

    public BoardService(Gloss plugin) {
        this.plugin = plugin;
        this.staticRender = raw -> plugin.text().renderStatic(raw);
        File folder = new File(plugin.getDataFolder(), BoardDoc.KIND);
        this.store = new DocumentStore<>(BoardDoc.KIND, folder, REVISER);
        this.storage = new BoardStorageQueue(store,
            new ExecutorStorageTaskRunner(plugin.getClass().getClassLoader(), "Gloss-Board-Storage"),
            plugin.getLogger());
        this.defaults = new ShippedDefaults(BoardDoc.KIND, folder, ShippedDocumentCatalog.BOARDS.names());
        this.registry = DocumentRegistry.folder(BoardDoc.KIND, folder, BoardDoc::parse, BoardDoc::revision,
            store::isOwnWrite);
        this.metas = new ConcurrentHashMap<>();
        this.selections = new ConcurrentHashMap<>();
        this.profiles = new ConcurrentHashMap<>();
        this.sticky = ConcurrentHashMap.newKeySet();
        this.conditionErrors = BoundedConditionErrorCallback.bounded(100, error ->
            Gloss.logExceptionStackThrottled(false, "board-condition-" + error.path(), error.cause(),
                "Board condition %s failed and was treated as false.", error.path()));
        this.renderCache = new BoardRenderCache();
        this.selectionOrder = new ArrayList<>();
        this.selectionStripeIndex = 0;
        this.selectionCursor = 0;
        this.selectionTaskId = -1;
    }

    public static String selectBoardId(List<GlossBoardMeta> boards, ExprScope scope) {
        return selectBoardId(boards, scope, BoundedConditionErrorCallback.silent());
    }

    private static String selectBoardId(List<GlossBoardMeta> boards, ExprScope scope,
                                        BoundedConditionErrorCallback errors) {
        GlossBoardMeta selected = null;
        for (GlossBoardMeta meta : boards) {
            if (!meta.matchesSelection(scope, errors)) {
                continue;
            }
            if (selected == null || meta.selection().priority() > selected.selection().priority()
                || meta.selection().priority() == selected.selection().priority()
                && meta.id().compareTo(selected.id()) < 0) {
                selected = meta;
            }
        }
        return selected == null ? null : selected.id();
    }

    /**
     * The shipped boards are extracted only while the sidebar is on, so a server that leaves the
     * feature off never grows a {@code boards/} folder. Everything else still runs, so turning the
     * feature on through {@link #reload()} picks the boards up without a restart.
     */
    public void enable() {
        if (plugin.cfg().boards().enabled()) {
            defaults.extractMissing();
        }
        loadAllBoards();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        if (plugin.cfg().boards().enabled()) {
            createManagers();
        }
        plugin.watchdog().register("boards", this::pollRegistry);
        plugin.scheduler().s(this::selectAllAutomatically, 1);
    }

    public void disable() {
        HandlerList.unregisterAll(this);
        plugin.watchdog().unregister("boards");
        registry.close();
        stopManagers();
        storage.shutdown();
        selections.clear();
        profiles.clear();
        sticky.clear();
        metas.clear();
        renderCache.clear();
        boardSnapshot = null;
        store.forgetAll();
    }

    public void reload() {
        boolean enabled = plugin.cfg().boards().enabled();
        if (enabled) {
            defaults.extractMissing();
        }
        loadAllBoards();
        if (enabled && ordinaryManager == null) {
            createManagers();
        } else if (enabled && ordinaryManagerIntervalTicks != plugin.cfg().boards().updateIntervalTicks()) {
            stopManagers();
            createManagers();
        } else if (!enabled && ordinaryManager != null) {
            stopManagers();
        }
        selectAllAutomatically();
    }

    public synchronized boolean createBoard(String id) {
        String boardId;
        try {
            boardId = requireSafeId(normalizeId(id));
        } catch (IllegalArgumentException failure) {
            return false;
        }
        if (metas.containsKey(boardId)) {
            return false;
        }
        GlossBoardMeta meta = new GlossBoardMeta(boardId);
        saveBoard(meta);
        return true;
    }

    public GlossBoardMeta board(String id) {
        return metas.get(normalizeId(id));
    }

    public synchronized boolean deleteBoard(String id) {
        String boardId;
        try {
            boardId = requireSafeId(normalizeId(id));
        } catch (IllegalArgumentException failure) {
            return false;
        }
        GlossBoardMeta removed = metas.remove(boardId);
        if (removed == null) {
            return false;
        }
        boardSnapshot = null;
        storage.delete(boardId);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (boardId.equals(selections.get(player.getUniqueId()))) {
                sticky.remove(player.getUniqueId());
                plugin.scheduler().runEntity(player, () -> selectAutomatically(player));
            }
        }
        return true;
    }

    /** Id-sorted snapshot of the loaded boards, rebuilt only when the board set changes. */
    public List<GlossBoardMeta> boards() {
        List<GlossBoardMeta> snapshot = boardSnapshot;
        if (snapshot != null) {
            return snapshot;
        }
        List<GlossBoardMeta> list = new ArrayList<>(metas.values());
        list.sort(Comparator.comparing(GlossBoardMeta::id));
        List<GlossBoardMeta> published = List.copyOf(list);
        boardSnapshot = published;
        return published;
    }

    public int boardCount() {
        return metas.size();
    }

    public synchronized void saveBoard(GlossBoardMeta meta) {
        if (meta == null) {
            return;
        }
        String boardId = requireSafeId(meta.id());
        metas.put(boardId, meta);
        boardSnapshot = null;
        reselectAll();
        BoardDoc doc = meta.toDoc(meta.nextRevision());
        storage.save(boardId, doc);
    }

    public List<String> resetToDefault(String nameOrStar) {
        return defaults.resetToDefault(nameOrStar);
    }

    public Optional<String> boardIdFor(Player player) {
        return Optional.ofNullable(selections.get(player.getUniqueId()));
    }

    public void setBoard(Player player, String boardId) {
        String normalized = normalizeId(boardId);
        if (normalized.isEmpty()) {
            clearBoard(player);
            return;
        }
        sticky.add(player.getUniqueId());
        selections.put(player.getUniqueId(), normalized);
        refreshProfile(player, metas.get(normalized));
        syncManager(player);
    }

    public void clearBoard(Player player) {
        sticky.add(player.getUniqueId());
        selections.remove(player.getUniqueId());
        profiles.remove(player.getUniqueId());
        syncManager(player);
    }

    public void reselectAll() {
        plugin.scheduler().s(this::selectAllAutomatically);
    }

    @EventHandler
    public void on(PlayerJoinEvent event) {
        selectAutomatically(event.getPlayer());
    }

    @EventHandler
    public void on(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        if (sticky.contains(player.getUniqueId())) {
            syncManager(player);
            return;
        }
        selectAutomatically(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void on(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        selections.remove(uuid);
        profiles.remove(uuid);
        sticky.remove(uuid);
        renderCache.forget(uuid);
        removeFromManagers(event.getPlayer());
    }

    private void createManagers() {
        int ordinaryIntervalTicks = plugin.cfg().boards().updateIntervalTicks();
        ordinaryManager = createManager(ordinaryIntervalTicks, "ordinary");
        ordinaryManagerIntervalTicks = ordinaryIntervalTicks;
        int animationIntervalTicks = Math.min(ordinaryIntervalTicks, ANIMATION_REFRESH_INTERVAL_TICKS);
        animationManager = animationIntervalTicks == ordinaryIntervalTicks
            ? null
            : createManager(animationIntervalTicks, "animation");
        // The sweep is armed every tick and walks a slice of the fleet, so a 1,000 player server
        // re-selects every viewer once per interval without a single-tick burst.
        selectionStripeIndex = 0;
        selectionTaskId = plugin.scheduler().sr(this::selectStripe, SELECTION_STRIPE_PERIOD_TICKS);
    }

    private BoardManager<Board> createManager(int intervalTicks, String cadenceName) {
        boolean fastCadence = intervalTicks <= ANIMATION_REFRESH_INTERVAL_TICKS;
        BoardSettings settings = new BoardSettings(new SelectionBoardProvider(fastCadence),
            ScoreDirection.DOWN, intervalTicks);
        try {
            return new BoardManager<>(plugin, settings, Board::new);
        } catch (Throwable failure) {
            Gloss.logExceptionStack(false, failure, "Sidebar %s driver is unavailable.", cadenceName);
            return null;
        }
    }

    private void stopManagers() {
        BoardManager<Board> activeOrdinaryManager = ordinaryManager;
        BoardManager<Board> activeAnimationManager = animationManager;
        ordinaryManager = null;
        animationManager = null;
        if (selectionTaskId != -1) {
            plugin.scheduler().csr(selectionTaskId);
            selectionTaskId = -1;
        }
        if (activeOrdinaryManager != null) {
            activeOrdinaryManager.onDisable();
        }
        if (activeAnimationManager != null && activeAnimationManager != activeOrdinaryManager) {
            activeAnimationManager.onDisable();
        }
        renderCache.clear();
    }

    /**
     * One condition scope per viewer per sweep: the selection loop already decided the board's
     * {@code show} condition with this scope, so the manager attach never re-evaluates it.
     */
    private void selectAutomatically(Player player) {
        GlossConditionScope scope = GlossConditionScope.viewer(plugin, player);
        String chosen = selectBoardId(boards(), scope, conditionErrors);
        UUID uuid = player.getUniqueId();
        GlossBoardMeta meta = chosen == null ? null : metas.get(chosen);
        if (meta == null) {
            selections.remove(uuid);
            profiles.remove(uuid);
        } else {
            selections.put(uuid, chosen);
            profiles.put(uuid, meta.activeProfile(scope, conditionErrors));
        }
        applyManager(player, meta, meta != null);
    }

    private void selectAllAutomatically() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            selectOne(player);
        }
    }

    /** Re-selects the slice of online players that belongs to this tick. */
    private void selectStripe() {
        if (ordinaryManager == null) {
            return;
        }
        int intervalTicks = Math.max(1, ordinaryManagerIntervalTicks);
        if (sweepsWholeFleet(intervalTicks)) {
            selectionStripeIndex = 0;
            selectionOrder.clear();
            selectionCursor = 0;
            selectAllAutomatically();
            return;
        }
        if (selectionStripeIndex >= intervalTicks) {
            selectionStripeIndex = 0;
        }
        if (selectionStripeIndex == 0) {
            beginSelectionCycle();
        }
        int remaining = selectionOrder.size() - selectionCursor;
        if (remaining > 0) {
            int slice = stripeSize(remaining, intervalTicks - selectionStripeIndex);
            for (int index = 0; index < slice; index++) {
                Player player = Bukkit.getPlayer(selectionOrder.get(selectionCursor++));
                if (player != null) {
                    selectOne(player);
                }
            }
        }
        selectionStripeIndex++;
    }

    /**
     * At a one-tick interval every tick is a whole cycle, so the roster snapshot, its sort and the
     * per-uuid player lookups are pure waste: sweep the online roster directly.
     */
    static boolean sweepsWholeFleet(int intervalTicks) {
        return intervalTicks <= 1;
    }

    /** Sweep slice for this tick: ceil(remaining / remaining stripes), so the cycle always closes. */
    static int stripeSize(int remaining, int remainingStripes) {
        if (remaining <= 0) {
            return 0;
        }
        int stripes = Math.max(1, remainingStripes);
        return (remaining + stripes - 1) / stripes;
    }

    private void beginSelectionCycle() {
        selectionOrder.clear();
        for (Player player : Bukkit.getOnlinePlayers()) {
            selectionOrder.add(player.getUniqueId());
        }
        selectionOrder.sort(Comparator.naturalOrder());
        selectionCursor = 0;
    }

    private void selectOne(Player player) {
        if (sticky.contains(player.getUniqueId())) {
            plugin.scheduler().runEntity(player, () -> resyncSelected(player));
            return;
        }
        plugin.scheduler().runEntity(player, () -> selectAutomatically(player));
    }

    private void syncManager(Player player) {
        if (ordinaryManager == null) {
            return;
        }
        plugin.scheduler().runEntity(player, () -> resyncSelected(player));
    }

    /** Refreshes the viewer's profile and manager for the board it is already on. */
    private void resyncSelected(Player player) {
        GlossBoardMeta meta = selectedMeta(player);
        GlossConditionScope scope = GlossConditionScope.viewer(plugin, player);
        refreshProfile(player, meta, scope);
        applyManager(player, meta, meta != null && meta.show().matches(scope, conditionErrors));
    }

    private void applyManager(Player player, GlossBoardMeta meta, boolean visible) {
        BoardManager<Board> activeOrdinaryManager = ordinaryManager;
        if (activeOrdinaryManager == null) {
            return;
        }
        BoardManager<Board> activeAnimationManager = animationManager;
        if (meta != null && visible) {
            BoardManager<Board> target = usesFastRefresh(meta) && activeAnimationManager != null
                ? activeAnimationManager : activeOrdinaryManager;
            BoardManager<Board> other = target == activeOrdinaryManager ? activeAnimationManager : activeOrdinaryManager;
            if (other != null) {
                other.remove(player);
            }
            if (target != activeAnimationManager) {
                renderCache.forget(player.getUniqueId());
            }
            if (!target.hasBoard(player)) {
                target.setup(player);
            }
            return;
        }
        activeOrdinaryManager.remove(player);
        if (activeAnimationManager != null) {
            activeAnimationManager.remove(player);
        }
        renderCache.forget(player.getUniqueId());
    }

    private void removeFromManagers(Player player) {
        BoardManager<Board> activeOrdinaryManager = ordinaryManager;
        BoardManager<Board> activeAnimationManager = animationManager;
        if (activeOrdinaryManager != null) {
            activeOrdinaryManager.remove(player);
        }
        if (activeAnimationManager != null) {
            activeAnimationManager.remove(player);
        }
    }

    private boolean usesFastRefresh(GlossBoardMeta meta) {
        return plugin.cfg().text().functions() && meta != null && meta.usesFastRefreshText();
    }

    static int refreshIntervalTicks(GlossBoardMeta meta, int configuredIntervalTicks) {
        return meta != null && meta.usesFastRefreshText()
            ? Math.min(configuredIntervalTicks, ANIMATION_REFRESH_INTERVAL_TICKS)
            : configuredIntervalTicks;
    }

    private GlossBoardMeta selectedMeta(Player player) {
        String boardId = selections.get(player.getUniqueId());
        return boardId == null ? null : metas.get(boardId);
    }

    private void refreshProfile(Player player, GlossBoardMeta meta) {
        refreshProfile(player, meta, meta == null ? null : GlossConditionScope.viewer(plugin, player));
    }

    private void refreshProfile(Player player, GlossBoardMeta meta, GlossConditionScope scope) {
        if (meta == null) {
            profiles.remove(player.getUniqueId());
            return;
        }
        profiles.put(player.getUniqueId(), meta.activeProfile(scope, conditionErrors));
    }

    private GlossBoardMeta.ActiveProfile selectedProfile(Player player, GlossBoardMeta meta) {
        GlossBoardMeta.ActiveProfile profile = profiles.get(player.getUniqueId());
        if (profile != null) {
            return profile;
        }
        refreshProfile(player, meta);
        return profiles.get(player.getUniqueId());
    }

    private void loadAllBoards() {
        registry.reload();
        Set<String> present = new HashSet<>();
        for (GlossDocument<BoardDoc> document : registry.snapshot().values()) {
            present.add(document.id());
            metas.put(document.id(), GlossBoardMeta.fromDoc(document.id(), document.value()));
        }
        metas.keySet().retainAll(present);
        boardSnapshot = null;
    }

    private void pollRegistry() {
        DocumentDelta delta = registry.poll();
        if (delta.isEmpty()) {
            return;
        }
        if (!registry.dispatch(delta, task -> SchedulerUtils.runGlobal(plugin, task),
            () -> applyRegistryDelta(delta))) {
            Gloss.warnThrottled("board-hotload-scheduling",
                "Board hot reload could not reach the server thread; the change will be retried.");
        }
    }

    private void applyRegistryDelta(DocumentDelta delta) {
        boolean dirty = false;
        for (String id : delta.loaded()) {
            GlossDocument<BoardDoc> document = registry.get(delta, id);
            if (document == null) {
                continue;
            }
            metas.put(id, GlossBoardMeta.fromDoc(id, document.value()));
            Gloss.log(Level.INFO, "Board document \"%s\" changed and was reloaded.", id);
            dirty = true;
        }
        for (String id : delta.removed()) {
            dirty = metas.remove(id) != null || dirty;
        }
        if (dirty) {
            boardSnapshot = null;
            reselectAll();
        }
    }

    static String normalizeId(String id) {
        return id == null ? "" : id.trim().replace(" ", "-");
    }

    static String requireSafeId(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("board id may not be blank");
        }
        if (id.contains("/") || id.contains("\\") || id.contains("..")) {
            throw new IllegalArgumentException("board id may not contain path characters: " + id);
        }
        return id;
    }

    private final class SelectionBoardProvider implements BoardProvider {
        private final boolean fastCadence;

        private SelectionBoardProvider(boolean fastCadence) {
            this.fastCadence = fastCadence;
        }

        @Override
        public String getTitle(Player player) {
            GlossBoardMeta meta = selectedMeta(player);
            if (meta == null) {
                return "";
            }
            GlossBoardMeta.ActiveProfile profile = selectedProfile(player, meta);
            GlossBoardMeta.RenderPlan plan = plan(meta, profile);
            if (fastCadence) {
                return renderCache.entry(player.getUniqueId()).title(plan, System.nanoTime(),
                    slowIntervalNanos(), player.getUniqueId().hashCode(), raw -> render(player, raw));
            }
            String cached = plan.staticTitle();
            if (cached != null) {
                return cached;
            }
            return render(player, plan.rawTitle());
        }

        @Override
        public List<String> getLines(Player player) {
            GlossBoardMeta meta = selectedMeta(player);
            if (meta == null) {
                return List.of();
            }
            GlossBoardMeta.ActiveProfile profile = selectedProfile(player, meta);
            GlossBoardMeta.RenderPlan plan = plan(meta, profile);
            if (fastCadence) {
                return Arrays.asList(renderCache.entry(player.getUniqueId()).lines(plan, System.nanoTime(),
                    slowIntervalNanos(), player.getUniqueId().hashCode(), raw -> render(player, raw)));
            }
            int count = plan.lineCount();
            List<String> rendered = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                String cached = plan.staticLine(index);
                if (cached != null) {
                    rendered.add(cached);
                    continue;
                }
                rendered.add(render(player, plan.rawLine(index)));
            }
            return rendered;
        }

        @Override
        public boolean hideScoreNumbers(Player player) {
            GlossBoardMeta meta = selectedMeta(player);
            GlossBoardMeta.ActiveProfile profile = meta == null ? null : selectedProfile(player, meta);
            return profile != null && profile.presentation().hideNumbers();
        }

        private String render(Player player, String raw) {
            String rendered = plugin.text().render(player, raw);
            return rendered == null ? "" : rendered;
        }

        private GlossBoardMeta.RenderPlan plan(GlossBoardMeta meta, GlossBoardMeta.ActiveProfile profile) {
            return meta.renderPlan(profile.id(), profile.presentation(), TextPipeline.emojiGeneration(),
                MAX_LINES, staticRender);
        }
    }

    /**
     * How long a viewer on the one-tick manager may serve a line that does not need per-tick
     * refresh from its last render: the ordinary board interval.
     */
    private long slowIntervalNanos() {
        return Math.max(1, ordinaryManagerIntervalTicks) * TICK_NANOS;
    }
}
