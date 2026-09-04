package art.arcane.gloss.animation;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.condition.ShowCondition;
import art.arcane.gloss.config.GlossConfigFile;
import art.arcane.gloss.doc.GlossDocument;
import art.arcane.gloss.emoji.EmojiDoc;
import art.arcane.gloss.emoji.EmojiService;
import art.arcane.gloss.text.TextPipeline;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShowRuntimeTest {
    @TempDir
    File folder;
    private Gloss plugin;
    private TextPipeline text;
    private AnimationService animations;
    private EmojiService emoji;
    private Object previousServer;
    private Field serverField;

    @BeforeEach
    void prepare() throws ReflectiveOperationException {
        serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        previousServer = serverField.get(null);
        Thread ownerThread = Thread.currentThread();
        Server server = (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[]{Server.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "isPrimaryThread", "isTickThread", "isGlobalTickThread", "isOwnedByCurrentRegion" ->
                    Thread.currentThread() == ownerThread;
                default -> null;
            });
        serverField.set(null, server);
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field unsafeField = unsafeClass.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        plugin = (Gloss) unsafeClass.getMethod("allocateInstance", Class.class)
            .invoke(unsafeField.get(null), Gloss.class);
        set(plugin, JavaPlugin.class, "dataFolder", folder);
        set(plugin, JavaPlugin.class, "server", server);
        GlossConfigFile config = new GlossConfigFile();
        config.normalize();
        set(plugin, Gloss.class, "config", GlossConfig.from(config));
        text = new TextPipeline(plugin);
        set(plugin, Gloss.class, "text", text);
        animations = new AnimationService(plugin);
        emoji = new EmojiService(plugin);
    }

    @AfterEach
    void clearPublishedEmoji() throws IllegalAccessException {
        serverField.set(null, previousServer);
        TextPipeline.publishEmojiTriggers(List.of());
        TextPipeline.publishConditionalEmojiTokens(List.of());
    }

    @Test
    void animationVisibilityReevaluatesWorldAndTimeAndCannotUseSharedFrames() throws ReflectiveOperationException {
        loadAnimation("world.name == 'survival' && world.time < 12000");
        AtomicLong time = new AtomicLong(1000L);
        Player survival = player("survival", time);
        Player lobby = player("lobby", time);

        assertEquals("before visible after", text.render(survival, "before |animation.test| after"));
        assertEquals("before  after", text.render(lobby, "before |animation.test| after"));
        time.set(13000L);
        assertEquals("before  after", text.render(survival, "before |animation.test| after"));
        time.set(1000L);
        assertEquals("visible", text.render(survival, "|animation.test|"));
        assertNull(animations.staticFrames(animations.clip("test")));
        assertTrue(animations.framesViewerSpecific("|animation.test|"));
        assertTrue(animations.hasDynamicAnimationContent(List.of("|animation.test|")));
        assertTrue(animations.hasFastDynamicAnimationContent(List.of("|animation.test|")));
    }

    @Test
    void falseAnimationRemainsEmptyInTokenAndStaticFramePaths() throws ReflectiveOperationException {
        loadAnimation("false");
        assertEquals("", text.renderStatic("|animation.test|"));
        assertEquals(List.of(""), animations.staticFrames(animations.clip("test")));
        assertFalse(animations.framesViewerSpecific("|animation.test|"));
        loadAnimation("true");
        assertEquals("visible", text.renderStatic("|animation.test|"));
        assertEquals(List.of("visible"), animations.staticFrames(animations.clip("test")));
    }

    @Test
    void emojiVisibilityReevaluatesWithoutSpecificPermissionsAndPreservesHiddenInput() throws ReflectiveOperationException {
        loadEmoji("world.name == 'survival' && world.time < 12000");
        AtomicLong time = new AtomicLong(1000L);
        Player survival = player("survival", time);
        Player lobby = player("lobby", time);
        assertEquals("visible visible", emoji.applyFor(survival, ":test: <3"));
        assertEquals(":test: <3", emoji.applyFor(lobby, ":test: <3"));
        time.set(13000L);
        assertEquals(":test: <3", emoji.applyFor(survival, ":test: <3"));
        time.set(1000L);
        assertEquals("visible visible", emoji.applyFor(survival, ":test: <3"));
        for (String raw : List.of(":test:", "<3")) {
            assertTrue((TextPipeline.classify(raw) & TextPipeline.HAS_FUNCTION) != 0);
            assertTrue((TextPipeline.classify(raw) & TextPipeline.HAS_PLACEHOLDER) != 0);
            assertTrue(TextPipeline.viewerDependent(raw));
            assertTrue(TextPipeline.viewerSpecific(raw));
            assertTrue(TextPipeline.timeDependent(raw));
            assertTrue(TextPipeline.requiresFastRefresh(raw));
        }
        assertFalse(TextPipeline.viewerDependent("plain text"));
    }

    @Test
    void asyncChatUsesLastSnapshotWithoutReadingViewerWorld() throws Exception {
        loadEmoji("world.time < 12000");
        AtomicLong time = new AtomicLong(1000L);
        AtomicBoolean allowReads = new AtomicBoolean(false);
        Player viewer = guardedPlayer("survival", time, allowReads);
        text.setViewerEmojiFilter(emoji::applyFor);
        assertEquals(":test:", CompletableFuture.supplyAsync(() -> text.chat(viewer, ":test:")).get(5, TimeUnit.SECONDS));
        allowReads.set(true);
        refreshEmoji(viewer);
        allowReads.set(false);
        time.set(13000L);
        assertEquals("visible", CompletableFuture.supplyAsync(() -> text.chat(viewer, ":test:")).get(5, TimeUnit.SECONDS));
        allowReads.set(true);
        refreshEmoji(viewer);
        allowReads.set(false);
        assertEquals(":test:", CompletableFuture.supplyAsync(() -> text.chat(viewer, ":test:")).get(5, TimeUnit.SECONDS));
    }

    @Test
    void conditionalEmojiInvalidatesPreviouslyCachedAnimationFrames() throws ReflectiveOperationException {
        loadEmoji("true");
        text.setEmojiFilter(emoji::apply);
        AnimationFrameCache cache = new AnimationFrameCache(text::renderStatic);
        AnimationClip clip = new AnimationClip("emoji", 1.0D, AnimationMode.ASCEND, List.of(":test:"));
        assertEquals(List.of("visible"), cache.staticFrames(clip, TextPipeline.emojiGeneration()));
        loadEmoji("world.time < 12000");
        assertNull(cache.staticFrames(clip, TextPipeline.emojiGeneration()));
        loadEmoji("false");
        assertEquals(List.of(":test:"), cache.staticFrames(clip, TextPipeline.emojiGeneration()));
        assertEquals(":test: <3", emoji.apply(":test: <3"));
    }

    private void refreshEmoji(Player viewer) throws ReflectiveOperationException {
        Field cacheField = EmojiService.class.getDeclaredField("visibility");
        cacheField.setAccessible(true);
        Object cache = cacheField.get(emoji);
        Method begin = cache.getClass().getDeclaredMethod("begin", UUID.class);
        begin.setAccessible(true);
        Object sample = begin.invoke(cache, viewer.getUniqueId());
        Method capture = cache.getClass().getDeclaredMethod("capture", sample.getClass(), Predicate.class);
        capture.setAccessible(true);
        Predicate<ShowCondition> resolver = show -> show.matches(plugin, viewer);
        capture.invoke(cache, sample, resolver);
    }

    private void loadAnimation(String expression) throws ReflectiveOperationException {
        AnimationDoc doc = new AnimationDoc(1, 1L, "ascend", 100L, List.of("visible"), ShowCondition.of(expression));
        rebuild(animations, GlossDocument.of("test", "{}", doc, 1L));
    }

    private void loadEmoji(String expression) throws ReflectiveOperationException {
        EmojiDoc doc = new EmojiDoc(1, 1L, "<3", "visible", true, ShowCondition.of(expression));
        rebuild(emoji, GlossDocument.of("test", "{}", doc, 1L));
    }

    private static void rebuild(Object service, GlossDocument<?> document) throws ReflectiveOperationException {
        Method method = service.getClass().getDeclaredMethod("rebuild", Map.class);
        method.setAccessible(true);
        method.invoke(service, Map.of(document.id(), document));
    }

    private static void set(Object target, Class<?> owner, String name, Object value) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Player player(String worldName, AtomicLong time) {
        return guardedPlayer(worldName, time, new AtomicBoolean(true));
    }

    private static Player guardedPlayer(String worldName, AtomicLong time, AtomicBoolean allowReads) {
        World world = (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getName" -> worldName;
                case "getTime" -> time.get();
                default -> throw new UnsupportedOperationException(method.getName());
            });
        UUID playerId = UUID.randomUUID();
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> playerId;
                case "getWorld" -> {
                    assertTrue(allowReads.get(), "async render accessed the live world");
                    yield world;
                }
                case "getLocation" -> {
                    assertTrue(allowReads.get(), "async render accessed the live location");
                    yield new Location(world, 0, 64, 0);
                }
                case "hasPermission" -> true;
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }
}
