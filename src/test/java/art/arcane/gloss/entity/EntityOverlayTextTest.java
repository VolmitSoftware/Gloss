package art.arcane.gloss.entity;

import art.arcane.gloss.expr.ExprFunctions;
import art.arcane.gloss.expr.ExprScope;
import art.arcane.gloss.particle.ParticleText;
import art.arcane.gloss.text.TextPipeline;
import org.bukkit.ChatColor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityOverlayTextTest {
    private static final java.util.function.Predicate<String> SHARED_CLIPS = line -> false;

    private static final EntityOverlayText.Snapshot NAMED = new EntityOverlayText.Snapshot(
        "Sentinel", 15, 20, 20, 5, 7, 4, 12, "zombie", 3.5);

    @Test
    void defaultsKeepNameAboveHealthAndCombatStatsLast() {
        ParticleText.Rendered frame = render(EntityOverlayDoc.DEFAULTS, NAMED, List.of("&7Speed &f0.3"));
        assertEquals("Sentinel\n|||||||||| 15/20\nx12\n-5\nSpeed 0.3\nATK 7 | ARM 4", plain(frame));
        assertTrue(frame.text().contains("§a||||||||§c||"));
    }

    @Test
    void unnamedAndUndamagedRowsDisappear() {
        ParticleText.Rendered frame = render(EntityOverlayDoc.DEFAULTS,
            new EntityOverlayText.Snapshot(null, 20, 20, 20, 0, 3, 0, 1, "zombie", 2), List.of());
        assertEquals("|||||||||| 20/20\nATK 3 | ARM 0", plain(frame));
    }

    @Test
    void arbitraryOrderCustomExpressionsAndSpacersUseTheSharedPipeline() {
        EntityOverlayDoc settings = doc("""
            "show":"entity.healthPercent == 75 && viewer.level > 1",
            "lines":[
              {"id":"stats","text":"<gold>{{ entity.armor + 2 }}</gold> |probe|"},
              {"id":"gap","type":"spacer"},
              {"id":"detail","type":"insight","text":"&bDetail: {insight}"},
              {"id":"footer","text":"{{ entity.type }} {name} {distance} :spark:"},
              {"id":"hidden","text":"hidden","show":"!entity.named"}
            ]
            """);
        TextPipeline pipeline = new TextPipeline(null);
        pipeline.registerFunction("probe", ignored -> "function");
        pipeline.setEmojiFilter(source -> source.replace(":spark:", "*"));
        EntityOverlayText.Prepared prepared = EntityOverlayText.prepare(pipeline, null, null,
            scope(Map.of("viewer.level", 3.0)), settings, NAMED, List.of("First", "Second"));
        assertEquals("6 function\n \nDetail: First\nDetail: Second\nzombie Sentinel 3.5 *", plain(prepared.frame(0)));
    }

    @Test
    void runtimeNamesAndInsightCannotExecuteAnyTemplateSyntax() {
        AtomicInteger calls = new AtomicInteger();
        TextPipeline pipeline = new TextPipeline(null);
        pipeline.registerFunction("probe", ignored -> { calls.incrementAndGet(); return "executed"; });
        pipeline.setEmojiFilter(source -> source.replace(":spark:", "executed"));
        String payload = "|probe| {{ 1 + 2 }} %player_name% <red>Name</red> <particles:bad>X</particles> :spark:";
        EntityOverlayText.Snapshot entity = new EntityOverlayText.Snapshot(payload, 1, 2, 1, 0, 0, 0, 1, "zombie", 1);
        EntityOverlayDoc settings = doc("""
            "lines":[
              {"id":"name","text":"<gradient:red:blue>{name}</gradient>"},
              {"id":"expression","text":"{{ entity.name }}"},
              {"id":"insight","type":"insight","text":"{insight}"}
            ]
            """);
        ParticleText.Rendered frame = EntityOverlayText.prepare(pipeline, null, null, scope(Map.of()),
            settings, entity, List.of(payload)).frame(0);
        assertEquals(String.join("\n", payload, payload, payload), plain(frame));
        assertEquals(0, calls.get());
        assertTrue(frame.spans().isEmpty());
    }

    @Test
    void authoredParticleSpansRetainNamedContentAndFormattedOffsets() {
        ParticleText.Rendered frame = render(doc("""
            "lines":[{"id":"name","text":"<gradient:red:blue><particles:name>{name}</particles></gradient>"}]
            """), NAMED, List.of());
        assertEquals("Sentinel", plain(frame));
        assertEquals(1, frame.spans().size());
        ParticleText.Span span = frame.spans().getFirst();
        assertEquals("name", span.name());
        assertEquals("Sentinel", ChatColor.stripColor(frame.text().substring(span.start(), span.end())));
    }

    @Test
    void hiddenRootSkipsAllRenderingAndEmptyLayoutsStayHidden() {
        AtomicInteger calls = new AtomicInteger();
        TextPipeline pipeline = new TextPipeline(null);
        pipeline.registerFunction("probe", ignored -> { calls.incrementAndGet(); return "executed"; });
        EntityOverlayText.Prepared hidden = EntityOverlayText.prepare(pipeline, null, null, scope(Map.of()),
            doc("\"show\":false,\"lines\":[{\"id\":\"test\",\"text\":\"|probe|\"}]"), NAMED, List.of());
        assertFalse(hidden.visible());
        assertEquals("", hidden.frame(0).text());
        assertEquals(0, calls.get());
        assertFalse(EntityOverlayText.prepare(pipeline, null, null, scope(Map.of()),
            doc("\"lines\":[]"), NAMED, List.of()).visible());
    }

    @Test
    void healthClampsAndTinyLivingHealthRetainsOneSegment() {
        assertEquals("&a||||||||||&c&8", EntityOverlayText.bar(EntityOverlayDoc.DEFAULTS,
            new EntityOverlayText.Snapshot(null, 30, 20, 100, 0, 0, 0, 1, "zombie", 0)));
        assertEquals("&c|&c&8|||||||||", EntityOverlayText.bar(EntityOverlayDoc.DEFAULTS,
            new EntityOverlayText.Snapshot(null, 0.01, 20, 0.01, 0, 0, 0, 1, "zombie", 0)));
    }

    @Test
    void unchangedDefaultDataCanReusePreparedOutput() {
        assertFalse(EntityOverlayText.refreshRequired(EntityOverlayDoc.DEFAULTS));
        assertTrue(EntityOverlayText.refreshRequired(doc("\"show\":\"viewer.level > 1\"")));
        assertTrue(EntityOverlayText.refreshRequired(doc("\"lines\":[{\"id\":\"clock\",\"text\":\"|animation.clock|\"}]")));
        assertTrue(EntityOverlayText.refreshRequired(doc("\"lines\":[{\"id\":\"clock\",\"text\":\"{{ time.seconds }}\"}]")));
    }

    @Test
    void personalTextIsOnlyRequiredWhenTheRenderActuallyVariesByViewer() {
        assertFalse(EntityOverlayText.personalRequired(EntityOverlayDoc.DEFAULTS, SHARED_CLIPS));
        assertFalse(EntityOverlayText.personalRequired(doc("\"show\":\"entity.health > 1\""), SHARED_CLIPS));
        assertTrue(EntityOverlayText.personalRequired(
            doc("\"lines\":[{\"id\":\"who\",\"text\":\"%player_name%\"}]"), SHARED_CLIPS));
        assertTrue(EntityOverlayText.personalRequired(
            doc("\"lines\":[{\"id\":\"who\",\"text\":\"|viewer|\"}]"), SHARED_CLIPS));
        assertTrue(EntityOverlayText.personalRequired(doc("\"show\":\"viewer.level > 1\""), SHARED_CLIPS));
        assertTrue(EntityOverlayText.personalRequired(
            doc("\"lines\":[{\"id\":\"far\",\"text\":\"{distance}\"}]"), SHARED_CLIPS));
    }

    @Test
    void animationClipsDecideSharingThroughTheAnimationService() {
        EntityOverlayDoc animated = doc("\"lines\":[{\"id\":\"clock\",\"text\":\"|animation.clock|\"}]");

        assertFalse(EntityOverlayText.personalRequired(animated, SHARED_CLIPS));
        assertTrue(EntityOverlayText.personalRequired(animated,
            line -> line.contains("|animation.clock|")));
    }

    private static ParticleText.Rendered render(EntityOverlayDoc settings, EntityOverlayText.Snapshot entity,
                                                List<String> insight) {
        return EntityOverlayText.prepare(new TextPipeline(null), null, null, scope(Map.of()),
            settings, entity, insight).frame(0);
    }

    private static EntityOverlayDoc doc(String fields) {
        return EntityOverlayDoc.parse("default.json", "{\"schemaVersion\":2,\"revision\":1," + fields + "}");
    }

    private static String plain(ParticleText.Rendered frame) {
        return ChatColor.stripColor(frame.text());
    }

    private static ExprScope scope(Map<String, Object> values) {
        return new ExprScope() {
            @Override
            public Object variable(String name) {
                return values.get(name);
            }

            @Override
            public Object call(String name, List<Object> args) {
                return ExprFunctions.call(name, args);
            }
        };
    }
}
