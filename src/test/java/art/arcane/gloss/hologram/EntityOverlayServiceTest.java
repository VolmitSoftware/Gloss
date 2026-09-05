package art.arcane.gloss.hologram;

import art.arcane.gloss.entity.EntityOverlayDoc;
import art.arcane.gloss.entity.EntityOverlayService;
import org.bukkit.Location;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
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
    void retiringOldViewerStateKeepsWorldChangeReplacement() throws Exception {
        try (CharacterizationHarness harness = new CharacterizationHarness(dataFolder)) {
            EntityOverlayService service = new EntityOverlayService(harness.gloss);
            UUID playerId = UUID.randomUUID();
            Object old = viewerState();
            Object replacement = viewerState();
            map(service, "viewers").put(playerId, replacement);
            Method remove = EntityOverlayService.class.getDeclaredMethod("removeViewer", UUID.class, old.getClass());
            remove.setAccessible(true);

            remove.invoke(service, playerId, old);

            assertSame(replacement, map(service, "viewers").get(playerId));
            Field active = old.getClass().getDeclaredField("active");
            active.setAccessible(true);
            assertFalse(active.getBoolean(old));
            assertTrue(active.getBoolean(replacement));
        }
    }

    @Test
    void hiddenAndExcludedEntitiesDoNotConsumeViewerLimit() throws Exception {
        try (CharacterizationHarness harness = new CharacterizationHarness(dataFolder)) {
            EntityOverlayService service = new EntityOverlayService(harness.gloss);
            Player viewer = proxy(Player.class, (object, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> UUID.fromString("00000000-0000-0000-0000-000000000123");
                case "canSee" -> false;
                default -> throw new AssertionError(method.getName());
            });
            Object state = viewerState();
            Set<UUID> selected = new HashSet<>();
            Method select = EntityOverlayService.class.getDeclaredMethod("select", Player.class, state.getClass(),
                Location.class, LivingEntity.class, Set.class, EntityOverlayDoc.class);
            select.setAccessible(true);

            select.invoke(service, viewer, state, null, entity(EntityType.ZOMBIE, new AtomicInteger(1)), selected,
                EntityOverlayDoc.DEFAULTS);
            select.invoke(service, viewer, state, null, entity(EntityType.ARMOR_STAND, new AtomicInteger(1)), selected,
                EntityOverlayDoc.DEFAULTS);

            assertTrue(selected.isEmpty());
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

    private static Object viewerState() throws ReflectiveOperationException {
        Class<?> state = Class.forName(EntityOverlayService.class.getName() + "$ViewerState");
        Constructor<?> constructor = state.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
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
