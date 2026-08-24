package art.arcane.gloss.board;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.doc.DocumentDelta;
import art.arcane.gloss.doc.ExecutorStorageTaskRunner;
import art.arcane.gloss.doc.DocumentRegistry;
import art.arcane.gloss.doc.DocumentReviser;
import art.arcane.gloss.doc.DocumentStore;
import art.arcane.gloss.doc.GlossDocument;
import art.arcane.gloss.doc.ShippedDefaults;
import art.arcane.gloss.doc.ShippedDocumentCatalog;
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
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.logging.Level;

public final class BoardService implements Listener {
    private static final int MAX_LINES = 15;
    private static final int ANIMATION_REFRESH_INTERVAL_TICKS = 1;

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
    private final Set<UUID> sticky;
    private final UnaryOperator<String> staticRender;
    private volatile BoardManager<Board> ordinaryManager;
    private volatile BoardManager<Board> animationManager;
    private volatile int ordinaryManagerIntervalTicks;
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
        this.sticky = ConcurrentHashMap.newKeySet();
    }

    public static String selectBoardId(String primaryGroup, List<GlossBoardMeta> boards,
                                       Predicate<String> permissionTest) {
        if (primaryGroup != null && !primaryGroup.isBlank()) {
            for (GlossBoardMeta meta : boards) {
                if (meta.groups().contains(primaryGroup) && permitted(meta, permissionTest)) {
                    return meta.id();
                }
            }
        }
        for (GlossBoardMeta meta : boards) {
            if (meta.permissionGated() && permissionTest.test(meta.permissionNode())) {
                return meta.id();
            }
        }
        for (GlossBoardMeta meta : boards) {
            if (meta.primary() && permitted(meta, permissionTest)) {
                return meta.id();
            }
        }
        return null;
    }

    private static boolean permitted(GlossBoardMeta meta, Predicate<String> permissionTest) {
        return !meta.permissionGated() || permissionTest.test(meta.permissionNode());
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
        sticky.clear();
        metas.clear();
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
        syncManager(player);
    }

    public void clearBoard(Player player) {
        sticky.add(player.getUniqueId());
        selections.remove(player.getUniqueId());
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
        sticky.remove(uuid);
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
    }

    private BoardManager<Board> createManager(int intervalTicks, String cadenceName) {
        BoardSettings settings = new BoardSettings(new SelectionBoardProvider(), ScoreDirection.DOWN, intervalTicks);
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
        if (activeOrdinaryManager != null) {
            activeOrdinaryManager.onDisable();
        }
        if (activeAnimationManager != null && activeAnimationManager != activeOrdinaryManager) {
            activeAnimationManager.onDisable();
        }
    }

    private void selectAutomatically(Player player) {
        String primaryGroup = plugin.groups().primaryGroupFor(player).orElse(null);
        String chosen = selectBoardId(primaryGroup, boards(), player::hasPermission);
        if (chosen == null) {
            selections.remove(player.getUniqueId());
        } else {
            selections.put(player.getUniqueId(), chosen);
        }
        syncManager(player);
    }

    private void selectAllAutomatically() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (sticky.contains(player.getUniqueId())) {
                syncManager(player);
                continue;
            }
            plugin.scheduler().runEntity(player, () -> selectAutomatically(player));
        }
    }

    private void syncManager(Player player) {
        BoardManager<Board> activeOrdinaryManager = ordinaryManager;
        if (activeOrdinaryManager == null) {
            return;
        }
        plugin.scheduler().runEntity(player, () -> {
            GlossBoardMeta meta = selectedMeta(player);
            BoardManager<Board> activeAnimationManager = animationManager;
            BoardManager<Board> target = meta != null && usesFastRefresh(meta)
                && activeAnimationManager != null ? activeAnimationManager : activeOrdinaryManager;
            BoardManager<Board> other = target == activeOrdinaryManager ? activeAnimationManager : activeOrdinaryManager;
            if (meta != null) {
                if (other != null) {
                    other.remove(player);
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
        });
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
        return plugin.cfg().text().functions() && usesFastRefreshText(meta);
    }

    static int refreshIntervalTicks(GlossBoardMeta meta, int configuredIntervalTicks) {
        return usesFastRefreshText(meta)
            ? Math.min(configuredIntervalTicks, ANIMATION_REFRESH_INTERVAL_TICKS)
            : configuredIntervalTicks;
    }

    private static boolean usesFastRefreshText(GlossBoardMeta meta) {
        if (meta == null) {
            return false;
        }
        if (TextPipeline.requiresFastRefresh(meta.title())) {
            return true;
        }
        for (String line : meta.lines()) {
            if (TextPipeline.requiresFastRefresh(line)) {
                return true;
            }
        }
        return false;
    }

    private GlossBoardMeta selectedMeta(Player player) {
        String boardId = selections.get(player.getUniqueId());
        return boardId == null ? null : metas.get(boardId);
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
        @Override
        public String getTitle(Player player) {
            GlossBoardMeta meta = selectedMeta(player);
            if (meta == null) {
                return "";
            }
            GlossBoardMeta.RenderPlan plan = plan(meta);
            String cached = plan.staticTitle();
            if (cached != null) {
                return cached;
            }
            String rendered = plugin.text().render(player, plan.rawTitle());
            if (rendered == null) {
                return "";
            }
            return rendered;
        }

        @Override
        public List<String> getLines(Player player) {
            GlossBoardMeta meta = selectedMeta(player);
            if (meta == null) {
                return List.of();
            }
            GlossBoardMeta.RenderPlan plan = plan(meta);
            int count = plan.lineCount();
            List<String> rendered = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                String cached = plan.staticLine(index);
                if (cached != null) {
                    rendered.add(cached);
                    continue;
                }
                String value = plugin.text().render(player, plan.rawLine(index));
                rendered.add(value == null ? "" : value);
            }
            return rendered;
        }

        @Override
        public boolean hideScoreNumbers(Player player) {
            GlossBoardMeta meta = selectedMeta(player);
            return meta != null && meta.hideNumbers();
        }

        private GlossBoardMeta.RenderPlan plan(GlossBoardMeta meta) {
            return meta.renderPlan(TextPipeline.emojiGeneration(), MAX_LINES, staticRender);
        }
    }
}
