package art.arcane.gloss.api;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TemporaryItemSpecTest {
    @Test
    void presentationOwnsLocationAndValidatesBounds() {
        World world = mock(World.class);
        ItemStack source = mock(ItemStack.class);
        ItemStack copy = mock(ItemStack.class);
        when(source.clone()).thenReturn(copy);
        when(copy.clone()).thenReturn(mock(ItemStack.class));
        when(copy.getType()).thenReturn(mock(Material.class));
        Location location = new Location(world, 1, 2, 3);
        TemporaryItemSpec spec = new TemporaryItemSpec(location, source, 1000, 16, 0.5F);
        location.setX(99);
        spec.location().setY(99);
        assertEquals(1, spec.location().getX());
        assertEquals(2, spec.location().getY());
        assertThrows(IllegalArgumentException.class, () -> new TemporaryItemSpec(location, source, 30_001, 16, 1));
        assertThrows(IllegalArgumentException.class, () -> new TemporaryItemSpec(location, source, 1000, Double.NaN, 1));
        assertThrows(IllegalArgumentException.class, () -> new TemporaryItemSpec(location, source, 1000, 16, 3));
    }
}
