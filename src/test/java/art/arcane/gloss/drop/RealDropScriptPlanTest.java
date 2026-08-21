package art.arcane.gloss.drop;

import art.arcane.gloss.GlossConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealDropScriptPlanTest {
    private static final double EPSILON = 1.0E-9D;
    private static final GlossConfig.RealDrops.Axis ZERO = new GlossConfig.RealDrops.Axis("0", "0", "0");
    private static final GlossConfig.RealDrops.Axis ONE = new GlossConfig.RealDrops.Axis("1", "1", "1");

    private static GlossConfig.RealDrops.Script script(List<GlossConfig.RealDrops.ScriptVar> vars,
                                                       GlossConfig.RealDrops.Axis offset,
                                                       GlossConfig.RealDrops.Axis rotation,
                                                       GlossConfig.RealDrops.Axis scale,
                                                       String glow, String visible) {
        return new GlossConfig.RealDrops.Script(true, vars, offset, rotation, scale, glow, visible);
    }

    private static GlossConfig.RealDrops.Script offsetScript(String x, String y, String z) {
        return script(List.of(), new GlossConfig.RealDrops.Axis(x, y, z), ZERO, ONE, "", "true");
    }

    private static RealDropScriptPlan.RealDropScriptContext context() {
        return new RealDropScriptPlan.RealDropScriptContext(
            2.0D, 40, 1, 3, 12,
            true, false, "ROLLING", 0.4D, 0.7D, false, false, 4,
            0.3D, -0.4D, 0.0D,
            2.5D, 11, 6,
            0.25D, "REDSTONE_TORCH", RealDropModel.ModelKind.FLAT);
    }

    private static RealDropScriptPlan.RealDropScriptSample sample(GlossConfig.RealDrops.Script script) {
        return RealDropScriptPlan.compile(script).sample(context());
    }

    @Test
    void theShippedDisabledScriptCompilesToNeutralValues() {
        GlossConfig.RealDrops.Script shipped = RealDropSettingsDoc.DEFAULTS.toConfig(true).script();
        RealDropScriptPlan.RealDropScriptSample sample = RealDropScriptPlan.compile(shipped).sample(context());

        assertFalse(shipped.enabled());
        assertEquals(0.0D, sample.offsetX(), EPSILON);
        assertEquals(0.0D, sample.offsetY(), EPSILON);
        assertEquals(0.0D, sample.offsetZ(), EPSILON);
        assertEquals(0.0D, sample.rotationY(), EPSILON);
        assertEquals(1.0D, sample.scaleX(), EPSILON);
        assertEquals(1.0D, sample.scaleY(), EPSILON);
        assertEquals(1.0D, sample.scaleZ(), EPSILON);
        assertEquals(0, sample.glowArgb());
        assertTrue(sample.visible());
    }

    @Test
    void everyBuiltInVariableResolvesToItsLiveValue() {
        assertEquals(2.0D, sample(offsetScript("t", "0", "0")).offsetX(), EPSILON);
        assertEquals(4.0D, sample(offsetScript("age / 10", "0", "0")).offsetX(), EPSILON);
        assertEquals(1.0D, sample(offsetScript("index", "0", "0")).offsetX(), EPSILON);
        assertEquals(3.0D, sample(offsetScript("count", "0", "0")).offsetX(), EPSILON);
        assertEquals(12.0D, sample(offsetScript("amount", "0", "0")).offsetX(), EPSILON);
        assertEquals(1.0D, sample(offsetScript("onGround ? 1 : 0", "0", "0")).offsetX(), EPSILON);
        assertEquals(0.0D, sample(offsetScript("settled ? 1 : 0", "0", "0")).offsetX(), EPSILON);
        assertEquals(0.0D, sample(offsetScript("inWater ? 1 : 0", "0", "0")).offsetX(), EPSILON);
        assertEquals(0.0D, sample(offsetScript("inLava ? 1 : 0", "0", "0")).offsetX(), EPSILON);
        assertEquals(4.0D, sample(offsetScript("bounces", "0", "0")).offsetX(), EPSILON);
        assertEquals(0.3D, sample(offsetScript("velocityX", "0", "0")).offsetX(), EPSILON);
        assertEquals(-0.4D, sample(offsetScript("velocityY", "0", "0")).offsetX(), EPSILON);
        assertEquals(0.0D, sample(offsetScript("velocityZ", "0", "0")).offsetX(), EPSILON);
        assertEquals(Math.sqrt(0.3D * 0.3D + 0.4D * 0.4D),
            sample(offsetScript("speed", "0", "0")).offsetX(), EPSILON);
        assertEquals(2.5D, sample(offsetScript("height", "0", "0")).offsetX(), EPSILON);
        assertEquals(11.0D, sample(offsetScript("blockLight", "0", "0")).offsetX(), EPSILON);
        assertEquals(6.0D, sample(offsetScript("skyLight", "0", "0")).offsetX(), EPSILON);
        assertEquals(0.25D, sample(offsetScript("random", "0", "0")).offsetX(), EPSILON);
        assertEquals(Math.PI, sample(offsetScript("pi", "0", "0")).offsetX(), EPSILON);
        assertEquals(1.0D, sample(offsetScript("isFlat ? 1 : 0", "0", "0")).offsetX(), EPSILON);
        assertEquals(0.0D, sample(offsetScript("isBlock ? 1 : 0", "0", "0")).offsetX(), EPSILON);
        assertEquals(0.0D, sample(offsetScript("isThin ? 1 : 0", "0", "0")).offsetX(), EPSILON);
        assertEquals(1.0D, sample(offsetScript("material == 'REDSTONE_TORCH' ? 1 : 0", "0", "0")).offsetX(), EPSILON);
    }

    @Test
    void materialTestsMatchExactNamesAndGlobs() {
        assertEquals(1.0D, sample(offsetScript("materialIs('redstone_torch') ? 1 : 0", "0", "0")).offsetX(), EPSILON);
        assertEquals(1.0D,
            sample(offsetScript("materialIs('minecraft:redstone_torch') ? 1 : 0", "0", "0")).offsetX(), EPSILON);
        assertEquals(0.0D, sample(offsetScript("materialIs('torch') ? 1 : 0", "0", "0")).offsetX(), EPSILON);
        assertEquals(1.0D, sample(offsetScript("materialMatches('*_TORCH') ? 1 : 0", "0", "0")).offsetX(), EPSILON);
        assertEquals(1.0D, sample(offsetScript("materialMatches('redstone_*') ? 1 : 0", "0", "0")).offsetX(), EPSILON);
        assertEquals(0.0D, sample(offsetScript("materialMatches('*_SLAB') ? 1 : 0", "0", "0")).offsetX(), EPSILON);
    }

    @Test
    void varsEvaluateInDeclarationOrderAndFeedLaterExpressions() {
        RealDropScriptPlan.RealDropScriptSample sample = sample(script(
            List.of(
                new GlossConfig.RealDrops.ScriptVar("base", "2"),
                new GlossConfig.RealDrops.ScriptVar("doubled", "base * 3"),
                new GlossConfig.RealDrops.ScriptVar("gated", "onGround && doubled > 5 ? doubled : 0")),
            new GlossConfig.RealDrops.Axis("gated", "base", "doubled"), ZERO, ONE, "", "true"));

        assertEquals(6.0D, sample.offsetX(), EPSILON);
        assertEquals(2.0D, sample.offsetY(), EPSILON);
        assertEquals(6.0D, sample.offsetZ(), EPSILON);
    }

    @Test
    void aVariableIsNotVisibleToTheExpressionThatDeclaredItOrToEarlierOnes() {
        GlossConfig.RealDrops.Script forwardReference = script(
            List.of(
                new GlossConfig.RealDrops.ScriptVar("first", "later + 1"),
                new GlossConfig.RealDrops.ScriptVar("later", "1")),
            ZERO, ZERO, ONE, "", "true");
        IllegalArgumentException forward = assertThrows(IllegalArgumentException.class,
            () -> RealDropScriptPlan.validate(forwardReference));
        assertTrue(forward.getMessage().startsWith("script.vars.first: unknown variable 'later'"),
            forward.getMessage());

        GlossConfig.RealDrops.Script selfReference = script(
            List.of(new GlossConfig.RealDrops.ScriptVar("loop", "loop + 1")),
            ZERO, ZERO, ONE, "", "true");
        IllegalArgumentException recursive = assertThrows(IllegalArgumentException.class,
            () -> RealDropScriptPlan.validate(selfReference));
        assertTrue(recursive.getMessage().contains("unknown variable 'loop'"), recursive.getMessage());
    }

    @Test
    void glowAcceptsColourNumbersColourStringsAndZeroForNoGlow() {
        assertEquals(0xFFFFAA55, sample(script(List.of(), ZERO, ZERO, ONE, "#FFAA55", "true")).glowArgb());
        assertEquals(0xFFFFAA55, sample(script(List.of(), ZERO, ZERO, ONE, "'#FFAA55'", "true")).glowArgb());
        assertEquals(0x80FFAA55, sample(script(List.of(), ZERO, ZERO, ONE, "'#80FFAA55'", "true")).glowArgb());
        assertEquals(0, sample(script(List.of(), ZERO, ZERO, ONE, "0", "true")).glowArgb());
        assertEquals(0, sample(script(List.of(), ZERO, ZERO, ONE, "", "true")).glowArgb());
        assertEquals(0xFF00FF00,
            sample(script(List.of(), ZERO, ZERO, ONE, "materialMatches('*_TORCH') ? rgb(0, 255, 0) : 0", "true"))
                .glowArgb());
        assertEquals(0,
            sample(script(List.of(), ZERO, ZERO, ONE, "materialMatches('*_SLAB') ? rgb(0, 255, 0) : 0", "true"))
                .glowArgb());
    }

    @Test
    void visibleGatesTheDisplayAndMustBeBoolean() {
        assertTrue(sample(script(List.of(), ZERO, ZERO, ONE, "", "true")).visible());
        assertFalse(sample(script(List.of(), ZERO, ZERO, ONE, "", "index > 5")).visible());

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> RealDropScriptPlan.validate(script(List.of(), ZERO, ZERO, ONE, "", "amount")));
        assertTrue(failure.getMessage().startsWith("script.visible must evaluate to true or false"),
            failure.getMessage());
    }

    @Test
    void resultsAreClampedToTheDocumentedBounds() {
        RealDropScriptPlan.RealDropScriptSample offsets = sample(offsetScript("9999", "-9999", "0"));
        assertEquals(RealDropScriptPlan.MAX_OFFSET_BLOCKS, offsets.offsetX(), EPSILON);
        assertEquals(-RealDropScriptPlan.MAX_OFFSET_BLOCKS, offsets.offsetY(), EPSILON);

        RealDropScriptPlan.RealDropScriptSample scales = sample(script(List.of(), ZERO, ZERO,
            new GlossConfig.RealDrops.Axis("9999", "-4", "1"), "", "true"));
        assertEquals(RealDropScriptPlan.MAX_SCALE_FACTOR, scales.scaleX(), EPSILON);
        assertEquals(0.0D, scales.scaleY(), EPSILON);
    }

    @Test
    void environmentProbingIsOnlyRequestedWhenAnExpressionAsksForIt() {
        assertFalse(RealDropScriptPlan.compile(offsetScript("sin(t)", "0", "0")).environmentRequired());
        assertTrue(RealDropScriptPlan.compile(offsetScript("height", "0", "0")).environmentRequired());
        assertTrue(RealDropScriptPlan.compile(offsetScript("0", "blockLight / 15", "0")).environmentRequired());
        assertTrue(RealDropScriptPlan.compile(script(
            List.of(new GlossConfig.RealDrops.ScriptVar("lit", "skyLight")),
            ZERO, ZERO, ONE, "", "true")).environmentRequired());
    }

    @Test
    void evaluationCadenceAndSharingFollowReferencedInputs() {
        RealDropScriptPlan staticPlan = RealDropScriptPlan.compile(offsetScript("amount", "count", "0"));
        assertFalse(staticPlan.continuousUpdatesRequired());
        assertFalse(staticPlan.perModelRequired());

        RealDropScriptPlan animatedPlan = RealDropScriptPlan.compile(offsetScript("stateTime", "0", "0"));
        assertTrue(animatedPlan.continuousUpdatesRequired());
        assertFalse(animatedPlan.perModelRequired());

        RealDropScriptPlan indexedPlan = RealDropScriptPlan.compile(offsetScript("index", "0", "0"));
        assertTrue(indexedPlan.perModelRequired());
    }

    @Test
    void aSyntaxErrorNamesTheFieldAndTheCharacterPosition() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> RealDropScriptPlan.validate(offsetScript("0", "1 + * 2", "0")));

        assertTrue(failure.getMessage().startsWith("script.offset.y: "), failure.getMessage());
        assertTrue(failure.getMessage().contains("at position 4"), failure.getMessage());
    }

    @Test
    void anUnknownNameNamesTheFieldAndWhereItAppears() {
        IllegalArgumentException variable = assertThrows(IllegalArgumentException.class,
            () -> RealDropScriptPlan.validate(offsetScript("0", "0", "1 + wobble")));
        assertEquals("script.offset.z: unknown variable 'wobble' at position 4", variable.getMessage());

        IllegalArgumentException function = assertThrows(IllegalArgumentException.class,
            () -> RealDropScriptPlan.validate(offsetScript("wiggle(t)", "0", "0")));
        assertEquals("script.offset.x: unknown function 'wiggle' at position 0", function.getMessage());
    }

    @Test
    void aVariableMayNotShadowABuiltInOrCarryAnUnusableName() {
        GlossConfig.RealDrops.Script shadowing = script(
            List.of(new GlossConfig.RealDrops.ScriptVar("speed", "1")), ZERO, ZERO, ONE, "", "true");
        IllegalArgumentException shadow = assertThrows(IllegalArgumentException.class,
            () -> RealDropScriptPlan.validate(shadowing));
        assertEquals("script.vars.speed shadows the built-in variable speed", shadow.getMessage());

        GlossConfig.RealDrops.Script badName = script(
            List.of(new GlossConfig.RealDrops.ScriptVar("two words", "1")), ZERO, ZERO, ONE, "", "true");
        IllegalArgumentException name = assertThrows(IllegalArgumentException.class,
            () -> RealDropScriptPlan.validate(badName));
        assertTrue(name.getMessage().startsWith("script.vars.two words is not a valid name"), name.getMessage());
    }

    @Test
    void aFieldThatProducesTheWrongTypeIsRefusedAtLoad() {
        IllegalArgumentException text = assertThrows(IllegalArgumentException.class,
            () -> RealDropScriptPlan.validate(offsetScript("material", "0", "0")));
        assertEquals("script.offset.x must evaluate to a number, got string", text.getMessage());

        IllegalArgumentException flag = assertThrows(IllegalArgumentException.class,
            () -> RealDropScriptPlan.validate(offsetScript("0", "onGround", "0")));
        assertEquals("script.offset.y must evaluate to a number, got boolean", flag.getMessage());

        IllegalArgumentException glow = assertThrows(IllegalArgumentException.class,
            () -> RealDropScriptPlan.validate(script(List.of(), ZERO, ZERO, ONE, "'not a colour'", "true")));
        assertTrue(glow.getMessage().startsWith("script.glow string must be #RRGGBB or #AARRGGBB"),
            glow.getMessage());
    }

    @Test
    void anOverlongExpressionIsRefused() {
        String source = "0 + ".repeat(RealDropScriptPlan.MAX_SOURCE_LENGTH) + "0";
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> RealDropScriptPlan.validate(offsetScript(source, "0", "0")));

        assertTrue(failure.getMessage().contains("exceeds " + RealDropScriptPlan.MAX_SOURCE_LENGTH + " characters"),
            failure.getMessage());
    }

    @Test
    void stableRandomIsFixedPerItemAndSpreadAcrossTheUnitRange() {
        UUID first = UUID.fromString("00000000-0000-4000-8000-000000000001");
        UUID second = UUID.fromString("00000000-0000-4000-8000-000000000002");

        double repeated = RealDropScriptPlan.RealDropScriptContext.stableRandom(first);
        assertEquals(repeated, RealDropScriptPlan.RealDropScriptContext.stableRandom(first), EPSILON);
        assertNotEquals(repeated, RealDropScriptPlan.RealDropScriptContext.stableRandom(second));
        assertTrue(repeated >= 0.0D && repeated < 1.0D, "random must live in [0, 1), was " + repeated);
    }
}
