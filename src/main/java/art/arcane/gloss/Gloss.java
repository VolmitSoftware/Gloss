package art.arcane.gloss;

import art.arcane.gloss.animation.AnimationService;
import art.arcane.gloss.api.GlossAPIProvider;
import art.arcane.gloss.board.BoardService;
import art.arcane.gloss.bubble.ChatBubblesService;
import art.arcane.gloss.chat.ChatService;
import art.arcane.gloss.command.GlossCommandService;
import art.arcane.gloss.drop.DropNameService;
import art.arcane.gloss.emoji.EmojiService;
import art.arcane.gloss.group.GroupService;
import art.arcane.gloss.hologram.HologramService;
import art.arcane.gloss.indicator.DamageIndicatorsService;
import art.arcane.gloss.motd.MotdService;
import art.arcane.gloss.service.GlossAPIImpl;
import art.arcane.gloss.service.MetricsRuntime;
import art.arcane.gloss.tab.TablistService;
import art.arcane.gloss.text.TextPipeline;
import art.arcane.gloss.util.SplashScreen;
import art.arcane.volmlib.integration.ReloadAware;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.volmlib.util.scheduling.SchedulerBridge;
import art.arcane.volmlib.util.scheduling.SchedulerRuntime;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.logging.Level;

public final class Gloss extends JavaPlugin implements ReloadAware {
    private static final int BSTATS_PLUGIN_ID = 0;

    public static Gloss instance;

    private final Deque<Runnable> teardowns = new ArrayDeque<>();

    private SchedulerRuntime scheduler;
    private GlossConfig config;
    private TextPipeline text;
    private AnimationService animations;
    private EmojiService emoji;
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

    @Override
    public void onLoad() {
        instance = this;
    }

    @Override
    public void onEnable() {
        instance = this;
        boolean success = true;
        String errorMessage = null;
        try {
            scheduler = installSchedulerBridge();
            saveDefaultConfig();
            config = GlossConfig.load(getConfig());
            text = new TextPipeline(this);
            animations = new AnimationService(this);
            emoji = new EmojiService(this);
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
            enableService("holograms", holograms::enable, holograms::disable);
            enableService("boards", boards::enable, boards::disable);
            enableService("groups", groups::enable, groups::disable);
            enableService("tablist", tablist::enable, tablist::disable);
            enableService("motd", motd::enable, motd::disable);
            enableService("chat", chat::enable, chat::disable);
            enableService("bubbles", bubbles::enable, bubbles::disable);
            enableService("indicators", indicators::enable, indicators::disable);
            enableService("drops", drops::enable, drops::disable);
            commands.register();
            metrics = MetricsRuntime.start(this, BSTATS_PLUGIN_ID);
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
        if (scheduler != null) {
            scheduler.cancelPluginTasks();
        }
        GlossAPIProvider.set(null);
        instance = null;
    }

    @Override
    public void onPreUnload(PreUnloadReason reason) {
        shutdownServices();
    }

    public void reloadAll() {
        reloadConfig();
        config = GlossConfig.load(getConfig());
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
        info("Reloaded in-place from disk.");
    }

    public SchedulerRuntime scheduler() {
        return scheduler;
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
