package art.arcane.gloss.board;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.doc.DocumentDelta;
import art.arcane.gloss.doc.DocumentRegistry;
import art.arcane.gloss.doc.DocumentReviser;
import art.arcane.gloss.doc.DocumentStore;
import art.arcane.gloss.doc.GlossDocument;
import art.arcane.gloss.doc.ShippedDefaults;
import art.arcane.gloss.doc.ShippedDocumentCatalog;
import art.arcane.volmlib.util.board.Board;
import art.arcane.volmlib.util.board.BoardManager;
import art.arcane.volmlib.util.board.BoardProvider;
import art.arcane.volmlib.util.board.BoardSettings;
import art.arcane.volmlib.util.board.ScoreDirection;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.File;
import java.io.IOException;
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
import java.util.logging.Level;

public final class BoardService implements Listener {
    private static final int MAX_LINES = 15;
    private static final int MAX_TITLE_LENGTH = 32;

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
    private final Map<String, GlossBoardMeta> metas;
    private final Map<UUID, String> selections;
    private final Set<UUID> sticky;
    private volatile BoardManager<Board> manager;
    private volatile int managerIntervalTicks;

    public BoardService(Gloss plugin) {
        this.plugin = plugin;
        File folder = new File(plugin.getDataFolder(), BoardDoc.KIND);
        this.store = new DocumentStore<>(BoardDoc.KIND, folder, REVISER);
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

    public void enable() {
        defaults.extractMissing();
        loadAllBoards();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        if (plugin.cfg().boards().enabled()) {
            createManager();
        }
        plugin.watchdog().register("boards", this::pollRegistry);
        plugin.scheduler().s(this::selectAllAutomatically, 1);
    }

    public void disable() {
        HandlerList.unregisterAll(this);
        plugin.watchdog().unregister("boards");
        BoardManager<Board> activeManager = manager;
        manager = null;
        if (activeManager != null) {
            activeManager.onDisable();
        }
        selections.clear();
        sticky.clear();
        metas.clear();
        store.forgetAll();
    }

    public void reload() {
        loadAllBoards();
        boolean enabled = plugin.cfg().boards().enabled();
        BoardManager<Board> activeManager = manager;
        if (enabled && activeManager == null) {
            createManager();
        } else if (enabled && managerIntervalTicks != plugin.cfg().boards().updateIntervalTicks()) {
            manager = null;
            activeManager.onDisable();
            createManager();
        } else if (!enabled && activeManager != null) {
            manager = null;
            activeManager.onDisable();
        }
        selectAllAutomatically();
    }

    public boolean createBoard(String id) {
        String boardId = normalizeId(id);
        if (boardId.isEmpty() || metas.containsKey(boardId)) {
            return false;
        }
        GlossBoardMeta meta = new GlossBoardMeta(boardId);
        saveBoard(meta);
        return true;
    }

    public GlossBoardMeta board(String id) {
        return metas.get(normalizeId(id));
    }

    public boolean deleteBoard(String id) {
        String boardId = normalizeId(id);
        GlossBoardMeta removed = metas.remove(boardId);
        if (removed == null) {
            return false;
        }
        try {
            store.delete(boardId);
        } catch (IOException failure) {
            Gloss.warn("Unable to delete board file " + boardId + ".json: " + failure.getMessage());
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (boardId.equals(selections.get(player.getUniqueId()))) {
                sticky.remove(player.getUniqueId());
                plugin.scheduler().runEntity(player, () -> selectAutomatically(player));
            }
        }
        return true;
    }

    public List<GlossBoardMeta> boards() {
        List<GlossBoardMeta> list = new ArrayList<>(metas.values());
        list.sort(Comparator.comparing(GlossBoardMeta::id));
        return list;
    }

    public void saveBoard(GlossBoardMeta meta) {
        if (meta == null) {
            return;
        }
        metas.put(meta.id(), meta);
        BoardDoc doc = meta.toDoc(meta.nextRevision());
        plugin.scheduler().a(() -> writeBoard(meta.id(), doc), 0);
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

    @EventHandler
    public void on(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        selections.remove(uuid);
        sticky.remove(uuid);
        BoardManager<Board> activeManager = manager;
        if (activeManager != null) {
            activeManager.remove(event.getPlayer());
        }
    }

    private void createManager() {
        int intervalTicks = plugin.cfg().boards().updateIntervalTicks();
        BoardSettings settings = new BoardSettings(new SelectionBoardProvider(), ScoreDirection.DOWN, intervalTicks);
        try {
            manager = new BoardManager<>(plugin, settings, Board::new);
            managerIntervalTicks = intervalTicks;
        } catch (Throwable failure) {
            manager = null;
            Gloss.warn("Sidebar driver unavailable: " + failure.getClass().getSimpleName()
                + (failure.getMessage() == null ? "" : ": " + failure.getMessage()));
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
        BoardManager<Board> activeManager = manager;
        if (activeManager == null) {
            return;
        }
        plugin.scheduler().runEntity(player, () -> {
            String boardId = selections.get(player.getUniqueId());
            if (boardId != null && metas.containsKey(boardId)) {
                if (!activeManager.hasBoard(player)) {
                    activeManager.setup(player);
                }
                return;
            }
            activeManager.remove(player);
        });
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
    }

    private void writeBoard(String id, BoardDoc doc) {
        try {
            store.write(id, doc);
        } catch (IOException failure) {
            Gloss.warn("Unable to save board file " + id + ".json: " + failure.getMessage());
        }
    }

    private void pollRegistry() {
        DocumentDelta delta = registry.poll();
        if (delta.isEmpty()) {
            return;
        }
        boolean dirty = false;
        for (String id : delta.loaded()) {
            GlossDocument<BoardDoc> document = registry.get(id);
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
            reselectAll();
        }
    }

    private String normalizeId(String id) {
        return id == null ? "" : id.trim().replace(" ", "-");
    }

    private final class SelectionBoardProvider implements BoardProvider {
        @Override
        public String getTitle(Player player) {
            GlossBoardMeta meta = selectedMeta(player);
            if (meta == null) {
                return "";
            }
            String rendered = plugin.text().render(player, meta.title());
            if (rendered == null) {
                return "";
            }
            return rendered.length() > MAX_TITLE_LENGTH ? rendered.substring(0, MAX_TITLE_LENGTH) : rendered;
        }

        @Override
        public List<String> getLines(Player player) {
            GlossBoardMeta meta = selectedMeta(player);
            if (meta == null) {
                return List.of();
            }
            List<String> raw = meta.contentView();
            List<String> rendered = new ArrayList<>(Math.min(raw.size(), MAX_LINES));
            for (String line : raw) {
                if (rendered.size() >= MAX_LINES) {
                    break;
                }
                String value = plugin.text().render(player, line);
                rendered.add(value == null ? "" : value);
            }
            return rendered;
        }
    }
}
