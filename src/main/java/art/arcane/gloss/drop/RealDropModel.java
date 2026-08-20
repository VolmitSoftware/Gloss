package art.arcane.gloss.drop;

import art.arcane.gloss.GlossConfig;
import org.bukkit.Material;

import java.util.UUID;

final class RealDropModel {
    private static final Offset[] OFFSETS = {
        new Offset(0.0F, 0.0F, 0.0F),
        new Offset(0.7F, 0.04F, 0.35F),
        new Offset(-0.55F, 0.08F, 0.55F),
        new Offset(0.4F, 0.12F, -0.65F),
        new Offset(-0.7F, 0.16F, -0.35F)
    };

    private RealDropModel() {
    }

    static int visualCount(int amount, int maxStackSize, int configuredMaximum) {
        int maximum = Math.max(1, Math.min(configuredMaximum, OFFSETS.length));
        if (maxStackSize <= 1 || amount <= 1) {
            return 1;
        }
        if (amount <= 16) {
            return Math.min(2, maximum);
        }
        if (amount <= 32) {
            return Math.min(3, maximum);
        }
        if (amount <= 48) {
            return Math.min(4, maximum);
        }
        return maximum;
    }

    static ModelKind modelKind(Material material) {
        return modelKind(material.name(), material.isBlock());
    }

    static ModelKind modelKind(String materialName, boolean block) {
        if (!block) {
            return ModelKind.FLAT;
        }
        if (materialName.endsWith("_SLAB") || materialName.endsWith("_CARPET")
            || materialName.endsWith("_PRESSURE_PLATE") || materialName.endsWith("_SNOW")) {
            return ModelKind.THIN;
        }
        return ModelKind.BLOCK;
    }

    static float scale(ModelKind kind, GlossConfig.RealDrops.Scale scale) {
        return switch (kind) {
            case BLOCK -> scale.defaultScale();
            case FLAT -> scale.flatItems();
            case THIN -> scale.thinBlocks();
        };
    }

    static Offset offset(int index, float spread) {
        Offset offset = OFFSETS[Math.max(0, Math.min(index, OFFSETS.length - 1))];
        return new Offset(offset.x() * spread, offset.y() * spread, offset.z() * spread);
    }

    static float yOffset(Material material) {
        String name = material.name();
        if (name.endsWith("SNOW")) {
            return 0.2F;
        }
        if (name.endsWith("TRIDENT")) {
            return 0.32F;
        }
        if (name.endsWith("_CARPET") || name.endsWith("_PRESSURE_PLATE") || name.endsWith("SHIELD")) {
            return 0.26F;
        }
        if (name.endsWith("_SLAB") || name.endsWith("_STAIRS") || name.endsWith("_WALL")
            || name.endsWith("_FENCE") || name.endsWith("_FENCE_GATE") || name.equals("DAYLIGHT_DETECTOR")) {
            return 0.16F;
        }
        if (name.contains("BED") || name.contains("SKULL") || name.contains("HEAD")
            || name.contains("SCULK") || name.contains("_TRAPDOOR") || name.equals("HEAVY_CORE")) {
            return 0.22F;
        }
        return 0.0F;
    }

    static Angles spin(UUID itemId, int bounceRevision, GlossConfig.RealDrops.Motion motion) {
        long seed = mix(itemId.getMostSignificantBits() ^ itemId.getLeastSignificantBits()
            ^ ((long) bounceRevision * 0x9E3779B97F4A7C15L));
        float x = varied(motion.degreesPerSecondX(), motion.variance(), seed);
        float y = varied(motion.degreesPerSecondY(), motion.variance(), mix(seed));
        float z = varied(motion.degreesPerSecondZ(), motion.variance(), mix(mix(seed)));
        return new Angles(x, y, z);
    }

    static Angles landing(UUID itemId, ModelKind kind, GlossConfig.RealDrops.Landing landing) {
        long seed = mix(itemId.getMostSignificantBits() + itemId.getLeastSignificantBits());
        float yaw = landing.randomYaw() ? unit(seed) * 180.0F : 0.0F;
        if ("FLAT".equals(landing.mode()) || kind == ModelKind.FLAT) {
            return new Angles(90.0F, yaw, 0.0F);
        }
        if ("UPRIGHT".equals(landing.mode())) {
            return new Angles(0.0F, yaw, 0.0F);
        }
        float tilt = landing.tiltDegrees();
        return new Angles(unit(mix(seed)) * tilt, yaw, unit(mix(mix(seed))) * tilt);
    }

    private static float varied(float configured, float variance, long seed) {
        float magnitude = configured * (1.0F + unit(seed) * variance);
        return (seed & 1L) == 0L ? magnitude : -magnitude;
    }

    private static float unit(long value) {
        long positive = value >>> 11;
        return (float) ((positive * 0x1.0p-53D * 2.0D) - 1.0D);
    }

    private static long mix(long value) {
        long mixed = value;
        mixed = (mixed ^ (mixed >>> 30)) * 0xBF58476D1CE4E5B9L;
        mixed = (mixed ^ (mixed >>> 27)) * 0x94D049BB133111EBL;
        return mixed ^ (mixed >>> 31);
    }

    enum ModelKind {
        BLOCK,
        FLAT,
        THIN
    }

    record Angles(float x, float y, float z) {
    }

    record Offset(float x, float y, float z) {
    }
}
