package art.arcane.gloss.hologram;

import art.arcane.gloss.util.common.RawEntityDimensions;
import art.arcane.gloss.packets.PacketEventsStub;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;

import java.lang.reflect.Field;
import java.util.Map;

final class NativeEntityDimensionsFixture {
    private NativeEntityDimensionsFixture() {
    }

    @SuppressWarnings("unchecked")
    static void install() {
        boolean temporaryApi = PacketEvents.getAPI() == null;
        if (temporaryApi) {
            PacketEventsStub.install();
        }
        try {
            Field field = RawEntityDimensions.class.getDeclaredField("DIMENSIONS");
            field.setAccessible(true);
            Map<EntityType, RawEntityDimensions.Dimensions> dimensions =
                (Map<EntityType, RawEntityDimensions.Dimensions>) field.get(null);
            dimensions.put(EntityTypes.COW, new RawEntityDimensions.Dimensions(0.9F, 1.4F));
            dimensions.put(EntityTypes.PIG, new RawEntityDimensions.Dimensions(0.9F, 0.9F));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        } finally {
            if (temporaryApi) {
                PacketEventsStub.uninstall();
            }
        }
    }
}
