package art.arcane.gloss.rig;

import art.arcane.gloss.motion.MotionDoc;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BbmodelImporterTest {
    private static BbmodelImporter.Documents convert(String rigId, String textureItem) throws IOException {
        String raw = Files.readString(Path.of("src/test/resources/bbmodel/cube-and-lid.bbmodel"));
        return BbmodelImporter.convert(raw, rigId, textureItem);
    }

    @Test
    void groupsBecomeBonesWithOriginsRelativeToTheirParent() throws IOException {
        RigDoc rig = convert("chest", null).rig();

        assertEquals(List.of("root", "lid"), rig.bones().stream().map(RigDoc.BoneDef::id).toList());
        RigDoc.BoneDef root = rig.bones().getFirst();
        RigDoc.BoneDef lid = rig.bones().get(1);
        assertEquals("root", lid.parent());
        assertNull(root.parent(), "the first outliner group is the rig root");
        assertEquals(List.of(0.0D, 0.0D, 0.0D), root.rest().translation());
        assertEquals(List.of(0.0D, 0.5D, 0.0D), lid.rest().translation());
    }

    @Test
    void cubesBecomeBlockPartsScaledToTheirSize() throws IOException {
        RigDoc rig = convert("chest", null).rig();

        assertEquals(List.of("base", "lid"), rig.parts().stream().map(RigDoc.PartDef::id).toList());
        RigDoc.PartDef base = rig.parts().getFirst();
        assertEquals("root", base.bone());
        assertEquals(PartType.BLOCK, base.partType());
        assertEquals(BbmodelImporter.DEFAULT_BLOCK, base.block());
        assertEquals(List.of(0.0D, 0.25D, 0.0D), base.transform().translation());
        assertEquals(List.of(1.0D, 0.5D, 1.0D), base.transform().scale());

        RigDoc.PartDef lid = rig.parts().get(1);
        assertEquals("lid", lid.bone());
        assertEquals(List.of(0.0D, 0.125D, 0.0D), lid.transform().translation());
        assertEquals(List.of(1.0D, 0.25D, 1.0D), lid.transform().scale());
    }

    @Test
    void aTextureMappingTurnsCubesIntoItemParts() throws IOException {
        RigDoc rig = convert("chest", "itemsadder:chest_body").rig();

        assertEquals(PartType.ITEM, rig.parts().getFirst().partType());
        assertNotNull(rig.parts().getFirst().item());
    }

    @Test
    void animationsBecomeMotionDocumentsInTicks() throws IOException {
        BbmodelImporter.Documents documents = convert("chest", null);
        Map<String, MotionDoc> motions = documents.motions();

        assertEquals(List.of("chest-open"), List.copyOf(motions.keySet()));
        MotionDoc open = motions.get("chest-open");
        assertEquals(30.0D, open.durationTicks(), 1.0E-9D);
        assertEquals("once", open.loop());
        assertEquals(2, open.tracks().size(), "axes that never leave their neutral value are dropped");

        MotionDoc.MotionTrack rotationX = open.tracks().getFirst();
        assertEquals("lid", rotationX.bone());
        assertEquals("rotation.x", rotationX.channel());
        assertEquals(2, rotationX.keyframes().size());
        assertEquals(0.0D, rotationX.keyframes().getFirst().tick(), 1.0E-9D);
        assertEquals("linear", rotationX.keyframes().getFirst().easing());
        assertEquals(30.0D, rotationX.keyframes().get(1).tick(), 1.0E-9D);
        assertEquals(-90.0D, ((Number) rotationX.keyframes().get(1).value()).doubleValue(), 1.0E-9D);
        assertEquals("ease_in_out", rotationX.keyframes().get(1).easing());

        MotionDoc.MotionTrack translationZ = open.tracks().getLast();
        assertEquals("translation.z", translationZ.channel());
        assertEquals(0.5D, ((Number) translationZ.keyframes().getFirst().value()).doubleValue(), 1.0E-9D);
        assertEquals("hold", translationZ.keyframes().getFirst().easing());
    }

    @Test
    void theResultCountsWhatLandedAndNamesWhatWasDropped() throws IOException {
        BbmodelImporter.Result result = convert("chest", null).result();

        assertEquals(2, result.bones());
        assertEquals(2, result.parts());
        assertEquals(1, result.animations());
        assertTrue(result.unsupported().contains("textures"), result.unsupported().toString());
        assertTrue(result.unsupported().contains("uv"), result.unsupported().toString());
    }

    @Test
    void aModelWithoutGeometryIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> BbmodelImporter.convert("{}", "empty", null));
    }
}
