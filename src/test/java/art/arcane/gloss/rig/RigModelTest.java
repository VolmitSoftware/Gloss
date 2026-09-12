package art.arcane.gloss.rig;

import com.github.retrooper.packetevents.util.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RigModelTest {
    private static final float EPSILON = 1.0E-4F;

    private static Bone bone(String id, String parent, float x, float y, float z) {
        return new Bone(id, parent, new Transform(new Vector3f(x, y, z), Quaternions.identity(), new Vector3f(1.0F, 1.0F, 1.0F)));
    }

    @Test
    void cyclesAreRefused() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> RigModel.of(List.of(
            bone("a", "b", 0.0F, 0.0F, 0.0F),
            bone("b", "a", 0.0F, 0.0F, 0.0F))));
        assertTrue(failure.getMessage().contains("cycle"), failure.getMessage());
    }

    @Test
    void unknownParentsAndDuplicateIdsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> RigModel.of(List.of(bone("a", "missing", 0.0F, 0.0F, 0.0F))));
        assertThrows(IllegalArgumentException.class, () -> RigModel.of(List.of(
            bone("a", null, 0.0F, 0.0F, 0.0F),
            bone("a", null, 0.0F, 0.0F, 0.0F))));
        assertThrows(IllegalArgumentException.class, () -> RigModel.of(List.of()));
    }

    @Test
    void topologicalOrderPutsParentsBeforeChildrenRegardlessOfDeclaration() {
        RigModel model = RigModel.of(List.of(
            bone("hand", "arm", 0.0F, 0.0F, 0.0F),
            bone("root", null, 0.0F, 0.0F, 0.0F),
            bone("arm", "root", 0.0F, 0.0F, 0.0F)));

        List<String> order = model.topological().stream().map(Bone::id).toList();
        assertEquals(List.of("root", "arm", "hand"), order);
        assertEquals(3, model.bones().size());
        assertTrue(model.hasBone("arm"));
    }

    @Test
    void worldTransformComposesRestAndOverridesDownTheChain() {
        RigModel model = RigModel.of(List.of(
            bone("root", null, 0.0F, 1.0F, 0.0F),
            bone("lid", "root", 0.0F, 0.9F, -0.45F)));
        Transform lift = new Transform(new Vector3f(0.0F, 0.15F, 0.0F), Quaternions.identity(), new Vector3f(1.0F, 1.0F, 1.0F));

        Transform lid = model.worldTransform(Map.of("root", lift), "lid");
        assertEquals(0.0F, lid.translation().getX(), EPSILON);
        assertEquals(2.05F, lid.translation().getY(), EPSILON);
        assertEquals(-0.45F, lid.translation().getZ(), EPSILON);

        Transform turned = new Transform(new Vector3f(0.0F, 0.0F, 0.0F), Quaternions.fromEulerDegrees(0.0F, 90.0F, 0.0F),
            new Vector3f(1.0F, 1.0F, 1.0F));
        Transform lidTurned = model.worldTransform(Map.of("root", turned), "lid");
        assertEquals(-0.45F, lidTurned.translation().getX(), EPSILON);
        assertEquals(1.9F, lidTurned.translation().getY(), EPSILON);
        assertEquals(0.0F, lidTurned.translation().getZ(), EPSILON);

        Map<String, Transform> all = model.worldTransforms(Map.of());
        assertEquals(1.0F, all.get("root").translation().getY(), EPSILON);
        assertEquals(1.9F, all.get("lid").translation().getY(), EPSILON);
    }
}
