package art.arcane.gloss.drop;

import art.arcane.gloss.GlossConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealDropDocumentScriptTest {
    @Test
    void absentScriptAndPhysicsBlocksResolveToTheDisabledNeutralDefaults() {
        RealDropSettingsDoc parsed = RealDropSettingsDoc.parse("default.json", document("{}"));
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
        GlossConfig.RealDrops.Physics physics = RealDropSettingsDoc.parse("default.json", document("""
            {
              "physics": {
                "enabled": true,
                "gravityMultiplier": 99,
                "bounce": 4,
                "waterBuoyancy": -2,
                "waterDrag": 0.35
              }
            }
            """)).toConfig(true).physics();

        assertTrue(physics.enabled());
        assertEquals(4.0F, physics.gravityMultiplier());
        assertEquals(0.9F, physics.bounce());
        assertEquals(0.0F, physics.waterBuoyancy());
        assertEquals(0.35F, physics.waterDrag());
    }

    @Test
    void aScriptBlockParsesItsExpressionsAndKeepsVarDeclarationOrder() {
        GlossConfig.RealDrops.Script script = RealDropSettingsDoc.parse("default.json", document("""
            {
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
            """)).toConfig(true).script();

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
            () -> RealDropSettingsDoc.parse("default.json", document("""
                {
                  "script": { "enabled": true, "offset": { "y": "sin(t" } }
                }
                """)));

        assertTrue(failure.getMessage().startsWith("default.json "), failure.getMessage());
        assertTrue(failure.getMessage().contains("script.offset.y"), failure.getMessage());
        assertTrue(failure.getMessage().contains("position"), failure.getMessage());
    }

    @Test
    void aBrokenExpressionIsRefusedEvenWhileTheScriptIsDisabled() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> RealDropSettingsDoc.parse("default.json", document("""
                {
                  "script": { "enabled": false, "scale": { "x": "nonsense * 2" } }
                }
                """)));

        assertTrue(failure.getMessage().contains("script.scale.x"), failure.getMessage());
        assertTrue(failure.getMessage().contains("unknown variable 'nonsense'"), failure.getMessage());
    }

    @Test
    void anEmptyOrNamelessVariableIsRefused() {
        IllegalArgumentException blank = assertThrows(IllegalArgumentException.class,
            () -> RealDropSettingsDoc.parse("default.json", document("""
                {
                  "script": { "enabled": true, "vars": { "wobble": "   " } }
                }
                """)));
        assertTrue(blank.getMessage().contains("script.vars.wobble must be a non-blank expression"),
            blank.getMessage());

        IllegalArgumentException nameless = assertThrows(IllegalArgumentException.class,
            () -> RealDropSettingsDoc.parse("default.json", document("""
                {
                  "script": { "enabled": true, "vars": { "  ": "1" } }
                }
                """)));
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
        String raw = document("{\"script\":{\"enabled\":true,\"vars\":{" + vars + "}}}");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> RealDropSettingsDoc.parse("default.json", raw));

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
            .matcher(Files.readString(document, StandardCharsets.UTF_8).replace("\r\n", "\n"));
        List<String> examples = new ArrayList<>();
        while (blocks.find()) {
            String block = blocks.group(1);
            if (block.contains("\"enabled\": true")) {
                examples.add(block);
            }
        }
        return examples;
    }

    private static String document(String presentation) {
        return "{\"schemaVersion\":3,\"revision\":1,\"presentation\":" + presentation
            + ",\"variants\":[],\"audience\":{\"when\":\"true\"}}";
    }

    private static String shippedDefault() throws IOException {
        try (InputStream input = RealDropDocumentScriptTest.class
            .getResourceAsStream("/defaults/real-drops/default.json")) {
            assertNotNull(input, "missing shipped real-drops default");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
