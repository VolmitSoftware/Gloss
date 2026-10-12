package art.arcane.gloss;

import art.arcane.gloss.menu.CharacterizationSupport;
import art.arcane.volmlib.integration.ReloadAware.PreUnloadReason;
import art.arcane.volmlib.util.inventorygui.BukkitInventoryShutdown;
import art.arcane.volmlib.util.localization.BukkitLanguageSwitcher;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import org.bukkit.Server;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlossReloadCleanupTest {
    private final Deque<Runnable> globalTasks = new ArrayDeque<>();
    private final Deque<Runnable> teardowns = new ArrayDeque<>();
    private CompletableFuture<Void> editorCleanup;
    private Object previousServer;
    private Gloss previousPlugin;
    private Gloss plugin;
    private int shutdowns;
    private int cancellations;
    private boolean globalContext;
    private boolean rejectTasks;

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        GlobalRegionScheduler global = (GlobalRegionScheduler) CharacterizationSupport.proxy(
            new Class<?>[]{GlobalRegionScheduler.class}, (proxy, method, args) -> switch (method.getName()) {
                case "execute" -> {
                    if (rejectTasks) {
                        throw new UnsupportedOperationException("scheduler stopped");
                    }
                    globalTasks.add((Runnable) args[1]);
                    yield null;
                }
                case "run" -> throw new UnsupportedOperationException("scheduler stopped");
                case "cancelTasks" -> {
                    cancellations++;
                    yield null;
                }
                default -> CharacterizationSupport.identity(proxy, method, args);
            });
        BukkitScheduler bukkit = (BukkitScheduler) CharacterizationSupport.proxy(
            new Class<?>[]{BukkitScheduler.class}, (proxy, method, args) -> switch (method.getName()) {
                case "cancelTasks" -> null;
                case "runTask" -> throw new UnsupportedOperationException("scheduler stopped");
                default -> CharacterizationSupport.identity(proxy, method, args);
            });
        Server server = (Server) CharacterizationSupport.proxy(new Class<?>[]{Server.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getGlobalRegionScheduler" -> global;
                case "getScheduler" -> bukkit;
                case "getAsyncScheduler" -> null;
                case "getLogger" -> CharacterizationSupport.mutedLogger();
                case "getName", "getVersion", "getBukkitVersion" -> "reload-test";
                case "isPrimaryThread", "isGlobalTickThread" -> globalContext;
                default -> CharacterizationSupport.identity(proxy, method, args);
            });
        previousServer = CharacterizationSupport.installServer(server);
        plugin = CharacterizationSupport.bareGloss(server);
        CharacterizationSupport.setField(plugin, "isEnabled", true);
        CharacterizationSupport.setField(plugin, "teardowns", teardowns);
        previousPlugin = CharacterizationSupport.installGloss(plugin);
        teardowns.push(() -> {
            assertTrue(globalContext, "Service teardown must run in the global lifecycle context");
            shutdowns++;
        });
        installEditorCleanup();
    }

    @AfterEach
    void tearDown() throws ReflectiveOperationException {
        CharacterizationSupport.restoreGloss(previousPlugin);
        CharacterizationSupport.restoreServer(previousServer);
    }

    @Test
    void reloadWaitsForEditorCleanupAndGlobalShutdown() {
        CompletableFuture<Void> completion = plugin.commitReload(PreUnloadReason.HOT_RELOAD).toCompletableFuture();
        CompletableFuture<Void> repeated = plugin.commitReload(PreUnloadReason.HOT_RELOAD).toCompletableFuture();

        assertFalse(completion.isDone());
        assertEquals(0, shutdowns);
        assertEquals(0, cancellations);
        assertTrue(globalTasks.isEmpty());

        editorCleanup.complete(null);

        assertFalse(completion.isDone());
        assertEquals(0, shutdowns);
        assertEquals(0, cancellations);
        assertEquals(1, globalTasks.size());
        runGlobalTask();

        completion.join();
        repeated.join();
        assertEquals(1, shutdowns);
        assertEquals(1, cancellations);
        plugin.commitReload(PreUnloadReason.HOT_UNLOAD).toCompletableFuture().join();
        assertTrue(globalTasks.isEmpty());
    }

    @Test
    void cleanupFailurePreservesServicesAndCanRetry() throws ReflectiveOperationException {
        CompletableFuture<Void> completion = plugin.commitReload(PreUnloadReason.HOT_RELOAD).toCompletableFuture();
        IllegalStateException failure = new IllegalStateException("inventory close failed");

        editorCleanup.completeExceptionally(failure);

        assertSame(failure, assertThrows(CompletionException.class, completion::join).getCause());
        assertEquals(0, shutdowns);
        assertEquals(0, cancellations);
        assertTrue(globalTasks.isEmpty());

        installEditorCleanup();
        CompletableFuture<Void> retry = plugin.commitReload(PreUnloadReason.HOT_RELOAD).toCompletableFuture();
        editorCleanup.complete(null);
        runGlobalTask();
        retry.join();
        assertEquals(1, shutdowns);
    }

    @Test
    void rejectedGlobalContinuationFailsWithoutTearingDownServices() {
        CompletableFuture<Void> completion = plugin.commitReload(PreUnloadReason.HOT_RELOAD).toCompletableFuture();
        rejectTasks = true;

        editorCleanup.complete(null);

        assertThrows(CompletionException.class, completion::join);
        assertEquals(0, shutdowns);
        assertEquals(0, cancellations);
    }

    @Test
    void directDisableFailsPendingReloadAndIgnoresLateCleanup() {
        CompletableFuture<Void> completion = plugin.commitReload(PreUnloadReason.HOT_RELOAD).toCompletableFuture();

        globalContext = true;
        plugin.onDisable();
        globalContext = false;
        assertThrows(CompletionException.class, completion::join);
        editorCleanup.complete(null);

        assertTrue(globalTasks.isEmpty());
        assertEquals(1, shutdowns);
        assertEquals(1, cancellations);
        assertThrows(CompletionException.class,
            () -> plugin.commitReload(PreUnloadReason.HOT_RELOAD).toCompletableFuture().join());
    }

    @Test
    void directDisableMakesAnAlreadyQueuedContinuationHarmless() {
        CompletableFuture<Void> completion = plugin.commitReload(PreUnloadReason.HOT_RELOAD).toCompletableFuture();
        editorCleanup.complete(null);

        globalContext = true;
        plugin.onDisable();
        globalContext = false;
        runGlobalTask();

        assertThrows(CompletionException.class, completion::join);
        assertEquals(1, shutdowns);
        assertEquals(1, cancellations);
    }

    @Test
    void cancellingACallersFutureDoesNotCancelCleanup() {
        CompletableFuture<Void> cancelled = plugin.commitReload(PreUnloadReason.HOT_RELOAD).toCompletableFuture();
        cancelled.cancel(false);
        CompletableFuture<Void> completion = plugin.commitReload(PreUnloadReason.HOT_RELOAD).toCompletableFuture();

        editorCleanup.complete(null);
        runGlobalTask();

        completion.join();
        assertEquals(1, shutdowns);
    }

    private void installEditorCleanup() throws ReflectiveOperationException {
        editorCleanup = new CompletableFuture<>();
        Class<?> editorType = Class.forName("art.arcane.volmlib.util.localization.BukkitLanguageEditor");
        Object editor = allocate(editorType);
        CharacterizationSupport.setField(editor, "inventoryShutdown", new BukkitInventoryShutdown(plugin, List.of()));
        CharacterizationSupport.setField(editor, "shutdownCompletion", editorCleanup);
        BukkitLanguageSwitcher switcher = (BukkitLanguageSwitcher) allocate(BukkitLanguageSwitcher.class);
        CharacterizationSupport.setField(switcher, "editor", editor);
        CharacterizationSupport.setField(switcher, "closed", true);
        CharacterizationSupport.setField(plugin, "languageSwitcher", switcher);
    }

    private void runGlobalTask() {
        globalContext = true;
        try {
            globalTasks.remove().run();
        } finally {
            globalContext = false;
        }
    }

    private static Object allocate(Class<?> type) throws ReflectiveOperationException {
        sun.reflect.ReflectionFactory factory = sun.reflect.ReflectionFactory.getReflectionFactory();
        Constructor<?> constructor = factory.newConstructorForSerialization(type, Object.class.getDeclaredConstructor());
        return constructor.newInstance();
    }
}
