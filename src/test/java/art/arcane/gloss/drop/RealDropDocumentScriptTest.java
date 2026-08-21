package art.arcane.gloss.drop;

import art.arcane.gloss.GlossConfig;
import art.arcane.volmlib.util.bukkit.json.BukkitJson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealDropDocumentScriptTest {
    /**
     * The exact shipped document as it stood before the physics and script blocks existed. Any
     * operator file written against that format must keep parsing to the same runtime settings.
     */
    private static final String LEGACY_DOCUMENT = """
        {
          "schemaVersion": 1,
          "revision": 1,
          "limits": {
            "updateIntervalTicks": 2,
            "settledPollIntervalTicks": 20,
            "maxVisualsPerStack": 3,
            "maxVisualsPerChunk": 128,
            "viewRange": 32.0,
            "spread": 0.18
          },
          "scale": {
            "defaultScale": 0.4,
            "flatItems": 0.65,
            "thinBlocks": 0.45
          },
          "motion": {
            "tumble": true,
            "speedMultiplier": 1.35,
            "degreesPerSecondX": 160.0,
            "degreesPerSecondY": 120.0,
            "degreesPerSecondZ": 100.0,
            "variance": 0.2,
            "changeOnBounce": true
          },
          "landing": {
            "mode": "NATURAL",
            "tiltDegrees": 10.0,
            "randomYaw": true,
            "transitionTicks": 4
          },
          "labels": {
            "enabled": true,
            "yOffset": 0.55,
            "scale": 0.85,
            "viewRange": 32.0,
            "billboard": "CENTER",
            "seeThrough": true,
            "shadow": true,
            "background": true,
            "backgroundRed": 0,
            "backgroundGreen": 0,
            "backgroundBlue": 0,
            "backgroundAlpha": 80
          },
          "filters": {
            "disabledWorlds": [],
            "materialBlacklist": [
              "BEDROCK",
              "BARRIER"
            ],
            "onlyPlayerDrops": false
          }
        }
        """;

    @Test
    void aDocumentWithNoScriptBlockRoundTripsEveryKeyItDeclaresUnchanged() {
        RealDropSettingsDoc parsed = RealDropSettingsDoc.parse("default.json", LEGACY_DOCUMENT);

        JsonObject original = JsonParser.parseString(LEGACY_DOCUMENT).getAsJsonObject();
        JsonObject reserialized = JsonParser.parseString(BukkitJson.GSON.toJson(parsed)).getAsJsonObject();
        for (Map.Entry<String, JsonElement> entry : original.entrySet()) {
            assertEquals(entry.getValue(), reserialized.get(entry.getKey()),
                "real-drops key '" + entry.getKey() + "' did not survive the round trip");
        }
    }

    @Test
    void aDocumentWithNoScriptBlockKeepsTheExactRuntimeSettingsItAlwaysHad() {
        GlossConfig.RealDrops legacy = RealDropSettingsDoc.parse("default.json", LEGACY_DOCUMENT).toConfig(true);
        GlossConfig.RealDrops bare = RealDropSettingsDoc
            .parse("default.json", "{\"schemaVersion\":1,\"revision\":1}").toConfig(true);

        assertEquals(bare.limits(), legacy.limits());
        assertEquals(bare.scale(), legacy.scale());
        assertEquals(bare.motion(), legacy.motion());
        assertEquals(bare.landing(), legacy.landing());
        assertEquals(bare.labels(), legacy.labels());
        assertEquals(bare.filters(), legacy.filters());
    }

    @Test
    void absentScriptAndPhysicsBlocksResolveToTheDisabledNeutralDefaults() {
        RealDropSettingsDoc parsed = RealDropSettingsDoc.parse("default.json", LEGACY_DOCUMENT);
        GlossConfig.RealDrops config = parsed.toConfig(true);

        assertFalse(config.physics().enabled());
        assertEquals(1.0F, config.physics().gravityMultiplier());
        assertEquals(0.0F, config.physics().bounce());
        assertEquals(0.0F, config.physics().waterBuoyancy());
        assertEquals(0.0F, config.physics().waterDrag());

        assertFalse(config.script().enabled());
        assertEquals(List.of(), config.script().vars());
        assertEquals(new GlossConfig.RealDrops.Axis("0", "0", "0"), config.script().offset());
        assertEquals(new GlossConfig.RealDrops.Axis("0", "0", "0"), config.script().rotation());
        assertEquals(new GlossConfig.RealDrops.Axis("1", "1", "1"), config.script().scale());
        assertEquals("", config.script().glow());
        assertEquals("true", config.script().visible());
    }

    @Test
    void theShippedDefaultCarriesTheDisabledScriptAndPhysicsBlocks() throws IOException {
        GlossConfig.RealDrops config = RealDropSettingsDoc
            .parse("default.json", shippedDefault()).toConfig(true);

        assertFalse(config.physics().enabled());
        assertFalse(config.script().enabled());
        assertEquals("true", config.script().visible());
        assertEquals(RealDropSettingsDoc.DEFAULTS.toConfig(true).limits(), config.limits());
        assertEquals(RealDropSettingsDoc.DEFAULTS.toConfig(true).labels(), config.labels());
    }

    @Test
    void physicsValuesAreClampedAtTheDocumentBoundary() {
        GlossConfig.RealDrops.Physics physics = RealDropSettingsDoc.parse("default.json", """
            {
              "schemaVersion": 1,
              "revision": 3,
              "physics": {
                "enabled": true,
                "gravityMultiplier": 99,
                "bounce": 4,
                "waterBuoyancy": -2,
                "waterDrag": 0.35
              }
            }
            """).toConfig(true).physics();

        assertTrue(physics.enabled());
        assertEquals(4.0F, physics.gravityMultiplier());
        assertEquals(0.9F, physics.bounce());
        assertEquals(0.0F, physics.waterBuoyancy());
        assertEquals(0.35F, physics.waterDrag());
    }

    @Test
    void aScriptBlockParsesItsExpressionsAndKeepsVarDeclarationOrder() {
        GlossConfig.RealDrops.Script script = RealDropSettingsDoc.parse("default.json", """
            {
              "schemaVersion": 1,
              "revision": 7,
              "script": {
                "enabled": true,
                "vars": {
                  "zeta": "sin(t)",
                  "alpha": "zeta * 2",
                  "middle": "alpha + 1"
                },
                "offset": { "y": "middle * 0.1" },
                "rotation": { "x": "t * 90" },
                "scale": { "x": "1 + zeta" },
                "glow": "materialIs('torch') ? #FFAA55 : 0",
                "visible": "amount > 1"
              }
            }
            """).toConfig(true).script();

        assertTrue(script.enabled());
        assertEquals(List.of("zeta", "alpha", "middle"),
            script.vars().stream().map(GlossConfig.RealDrops.ScriptVar::name).toList());
        assertEquals("0", script.offset().x());
        assertEquals("middle * 0.1", script.offset().y());
        assertEquals("t * 90", script.rotation().x());
        assertEquals("0", script.rotation().z());
        assertEquals("1 + zeta", script.scale().x());
        assertEquals("1", script.scale().y());
        assertEquals("materialIs('torch') ? #FFAA55 : 0", script.glow());
        assertEquals("amount > 1", script.visible());
    }

    @Test
    void aBrokenExpressionIsRefusedWithTheFileTheFieldAndThePosition() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> RealDropSettingsDoc.parse("default.json", """
                {
                  "schemaVersion": 1,
                  "revision": 2,
                  "script": { "enabled": true, "offset": { "y": "sin(t" } }
                }
                """));

        assertTrue(failure.getMessage().startsWith("default.json "), failure.getMessage());
        assertTrue(failure.getMessage().contains("script.offset.y"), failure.getMessage());
        assertTrue(failure.getMessage().contains("position"), failure.getMessage());
    }

    @Test
    void aBrokenExpressionIsRefusedEvenWhileTheScriptIsDisabled() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> RealDropSettingsDoc.parse("default.json", """
                {
                  "schemaVersion": 1,
                  "revision": 2,
                  "script": { "enabled": false, "scale": { "x": "nonsense * 2" } }
                }
                """));

        assertTrue(failure.getMessage().contains("script.scale.x"), failure.getMessage());
        assertTrue(failure.getMessage().contains("unknown variable 'nonsense'"), failure.getMessage());
    }

    @Test
    void anEmptyOrNamelessVariableIsRefused() {
        IllegalArgumentException blank = assertThrows(IllegalArgumentException.class,
            () -> RealDropSettingsDoc.parse("default.json", """
                {
                  "schemaVersion": 1,
                  "revision": 2,
                  "script": { "enabled": true, "vars": { "wobble": "   " } }
                }
                """));
        assertTrue(blank.getMessage().contains("script.vars.wobble must be a non-blank expression"),
            blank.getMessage());

        IllegalArgumentException nameless = assertThrows(IllegalArgumentException.class,
            () -> RealDropSettingsDoc.parse("default.json", """
                {
                  "schemaVersion": 1,
                  "revision": 2,
                  "script": { "enabled": true, "vars": { "  ": "1" } }
                }
                """));
        assertTrue(nameless.getMessage().contains("script.vars declares an entry with no name"),
            nameless.getMessage());
    }

    @Test
    void tooManyVariablesAreRefused() {
        StringBuilder vars = new StringBuilder();
        for (int index = 0; index <= RealDropSettingsDoc.Script.MAX_VARS; index++) {
            if (index > 0) {
                vars.append(',');
            }
            vars.append("\"v").append(index).append("\": \"1\"");
        }
        String document = "{\"schemaVersion\":1,\"revision\":2,\"script\":{\"enabled\":true,\"vars\":{"
            + vars + "}}}";

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> RealDropSettingsDoc.parse("default.json", document));

        assertTrue(failure.getMessage().contains("the limit is " + RealDropSettingsDoc.Script.MAX_VARS),
            failure.getMessage());
    }

    /**
     * Every worked example in DROP_SCRIPT_FORMAT.md, pinned so the file the editor implements
     * against cannot drift away from what the compiler accepts.
     */
    @Test
    void everyWorkedExampleInTheFormatDocumentLoads() throws IOException {
        List<String> examples = workedExamples();
        assertEquals(4, examples.size(),
            "DROP_SCRIPT_FORMAT.md should carry four enabled worked examples, found " + examples.size());
        for (String example : examples) {
            GlossConfig.RealDrops config = RealDropSettingsDoc.parse("default.json", example).toConfig(true);
            assertTrue(config.script().enabled() || config.physics().enabled(),
                "worked example enables nothing: " + example);
        }
    }

    private static List<String> workedExamples() throws IOException {
        Path document = Path.of(System.getProperty("user.dir"), "DROP_SCRIPT_FORMAT.md");
        assertTrue(Files.isRegularFile(document), "missing " + document);
        Matcher blocks = Pattern.compile("```json\\n(.*?)```", Pattern.DOTALL)
            .matcher(Files.readString(document, StandardCharsets.UTF_8));
        List<String> examples = new ArrayList<>();
        while (blocks.find()) {
            String block = blocks.group(1);
            if (block.contains("\"enabled\": true")) {
                examples.add(block);
            }
        }
        return examples;
    }

    private static String shippedDefault() throws IOException {
        try (InputStream input = RealDropDocumentScriptTest.class
            .getResourceAsStream("/defaults/real-drops/default.json")) {
            assertNotNull(input, "missing shipped real-drops default");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
