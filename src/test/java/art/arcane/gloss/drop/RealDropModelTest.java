package art.arcane.gloss.drop;

import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.config.GlossConfigFile;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealDropModelTest {
    private static GlossConfig.RealDrops defaults() {
        GlossConfigFile file = new GlossConfigFile();
        file.normalize();
        return GlossConfig.from(file).realDrops();
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
        assertEquals(RealDropModel.ModelKind.FLAT, RealDropModel.modelKind("DIAMOND_SWORD", false));
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
    void flatItemsLandFlatAndBlocksUseTheConfiguredNaturalTilt() {
        UUID itemId = UUID.fromString("530f06c0-439c-4d79-b9ba-6efdaae8aaec");
        GlossConfig.RealDrops.Landing landing = defaults().landing();
        assertEquals(90.0F, RealDropModel.landing(itemId, RealDropModel.ModelKind.FLAT, landing).x());
        RealDropModel.Angles block = RealDropModel.landing(itemId, RealDropModel.ModelKind.BLOCK, landing);
        assertTrue(Math.abs(block.x()) <= landing.tiltDegrees());
        assertTrue(Math.abs(block.z()) <= landing.tiltDegrees());
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
