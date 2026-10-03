package art.arcane.gloss.hologram;

import art.arcane.gloss.util.common.RawEntityDimensions;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

record EntityLineBounds(double width, double height, double depth, double centerY, double centerZ) {
    private static final EntityLineBounds CHICKEN = new EntityLineBounds(0.5D, 15D / 16D,
        12D / 16D, 1.501D - 16.5D / 16D, -2D / 16D);
    private static final Map<EntityType, EntityLineBounds> ESTIMATED = new ConcurrentHashMap<>();

    static EntityLineBounds forType(EntityType type) {
        if (type == EntityTypes.CHICKEN) {
            return CHICKEN;
        }
        return ESTIMATED.computeIfAbsent(type, EntityLineBounds::collisionEstimate);
    }

    double diagonal() {
        return Math.sqrt(width * width + height * height + depth * depth);
    }

    private static EntityLineBounds collisionEstimate(EntityType type) {
        RawEntityDimensions.Dimensions dimensions = RawEntityDimensions.dimensions(type);
        return new EntityLineBounds(dimensions.width(), dimensions.height(), dimensions.width(),
            dimensions.height() / 2D, 0D);
    }
}
