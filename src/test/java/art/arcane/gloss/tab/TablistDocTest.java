package art.arcane.gloss.tab;

import art.arcane.gloss.doc.DocumentEnvelope;
import art.arcane.volmlib.util.bukkit.json.BukkitJson;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TablistDocTest {
    @Test
    void parseReadsTheV2Shape() {
        String json = """
            {
              "schemaVersion": 1,
              "revision": 2,
              "useHeaderFooter": true,
              "header": "&d&lGloss",
              "footer": "&7VolmitSoftware.com",
              "groupListNames": true,
              "nameFormats": {
                "default": "$player",
                "_op": "&6$player"
              }
            }
            """;

        TablistDoc doc = TablistDoc.parse("tablist.json", json);

        assertEquals(1, doc.schemaVersion());
        assertEquals(2L, doc.revision());
        assertTrue(doc.useHeaderFooter());
        assertEquals("&d&lGloss", doc.header());
        assertEquals("&7VolmitSoftware.com", doc.footer());
        assertTrue(doc.groupListNames());
        assertEquals(Map.of("default", "$player", "_op", "&6$player"), doc.nameFormats());
    }

    @Test
    void gsonRoundTripPreservesAllFields() {
        TablistDoc original = new TablistDoc(1, 7L, false, "&aTop", "&7Bottom", false,
            Map.of("staff", "&c$player"));

        TablistDoc decoded = TablistDoc.parse("tablist.json", BukkitJson.GSON.toJson(original));

        assertEquals(original, decoded);
    }

    @Test
    void legacyShapeWithoutEnvelopeIsRejected() {
        String legacy = "{\"header\":\"hi\",\"footer\":\"bye\"}";

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> TablistDoc.parse("tablist.json", legacy));

        assertTrue(failure.getMessage().contains("schemaVersion"));
    }

    @Test
    void revisionBoundsAreEnforced() {
        assertThrows(IllegalArgumentException.class,
            () -> new TablistDoc(1, 0L, true, "", "", true, Map.of()));
        assertThrows(IllegalArgumentException.class,
            () -> new TablistDoc(1, DocumentEnvelope.MAX_SAFE_REVISION + 1L, true, "", "", true, Map.of()));
    }

    @Test
    void nullTextAndFormatsNormalize() {
        Map<String, String> formats = new HashMap<>();
        formats.put(" Staff ", null);
        formats.put("", "&7$player");
        formats.put(null, "&7$player");

        TablistDoc doc = new TablistDoc(1, 1L, true, null, null, true, formats);

        assertEquals("", doc.header());
        assertEquals("", doc.footer());
        assertEquals(Map.of("staff", ""), doc.nameFormats());

        assertEquals(Map.of(), new TablistDoc(1, 1L, true, "", "", true, null).nameFormats());
    }

    @Test
    void opFormatWinsForOpsWhenPresent() {
        Map<String, String> formats = Map.of("default", "$player", "_op", "&6$player", "staff", "&c$player");

        TablistService.ListNameChoice choice = TablistService.chooseListName(true, "staff", formats);

        assertEquals("&6$player", choice.template());
        assertEquals("_op", choice.groupName());
    }

    @Test
    void primaryGroupFormatWinsForNonOps() {
        Map<String, String> formats = Map.of("default", "$player", "staff", "&c$player");

        TablistService.ListNameChoice choice = TablistService.chooseListName(false, "staff", formats);

        assertEquals("&c$player", choice.template());
        assertEquals("staff", choice.groupName());
    }

    @Test
    void opsWithoutOpFormatUseTheirPrimaryGroup() {
        Map<String, String> formats = Map.of("default", "$player", "staff", "&c$player");

        TablistService.ListNameChoice choice = TablistService.chooseListName(true, "staff", formats);

        assertEquals("&c$player", choice.template());
        assertEquals("staff", choice.groupName());
    }

    @Test
    void mixedCaseVaultGroupMatchesLowercasedFormatKey() {
        Map<String, String> formats = Map.of("default", "$player", "vip", "&d$player");

        TablistService.ListNameChoice choice = TablistService.chooseListName(false, "VIP", formats);

        assertEquals("&d$player", choice.template());
        assertEquals("VIP", choice.groupName());
    }

    @Test
    void unmatchedGroupFallsBackToDefaultFormat() {
        Map<String, String> formats = Map.of("default", "&7$player");

        TablistService.ListNameChoice choice = TablistService.chooseListName(false, "builders", formats);

        assertEquals("&7$player", choice.template());
        assertEquals("builders", choice.groupName());
    }

    @Test
    void missingDefaultFormatFallsBackToPlayerToken() {
        TablistService.ListNameChoice choice = TablistService.chooseListName(false, null, Map.of());

        assertEquals("$player", choice.template());
        assertEquals("", choice.groupName());
    }
}
