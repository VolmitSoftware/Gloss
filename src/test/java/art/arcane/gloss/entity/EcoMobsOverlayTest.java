package art.arcane.gloss.entity;

import com.willfp.eco.core.registry.Registry;
import com.willfp.eco.core.Eco;
import com.willfp.ecomobs.mob.EcoMob;
import com.willfp.ecomobs.mob.EcoMobs;
import com.willfp.ecomobs.mob.LivingMob;
import com.willfp.libreforge.loader.configs.RegistrableCategory;
import org.bukkit.entity.EntityType;
import org.bukkit.NamespacedKey;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

class EcoMobsOverlayTest {
    private static final String DOCUMENT = """
        {"schemaVersion":2,"revision":1,"includePlayers":false,"range":16.0,
         "lines":[{"id":"name","show":"entity.named","text":"{name}"}]}
        """;

    @TempDir
    File dataFolder;

    @BeforeAll
    static void initializeEcoNames() throws ReflectiveOperationException {
        Field field = Class.forName("com.willfp.eco.core.Eco$Instance").getDeclaredField("eco");
        field.setAccessible(true);
        Object previous = field.get(null);
        Eco eco = (Eco) Proxy.newProxyInstance(Eco.class.getClassLoader(), new Class<?>[]{Eco.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "createNamespacedKey" -> new NamespacedKey((String) args[0], (String) args[1]);
                default -> throw new UnsupportedOperationException(method.getName());
            });
        try {
            field.set(null, eco);
            Class.forName("com.willfp.ecomobs.mob.impl.ConfigDrivenEcoMobKt");
        } finally {
            field.set(null, previous);
        }
    }

    @Test
    void clientOnlyNamesRefreshAndPluginLifecycleRestoresVanillaNames() throws Exception {
        try (EntityOverlayHarness harness = new EntityOverlayHarness(dataFolder)) {
            EntityOverlayHarness.WorldState world = harness.world("world");
            harness.join("viewer", world, 0, 64, 0);
            EntityOverlayHarness.MobHandle mob = harness.mob(world, EntityType.ZOMBIE, 4, 64, 0);
            mob.ecoMobId = "sentinel";
            AtomicReference<String> name = new AtomicReference<>("§cSentinel 20/20");
            Registry<EcoMob> registry = registry();
            registry.register(mob("sentinel", name));
            try {
                EntityOverlayService service = harness.service(DOCUMENT);
                harness.drive(service);
                EntityOverlayTarget overlay = harness.overlays(service).get(mob.uuid);
                assertEquals("", overlay.sample.snapshot().name());
                assertNull(overlay.shared.display);

                Plugin ecoMobs = plugin("EcoMobs");
                service.onPluginEnable(new PluginEnableEvent(ecoMobs));
                harness.drive(service);
                assertEquals("§cSentinel 20/20", overlay.shared.snapshot.name());
                assertEquals("§cSentinel 20/20", overlay.shared.frame.text());

                name.set("§cSentinel 9/20");
                harness.drive(service);
                assertEquals("§cSentinel 9/20", overlay.shared.frame.text());

                mob.customName = "Vanilla Name";
                service.onPluginDisable(new PluginDisableEvent(ecoMobs));
                harness.drive(service);
                assertEquals("Vanilla Name", overlay.shared.frame.text());

                service.onPluginEnable(new PluginEnableEvent(ecoMobs));
                harness.drive(service);
                assertEquals("§cSentinel 9/20", overlay.shared.frame.text());
                mob.ecoMobId = null;
                harness.drive(service);
                assertEquals("Vanilla Name", overlay.shared.frame.text());
            } finally {
                registry.remove("sentinel");
            }
        }
    }

    @Test
    void insightTargetsResolveEcoMobsNamesOutsideTheScanRange() throws Exception {
        try (EntityOverlayHarness harness = new EntityOverlayHarness(dataFolder)) {
            EntityOverlayHarness.WorldState world = harness.world("world");
            EntityOverlayHarness.PlayerHandle viewer = harness.join("viewer", world, 0, 64, 0);
            EntityOverlayHarness.MobHandle mob = harness.mob(world, EntityType.ZOMBIE, 40, 64, 0);
            mob.ecoMobId = "sentinel";
            Registry<EcoMob> registry = registry();
            registry.register(mob("sentinel", new AtomicReference<>("§6Distant Sentinel")));
            try {
                EntityOverlayService service = harness.service(DOCUMENT);
                service.onPluginEnable(new PluginEnableEvent(plugin("EcoMobs")));
                assertTrue(service.updateInsight(plugin("Observer"), viewer.proxy, mob.proxy,
                    List.of("observed"), 10000));
                harness.drive(service);
                EntityOverlayTarget overlay = harness.overlays(service).get(mob.uuid);
                assertEquals("§6Distant Sentinel", overlay.personal.get(viewer.uuid).snapshot.name());
                assertEquals("§6Distant Sentinel", overlay.personal.get(viewer.uuid).frame.text());
            } finally {
                registry.remove("sentinel");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Registry<EcoMob> registry() throws ReflectiveOperationException {
        Field field = RegistrableCategory.class.getDeclaredField("registry");
        field.setAccessible(true);
        return (Registry<EcoMob>) field.get(EcoMobs.INSTANCE);
    }

    private static EcoMob mob(String id, AtomicReference<String> name) {
        LivingMob living = (LivingMob) Proxy.newProxyInstance(EcoMob.class.getClassLoader(),
            new Class<?>[]{LivingMob.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getDisplayName" -> name.get();
                default -> throw new UnsupportedOperationException(method.getName());
            });
        return (EcoMob) Proxy.newProxyInstance(EcoMob.class.getClassLoader(),
            new Class<?>[]{EcoMob.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getID", "getId" -> id;
                case "getLivingMob" -> living;
                case "onRegister", "onRemove" -> null;
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }

    private static Plugin plugin(String name) {
        return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(),
            new Class<?>[]{Plugin.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getName" -> name;
                case "isEnabled" -> true;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }
}
