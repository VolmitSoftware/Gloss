package art.arcane.gloss;

import art.arcane.gloss.animation.AnimationService;
import art.arcane.gloss.api.GlossAPIProvider;
import art.arcane.gloss.api.internal.GlossApiServiceImpl;
import art.arcane.gloss.board.BoardService;
import art.arcane.gloss.bubble.ChatBubblesService;
import art.arcane.gloss.chat.ChatService;
import art.arcane.gloss.command.GlossCommandService;
import art.arcane.gloss.config.ConfigManager;
import art.arcane.gloss.config.GlossConfigFile;
import art.arcane.gloss.config.GlossConfigLoader;
import art.arcane.gloss.doc.DataWatchdog;
import art.arcane.gloss.drop.DropNameService;
import art.arcane.gloss.editor.sync.EditorSyncService;
import art.arcane.gloss.emoji.EmojiService;
import art.arcane.gloss.group.GroupService;
import art.arcane.gloss.hologram.HologramAnimator;
import art.arcane.gloss.hologram.HologramService;
import art.arcane.gloss.importer.HoloUiDataImporter;
import art.arcane.gloss.importer.LegacyGlossDataImporter;
import art.arcane.gloss.indicator.DamageIndicatorsService;
import art.arcane.gloss.integrate.IntegrationBridgeService;
import art.arcane.gloss.integration.ItemProviderRegistry;
import art.arcane.gloss.integration.protection.ContainerProtectionService;
import art.arcane.gloss.locale.GlossLocalization;
import art.arcane.gloss.menu.MenuSessionManager;
import art.arcane.gloss.motd.MotdService;
import art.arcane.gloss.panel.PanelRuntimeManager;
import art.arcane.gloss.panel.PanelService;
import art.arcane.gloss.persistence.GlossPersistenceCoordinator;
import art.arcane.gloss.persistence.GlossProjectTransaction;
import art.arcane.gloss.preview.PreviewScaleService;
import art.arcane.gloss.preview.doc.PreviewDocumentRegistry;
import art.arcane.gloss.service.GlossAPIImpl;
import art.arcane.gloss.service.GlossIntegrationService;
import art.arcane.gloss.service.MetricsRuntime;
import art.arcane.gloss.service.GlossPlaceholderInstaller;
import art.arcane.gloss.service.PanelCreationService;
import art.arcane.gloss.tab.TablistService;
import art.arcane.gloss.text.TextPipeline;
import art.arcane.gloss.util.SplashScreen;
import art.arcane.volmlib.integration.ReloadAware;
import art.arcane.volmlib.util.bukkit.Events;
import art.arcane.volmlib.util.bukkit.papi.PlaceholderRegistration;
import art.arcane.volmlib.util.hud.HudActionBar;
import art.arcane.volmlib.util.io.FileWatcher;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.volmlib.util.scheduling.SchedulerBridge;
import art.arcane.volmlib.util.scheduling.SchedulerRuntime;
import art.arcane.volmlib.util.scheduling.SchedulerUtils;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.manager.protocol.ProtocolManager;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.settings.PacketEventsSettings;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import io.github.slimjar.app.builder.SpigotApplicationBuilder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.java.JavaPlugin;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class Gloss extends JavaPlugin implements ReloadAware {
    private static final int BSTATS_PLUGIN_ID = 33525;
    private static final String PLACEHOLDER_API_PLUGIN = "PlaceholderAPI";

    public static Gloss instance;

    private final Deque<Runnable> teardowns = new ArrayDeque<>();

    private SchedulerRuntime scheduler;
    private GlossConfigLoader configLoader;
    private FileWatcher configWatcher;
    private DataWatchdog watchdog;
    private volatile GlossConfig config;
    private TextPipeline text;
    private AnimationService animations;
    private EmojiService emoji;
    private HologramAnimator animator;
    private HologramService holograms;
    private BoardService boards;
    private GroupService groups;
    private TablistService tablist;
    private MotdService motd;
    private ChatService chat;
    private ChatBubblesService bubbles;
    private DamageIndicatorsService indicators;
    private DropNameService drops;
    private GlossCommandService commands;
    private GlossAPIImpl api;
    private MetricsRuntime metrics;

    private HudActionBar hudBar;
    private GlossLocalization localization;
    private GlossPersistenceCoordinator persistenceCoordinator;
    private GlossProjectTransaction projectTransaction;
    private ConfigManager configManager;
    private PanelService panelService;
    private PanelRuntimeManager panelRuntime;
    private PreviewDocumentRegistry previewRegistry;
    private ItemProviderRegistry itemProviders;
    private ContainerProtectionService containerProtection;
    private MenuSessionManager sessionManager;
    private PanelCreationService panelCreation;
    private EditorSyncService editorSyncService;
    private GlossIntegrationService integrationService;
    private IntegrationBridgeService integrationBridge;
    private GlossApiServiceImpl apiService;
    private PlaceholderRegistration placeholderRegistration;
    private volatile Events placeholderEnableListener;

    public Gloss() {
        getLogger().info("Loading dependencies...");
        new SpigotApplicationBuilder(this).build();
        getLogger().info("Dependencies loaded.");
    }

    public static void info(String message) {
        if (instance != null) {
            instance.getLogger().info(message);
        }
    }

    public static void warn(String message) {
        if (instance != null) {
            instance.getLogger().warning(message);
        }
    }

    public static void verbose(String message) {
        if (instance != null) {
            instance.getLogger().fine(message);
        }
    }

    public static void log(Level logLevel, String message, Object... args) {
        logger().log(logLevel, args.length > 0 ? String.format(message, args) : message);
    }

    public static void logException(boolean isSevere, Throwable failure, int indents) {
        StringBuilder format = new StringBuilder("%s%s");
        for (int i = 0; i < indents; i++) {
            format.insert(0, "\t");
        }
        log(isSevere ? Level.SEVERE : Level.WARNING,
            format.toString(), failure.getClass().getSimpleName(),
            failure.getMessage() != null ? " - " + failure.getMessage() : "");
    }

    public static void logExceptionStack(boolean isSevere, Throwable failure, String message, Object... args) {
        String formatted = args.length > 0 ? String.format(message, args) : message;
        logger().log(isSevere ? Level.SEVERE : Level.WARNING, formatted, failure);
    }

    private static Logger logger() {
        Gloss current = instance;
        return current == null ? Logger.getLogger("Gloss") : current.getLogger();
    }

    @Override
    public void onLoad() {
        instance = this;
        SpigotPacketEventsBuilder.clearBuildCache();
        PacketEventsSettings packetEventsSettings = new PacketEventsSettings().checkForUpdates(false);
        PacketEvents.setAPI(SpigotPacketEventsBuilder.buildNoCache(this, packetEventsSettings));
        PacketEvents.getAPI().load();
    }

    @Override
    public void onEnable() {
        instance = this;
        boolean success = true;
        String errorMessage = null;
        try {
            scheduler = installSchedulerBridge();
            ImageIO.scanForPlugins();
            enableService("packetevents", this::initPacketEvents, this::terminatePacketEvents);
            configLoader = new GlossConfigLoader(getDataFolder());
            GlossConfigFile bootConfigFile = loadBootConfig();
            runDataImporters(bootConfigFile);
            config = GlossConfig.from(bootConfigFile);
            watchdog = new DataWatchdog(this);
            enableService("data-watchdog", this::startDataWatchdog, this::stopDataWatchdog);
            text = new TextPipeline(this);
            animations = new AnimationService(this);
            emoji = new EmojiService(this);
            animator = new HologramAnimator(this);
            holograms = new HologramService(this);
            boards = new BoardService(this);
            groups = new GroupService(this);
            tablist = new TablistService(this);
            motd = new MotdService(this);
            chat = new ChatService(this);
            bubbles = new ChatBubblesService(this);
            indicators = new DamageIndicatorsService(this);
            drops = new DropNameService(this);
            commands = new GlossCommandService(this);
            api = new GlossAPIImpl(this);

            enableService("text", text::enable, text::disable);
            enableService("animations", animations::enable, animations::disable);
            enableService("emoji", emoji::enable, emoji::disable);
            enableService("hologram-animator", animator::start, animator::stop);
            enableService("holograms", holograms::enable, holograms::disable);
            enableService("boards", boards::enable, boards::disable);
            enableService("groups", groups::enable, groups::disable);
            enableService("tablist", tablist::enable, tablist::disable);
            enableService("motd", motd::enable, motd::disable);
            enableService("chat", chat::enable, chat::disable);
            enableService("bubbles", bubbles::enable, bubbles::disable);
            enableService("indicators", indicators::enable, indicators::disable);
            enableService("drops", drops::enable, drops::disable);

            hudBar = new HudActionBar(this);
            enableService("hud", () -> {
            }, hudBar::shutdown);
            localization = new GlossLocalization(getDataFolder(), getLogger());
            persistenceCoordinator = new GlossPersistenceCoordinator();
            projectTransaction = new GlossProjectTransaction(getDataFolder().toPath());
            try {
                persistenceCoordinator.write(() -> {
                    projectTransaction.recover();
                    return null;
                });
            } catch (Exception failure) {
                throw new IllegalStateException("Unable to recover Gloss editor sync persistence", failure);
            }
            configManager = new ConfigManager(getDataFolder());
            panelService = new PanelService(this);
            enableService("panels", this::startPanelService, panelService::shutdown);
            previewRegistry = new PreviewDocumentRegistry(getDataFolder());
            enableService("previews", this::startPreviewWatching, previewRegistry::stopWatching);
            itemProviders = new ItemProviderRegistry(this);
            enableService("item-providers", itemProviders::activateAll, itemProviders::shutdown);
            containerProtection = new ContainerProtectionService(this);
            enableService("protection", containerProtection::activate, containerProtection::shutdown);
            sessionManager = new MenuSessionManager();
            enableService("menus", () -> {
            }, sessionManager::destroyAll);
            panelRuntime = new PanelRuntimeManager(this, panelService);
            enableService("panel-runtime", () -> {
            }, panelRuntime::shutdown);
            panelCreation = new PanelCreationService(this);
            enableService("panel-creation", () -> {
            }, panelCreation::shutdown);
            editorSyncService = new EditorSyncService(this);
            enableService("editor-sync", this::startEditorSync, editorSyncService::shutdown);
            enableService("panel-creation-intake", () -> {
            }, panelCreation::stopAccepting);
            enableService("config-watchers", configManager::startWatching, configManager::shutdown);
            enableService("preview-scale", this::startPreviewScale, PreviewScaleService::shutdown);
            getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");
            enableService("bungee-channel", () -> {
            }, () -> getServer().getMessenger().unregisterOutgoingPluginChannel(this, "BungeeCord"));

            enableService("commands", commands::register, commands::shutdown);
            metrics = MetricsRuntime.start(this, BSTATS_PLUGIN_ID);
            integrationService = new GlossIntegrationService();
            enableService("integration", integrationService::register, integrationService::unregister);
            integrationBridge = new IntegrationBridgeService(this);
            enableService("integration-bridge", integrationBridge::enable, integrationBridge::disable);
            apiService = new GlossApiServiceImpl(this);
            enableService("api-service", () -> apiService.register(api), apiService::unregister);
            placeholderRegistration = new PlaceholderRegistration(getLogger());
            enableService("placeholders", this::installPlaceholders, this::shutdownPlaceholders);
            GlossAPIProvider.set(api);
        } catch (Throwable failure) {
            success = false;
            errorMessage = failure.getClass().getSimpleName()
                + (failure.getMessage() == null ? "" : ": " + failure.getMessage());
            getLogger().log(Level.SEVERE, "Gloss failed to enable", failure);
            GlossAPIProvider.set(null);
            shutdownServices();
        }

        GlossConfig activeConfig = config;
        if (!success || activeConfig == null || activeConfig.splashScreen()) {
            SplashScreen.print(this, success, errorMessage);
        }
    }

    @Override
    public void onDisable() {
        shutdownServices();
        SchedulerUtils.cancelPluginTasks(this);
        if (scheduler != null) {
            scheduler.cancelPluginTasks();
        }
        GlossAPIProvider.set(null);
        instance = null;
    }

    @Override
    public void onPreUnload(PreUnloadReason reason) {
        log(Level.INFO, "Pre-unload hook fired (%s). Tearing down Gloss sessions and PacketEvents.", reason);
        shutdownServices();
        SchedulerUtils.cancelPluginTasks(this);
        if (scheduler != null) {
            scheduler.cancelPluginTasks();
        }
    }

    /**
     * Operator-facing reload (/gloss reload). Cycles every service unconditionally: the operator is
     * asking for everything on disk to be re-read, not just the parts config.toml happens to
     * mention.
     */
    public void reloadAll() {
        applyReloadedConfig(true);
    }

    private void applyReloadedConfig(boolean cycleEveryService) {
        GlossConfigFile reloaded;
        try {
            reloaded = configLoader.loadForReload();
        } catch (IOException failure) {
            logExceptionStack(false, failure,
                "config.toml is invalid; keeping the last good configuration.");
            return;
        }
        GlossConfig previous = config;
        GlossConfig next = GlossConfig.from(reloaded);
        config = next;
        reloadServices(previous, next, cycleEveryService);
        applyMergedConfigHooks(previous, next);
        info("Reloaded in-place from disk.");
    }

    /**
     * A service reload re-parses that service's documents and, for holograms, despawns and respawns
     * every display it owns. On the watchdog path that cost is only owed to services whose own
     * config section actually moved — editing an unrelated key in config.toml used to respawn every
     * hologram on the server. The sections are records, so an unchanged section compares equal.
     * Documents themselves keep hot-reloading through their own watchdog entries either way.
     */
    private void reloadServices(GlossConfig previous, GlossConfig next, boolean cycleEveryService) {
        if (previous == null || cycleEveryService) {
            text.reload();
            animations.reload();
            emoji.reload();
            holograms.reload();
            boards.reload();
            groups.reload();
            tablist.reload();
            motd.reload();
            bubbles.reload();
            indicators.reload();
            drops.reload();
            return;
        }
        if (!previous.text().equals(next.text())) {
            text.reload();
        }
        if (!previous.animations().equals(next.animations())) {
            animations.reload();
        }
        if (!previous.emoji().equals(next.emoji())) {
            emoji.reload();
        }
        if (!previous.holograms().equals(next.holograms())) {
            holograms.reload();
        }
        if (!previous.boards().equals(next.boards())) {
            boards.reload();
        }
        if (!previous.groups().equals(next.groups())) {
            groups.reload();
        }
        if (!previous.tablist().equals(next.tablist())) {
            tablist.reload();
        }
        if (!previous.motd().equals(next.motd())) {
            motd.reload();
        }
        if (!previous.bubbles().equals(next.bubbles())) {
            bubbles.reload();
        }
        if (!previous.indicators().equals(next.indicators())) {
            indicators.reload();
        }
        if (!previous.drops().equals(next.drops())) {
            drops.reload();
        }
    }

    private GlossConfigFile loadBootConfig() {
        try {
            return configLoader.loadForBoot();
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to load config.toml", failure);
        }
    }

    /**
     * The data importers run in exactly this slot: after config.toml is loaded (so the HoloUi
     * settings overlay lands in the in-memory boot config before {@link GlossConfig#from}
     * snapshots it) and before the DataWatchdog and every service constructs — ConfigManager
     * scans menus/ in its constructor and PanelService/registries scan on start, so imported and
     * migrated files must already be in place. Importer failures never abort enable.
     */
    private void runDataImporters(GlossConfigFile bootConfigFile) {
        try {
            HoloUiDataImporter holoUiImporter = new HoloUiDataImporter(getDataFolder(), configLoader);
            if (holoUiImporter.shouldRun()) {
                holoUiImporter.run(bootConfigFile, false);
            }
        } catch (RuntimeException failure) {
            logExceptionStack(false, failure, "HoloUi data import failed; continuing enable.");
        }
        try {
            new LegacyGlossDataImporter(getDataFolder(), configLoader).run(bootConfigFile);
        } catch (RuntimeException failure) {
            logExceptionStack(false, failure, "Legacy Gloss data migration failed; continuing enable.");
        }
    }

    private void startDataWatchdog() {
        configWatcher = new FileWatcher(configLoader.file());
        watchdog.register("config", this::configWatchTick);
        watchdog.start(config.hotload().watchIntervalTicks());
    }

    private void stopDataWatchdog() {
        watchdog.stop();
        watchdog.unregister("config");
        configWatcher = null;
    }

    /**
     * Runs on the watchdog IO thread. The stat and the self-write hash are exactly the work that
     * belongs there; the reload itself cycles services that spawn entities and send packets, so it
     * hops to the server context. The self-write guard stays ahead of the hop so Gloss never
     * reloads its own canonicalising rewrite of config.toml.
     */
    private void configWatchTick() {
        FileWatcher watcher = configWatcher;
        if (watcher == null || !watcher.checkModified()) {
            return;
        }
        if (configLoader.isSelfWrite()) {
            return;
        }
        info("config.toml changed on disk; reloading.");
        if (SchedulerUtils.runGlobal(this, () -> applyReloadedConfig(false))) {
            return;
        }
        warn("config.toml reload could not be scheduled onto the server thread; skipping this pass.");
    }

    private void applyMergedConfigHooks(GlossConfig previous, GlossConfig next) {
        if (sessionManager != null) {
            sessionManager.controlHitboxDebug(next.debug().hitbox());
            sessionManager.controlPositionDebug(next.debug().position());
        }
        boolean scaleChanged = previous.previews().scale() != next.previews().scale()
            || previous.menus().uiScale() != next.menus().uiScale();
        if (scaleChanged) {
            if (itemProviders != null) {
                itemProviders.invalidate();
            }
            if (sessionManager != null) {
                sessionManager.refreshVisuals();
            }
            if (panelRuntime != null) {
                panelRuntime.refreshVisuals();
            }
        }
        boolean customItemsChanged = previous.customItems().enabled() != next.customItems().enabled()
            || !previous.customItems().providers().equals(next.customItems().providers());
        if (customItemsChanged && itemProviders != null) {
            itemProviders.reload();
        }
        if (previous.hotload().watchIntervalTicks() != next.hotload().watchIntervalTicks()) {
            watchdog.restart(next.hotload().watchIntervalTicks());
        }
        if (integrationBridge != null
            && previous.integration().sampleIntervalTicks() != next.integration().sampleIntervalTicks()) {
            integrationBridge.restart(next.integration().sampleIntervalTicks());
        }
    }

    public SchedulerRuntime scheduler() {
        return scheduler;
    }

    public DataWatchdog watchdog() {
        return watchdog;
    }

    public GlossConfigLoader configLoader() {
        return configLoader;
    }

    public GlossConfig cfg() {
        return config;
    }

    public TextPipeline text() {
        return text;
    }

    public AnimationService animations() {
        return animations;
    }

    public EmojiService emoji() {
        return emoji;
    }

    public HologramAnimator animator() {
        return animator;
    }

    public HologramService holograms() {
        return holograms;
    }

    public BoardService boards() {
        return boards;
    }

    public GroupService groups() {
        return groups;
    }

    public TablistService tablist() {
        return tablist;
    }

    public MotdService motd() {
        return motd;
    }

    public ChatService chat() {
        return chat;
    }

    public ChatBubblesService bubbles() {
        return bubbles;
    }

    public DamageIndicatorsService indicators() {
        return indicators;
    }

    public DropNameService drops() {
        return drops;
    }

    public HudActionBar getHudBar() {
        return hudBar;
    }

    public GlossLocalization getLocalization() {
        return localization;
    }

    public GlossPersistenceCoordinator getPersistenceCoordinator() {
        return persistenceCoordinator;
    }

    public GlossProjectTransaction getProjectTransaction() {
        return projectTransaction;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public PanelService getPanelService() {
        return panelService;
    }

    public PanelRuntimeManager getPanelRuntime() {
        return panelRuntime;
    }

    public PreviewDocumentRegistry getPreviewRegistry() {
        return previewRegistry;
    }

    public ItemProviderRegistry getItemProviders() {
        return itemProviders;
    }

    public ContainerProtectionService getContainerProtection() {
        return containerProtection;
    }

    public MenuSessionManager getSessionManager() {
        return sessionManager;
    }

    public PanelCreationService getPanelCreationService() {
        return panelCreation;
    }

    public EditorSyncService getEditorSyncService() {
        return editorSyncService;
    }

    public GlossIntegrationService getIntegrationService() {
        return integrationService;
    }

    public IntegrationBridgeService getIntegrationBridge() {
        return integrationBridge;
    }

    public GlossApiServiceImpl getApiService() {
        return apiService;
    }

    public PlaceholderRegistration getPlaceholderRegistration() {
        return placeholderRegistration;
    }

    private void initPacketEvents() {
        prewarmPacketEventsUsers();
        try {
            PacketEvents.getAPI().init();
        } catch (NullPointerException ex) {
            if (!isPacketEventsUserBindFailure(ex)) {
                throw ex;
            }
            prewarmPacketEventsUsers();
            PacketEvents.getAPI().init();
        }
    }

    private void terminatePacketEvents() {
        if (PacketEvents.getAPI() != null) {
            PacketEvents.getAPI().terminate();
        }
        SpigotPacketEventsBuilder.clearBuildCache();
    }

    private void startPanelService() {
        if (!config.panels().enabled()) {
            return;
        }
        panelService.start();
    }

    private void startPreviewWatching() {
        if (!config.previews().enabled()) {
            return;
        }
        previewRegistry.startWatching();
    }

    private void startPreviewScale() {
        if (!config.previews().enabled()) {
            return;
        }
        PreviewScaleService.init(this);
    }

    private void startEditorSync() {
        try {
            editorSyncService.start();
        } catch (RuntimeException failure) {
            getLogger().log(Level.SEVERE,
                "Editor sync was disabled because its secure session store could not be loaded. "
                    + "Repair or remove editor-sync-sessions.json; Gloss will keep one-way editor handoffs available.",
                failure);
        }
    }

    private void installPlaceholders() {
        if (tryInstallPlaceholders() || placeholderEnableListener != null) {
            return;
        }
        placeholderEnableListener = Events.listen(this, PluginEnableEvent.class, event -> {
            if (!PLACEHOLDER_API_PLUGIN.equals(event.getPlugin().getName())) {
                return;
            }
            if (tryInstallPlaceholders()) {
                stopPlaceholderWatch();
            }
        });
    }

    private boolean tryInstallPlaceholders() {
        if (placeholderRegistration.isRegistered()) {
            return true;
        }
        if (!PlaceholderRegistration.isPlaceholderApiEnabled()) {
            return false;
        }
        GlossPlaceholderInstaller.install(placeholderRegistration, sessionManager.getOpenMenus(), getLogger());
        return placeholderRegistration.isRegistered();
    }

    private void stopPlaceholderWatch() {
        Events listener = placeholderEnableListener;
        if (listener == null) {
            return;
        }
        placeholderEnableListener = null;
        listener.unregister();
    }

    private void shutdownPlaceholders() {
        stopPlaceholderWatch();
        placeholderRegistration.unregister();
    }

    private void prewarmPacketEventsUsers() {
        PacketEventsAPI<?> api = PacketEvents.getAPI();
        if (api == null) {
            return;
        }
        PlayerManager playerManager = api.getPlayerManager();
        ProtocolManager protocolManager = api.getProtocolManager();
        ClientVersion fallbackVersion = api.getServerManager().getVersion().toClientVersion();
        Collection<? extends Player> onlinePlayers = Bukkit.getOnlinePlayers();
        for (Player player : onlinePlayers) {
            Object channel = playerManager.getChannel(player);
            if (channel == null) {
                continue;
            }
            User existingUser = protocolManager.getUser(channel);
            if (existingUser != null) {
                continue;
            }
            UserProfile profile = new UserProfile(player.getUniqueId(), player.getName());
            User newUser = new User(channel, ConnectionState.PLAY, fallbackVersion, profile);
            protocolManager.setUser(channel, newUser);
        }
    }

    private boolean isPacketEventsUserBindFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            for (StackTraceElement element : current.getStackTrace()) {
                if (element.getClassName().endsWith("SpigotChannelInjector")
                    && element.getMethodName().equals("updatePlayer")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private void enableService(String system, Runnable starter, Runnable stopper) {
        starter.run();
        teardowns.push(() -> shutdownQuietly(stopper, system));
    }

    private void shutdownServices() {
        if (metrics != null) {
            metrics.shutdown();
            metrics = null;
        }
        while (!teardowns.isEmpty()) {
            teardowns.pop().run();
        }
    }

    private void shutdownQuietly(Runnable action, String system) {
        try {
            action.run();
        } catch (Throwable failure) {
            getLogger().log(Level.WARNING, "Failed to shut down " + system, failure);
        }
    }

    private SchedulerRuntime installSchedulerBridge() {
        SchedulerRuntime runtime = new SchedulerRuntime(
            () -> this,
            this::runAsyncTask,
            message -> getLogger().fine(message),
            message -> getLogger().warning(message),
            throwable -> getLogger().log(Level.SEVERE, "Gloss scheduler error", throwable)
        );

        SchedulerBridge.setSyncScheduler(runtime::s);
        SchedulerBridge.setDelayedSyncScheduler(runtime::s);
        SchedulerBridge.setAsyncScheduler(runnable -> runtime.a(runnable, 0));
        SchedulerBridge.setDelayedAsyncScheduler(runtime::a);
        SchedulerBridge.setSyncRepeatingScheduler(runtime::sr);
        SchedulerBridge.setAsyncRepeatingScheduler(runtime::ar);
        SchedulerBridge.setCancelScheduler(runtime::csr);
        SchedulerBridge.setErrorHandler(throwable -> getLogger().log(Level.SEVERE, "Gloss scheduler error", throwable));
        SchedulerBridge.setInfoLogger(message -> getLogger().info(message));
        return runtime;
    }

    private void runAsyncTask(Runnable runnable) {
        if (!FoliaScheduler.runAsync(this, runnable)) {
            getLogger().warning("An asynchronous Gloss task was rejected by the scheduler and did not run.");
        }
    }
}
