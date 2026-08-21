package art.arcane.gloss.drop;

import art.arcane.gloss.GlossConfig;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealDropModelTest {
    private static final float EPSILON = 0.0001F;

    private static GlossConfig.RealDrops defaults() {
        return RealDropSettingsDoc.DEFAULTS.toConfig(true);
    }

    @Test
    void visualCountIsBoundedAndSuggestsLargerStacksWithoutOneDisplayPerItem() {
        assertEquals(1, RealDropModel.visualCount(1, 64, 5));
        assertEquals(2, RealDropModel.visualCount(16, 64, 5));
        assertEquals(3, RealDropModel.visualCount(32, 64, 5));
        assertEquals(4, RealDropModel.visualCount(48, 64, 5));
        assertEquals(5, RealDropModel.visualCount(64, 64, 5));
        assertEquals(3, RealDropModel.visualCount(64, 64, 3));
        assertEquals(1, RealDropModel.visualCount(64, 1, 5));
    }

    @Test
    void materialShapeSelectsTheConfiguredScaleFamily() {
        GlossConfig.RealDrops.Scale scale = defaults().scale();
        assertEquals(RealDropModel.ModelKind.BLOCK, RealDropModel.modelKind("STONE", true));
        assertEquals(RealDropModel.ModelKind.THIN, RealDropModel.modelKind("OAK_SLAB", true));
        assertEquals(RealDropModel.ModelKind.THIN, RealDropModel.modelKind("WHITE_CARPET", true));
        assertEquals(RealDropModel.ModelKind.THIN,
            RealDropModel.modelKind("LIGHT_WEIGHTED_PRESSURE_PLATE", true));
        assertEquals(RealDropModel.ModelKind.THIN, RealDropModel.modelKind("SNOW", true));
        assertEquals(RealDropModel.ModelKind.BLOCK, RealDropModel.modelKind("POWDER_SNOW", true));
        assertEquals(RealDropModel.ModelKind.BLOCK, RealDropModel.modelKind("OAK_DOOR", true));
        assertEquals(RealDropModel.ModelKind.BLOCK, RealDropModel.modelKind("RAIL", true));
        assertEquals(RealDropModel.ModelKind.BLOCK, RealDropModel.modelKind("OAK_SIGN", true));
        assertEquals(RealDropModel.ModelKind.BLOCK, RealDropModel.modelKind("TORCH", true));
        assertEquals(RealDropModel.ModelKind.BLOCK, RealDropModel.modelKind("DANDELION", true));
        assertEquals(RealDropModel.ModelKind.BLOCK, RealDropModel.modelKind("IRON_BARS", true));
        assertEquals(RealDropModel.ModelKind.BLOCK, RealDropModel.modelKind("HOPPER", true));
        assertEquals(RealDropModel.ModelKind.BLOCK, RealDropModel.modelKind("OAK_STAIRS", true));
        assertEquals(RealDropModel.ModelKind.FLAT, RealDropModel.modelKind("DIAMOND_SWORD", false));
        assertEquals(RealDropModel.ModelKind.FLAT, RealDropModel.modelKind("TRIDENT", false));
        assertEquals(RealDropModel.ModelKind.FLAT, RealDropModel.modelKind("SHIELD", false));
        assertEquals(scale.defaultScale(), RealDropModel.scale(RealDropModel.ModelKind.BLOCK, scale));
        assertEquals(scale.thinBlocks(), RealDropModel.scale(RealDropModel.ModelKind.THIN, scale));
        assertEquals(scale.flatItems(), RealDropModel.scale(RealDropModel.ModelKind.FLAT, scale));
    }

    @Test
    void tumbleIsDeterministicAndChangesOnBounce() {
        UUID itemId = UUID.fromString("9a97fbb6-5f67-47dd-94fa-f2e1f4018a86");
        GlossConfig.RealDrops.Motion motion = defaults().motion();
        RealDropModel.Angles first = RealDropModel.spin(itemId, 0, motion);
        assertEquals(first, RealDropModel.spin(itemId, 0, motion));
        assertNotEquals(first, RealDropModel.spin(itemId, 1, motion));
        assertTrue(Float.isFinite(first.x()));
        assertTrue(Float.isFinite(first.y()));
        assertTrue(Float.isFinite(first.z()));
    }

    @Test
    void tumbleSpeedMultiplierScalesEveryAxis() {
        UUID itemId = UUID.fromString("32f44d5c-a2dc-4f92-9000-8f8449c856fa");
        GlossConfig.RealDrops.Motion base = new GlossConfig.RealDrops.Motion(
            true, 1.0F, 160.0F, 120.0F, 100.0F, 0.2F, true, 0.35F, 0.35F, 1.0F);
        GlossConfig.RealDrops.Motion faster = new GlossConfig.RealDrops.Motion(
            true, 1.5F, 160.0F, 120.0F, 100.0F, 0.2F, true, 0.35F, 0.35F, 1.0F);

        RealDropModel.Angles baseSpin = RealDropModel.spin(itemId, 0, base);
        RealDropModel.Angles fasterSpin = RealDropModel.spin(itemId, 0, faster);

        assertEquals(baseSpin.x() * 1.5F, fasterSpin.x(), 0.0001F);
        assertEquals(baseSpin.y() * 1.5F, fasterSpin.y(), 0.0001F);
        assertEquals(baseSpin.z() * 1.5F, fasterSpin.z(), 0.0001F);
    }

    @Test
    void shippedMotionIsFasterAndDropLabelsSeeThroughCaveBlocks() {
        GlossConfig.RealDrops drops = defaults();

        assertEquals(1.35F, drops.motion().speedMultiplier());
        assertTrue(drops.labels().seeThrough());
    }

    @Test
    void flatItemsAndThinBlocksSettleFlushWhileBlocksUseNaturalTilt() {
        UUID itemId = UUID.fromString("530f06c0-439c-4d79-b9ba-6efdaae8aaec");
        GlossConfig.RealDrops.Landing landing = defaults().landing();
        assertEquals(90.0F, RealDropModel.landing(itemId, RealDropModel.ModelKind.FLAT, landing).x());
        RealDropModel.Angles thin = RealDropModel.landing(itemId, RealDropModel.ModelKind.THIN, landing);
        assertEquals(0.0F, thin.x());
        assertEquals(0.0F, thin.z());
        RealDropModel.Angles block = RealDropModel.landing(itemId, RealDropModel.ModelKind.BLOCK, landing);
        assertTrue(Math.abs(block.x()) <= landing.tiltDegrees());
        assertTrue(Math.abs(block.z()) <= landing.tiltDegrees());
    }

    @Test
    void ordinaryCubeModelsRestTheirBottomOnTheCarrierSurface() {
        GlossConfig.RealDrops.Scale scale = defaults().scale();
        Quaternionf rotation = new Quaternionf();

        assertEquals(scale.defaultScale() * 0.5F,
            RealDropModel.yOffset(Material.STONE, RealDropModel.ModelKind.BLOCK,
                scale.defaultScale(), rotation, true));
        assertEquals(0.0F,
            RealDropModel.yOffset(Material.DIAMOND_SWORD, RealDropModel.ModelKind.FLAT,
                scale.flatItems(), rotation, true));
    }

    @Test
    void blockGeometryUsesThePlacedBlockBoundsInsteadOfTheItemSprite() {
        RealDropModel.BlockGeometry cube = RealDropModel.blockGeometry(Material.COBBLESTONE);
        RealDropModel.BlockGeometry slab = RealDropModel.blockGeometry(Material.OAK_SLAB);
        RealDropModel.BlockGeometry carpet = RealDropModel.blockGeometry(Material.RED_CARPET);

        assertEquals(0.5F, cube.halfY());
        assertEquals(0.25F, slab.centerY());
        assertEquals(0.25F, slab.halfY());
        assertEquals(0.03125F, carpet.centerY());
        assertEquals(0.03125F, carpet.halfY());
        assertTrue(RealDropService.usesBlockDisplay(RealDropModel.modelKind("OAK_SLAB", true)));
        assertTrue(RealDropService.usesBlockDisplay(RealDropModel.modelKind("TORCH", true)));
        assertFalse(RealDropService.usesBlockDisplay(RealDropModel.modelKind("DIAMOND_PICKAXE", false)));
    }

    @Test
    void flatItemsChooseTheAlreadyNearestBroadFaceAndKeepTheirHeading() {
        Quaternionf front = new Quaternionf().rotateY(0.7F).rotateX((float) Math.PI * 0.48F);
        Quaternionf back = new Quaternionf().rotateY(-0.4F).rotateX((float) Math.PI * -0.48F);
        Quaternionf alignedFront = RealDropModel.broadFaceAlignedRotation(front);
        Quaternionf alignedBack = RealDropModel.broadFaceAlignedRotation(back);

        assertTrue(Math.abs(front.dot(alignedFront)) > 0.99F);
        assertTrue(Math.abs(back.dot(alignedBack)) > 0.99F);
        assertTrue(alignedFront.transform(new Vector3f(0.0F, 0.0F, 1.0F)).y() < -0.99F);
        assertTrue(alignedBack.transform(new Vector3f(0.0F, 0.0F, 1.0F)).y() > 0.99F);
    }

    @Test
    void allSixAlignedBlockFacesStayFlushAndAboveTheSurface() {
        GlossConfig.RealDrops drops = defaults();

        for (int face = 0; face < 6; face++) {
            Quaternionf rotation = RealDropModel.faceAlignedRotation(RealDropModel.blockFaceRotation(face));
            Vector3f faceNormal = switch (face) {
                case 0 -> new Vector3f(0.0F, -1.0F, 0.0F);
                case 1 -> new Vector3f(0.0F, 1.0F, 0.0F);
                case 2 -> new Vector3f(-1.0F, 0.0F, 0.0F);
                case 3 -> new Vector3f(1.0F, 0.0F, 0.0F);
                case 4 -> new Vector3f(0.0F, 0.0F, -1.0F);
                case 5 -> new Vector3f(0.0F, 0.0F, 1.0F);
                default -> throw new IllegalStateException("Unexpected face " + face);
            };
            rotation.transform(faceNormal);
            assertEquals(0.0F, faceNormal.x(), EPSILON);
            assertEquals(-1.0F, faceNormal.y(), EPSILON);
            assertEquals(0.0F, faceNormal.z(), EPSILON);
            float support = RealDropModel.verticalHalfExtent(drops.scale().defaultScale(), rotation);
            assertEquals(support, RealDropModel.yOffset(Material.STONE, RealDropModel.ModelKind.BLOCK,
                drops.scale().defaultScale(), rotation, true), EPSILON);
        }
    }

    @Test
    void eachBlockFaceRotationPlacesItsSelectedFaceAgainstTheSurface() {
        List<Vector3f> normals = List.of(
            new Vector3f(0.0F, -1.0F, 0.0F),
            new Vector3f(0.0F, 1.0F, 0.0F),
            new Vector3f(-1.0F, 0.0F, 0.0F),
            new Vector3f(1.0F, 0.0F, 0.0F),
            new Vector3f(0.0F, 0.0F, -1.0F),
            new Vector3f(0.0F, 0.0F, 1.0F));

        for (int face = 0; face < normals.size(); face++) {
            Vector3f transformed = RealDropModel.blockFaceRotation(face)
                .transform(new Vector3f(normals.get(face)));
            assertEquals(0.0F, transformed.x(), EPSILON);
            assertEquals(-1.0F, transformed.y(), EPSILON);
            assertEquals(0.0F, transformed.z(), EPSILON);
        }
    }

    @Test
    void groundTravelRollsAcrossFacesAndMomentumSelectsTheSettledFace() {
        float scale = defaults().scale().defaultScale();
        Quaternionf forward = RealDropModel.rollRotation(new Quaternionf(), scale, 0.0D, scale);
        Quaternionf backward = RealDropModel.rollRotation(new Quaternionf(), -scale, 0.0D, scale);
        Quaternionf forwardTwice = RealDropModel.rollRotation(forward, scale, 0.0D, scale);
        Quaternionf right = RealDropModel.rollRotation(new Quaternionf(), 0.0D, scale, scale);
        Quaternionf left = RealDropModel.rollRotation(new Quaternionf(), 0.0D, -scale, scale);

        assertEquals(0, RealDropModel.nearestDownFace(new Quaternionf()));
        assertEquals(3, RealDropModel.nearestDownFace(forward));
        assertEquals(2, RealDropModel.nearestDownFace(backward));
        assertEquals(1, RealDropModel.nearestDownFace(forwardTwice));
        assertEquals(5, RealDropModel.nearestDownFace(right));
        assertEquals(4, RealDropModel.nearestDownFace(left));
    }

    @Test
    void gravityContinuouslyFinishesTheMomentumDrivenRollWithoutALateCorrection() {
        float scale = defaults().scale().defaultScale();
        RealDropModel.BlockRoll roll = RealDropModel.groundedBlockRotation(
            new Quaternionf(), scale * 0.75D, 0.0D, 0.05D, scale,
            1.0F, 0.55F, 0.15F, 0.008726646F);
        int momentumFace = RealDropModel.nearestDownFace(roll.rotation());
        float previousExtent = RealDropModel.verticalHalfExtent(scale, roll.rotation());

        for (int sample = 0; sample < 24 && !roll.aligned(); sample++) {
            roll = RealDropModel.groundedBlockRotation(
                roll.rotation(), 0.0D, 0.0D, 0.0D, scale,
                1.0F, 0.55F, 0.15F, 0.008726646F);
            float extent = RealDropModel.verticalHalfExtent(scale, roll.rotation());
            assertTrue(extent <= previousExtent + EPSILON);
            previousExtent = extent;
        }

        assertTrue(roll.aligned());
        assertEquals(momentumFace, RealDropModel.nearestDownFace(roll.rotation()));
        assertEquals(scale * 0.5F, RealDropModel.verticalHalfExtent(scale, roll.rotation()), EPSILON);
    }

    @Test
    void flatLandingAndEveryStackYawKeepTheModelNormalVertical() {
        GlossConfig.RealDrops.Landing landing = defaults().landing();
        List<UUID> itemIds = List.of(
            UUID.fromString("530f06c0-439c-4d79-b9ba-6efdaae8aaec"),
            UUID.fromString("09065aeb-8c10-47f2-9871-be4c0bf60db4"),
            UUID.fromString("ac92e158-2772-4c3b-8913-0271b1c7f762"));

        for (UUID itemId : itemIds) {
            Quaternionf landingRotation = RealDropModel.landingRotation(
                itemId, RealDropModel.ModelKind.FLAT, landing);
            for (int index = 0; index < 5; index++) {
                Quaternionf indexed = RealDropModel.indexedRotation(landingRotation, index);
                Vector3f normal = indexed.transform(new Vector3f(0.0F, 0.0F, 1.0F));
                assertEquals(0.0F, normal.x(), EPSILON);
                assertEquals(1.0F, Math.abs(normal.y()), EPSILON);
                assertEquals(0.0F, normal.z(), EPSILON);
            }
        }
    }

    @Test
    void additionalModelsUseBoundedSpreadOffsets() {
        assertEquals(new RealDropModel.Offset(0.0F, 0.0F, 0.0F), RealDropModel.offset(0, 0.2F));
        RealDropModel.Offset fifth = RealDropModel.offset(4, 0.2F);
        assertTrue(Math.abs(fifth.x()) <= 0.2F);
        assertTrue(Math.abs(fifth.y()) <= 0.2F);
        assertTrue(Math.abs(fifth.z()) <= 0.2F);
    }
}
