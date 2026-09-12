package art.arcane.gloss.rig;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RigInstanceDocTest {
    @Test
    void specExampleParsesWithVarsAndAudience() {
        RigInstanceDoc doc = RigInstanceDoc.parse("altar.json", """
            { "schemaVersion": 1, "revision": 1, "rig": "pedestal", "world": "world", "x": 10.5, "y": 65, "z": -3.5,
              "yaw": 90, "pitch": 0, "scale": 1, "vars": { "opened": false }, "audience": { "when": "true" } }
            """);
        assertEquals("pedestal", doc.rig());
        assertEquals("world", doc.world());
        assertEquals(10.5D, doc.x(), 1.0E-9D);
        assertEquals(90.0F, doc.yaw(), 1.0E-6F);
        assertEquals(1.0D, doc.scale(), 1.0E-9D);
        assertEquals(Boolean.FALSE, doc.vars().get("opened"));
        assertTrue(doc.audience().when().isAlwaysVisible());
    }

    @Test
    void defaultsAndRefusals() {
        RigInstanceDoc doc = RigInstanceDoc.parse("min.json",
            "{ \"schemaVersion\": 1, \"revision\": 1, \"rig\": \"pedestal\", \"world\": \"world\", \"x\": 1, \"y\": 2, \"z\": 3 }");
        assertEquals(0.0F, doc.yaw(), 1.0E-6F);
        assertEquals(0.0F, doc.pitch(), 1.0E-6F);
        assertEquals(1.0D, doc.scale(), 1.0E-9D);
        assertTrue(doc.vars().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> RigInstanceDoc.parse("bad.json",
            "{ \"schemaVersion\": 1, \"revision\": 1, \"world\": \"world\", \"x\": 1, \"y\": 2, \"z\": 3 }"));
        assertThrows(IllegalArgumentException.class, () -> RigInstanceDoc.parse("bad.json",
            "{ \"schemaVersion\": 1, \"revision\": 1, \"rig\": \"pedestal\", \"x\": 1, \"y\": 2, \"z\": 3 }"));
        assertThrows(IllegalArgumentException.class, () -> RigInstanceDoc.parse("bad.json",
            "{ \"schemaVersion\": 1, \"revision\": 1, \"rig\": \"pedestal\", \"world\": \"world\", \"x\": 1, \"y\": 2, \"z\": 3, \"scale\": 0 }"));
    }

    @Test
    void withersProduceUpdatedCopies() {
        RigInstanceDoc doc = RigInstanceDoc.parse("min.json",
            "{ \"schemaVersion\": 1, \"revision\": 1, \"rig\": \"pedestal\", \"world\": \"world\", \"x\": 1, \"y\": 2, \"z\": 3 }");
        RigInstanceDoc moved = doc.withPosition("nether", 4.0D, 5.0D, 6.0D, 45.0F, 10.0F);
        assertEquals("nether", moved.world());
        assertEquals(6.0D, moved.z(), 1.0E-9D);
        assertEquals(45.0F, moved.yaw(), 1.0E-6F);
        assertEquals(2L, doc.withRevision(2L).revision());
        assertEquals(2.5D, doc.withScale(2.5D).scale(), 1.0E-9D);
        assertEquals(Boolean.TRUE, doc.withVars(Map.of("opened", Boolean.TRUE)).vars().get("opened"));
        assertEquals("open", doc.withState("open").state());
    }
}
