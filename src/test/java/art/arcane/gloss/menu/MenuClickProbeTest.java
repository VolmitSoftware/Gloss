package art.arcane.gloss.menu;

import art.arcane.gloss.integration.protection.ContainerProtectionProbe;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The container-preview access probe fires a real {@link PlayerInteractEvent} so other plugins can
 * answer it. Gloss listens for that event at HIGHEST and runs the viewer's hologram and panel
 * action list from it, so the probe must never reach the click dispatch: it would run a viewer's
 * click actions on every preview refresh and read its own cancel back as a deny.
 */
class MenuClickProbeTest {
    private static final UUID VIEWER = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

    @Test
    void aProtectionProbeIsNotDispatchedAsAViewerClick() {
        Player viewer = player();
        Block chest = block();

        assertFalse(MenuSessionManager.isViewerClick(ContainerProtectionProbe.blockProbe(viewer, chest)),
            "the access probe must not run the viewer's click actions");
    }

    @Test
    void theViewersOwnRightClickIsStillDispatched() {
        PlayerInteractEvent click = new PlayerInteractEvent(player(), Action.RIGHT_CLICK_BLOCK, null,
            block(), BlockFace.UP, EquipmentSlot.HAND);

        assertTrue(MenuSessionManager.isViewerClick(click));
    }

    @Test
    void anOffHandClickAndACancelledClickAreStillSkipped() {
        PlayerInteractEvent offHand = new PlayerInteractEvent(player(), Action.RIGHT_CLICK_BLOCK, null,
            block(), BlockFace.UP, EquipmentSlot.OFF_HAND);
        PlayerInteractEvent cancelled = new PlayerInteractEvent(player(), Action.RIGHT_CLICK_BLOCK, null,
            block(), BlockFace.UP, EquipmentSlot.HAND);
        cancelled.setCancelled(true);
        PlayerInteractEvent physical = new PlayerInteractEvent(player(), Action.PHYSICAL, null,
            block(), BlockFace.UP, EquipmentSlot.HAND);

        assertFalse(MenuSessionManager.isViewerClick(offHand));
        assertFalse(MenuSessionManager.isViewerClick(cancelled));
        assertFalse(MenuSessionManager.isViewerClick(physical));
    }

    private static Player player() {
        PlayerInventory inventory = (PlayerInventory) proxy(PlayerInventory.class,
            (self, method, args) -> identity(self, method, args, "PlayerInventory[viewer]"));
        return (Player) proxy(Player.class, (self, method, args) -> switch (method.getName()) {
            case "getName" -> "viewer";
            case "getUniqueId" -> VIEWER;
            case "getInventory" -> inventory;
            case "isSneaking" -> false;
            default -> identity(self, method, args, "Player[viewer]");
        });
    }

    private static Block block() {
        return (Block) proxy(Block.class, (self, method, args) -> switch (method.getName()) {
            case "getType" -> Material.CHEST;
            case "getLocation" -> new Location(null, 8.0D, 64.0D, 8.0D);
            default -> identity(self, method, args, "Block[chest]");
        });
    }

    private static Object proxy(Class<?> type, InvocationHandler handler) {
        return Proxy.newProxyInstance(MenuClickProbeTest.class.getClassLoader(),
            new Class<?>[]{type}, handler);
    }

    private static Object identity(Object self, Method method, Object[] args, String name) {
        return switch (method.getName()) {
            case "hashCode" -> System.identityHashCode(self);
            case "equals" -> self == args[0];
            case "toString" -> name;
            default -> method.getReturnType().isPrimitive()
                ? (method.getReturnType() == boolean.class ? Boolean.FALSE : (Object) 0)
                : null;
        };
    }
}
