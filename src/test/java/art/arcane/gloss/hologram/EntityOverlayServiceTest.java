package art.arcane.gloss.hologram;

import art.arcane.gloss.entity.EntityOverlayDoc;
import art.arcane.gloss.entity.EntityOverlayService;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityOverlayServiceTest {
    @TempDir
    File dataFolder;

    @Test
    void disabledSharedEngineIgnoresDamageUntilEnabled() throws Exception {
        try (CharacterizationHarness harness = new CharacterizationHarness(dataFolder)) {
            EntityOverlayService service = new EntityOverlayService(harness.gloss);
            Field settings = EntityOverlayService.class.getDeclaredField("settings");
            settings.setAccessible(true);
            settings.set(service, EntityOverlayDoc.DEFAULTS);
            Field started = EntityOverlayService.class.getDeclaredField("started");
            started.setAccessible(true);
            started.setBoolean(service, true);
            LivingEntity entity = entity(EntityType.ZOMBIE, new AtomicInteger(1));
            DamageSource source = proxy(DamageSource.class, (object, method, args) -> {
                throw new AssertionError(method.getName());
            });
            EntityDamageEvent damage = new EntityDamageEvent(entity, EntityDamageEvent.DamageCause.CUSTOM, source, 4.0);

            harness.configure(file -> file.features.holograms = false);
            service.onDamage(damage);
            assertTrue(map(service, "hits").isEmpty());

            harness.configure(file -> file.features.holograms = true);
            service.onDamage(damage);
            assertTrue(map(service, "hits").containsKey(entity.getUniqueId()));
        }
    }

    @Test
    void persistedReactCountSurvivesNewServiceAndTracksPluginAvailability() throws Exception {
        try (CharacterizationHarness harness = new CharacterizationHarness(dataFolder)) {
            EntityOverlayService first = new EntityOverlayService(harness.gloss);
            AtomicInteger count = new AtomicInteger(7);
            LivingEntity entity = entity(EntityType.ZOMBIE, count);
            Plugin react = react();
            first.onPluginEnable(new PluginEnableEvent(react));
            first.refreshStack(entity, 10);
            assertEquals(7, stackCount(first, entity));

            EntityOverlayService replacement = new EntityOverlayService(harness.gloss);
            replacement.onPluginEnable(new PluginEnableEvent(react));
            assertEquals(7, stackCount(replacement, entity));
            count.set(1);
            assertEquals(1, stackCount(first, entity));
            count.set(4);
            replacement.onPluginDisable(new PluginDisableEvent(react));
            assertEquals(1, stackCount(replacement, entity));
            replacement.onPluginEnable(new PluginEnableEvent(react));
            assertEquals(4, stackCount(replacement, entity));
        }
    }

    @Test
    void pluginRemovalClearsStackCacheWithoutDeathOrChunkUnload() throws Exception {
        try (CharacterizationHarness harness = new CharacterizationHarness(dataFolder)) {
            EntityOverlayService service = new EntityOverlayService(harness.gloss);
            LivingEntity entity = entity(EntityType.ZOMBIE, new AtomicInteger(3));
            service.refreshStack(entity, 3);
            assertEquals(1, map(service, "stackCounts").size());

            service.onRemove(new EntityRemoveEvent(entity, EntityRemoveEvent.Cause.PLUGIN));

            assertTrue(map(service, "stackCounts").isEmpty());
        }
    }

    @Test
    void leavingTheWorldDropsTheViewerAnchorAndItsInsight() throws Exception {
        try (CharacterizationHarness harness = new CharacterizationHarness(dataFolder)) {
            EntityOverlayService service = new EntityOverlayService(harness.gloss);
            Field settings = EntityOverlayService.class.getDeclaredField("settings");
            settings.setAccessible(true);
            settings.set(service, EntityOverlayDoc.DEFAULTS);
            Field started = EntityOverlayService.class.getDeclaredField("started");
            started.setAccessible(true);
            started.setBoolean(service, true);
            CharacterizationHarness.PlayerHandle viewer = harness.join("Viewer", harness.world("world"), 0, 64, 0);
            LivingEntity target = entity(EntityType.ZOMBIE, new AtomicInteger(1));
            assertTrue(service.updateInsight(harness.gloss, viewer.proxy, target, java.util.List.of("detail"), 5000L));
            map(service, "anchors").put(viewer.uuid, new Object());

            Method remove = EntityOverlayService.class.getDeclaredMethod("removeViewer", UUID.class);
            remove.setAccessible(true);
            remove.invoke(service, viewer.uuid);

            assertTrue(map(service, "anchors").isEmpty());
            assertTrue(map(service, "insights").isEmpty());
            assertTrue(map(service, "insightTargets").isEmpty());
        }
    }

    private static LivingEntity entity(EntityType type, AtomicInteger count) {
        UUID id = UUID.randomUUID();
        PersistentDataContainer data = proxy(PersistentDataContainer.class, (object, method, args) -> {
            if (method.getName().equals("get")) {
                assertEquals("react:react-stack-count", args[0].toString());
                return count.get();
            }
            throw new AssertionError(method.getName());
        });
        return proxy(LivingEntity.class, (object, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> id;
            case "getType" -> type;
            case "getHealth" -> 20.0;
            case "getPersistentDataContainer" -> data;
            default -> throw new AssertionError(method.getName());
        });
    }

    private static Plugin react() {
        return proxy(Plugin.class, (object, method, args) -> switch (method.getName()) {
            case "getName" -> "React";
            case "hashCode" -> System.identityHashCode(object);
            case "equals" -> object == args[0];
            default -> throw new AssertionError(method.getName());
        });
    }

    private static int stackCount(EntityOverlayService service, LivingEntity entity) throws ReflectiveOperationException {
        Method method = EntityOverlayService.class.getDeclaredMethod("stackCount", LivingEntity.class);
        method.setAccessible(true);
        return (int) method.invoke(service, entity);
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, Object> map(EntityOverlayService service, String name) throws ReflectiveOperationException {
        Field field = EntityOverlayService.class.getDeclaredField(name);
        field.setAccessible(true);
        return (Map<UUID, Object>) field.get(service);
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}
