package art.arcane.gloss.util.common;

import art.arcane.gloss.packets.PacketEventsStub;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import org.junit.Test;
import org.junit.Before;
import org.junit.After;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class RawEntityDimensionsTest {
  @Before
  public void installPackets() {
    PacketEventsStub.install();
  }

  @After
  public void uninstallPackets() {
    PacketEventsStub.uninstall();
  }

  @Test
  public void rowBoundsUseTheTypesNativeWidthAndHeight() throws ReflectiveOperationException {
    Field cacheField = RawEntityDimensions.class.getDeclaredField("DIMENSIONS");
    cacheField.setAccessible(true);
    @SuppressWarnings("unchecked")
    Map<EntityType, RawEntityDimensions.Dimensions> cache =
        (Map<EntityType, RawEntityDimensions.Dimensions>) cacheField.get(null);
    cache.put(EntityTypes.CHICKEN, new RawEntityDimensions.Dimensions(0.4F, 0.7F));
    cache.put(EntityTypes.ZOMBIE, new RawEntityDimensions.Dimensions(0.6F, 1.95F));
    try {
      assertEquals(0.35D, RawEntityDimensions.renderedHeight(EntityTypes.CHICKEN, 0.5D), 0.000001D);
      assertEquals(1.05D, RawEntityDimensions.renderedHeight(EntityTypes.CHICKEN, 1.5D), 0.000001D);
      assertEquals(2.925D, RawEntityDimensions.renderedHeight(EntityTypes.ZOMBIE, 1.5D), 0.000001D);
      assertEquals(0.9D, RawEntityDimensions.renderedWidth(EntityTypes.ZOMBIE, 1.5D), 0.000001D);
    } finally {
      cache.remove(EntityTypes.CHICKEN);
      cache.remove(EntityTypes.ZOMBIE);
    }
  }
}
