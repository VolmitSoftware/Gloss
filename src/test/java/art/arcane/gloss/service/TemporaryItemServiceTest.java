package art.arcane.gloss.service;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.api.TemporaryItem;
import art.arcane.gloss.api.TemporaryItemSpec;
import art.arcane.gloss.menu.DisplayEntityGroup;
import art.arcane.gloss.menu.DisplayEntityManager;
import art.arcane.gloss.util.common.DisplayEntity;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class TemporaryItemServiceTest {
    @Test
    void rejectedPlayerDispatchClosesPresentationAndShutdownRejectsNewWork() {
        Gloss plugin = mock(Gloss.class);
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(mock(PluginManager.class));
        Plugin owner = mock(Plugin.class);
        when(owner.isEnabled()).thenReturn(true);
        Player player = mock(Player.class);
        ItemStack item = mock(ItemStack.class);
        when(item.clone()).thenReturn(item);
        when(item.getType()).thenReturn(mock(Material.class));
        TemporaryItemSpec spec = new TemporaryItemSpec(new Location(mock(World.class), 1, 2, 3), item, 1000, 16, 1);
        try (MockedStatic<FoliaScheduler> scheduler = mockStatic(FoliaScheduler.class)) {
            TemporaryItemService service = new TemporaryItemService(plugin);
            TemporaryItem result = service.create(owner, player, spec);
            assertTrue(result.isClosed());
            result.destroy();
            service.close();
            assertThrows(IllegalStateException.class, () -> service.create(owner, player, spec));
        }
    }
    @Test
    void leavingRangeRetiresTheCosmeticDisplay() {
        Gloss plugin = mock(Gloss.class);
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(mock(PluginManager.class));
        Plugin owner = mock(Plugin.class);
        when(owner.isEnabled()).thenReturn(true);
        World world = mock(World.class);
        Player player = mock(Player.class);
        when(player.isOnline()).thenReturn(true);
        when(player.getLocation()).thenReturn(new Location(world, 1, 2, 3));
        ItemStack item = mock(ItemStack.class);
        when(item.clone()).thenReturn(item);
        when(item.getType()).thenReturn(mock(Material.class));
        TemporaryItemSpec spec = new TemporaryItemSpec(new Location(world, 1, 2, 3), item, 30_000, 16, 1);
        DisplayEntityGroup group = mock(DisplayEntityGroup.class);
        DisplayEntity display = mock(DisplayEntity.class);
        UUID id = UUID.randomUUID();
        List<Runnable> scheduled = new ArrayList<>();
        try (MockedStatic<FoliaScheduler> scheduler = mockStatic(FoliaScheduler.class);
             MockedStatic<DisplayEntityManager> manager = mockStatic(DisplayEntityManager.class);
             MockedStatic<DisplayEntity.Builder> builder = mockStatic(DisplayEntity.Builder.class)) {
            scheduler.when(() -> FoliaScheduler.runEntity(eq(plugin), eq(player), any(Runnable.class), anyLong(), any(Runnable.class)))
                    .thenAnswer(call -> {
                        scheduled.add(call.getArgument(2));
                        return true;
                    });
            manager.when(() -> DisplayEntityManager.group(player, VisibilityGovernor.Surface.HOLOGRAM)).thenReturn(group);
            manager.when(() -> DisplayEntityManager.add(group, display)).thenReturn(id);
            builder.when(() -> DisplayEntity.Builder.itemDisplay(item, spec.location(), 1)).thenReturn(display);
            TemporaryItemService service = new TemporaryItemService(plugin);
            TemporaryItem result = service.create(owner, player, spec);
            scheduled.getFirst().run();
            assertFalse(result.isClosed());
            manager.verify(() -> DisplayEntityManager.spawn(id, player));
            when(player.getLocation()).thenReturn(new Location(world, 99, 2, 3));
            scheduled.get(1).run();
            assertTrue(result.isClosed());
            result.destroy();
            manager.verify(() -> DisplayEntityManager.retire(plugin, group));
            service.close();
        }
    }}
