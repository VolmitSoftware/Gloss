package art.arcane.gloss;

import art.arcane.volmlib.util.diagnostics.BukkitDebugDump;
import art.arcane.gloss.animation.AnimationService;
import art.arcane.gloss.api.GlossAPIProvider;
import art.arcane.gloss.api.internal.GlossApiServiceImpl;
import art.arcane.gloss.board.BoardService;
import art.arcane.gloss.bubble.ChatBubblesService;
import art.arcane.gloss.chat.ChatService;
import art.arcane.gloss.command.GlossCommandService;
import art.arcane.gloss.config.menu.MenuCatalog;
import art.arcane.gloss.config.GlossConfigFile;
import art.arcane.gloss.config.GlossConfigLoader;
import art.arcane.gloss.doc.DataWatchdog;
import art.arcane.gloss.drop.DropNameService;
import art.arcane.gloss.editor.sync.EditorSyncService;
import art.arcane.gloss.editor.sync.EditorSyncDocumentKind;
import art.arcane.gloss.emoji.EmojiService;
import art.arcane.gloss.group.GroupService;
import art.arcane.gloss.hologram.HologramAnimator;
import art.arcane.gloss.hologram.HologramService;
import art.arcane.gloss.image.ImageAssets;
import art.arcane.gloss.importer.HoloUiDataImporter;
import art.arcane.gloss.indicator.DamageIndicatorsService;
import art.arcane.gloss.integrate.IntegrationBridgeService;
import art.arcane.gloss.integration.ItemProviderRegistry;
import art.arcane.gloss.profile.PlayerHeadService;
import art.arcane.gloss.integration.protection.ContainerProtectionService;
import art.arcane.gloss.locale.GlossLocalization;
import art.arcane.volmlib.util.localization.BukkitLanguageSwitcher;
import art.arcane.volmlib.util.localization.LocalizationSnapshot;
import art.arcane.gloss.menu.MenuSessionManager;
import art.arcane.gloss.motd.MotdService;
import art.arcane.gloss.panel.PanelRuntimeManager;
import art.arcane.gloss.panel.PanelService;
import art.arcane.gloss.particle.ParticleService;
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
import art.arcane.volmlib.util.plugin.ComponentLog;
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
import java.nio.file.NoSuchFileException;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class Gloss extends JavaPlugin implements ReloadAware {
    private static final int BSTATS_PLUGIN_ID = 33525;
    private static final String PLACEHOLDER_API_PLUGIN = "PlaceholderAPI";
    private static final String LOCALE_WATCHDOG_ENTRY = "locale";
    private static final long CONFIG_RECONCILIATION_NANOS = TimeUnit.SECONDS.toNanos(6L);
    private static final long FAILURE_LOG_THROTTLE_NANOS = TimeUnit.MINUTES.toNanos(1L);
    private static final ConcurrentMap<String, FailureLogThrottle> FAILURE_LOG_THROTTLES = new ConcurrentHashMap<>();
    private static final Logger FALLBACK_LOGGER = Logger.getLogger("Gloss");
    private static final String LOG_DISCRIMINATOR = ComponentLog.discriminator("Gloss", "&#b47aff");

    public static Gloss instance;

    private final Deque<Runnable> teardowns = new ArrayDeque<>();
    private final AtomicLong configReloadGeneration = new AtomicLong();

    private SchedulerRuntime scheduler;
    private GlossConfigLoader configLoader;
    private FileWatcher configWatcher;
    private volatile GlossConfigLoader.ReloadSnapshot pendingConfigSnapshot;
    private volatile long nextConfigReconciliationNanos;
    private DataWatchdog watchdog;
    private volatile GlossConfig config;
    private BukkitLanguageSwitcher languageSwitcher;
    private BukkitDebugDump debugDump;
    private TextPipeline text;
    private AnimationService animations;
    private EmojiService emoji;
    private HologramAnimator animator;
    private ParticleService particles;
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
    private MenuCatalog menuCatalog;
    private ImageAssets imageAssets;
    private PanelService panelService;
    private PanelRuntimeManager panelRuntime;
    private PreviewDocumentRegistry previewRegistry;
    private ItemProviderRegistry itemProviders;
    private PlayerHeadService playerHeads;
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

    public static void info(String message, Object... args) {
        log(Level.INFO, message, args);
    }

    public static void warn(String message, Object... args) {
        log(Level.WARNING, message, args);
    }

    public static void verbose(String message, Object... args) {
        log(Level.FINE, message, args);
    }

    public static void warnThrottled(String key, String message, Object... args) {
        logThrottled(Level.WARNING, key, message, args);
    }

    public static void log(Level logLevel, String message, Object... args) {
        Logger current = logger();
        if (!current.isLoggable(logLevel)) {
            return;
        }
        ComponentLog.logLegacy(instance, FALLBACK_LOGGER, LOG_DISCRIMINATOR, logLevel,
            format(message, args), null);
    }

    public static void logThrottled(Level logLevel, String key, String message, Object... args) {
        Logger current = logger();
        if (!current.isLoggable(logLevel)) {
            return;
        }
        long suppressed = claimFailureLog(key);
        if (suppressed < 0L) {
            return;
        }
        ComponentLog.logLegacy(instance, FALLBACK_LOGGER, LOG_DISCRIMINATOR, logLevel,
            withSuppressed(format(message, args), suppressed), null);
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
        Level level = isSevere ? Level.SEVERE : Level.WARNING;
        Logger current = logger();
        if (!current.isLoggable(level)) {
            return;
        }
        ComponentLog.logLegacy(instance, FALLBACK_LOGGER, LOG_DISCRIMINATOR, level,
            format(message, args), failure);
    }

    public static void logExceptionStackThrottled(boolean isSevere, String key, Throwable failure,
                                                   String message, Object... args) {
        Level level = isSevere ? Level.SEVERE : Level.WARNING;
        Logger current = logger();
        if (!current.isLoggable(level)) {
            return;
        }
        long suppressed = claimFailureLog(key);
        if (suppressed < 0L) {
            return;
        }
        ComponentLog.logLegacy(instance, FALLBACK_LOGGER, LOG_DISCRIMINATOR, level,
            withSuppressed(format(message, args), suppressed), failure);
    }

    private static Logger logger() {
        Gloss current = instance;
        return current == null ? FALLBACK_LOGGER : current.getLogger();
    }

    private static String format(String message, Object... args) {
        return args.length > 0 ? String.format(message, args) : message;
    }

    private static long claimFailureLog(String key) {
        FailureLogThrottle throttle = FAILURE_LOG_THROTTLES.computeIfAbsent(key,
            ignored -> new FailureLogThrottle());
        return throttle.claim(System.nanoTime());
    }

    private static String withSuppressed(String message, long suppressed) {
        return suppressed > 0L ? message + " (" + suppressed + " similar failures suppressed.)" : message;
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
        Throwable startupFailure = null;
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
            particles = new ParticleService(this);
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
            enableService("particles", () -> {
            }, particles::clear);
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
            localization = new GlossLocalization(getDataFolder(), getLogger(), config.language());
            enableService("diagnostic-reports", () -> debugDump = BukkitDebugDump.create(this), () -> {
                if (debugDump != null) {
                    debugDump.close();
                    debugDump = null;
                }
            });
            languageSwitcher = BukkitLanguageSwitcher.register(this, localization.enableLanguages(this),
                    new BukkitLanguageSwitcher.Options("gloss", "gloss.admin",
                            GlossCommandService.menuTheme(), localization.directorResolver(), localization.editorOptions()));
            if (!config.language().equals(localization.activeLocale())) {
                localization.reloadConfigured(config.language());
            }
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
            menuCatalog = new MenuCatalog(getDataFolder());
            imageAssets = new ImageAssets(getDataFolder());
            panelService = new PanelService(this);
            enableService("panels", this::startPanelService, panelService::shutdown);
            startPreviewRegistry();
            itemProviders = new ItemProviderRegistry(this);
            enableService("item-providers", itemProviders::activateAll, itemProviders::shutdown);
            enableService("player-heads",
                () -> playerHeads = PlayerHeadService.fromConfig(cfg().playerHeads()),
                () -> {
                    PlayerHeadService current = playerHeads;
                    playerHeads = null;
                    if (current != null) {
                        current.invalidate();
                    }
                });
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
            enableService("menu-catalog", menuCatalog::startWatching, menuCatalog::shutdown);
            enableService("image-assets", imageAssets::startWatching, imageAssets::stopWatching);
            enableService("locale-watcher", this::startLocaleWatcher, this::stopLocaleWatcher);
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
            startupFailure = failure;
            logExceptionStack(true, failure, "Gloss failed to enable.");
            GlossAPIProvider.set(null);
            shutdownServices();
        }

        GlossConfig activeConfig = config;
        if (!success || activeConfig == null || activeConfig.splashScreen()) {
            SplashScreen.print(this, success);
        }
        if (startupFailure != null) {
            throw propagateEnableFailure(startupFailure);
        }
    }

    static RuntimeException propagateEnableFailure(Throwable failure) {
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure instanceof RuntimeException runtimeFailure) {
            return runtimeFailure;
        }
        return new IllegalStateException("Gloss failed to enable", failure);
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
     * asking for everything on disk to be re-read, not just the parts gloss.toml happens to
     * mention.
     */
    public void reloadAll() {
        configReloadGeneration.incrementAndGet();
        pendingConfigSnapshot = null;
        try {
            applyReloadedConfig(true);
        } finally {
            DataWatchdog current = watchdog;
            if (current != null) {
                current.deferAutomaticPass();
            }
        }
    }

    public CompletableFuture<Void> publishEditorSyncRuntime(
        Set<EditorSyncDocumentKind> changedKinds,
        boolean imagesChanged
    ) {
        Set<EditorSyncDocumentKind> kinds = Set.copyOf(changedKinds);
        CompletableFuture<Void> publication = new CompletableFuture<>();
        boolean accepted = SchedulerUtils.runGlobal(this, () -> {
            try {
                if (kinds.contains(EditorSyncDocumentKind.ANIMATION)) {
                    animations.reload();
                }
                if (kinds.contains(EditorSyncDocumentKind.EMOJI)) {
                    emoji.reload();
                }
                if (kinds.contains(EditorSyncDocumentKind.HOLOGRAM)) {
                    holograms.reload();
                }
                if (kinds.contains(EditorSyncDocumentKind.SCOREBOARD)) {
                    boards.reload();
                }
                if (kinds.contains(EditorSyncDocumentKind.MOTD)) {
                    motd.reload();
                }
                if (kinds.contains(EditorSyncDocumentKind.BUBBLE_STYLE)) {
                    bubbles.reload();
                }
                if (kinds.contains(EditorSyncDocumentKind.TABLIST)) {
                    tablist.reload();
                }
                if (kinds.contains(EditorSyncDocumentKind.REAL_DROPS)) {
                    drops.reload();
                }
                if (kinds.contains(EditorSyncDocumentKind.CONTAINER_PREVIEW)
                    && previewRegistry != null) {
                    previewRegistry.reload();
                }
                if (kinds.contains(EditorSyncDocumentKind.DAMAGE_INDICATORS)) {
                    indicators.reloadSettings();
                }
                if (imagesChanged) {
                    imageAssets.publishEditorSyncChanges();
                }
                if (kinds.contains(EditorSyncDocumentKind.PANEL)) {
                    panelService.publishExternalReload();
                }
                publication.complete(null);
            } catch (IOException | RuntimeException failure) {
                publication.completeExceptionally(failure);
            }
        });
        if (!accepted) {
            publication.completeExceptionally(
                new IllegalStateException("unable to schedule editor sync runtime publication"));
        }
        return publication;
    }

    private void applyReloadedConfig(boolean cycleEveryService) {
        applyReloadedConfig(cycleEveryService, null);
    }

    private boolean applyReloadedConfig(
        boolean cycleEveryService,
        GlossConfigLoader.ReloadSnapshot reloadSnapshot
    ) {
        GlossConfigFile reloaded;
        try {
            reloaded = reloadSnapshot == null
                ? configLoader.loadForReload()
                : configLoader.loadForReload(reloadSnapshot);
        } catch (IOException failure) {
            logExceptionStack(false, failure,
                "gloss.toml is invalid; keeping the last good configuration.");
            return false;
        }
        GlossConfig previous = config;
        GlossConfig next = GlossConfig.from(reloaded);
        config = next;
        reloadServices(previous, next, cycleEveryService);
        applyMergedConfigHooks(previous, next, cycleEveryService);
        info("Reloaded in-place from disk.");
        return true;
    }

    /**
     * A service reload re-parses that service's documents and, for holograms, despawns and respawns
     * every display it owns. On the watchdog path that cost is only owed to services whose own
     * config section actually moved — editing an unrelated key in gloss.toml used to respawn every
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
        if (!previous.drops().equals(next.drops()) || !previous.realDrops().equals(next.realDrops())) {
            drops.reload();
        }
    }

    private GlossConfigFile loadBootConfig() {
        try {
            return configLoader.loadForBoot();
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to load gloss.toml", failure);
        }
    }

    /**
     * The data importers run in exactly this slot: after gloss.toml is loaded (so the HoloUi
     * settings overlay lands in the in-memory boot config before {@link GlossConfig#from}
     * snapshots it) and before the DataWatchdog and every service constructs — MenuCatalog
     * scans menus/ in its constructor and PanelService/registries scan on start, so imported files
     * must already be in place. Importer failures never abort enable.
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
    }

    private void startDataWatchdog() {
        FileWatcher previous = configWatcher;
        configWatcher = new FileWatcher(configLoader.file());
        nextConfigReconciliationNanos = System.nanoTime() + CONFIG_RECONCILIATION_NANOS;
        if (previous != null) {
            previous.close();
        }
        watchdog.register("config", this::configWatchTick);
        watchdog.start(config.hotload().watchIntervalTicks());
    }

    private void stopDataWatchdog() {
        watchdog.stop();
        watchdog.unregister("config");
        FileWatcher previous = configWatcher;
        configWatcher = null;
        if (previous != null) {
            previous.close();
        }
    }

    /**
     * The language file rides the watchdog as its own entry. It used to be a tail call on the menu
     * pass, which meant a menu folder that failed to poll took the locale reload down with it.
     */
    private void startLocaleWatcher() {
        watchdog.register(LOCALE_WATCHDOG_ENTRY, () -> {
            if (localization.update()) {
                watchdog.recordHotload("language.yml", 1);
            }
        });
    }

    public BukkitDebugDump debugDump() {
        return debugDump;
    }

    public BukkitLanguageSwitcher languageSwitcher() {
        return languageSwitcher;
    }

    public synchronized void selectLanguage(String locale, LocalizationSnapshot snapshot) throws IOException {
        GlossConfigFile updated = configLoader.loadForReload();
        updated.language = locale;
        configLoader.save(updated);
        config = config.withLanguage(locale);
        localization.install(locale, snapshot);
    }

    private void stopLocaleWatcher() {
        if (languageSwitcher != null) {
            languageSwitcher.close();
            languageSwitcher = null;
        }
        watchdog.unregister(LOCALE_WATCHDOG_ENTRY);
        if (localization != null) {
            localization.close();
        }
    }

    /**
     * Runs on the watchdog IO thread. Snapshot capture and self-write comparison happen here; the
     * reload itself cycles services that spawn entities and send packets, so it hops to the server
     * context. Two consecutive passes must see the same bytes before an automatic apply starts.
     */
    private void configWatchTick() {
        long reloadGeneration = configReloadGeneration.get();
        FileWatcher watcher = configWatcher;
        if (watcher == null) {
            return;
        }
        boolean watcherChanged = watcher.checkModifiedEvents();
        long now = System.nanoTime();
        boolean reconciliationDue = now >= nextConfigReconciliationNanos;
        if (reconciliationDue) {
            nextConfigReconciliationNanos = now + CONFIG_RECONCILIATION_NANOS;
        }
        if (!watcherChanged && pendingConfigSnapshot == null && !reconciliationDue) {
            return;
        }
        GlossConfigLoader.ReloadSnapshot snapshot;
        try {
            snapshot = configLoader.captureReloadSnapshot();
        } catch (NoSuchFileException missing) {
            pendingConfigSnapshot = null;
            return;
        } catch (IOException failure) {
            throw new IllegalStateException("Could not capture a stable gloss.toml snapshot", failure);
        }
        boolean selfWrite = configLoader.isSelfWrite(snapshot);
        if (!watcherChanged && selfWrite) {
            pendingConfigSnapshot = null;
            return;
        }
        if (selfWrite) {
            pendingConfigSnapshot = null;
            return;
        }
        GlossConfigLoader.ReloadSnapshot pending = pendingConfigSnapshot;
        if (pending == null || !pending.sha256().equals(snapshot.sha256())) {
            pendingConfigSnapshot = snapshot;
            return;
        }
        pendingConfigSnapshot = null;
        info("gloss.toml changed on disk; reloading.");
        if (SchedulerUtils.runGlobal(this, () -> {
            if (reloadGeneration == configReloadGeneration.get()
                && applyReloadedConfig(false, snapshot)) {
                watchdog.recordHotload("gloss.toml", 1);
            }
        })) {
            return;
        }
        pendingConfigSnapshot = snapshot;
        warnThrottled("config-hotload-scheduling",
            "gloss.toml reload could not be scheduled onto the server thread; skipping this pass.");
    }

    private void applyMergedConfigHooks(GlossConfig previous, GlossConfig next, boolean cycleEveryService) {
        if (menuCatalog != null
            && (cycleEveryService || !previous.menus().enabled() && next.menus().enabled())) {
            menuCatalog.loadShippedDefaults(next.menus().enabled());
        }
        if (localization != null) {
            if (!previous.language().equals(next.language())) {
                localization.reloadConfigured(next.language());
            } else if (cycleEveryService) {
                localization.reloadConfigured(next.language());
            }
        }
        if (previous.metrics() != next.metrics()) {
            if (metrics != null) {
                metrics.shutdown();
                metrics = null;
            }
            if (next.metrics()) {
                metrics = MetricsRuntime.start(this, BSTATS_PLUGIN_ID);
            }
        }
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
        if (playerHeads != null && !previous.playerHeads().equals(next.playerHeads())) {
            // TTLs and the cache ceiling are baked into the service, and an operator who just fixed
            // a name or flipped resolution on should see it on the next refresh, not in six hours.
            PlayerHeadService previousHeads = playerHeads;
            playerHeads = PlayerHeadService.fromConfig(next.playerHeads());
            previousHeads.invalidate();
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

    public ParticleService particles() {
        return particles;
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

    public MenuCatalog getMenuCatalog() {
        return menuCatalog;
    }

    public ImageAssets getImageAssets() {
        return imageAssets;
    }

    public PanelService getPanelService() {
        return panelService;
    }

    public PanelRuntimeManager getPanelRuntime() {
        return panelRuntime;
    }

    /** Null before enable finishes, and for the whole run when {@code previews.enabled} is false. */
    public PreviewDocumentRegistry getPreviewRegistry() {
        return previewRegistry;
    }

    public ItemProviderRegistry getItemProviders() {
        return itemProviders;
    }

    /**
     * The player-head profile cache. Null before enable finishes and after disable, which every
     * caller already treats as "no head resolved yet" rather than an error.
     */
    public PlayerHeadService playerHeads() {
        return playerHeads;
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

    /**
     * Constructing the registry extracts fourteen shipped documents into {@code previews/}. With
     * previews off there is nothing to serve them to, so the registry is never built and the folder
     * is never created; every caller of {@link #getPreviewRegistry()} already reads it as nullable.
     */
    private void startPreviewRegistry() {
        if (!config.previews().enabled()) {
            return;
        }
        previewRegistry = new PreviewDocumentRegistry(getDataFolder());
        enableService("previews", previewRegistry::startWatching, previewRegistry::stopWatching);
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
            logExceptionStack(true, failure,
                "Editor sync was disabled because its secure session store could not be loaded. "
                    + "Repair or remove editor-sync-sessions.json; live web edit commands remain unavailable until restart.");
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
        DataWatchdog currentWatchdog = watchdog;
        if (currentWatchdog != null) {
            currentWatchdog.stop();
        }
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
            logExceptionStack(false, failure, "Failed to shut down %s.", system);
        }
    }

    private SchedulerRuntime installSchedulerBridge() {
        SchedulerRuntime runtime = new SchedulerRuntime(
            () -> this,
            this::runAsyncTask,
            Gloss::verbose,
            Gloss::warn,
            throwable -> logExceptionStack(true, throwable, "Gloss scheduler error.")
        );

        SchedulerBridge.setSyncScheduler(runtime::s);
        SchedulerBridge.setDelayedSyncScheduler(runtime::s);
        SchedulerBridge.setAsyncScheduler(runnable -> runtime.a(runnable, 0));
        SchedulerBridge.setDelayedAsyncScheduler(runtime::a);
        SchedulerBridge.setSyncRepeatingScheduler(runtime::sr);
        SchedulerBridge.setAsyncRepeatingScheduler(runtime::ar);
        SchedulerBridge.setCancelScheduler(runtime::csr);
        SchedulerBridge.setErrorHandler(throwable -> logExceptionStack(true, throwable, "Gloss scheduler error."));
        SchedulerBridge.setInfoLogger(Gloss::verbose);
        return runtime;
    }

    private void runAsyncTask(Runnable runnable) {
        if (!FoliaScheduler.runAsync(this, runnable)) {
            logThrottled(Level.WARNING, "async-task-rejected",
                "An asynchronous Gloss task was rejected by the scheduler and did not run.");
        }
    }

    private static final class FailureLogThrottle {
        private final AtomicLong nextLogAtNanos = new AtomicLong(Long.MIN_VALUE);
        private final AtomicLong suppressed = new AtomicLong();

        private long claim(long nowNanos) {
            while (true) {
                long next = nextLogAtNanos.get();
                if (next != Long.MIN_VALUE && nowNanos - next < 0L) {
                    suppressed.incrementAndGet();
                    return -1L;
                }
                if (nextLogAtNanos.compareAndSet(next, nowNanos + FAILURE_LOG_THROTTLE_NANOS)) {
                    return suppressed.getAndSet(0L);
                }
            }
        }
    }
}
