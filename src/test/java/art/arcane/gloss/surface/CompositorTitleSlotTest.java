package art.arcane.gloss.surface;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.config.GlossConfigFile;
import art.arcane.gloss.menu.CharacterizationSupport;
import art.arcane.volmlib.util.hud.HudPriority;
import art.arcane.volmlib.util.hud.HudTitleService;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The title slot is shared with every other VolmLib plugin, and a claim is only stood down by
 * releasing it: bidding again at the same priority loses to the live claim. These drive the real
 * compositor, so a letterbox that only publishes an empty title fails them the way it fails a
 * viewer whose ride ended early.
 */
class CompositorTitleSlotTest {
    private static final int MAX_RIDE_SECONDS = 30;

    private Object previousServer;
    private Gloss previousGloss;
    private Gloss gloss;
    private CompositorDelivery delivery;
    private FakeViewer viewer;

    @BeforeEach
    void installHeadlessServer() throws ReflectiveOperationException {
        Server server = CharacterizationSupport.server(Map.of());
        previousServer = CharacterizationSupport.installServer(server);
        gloss = named(CharacterizationSupport.bareGloss(server), "Gloss");
        GlossConfigFile file = new GlossConfigFile();
        file.normalize();
        CharacterizationSupport.setField(gloss, "config", GlossConfig.from(file));
        previousGloss = CharacterizationSupport.installGloss(gloss);
        delivery = new CompositorDelivery(gloss);
        viewer = new FakeViewer();
    }

    @AfterEach
    void restore() throws ReflectiveOperationException {
        delivery.shutdown();
        CharacterizationSupport.restoreGloss(previousGloss);
        CharacterizationSupport.restoreServer(previousServer);
    }

    @Test
    void theRideHoldsTheSlotAndEndingItHandsTheSlotBack() throws ReflectiveOperationException {
        HudTitleService otherPlugin = new HudTitleService(foreignPlugin());

        SurfaceLetterbox.apply(delivery, viewer.player, true, MAX_RIDE_SECONDS);
        assertEquals(1, viewer.titles.size());
        assertFalse(claimBy(otherPlugin), "the bars hold the slot while the ride runs");

        SurfaceLetterbox.apply(delivery, viewer.player, false, MAX_RIDE_SECONDS);

        assertTrue(claimBy(otherPlugin), "the ride ended, so another plugin may take the slot");
    }

    @Test
    void aSurfaceTitleAtTheSamePriorityDrawsOnceTheRideEnds() {
        SurfaceLetterbox.apply(delivery, viewer.player, true, MAX_RIDE_SECONDS);
        SurfaceLetterbox.apply(delivery, viewer.player, false, MAX_RIDE_SECONDS);

        delivery.title(viewer.player, "gloss:surface:welcome", HudPriority.AMBIENT, "welcome", "", 10, 40, 10);

        assertEquals(2, viewer.titles.size());
        assertEquals("welcome", viewer.titles.getLast());
    }

    private boolean claimBy(HudTitleService service) {
        return service.open(viewer.player, "wormholes:notice", HudPriority.AMBIENT, 2_000L)
            .show("elsewhere", "", 0, 20, 0);
    }

    /** A second plugin on the same server; the name sorts after Gloss so no tie hands it the slot. */
    private Plugin foreignPlugin() throws ReflectiveOperationException {
        return named(CharacterizationSupport.bareGloss(gloss.getServer()), "Wormholes");
    }

    /** getName() reads the plugin meta on this server API, and an allocated plugin has none. */
    private static Gloss named(Gloss plugin, String name) throws ReflectiveOperationException {
        PluginDescriptionFile meta = new PluginDescriptionFile(name, "0.0-characterization",
            "art.arcane.gloss.Gloss");
        CharacterizationSupport.setField(plugin, "description", meta);
        CharacterizationSupport.setField(plugin, "pluginMeta", meta);
        return plugin;
    }

    /** A viewer that remembers its metadata, which is where the shared HUD ledger keeps its bids. */
    private static final class FakeViewer {
        private final UUID id = UUID.randomUUID();
        private final Map<String, Map<Plugin, MetadataValue>> metadata = new LinkedHashMap<>();
        private final List<String> titles = new ArrayList<>();
        private final Player player;

        private FakeViewer() {
            player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[]{Player.class}, this::answer);
        }

        private Object answer(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "getUniqueId" -> id;
                case "getName" -> "rider";
                case "isOnline" -> true;
                case "sendTitle" -> {
                    titles.add((String) args[0]);
                    yield null;
                }
                case "setMetadata" -> {
                    metadata.computeIfAbsent((String) args[0], key -> new LinkedHashMap<>())
                        .put(((MetadataValue) args[1]).getOwningPlugin(), (MetadataValue) args[1]);
                    yield null;
                }
                case "getMetadata" -> List.copyOf(metadata.getOrDefault(args[0], Map.of()).values());
                case "hasMetadata" -> !metadata.getOrDefault(args[0], Map.of()).isEmpty();
                case "removeMetadata" -> {
                    metadata.getOrDefault(args[0], Map.of()).remove(args[1]);
                    yield null;
                }
                case "hashCode" -> id.hashCode();
                case "equals" -> proxy == args[0];
                case "toString" -> "Player[rider]";
                default -> throw new UnsupportedOperationException(method.getName());
            };
        }
    }
}
