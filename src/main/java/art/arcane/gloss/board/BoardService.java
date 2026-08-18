package art.arcane.gloss.board;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.group.GlossGroup;
import art.arcane.volmlib.util.board.Board;
import art.arcane.volmlib.util.board.BoardManager;
import art.arcane.volmlib.util.board.BoardProvider;
import art.arcane.volmlib.util.board.BoardSettings;
import art.arcane.volmlib.util.board.ScoreDirection;
import art.arcane.volmlib.util.io.FolderWatcher;
import art.arcane.volmlib.util.io.IO;
import art.arcane.volmlib.util.json.JSONException;
import art.arcane.volmlib.util.json.JSONObject;
import art.arcane.volmlib.util.math.M;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

public final class BoardService implements Listener {
    private static final int MAX_LINES = 15;
    private static final int MAX_TITLE_LENGTH = 32;
    private static final long SELF_WRITE_SUPPRESS_MS = 10000L;
    private static final String BOARD_FILE_SUFFIX = ".json";

    private final Gloss plugin;
    private final Map<String, GlossBoardMeta> metas;
    private final Map<UUID, String> selections;
    private final Set<UUID> sticky;
    private final Map<String, Long> selfWrites;
    private volatile BoardManager<Board> manager;
    private volatile int managerIntervalTicks;
    private volatile FolderWatcher watcher;
    private volatile int watcherTaskId;

    public BoardService(Gloss plugin) {
        this.plugin = plugin;
        this.metas = new ConcurrentHashMap<>();
        this.selections = new ConcurrentHashMap<>();
        this.sticky = ConcurrentHashMap.newKeySet();
        this.selfWrites = new ConcurrentHashMap<>();
        this.watcherTaskId = -1;
    }

    public static String selectBoardId(String groupDefaultBoard, List<GlossBoardMeta> boards, Predicate<String> permissionTest) {
        if (groupDefaultBoard != null && !groupDefaultBoard.isBlank()) {
            for (GlossBoardMeta meta : boards) {
                if (meta.id().equals(groupDefaultBoard)) {
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
            if (meta.primary()) {
                return meta.id();
            }
        }
        return null;
    }

    public void enable() {
        loadAllBoards();
        watcher = new FolderWatcher(boardsFolder());
        Bukkit.getPluginManager().registerEvents(this, plugin);
        if (plugin.cfg().boards().enabled()) {
            createManager();
        }
        startWatcherTask();
        plugin.scheduler().s(this::selectAllAutomatically, 1);
    }

    public void disable() {
        HandlerList.unregisterAll(this);
        stopWatcherTask();
        BoardManager<Board> activeManager = manager;
        manager = null;
        if (activeManager != null) {
            activeManager.onDisable();
        }
        watcher = null;
        selections.clear();
        sticky.clear();
        selfWrites.clear();
        metas.clear();
    }

    public void reload() {
        loadAllBoards();
        watcher = new FolderWatcher(boardsFolder());
        stopWatcherTask();
        startWatcherTask();
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
        File file = boardFile(boardId);
        selfWrites.put(file.getAbsolutePath(), M.ms());
        if (file.exists() && !file.delete()) {
            Gloss.warn("Unable to delete board file " + file.getName());
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
        String payload = meta.toJson().toString(4);
        File file = boardFile(meta.id());
        plugin.scheduler().a(() -> writeBoardFile(file, payload), 0);
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

    private void startWatcherTask() {
        watcherTaskId = plugin.scheduler().ar(this::pollWatcher, plugin.cfg().hotload().watchIntervalTicks());
    }

    private void stopWatcherTask() {
        if (watcherTaskId != -1) {
            plugin.scheduler().car(watcherTaskId);
            watcherTaskId = -1;
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
        String groupDefault = plugin.groups().groupFor(player)
            .map(GlossGroup::defaultBoard)
            .filter(board -> !board.isBlank())
            .orElse(null);
        String chosen = selectBoardId(groupDefault, boards(), player::hasPermission);
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
        Map<String, GlossBoardMeta> loaded = new HashMap<>();
        File[] files = boardsFolder().listFiles((directory, name) -> name.endsWith(BOARD_FILE_SUFFIX));
        if (files != null) {
            for (File file : files) {
                GlossBoardMeta meta = readBoardFile(file);
                if (meta != null) {
                    loaded.put(meta.id(), meta);
                }
            }
        }
        metas.putAll(loaded);
        metas.keySet().retainAll(loaded.keySet());
    }

    private GlossBoardMeta readBoardFile(File file) {
        try {
            JSONObject json = new JSONObject(IO.readAll(file));
            return GlossBoardMeta.fromJson(boardId(file), json);
        } catch (JSONException failure) {
            Gloss.warn("Invalid board json in " + file.getName() + ": " + failure.getMessage());
        } catch (IOException failure) {
            Gloss.warn("Unable to read board file " + file.getName() + ": " + failure.getMessage());
        }
        return null;
    }

    private void writeBoardFile(File file, String payload) {
        selfWrites.put(file.getAbsolutePath(), M.ms());
        try {
            IO.writeAll(file, payload);
        } catch (IOException failure) {
            Gloss.warn("Unable to save board file " + file.getName() + ": " + failure.getMessage());
        }
    }

    private void pollWatcher() {
        FolderWatcher activeWatcher = watcher;
        if (activeWatcher == null || !activeWatcher.checkModified()) {
            return;
        }
        boolean dirty = false;
        List<File> touched = new ArrayList<>(activeWatcher.getCreated());
        touched.addAll(activeWatcher.getChanged());
        for (File file : touched) {
            if (!file.getName().endsWith(BOARD_FILE_SUFFIX) || suppressed(file)) {
                continue;
            }
            GlossBoardMeta meta = readBoardFile(file);
            if (meta != null) {
                metas.put(meta.id(), meta);
                dirty = true;
            }
        }
        for (File file : activeWatcher.getDeleted()) {
            if (!file.getName().endsWith(BOARD_FILE_SUFFIX) || suppressed(file)) {
                continue;
            }
            dirty = metas.remove(boardId(file)) != null || dirty;
        }
        if (dirty) {
            reselectAll();
        }
    }

    private boolean suppressed(File file) {
        Long stamp = selfWrites.remove(file.getAbsolutePath());
        return stamp != null && M.ms() - stamp < SELF_WRITE_SUPPRESS_MS;
    }

    private String normalizeId(String id) {
        return id == null ? "" : id.trim().replace(" ", "-");
    }

    private String boardId(File file) {
        String name = file.getName();
        return name.substring(0, name.length() - BOARD_FILE_SUFFIX.length()).replace(" ", "-");
    }

    private File boardFile(String boardId) {
        return new File(boardsFolder(), boardId + BOARD_FILE_SUFFIX);
    }

    private File boardsFolder() {
        File folder = new File(plugin.getDataFolder(), "boards");
        if (!folder.exists() && !folder.mkdirs()) {
            Gloss.warn("Unable to create boards folder at " + folder.getAbsolutePath());
        }
        return folder;
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
