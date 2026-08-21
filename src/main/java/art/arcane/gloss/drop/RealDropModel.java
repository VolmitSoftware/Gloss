package art.arcane.gloss.drop;

import art.arcane.gloss.GlossConfig;
import org.bukkit.Material;
import org.joml.Quaternionf;
import java.util.UUID;

final class RealDropModel {
    private static final float DEG_TO_RAD = (float) (Math.PI / 180.0D);
    private static final float HALF_PI = (float) (Math.PI * 0.5D);
    private static final float STACK_YAW_RADIANS = 0.41F;

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
            || materialName.endsWith("_PRESSURE_PLATE") || materialName.equals("SNOW")) {
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

    static float yOffset(Material material, ModelKind kind, float scale, Quaternionf rotation, boolean grounded) {
        float authored = authoredYOffset(material);
        if (!grounded || kind != ModelKind.BLOCK) {
            return authored;
        }
        return Math.max(authored, verticalHalfExtent(scale, rotation));
    }

    private static float authoredYOffset(Material material) {
        String name = material.name();
        if (name.endsWith("TRIDENT")) {
            return 0.32F;
        }
        if (name.endsWith("SHIELD")) {
            return 0.26F;
        }
        return 0.0F;
    }

    static Angles spin(UUID itemId, int bounceRevision, GlossConfig.RealDrops.Motion motion) {
        long seed = mix(itemId.getMostSignificantBits() ^ itemId.getLeastSignificantBits()
            ^ ((long) bounceRevision * 0x9E3779B97F4A7C15L));
        float multiplier = motion.speedMultiplier();
        float x = varied(motion.degreesPerSecondX(), motion.variance(), seed) * multiplier;
        float y = varied(motion.degreesPerSecondY(), motion.variance(), mix(seed)) * multiplier;
        float z = varied(motion.degreesPerSecondZ(), motion.variance(), mix(mix(seed))) * multiplier;
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
        if (kind == ModelKind.THIN) {
            return new Angles(0.0F, yaw, 0.0F);
        }
        float tilt = landing.tiltDegrees();
        return new Angles(unit(mix(seed)) * tilt, yaw, unit(mix(mix(seed))) * tilt);
    }

    static Quaternionf baseRotation(ModelKind kind) {
        return kind == ModelKind.FLAT
            ? new Quaternionf().rotateX(90.0F * DEG_TO_RAD)
            : new Quaternionf();
    }

    static Quaternionf landingRotation(UUID itemId, ModelKind kind, GlossConfig.RealDrops.Landing landing) {
        Angles angles = landing(itemId, kind, landing);
        if ("FLAT".equals(landing.mode()) || kind == ModelKind.FLAT) {
            return new Quaternionf()
                .rotateY(angles.y() * DEG_TO_RAD)
                .rotateX(angles.x() * DEG_TO_RAD);
        }
        if ("NATURAL".equals(landing.mode()) && kind != ModelKind.FLAT) {
            long seed = mix(itemId.getMostSignificantBits() ^ itemId.getLeastSignificantBits());
            return blockLandingRotation(itemId, landing, (int) Math.floorMod(seed, 6L));
        }
        return quaternion(angles);
    }

    static Quaternionf rollRotation(Quaternionf current, double deltaX, double deltaZ, float scale) {
        double distance = Math.hypot(deltaX, deltaZ);
        if (distance <= 1.0E-6D) {
            return new Quaternionf(current);
        }
        float axisX = (float) (deltaZ / distance);
        float axisZ = (float) (-deltaX / distance);
        float angle = (float) (distance / Math.max(0.05F, scale)) * HALF_PI;
        return new Quaternionf().rotateAxis(angle, axisX, 0.0F, axisZ).mul(current);
    }

    static BlockRoll groundedBlockRotation(Quaternionf current, double deltaX, double deltaZ,
                                           double horizontalSpeed, float scale, float rollMultiplier,
                                           float faceAttraction, float movingFaceAttraction,
                                           float alignmentRadians) {
        Quaternionf rolled = rollRotation(
            current, deltaX * rollMultiplier, deltaZ * rollMultiplier, scale);
        Quaternionf target = faceAlignedRotation(rolled);
        float difference = rotationDifference(rolled, target);
        if (difference <= alignmentRadians) {
            return new BlockRoll(target, true);
        }
        double speedReference = Math.max(0.02D, scale * 0.25D);
        float motionRatio = (float) Math.min(1.0D, horizontalSpeed / speedReference);
        float gravityBlend = faceAttraction + (movingFaceAttraction - faceAttraction) * motionRatio;
        return new BlockRoll(rolled.slerp(target, gravityBlend), false);
    }

    static Quaternionf blockFaceRotation(int face) {
        return switch (face) {
            case 0 -> new Quaternionf();
            case 1 -> new Quaternionf().rotateX((float) Math.PI);
            case 2 -> new Quaternionf().rotateZ((float) (Math.PI * 0.5D));
            case 3 -> new Quaternionf().rotateZ((float) (Math.PI * -0.5D));
            case 4 -> new Quaternionf().rotateX((float) (Math.PI * -0.5D));
            case 5 -> new Quaternionf().rotateX((float) (Math.PI * 0.5D));
            default -> throw new IllegalArgumentException("face must be between 0 and 5");
        };
    }

    static int nearestDownFace(Quaternionf rotation) {
        float x = rotation.x();
        float y = rotation.y();
        float z = rotation.z();
        float w = rotation.w();
        float rowX = 2.0F * (x * y + z * w);
        float rowY = 1.0F - 2.0F * (x * x + z * z);
        float rowZ = 2.0F * (y * z - x * w);
        int face = 0;
        float lowest = -rowY;
        if (rowY < lowest) {
            face = 1;
            lowest = rowY;
        }
        if (-rowX < lowest) {
            face = 2;
            lowest = -rowX;
        }
        if (rowX < lowest) {
            face = 3;
            lowest = rowX;
        }
        if (-rowZ < lowest) {
            face = 4;
            lowest = -rowZ;
        }
        if (rowZ < lowest) {
            face = 5;
        }
        return face;
    }

    static Quaternionf faceAlignedRotation(Quaternionf current) {
        int face = nearestDownFace(current);
        Quaternionf base = blockFaceRotation(face);
        boolean zTangent = face < 4;
        float currentHeading = tangentHeading(current, zTangent);
        float baseHeading = tangentHeading(base, zTangent);
        return new Quaternionf().rotateY(currentHeading - baseHeading).mul(base);
    }

    static Quaternionf broadFaceAlignedRotation(Quaternionf current) {
        float x = current.x();
        float y = current.y();
        float z = current.z();
        float w = current.w();
        float rowZ = 2.0F * (y * z - x * w);
        int face = rowZ <= 0.0F ? 5 : 4;
        Quaternionf base = blockFaceRotation(face);
        float currentHeading = tangentHeading(current, false);
        float baseHeading = tangentHeading(base, false);
        return new Quaternionf().rotateY(currentHeading - baseHeading).mul(base);
    }

    static BlockGeometry blockGeometry(Material material) {
        String name = material.name();
        if (name.endsWith("_SLAB")) {
            return new BlockGeometry(0.5F, 0.25F, 0.5F, 0.5F, 0.25F, 0.5F);
        }
        if (name.endsWith("_CARPET")) {
            return new BlockGeometry(0.5F, 0.03125F, 0.5F, 0.5F, 0.03125F, 0.5F);
        }
        if (name.endsWith("_PRESSURE_PLATE")) {
            return new BlockGeometry(0.5F, 0.03125F, 0.5F, 0.5F, 0.03125F, 0.5F);
        }
        if (name.equals("SNOW")) {
            return new BlockGeometry(0.5F, 0.0625F, 0.5F, 0.5F, 0.0625F, 0.5F);
        }
        if (name.equals("CAKE")) {
            return new BlockGeometry(0.5F, 0.25F, 0.5F, 0.4375F, 0.25F, 0.4375F);
        }
        if (name.equals("LANTERN") || name.equals("SOUL_LANTERN")) {
            return new BlockGeometry(0.5F, 0.34375F, 0.5F, 0.25F, 0.34375F, 0.25F);
        }
        if (name.endsWith("_CANDLE") || name.equals("CANDLE")) {
            return new BlockGeometry(0.5F, 0.1875F, 0.5F, 0.0625F, 0.1875F, 0.0625F);
        }
        if (name.endsWith("_BED")) {
            return new BlockGeometry(0.5F, 0.28125F, 0.5F, 0.5F, 0.28125F, 0.5F);
        }
        if (name.endsWith("_SAPLING")) {
            return new BlockGeometry(0.5F, 0.5F, 0.5F, 0.375F, 0.5F, 0.375F);
        }
        return new BlockGeometry(0.5F, 0.5F, 0.5F, 0.5F, 0.5F, 0.5F);
    }

    static float verticalHalfExtent(BlockGeometry geometry, float scaleX, float scaleY,
                                    float scaleZ, Quaternionf rotation) {
        float x = rotation.x();
        float y = rotation.y();
        float z = rotation.z();
        float w = rotation.w();
        float rowX = 2.0F * (x * y + z * w);
        float rowY = 1.0F - 2.0F * (x * x + z * z);
        float rowZ = 2.0F * (y * z - x * w);
        return Math.abs(rowX) * geometry.halfX() * scaleX
            + Math.abs(rowY) * geometry.halfY() * scaleY
            + Math.abs(rowZ) * geometry.halfZ() * scaleZ;
    }

    static float verticalHalfExtent(float scale, Quaternionf rotation) {
        float x = rotation.x();
        float y = rotation.y();
        float z = rotation.z();
        float w = rotation.w();
        float rowX = 2.0F * (x * y + z * w);
        float rowY = 1.0F - 2.0F * (x * x + z * z);
        float rowZ = 2.0F * (y * z - x * w);
        return scale * 0.5F * (Math.abs(rowX) + Math.abs(rowY) + Math.abs(rowZ));
    }

    static Quaternionf indexedRotation(Quaternionf rotation, int index) {
        if (index <= 0) {
            return new Quaternionf(rotation);
        }
        return new Quaternionf()
            .rotateY(index * STACK_YAW_RADIANS)
            .mul(rotation);
    }

    private static float tangentHeading(Quaternionf rotation, boolean zTangent) {
        float x = rotation.x();
        float y = rotation.y();
        float z = rotation.z();
        float w = rotation.w();
        float transformedX = zTangent
            ? 2.0F * (x * z + y * w)
            : 1.0F - 2.0F * (y * y + z * z);
        float transformedZ = zTangent
            ? 1.0F - 2.0F * (x * x + y * y)
            : 2.0F * (x * z - y * w);
        return (float) Math.atan2(transformedX, transformedZ);
    }

    static float rotationDifference(Quaternionf first, Quaternionf second) {
        float dot = Math.abs(first.x() * second.x() + first.y() * second.y()
            + first.z() * second.z() + first.w() * second.w());
        return 2.0F * (float) Math.acos(Math.min(1.0F, dot));
    }

    private static Quaternionf blockLandingRotation(UUID itemId, GlossConfig.RealDrops.Landing landing, int face) {
        Angles angles = landing(itemId, ModelKind.BLOCK, landing);
        float faceTwist = (angles.x() + angles.z()) * 0.5F;
        return new Quaternionf()
            .rotateY((angles.y() + faceTwist) * DEG_TO_RAD)
            .mul(blockFaceRotation(face));
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

    private static Quaternionf quaternion(Angles angles) {
        return new Quaternionf().rotateXYZ(
            angles.x() * DEG_TO_RAD,
            angles.y() * DEG_TO_RAD,
            angles.z() * DEG_TO_RAD);
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

    record BlockRoll(Quaternionf rotation, boolean aligned) {
    }

    record BlockGeometry(float centerX, float centerY, float centerZ,
                         float halfX, float halfY, float halfZ) {
    }
}
