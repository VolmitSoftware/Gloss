package art.arcane.gloss.rig;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RigDocTest {
    static final String PEDESTAL = """
        {
          "schemaVersion": 1, "revision": 1,
          "show": "true",
          "bones": [
            { "id": "root", "parent": null, "rest": { "translation": [0, 0, 0], "rotation": [0, 0, 0], "scale": [1, 1, 1] } },
            { "id": "lid", "parent": "root", "rest": { "translation": [0, 0.9, -0.45], "rotation": [0, 0, 0], "scale": [1, 1, 1] } }
          ],
          "parts": [
            { "id": "base", "bone": "root", "type": "block", "block": "minecraft:chiseled_stone_bricks", "transform": { "translation": [-0.5, 0, -0.5], "scale": [1, 0.9, 1] }, "brightness": 15 },
            { "id": "lidPart", "bone": "lid", "type": "item", "item": { "type": "customItem", "provider": "itemsadder", "item": "chest_lid" }, "transform": { "translation": [0, 0, 0.45], "scale": [1, 0.1, 1] } },
            { "id": "label", "bone": "root", "type": "text", "text": "&6{{ rig.state }}", "transform": { "translation": [0, 1.4, 0] }, "billboard": "vertical" }
          ],
          "clips": { "idle": "breathe", "open": { "motion": "chest-open", "loop": "once" } },
          "graph": {
            "initial": "idle",
            "states": { "idle": { "clip": "idle" }, "open": { "clip": "open", "then": "idle" } },
            "transitions": [ { "from": "idle", "to": "open", "when": "rig.var.opened == true" } ]
          },
          "hitboxes": [ { "part": "base", "size": [1, 1, 1], "actions": [ { "type": "setRig", "var": "opened", "value": "true" }, { "type": "sound", "sound": "minecraft:block.chest.open" } ] } ],
          "lod": { "reducedAt": 32, "minimalAt": 64, "cullAt": 96, "minimalPart": "base" },
          "audience": { "when": "true" }
        }
        """;

    @Test
    void specExampleParsesIntoBonesPartsClipsGraphHitboxesAndLod() {
        RigDoc doc = RigDoc.parse("pedestal.json", PEDESTAL);

        assertEquals(2, doc.bones().size());
        assertEquals("root", doc.bones().get(0).id());
        assertEquals(3, doc.parts().size());
        assertEquals(PartType.ITEM, doc.parts().get(1).partType());
        assertEquals("breathe", doc.clips().get("idle").motion());
        assertNull(doc.clips().get("idle").loop());
        assertEquals("chest-open", doc.clips().get("open").motion());
        assertEquals("once", doc.clips().get("open").loop());
        assertEquals("idle", doc.graph().initial());
        assertEquals("idle", doc.graph().states().get("open").then());
        assertEquals(1, doc.graph().transitions().size());
        assertEquals(1, doc.hitboxes().size());
        assertEquals(2, doc.hitboxes().get(0).actions().size());
        assertEquals(32.0D, doc.lod().reducedAt(), 1.0E-9D);
        assertEquals(96.0D, doc.lod().cullAt(), 1.0E-9D);
        assertEquals("base", doc.lod().minimalPart());
        assertTrue(doc.audience().when().isAlwaysVisible());
        assertEquals(0.9F, doc.parts().get(0).toPart().local().scale().getY(), 1.0E-6F);
        assertEquals("vertical", doc.parts().get(2).billboard());
        RigModel model = doc.model();
        assertEquals(List.of("root", "lid"), model.topological().stream().map(Bone::id).toList());
    }

    @Test
    void boneCyclesAreRefused() {
        String raw = """
            { "schemaVersion": 1, "revision": 1,
              "bones": [ { "id": "a", "parent": "b" }, { "id": "b", "parent": "a" } ],
              "parts": [ { "id": "p", "bone": "a", "type": "block", "block": "minecraft:stone" } ] }
            """;
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> RigDoc.parse("bad.json", raw));
        assertTrue(failure.getMessage().contains("cycle"), failure.getMessage());
    }

    @Test
    void partsOnUnknownBonesAreRefused() {
        String raw = """
            { "schemaVersion": 1, "revision": 1,
              "bones": [ { "id": "root" } ],
              "parts": [ { "id": "p", "bone": "nowhere", "type": "block", "block": "minecraft:stone" } ] }
            """;
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> RigDoc.parse("bad.json", raw));
        assertTrue(failure.getMessage().contains("nowhere"), failure.getMessage());
    }

    @Test
    void partCapIsEnforced() {
        StringBuilder parts = new StringBuilder();
        for (int index = 0; index <= RigDoc.MAX_PARTS; index++) {
            if (index > 0) {
                parts.append(',');
            }
            parts.append("{ \"id\": \"p").append(index).append("\", \"bone\": \"root\", \"type\": \"block\", \"block\": \"minecraft:stone\" }");
        }
        String raw = "{ \"schemaVersion\": 1, \"revision\": 1, \"bones\": [ { \"id\": \"root\" } ], \"parts\": [" + parts + "] }";
        assertThrows(IllegalArgumentException.class, () -> RigDoc.parse("bad.json", raw));
        RigDoc doc = RigDoc.parse("ok.json", "{ \"schemaVersion\": 1, \"revision\": 1, \"bones\": [ { \"id\": \"root\" } ], \"parts\": ["
            + parts.substring(0, parts.lastIndexOf(",{")) + "] }");
        assertEquals(RigDoc.MAX_PARTS, doc.parts().size());
        assertThrows(IllegalArgumentException.class, () -> doc.requirePartCap(RigDoc.MAX_PARTS - 1));
    }

    @Test
    void transitionConditionsAreCompiledAtLoad() {
        String raw = """
            { "schemaVersion": 1, "revision": 1,
              "bones": [ { "id": "root" } ],
              "parts": [ { "id": "p", "bone": "root", "type": "block", "block": "minecraft:stone" } ],
              "clips": { "idle": "breathe" },
              "graph": { "initial": "idle", "states": { "idle": { "clip": "idle" }, "open": { "clip": "idle" } },
                         "transitions": [ { "from": "idle", "to": "open", "when": "rig.var.opened ==" } ] } }
            """;
        assertThrows(IllegalArgumentException.class, () -> RigDoc.parse("bad.json", raw));
    }

    @Test
    void graphReferencesMustResolve() {
        String unknownState = """
            { "schemaVersion": 1, "revision": 1,
              "bones": [ { "id": "root" } ],
              "parts": [ { "id": "p", "bone": "root", "type": "block", "block": "minecraft:stone" } ],
              "clips": { "idle": "breathe" },
              "graph": { "initial": "missing", "states": { "idle": { "clip": "idle" } }, "transitions": [] } }
            """;
        assertThrows(IllegalArgumentException.class, () -> RigDoc.parse("bad.json", unknownState));
        String unknownClip = """
            { "schemaVersion": 1, "revision": 1,
              "bones": [ { "id": "root" } ],
              "parts": [ { "id": "p", "bone": "root", "type": "block", "block": "minecraft:stone" } ],
              "graph": { "initial": "idle", "states": { "idle": { "clip": "nope" } }, "transitions": [] } }
            """;
        assertThrows(IllegalArgumentException.class, () -> RigDoc.parse("bad.json", unknownClip));
        String badHitbox = """
            { "schemaVersion": 1, "revision": 1,
              "bones": [ { "id": "root" } ],
              "parts": [ { "id": "p", "bone": "root", "type": "block", "block": "minecraft:stone" } ],
              "hitboxes": [ { "part": "ghost", "size": [1, 1, 1] } ] }
            """;
        assertThrows(IllegalArgumentException.class, () -> RigDoc.parse("bad.json", badHitbox));
        String badLod = """
            { "schemaVersion": 1, "revision": 1,
              "bones": [ { "id": "root" } ],
              "parts": [ { "id": "p", "bone": "root", "type": "block", "block": "minecraft:stone" } ],
              "lod": { "reducedAt": 64, "minimalAt": 32, "cullAt": 96 } }
            """;
        assertThrows(IllegalArgumentException.class, () -> RigDoc.parse("bad.json", badLod));
    }

    @Test
    void minimalDocumentGetsDefaults() {
        RigDoc doc = RigDoc.parse("min.json", """
            { "schemaVersion": 1, "revision": 1,
              "bones": [ { "id": "root" } ],
              "parts": [ { "id": "p", "bone": "root", "type": "block", "block": "minecraft:stone" } ] }
            """);
        assertTrue(doc.clips().isEmpty());
        assertNull(doc.graph());
        assertTrue(doc.hitboxes().isEmpty());
        assertEquals(RigDoc.Lod.DEFAULT_REDUCED_AT, doc.lod().reducedAt(), 1.0E-9D);
        assertEquals(RigDoc.Lod.DEFAULT_MINIMAL_AT, doc.lod().minimalAt(), 1.0E-9D);
        assertEquals(RigDoc.Lod.DEFAULT_CULL_AT, doc.lod().cullAt(), 1.0E-9D);
        assertTrue(doc.show().isAlwaysVisible());
        assertTrue(doc.audience().when().isAlwaysVisible());
        List<String> ids = new ArrayList<>();
        for (RigDoc.PartDef part : doc.parts()) {
            ids.add(part.id());
        }
        assertEquals(List.of("p"), ids);
    }
}
