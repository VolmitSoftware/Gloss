package art.arcane.gloss.drop;

import art.arcane.gloss.animation.AnimationClip;
import art.arcane.gloss.animation.AnimationMode;
import art.arcane.gloss.hologram.AnimationTemplate;
import art.arcane.gloss.particle.ParticleText;
import art.arcane.gloss.text.TextPipeline;
import org.bukkit.ChatColor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DropLabelTemplateTest {
    @Test
    void itemNamesCannotExecuteGlossOrMiniMessageAndKeepSectionColors() {
        TextPipeline pipeline = new TextPipeline(null);
        DropLabelTemplate template = new DropLabelTemplate();
        String name = "§b<red>{{ player.name }} %player_name% |animation.fast| {count}</red>";
        String authored = "&73x " + template.literal(name) + " &7end";
        ParticleText.Rendered rendered = template.render(authored, source -> {
            assertFalse(source.contains("player.name"));
            assertFalse(source.contains("<red>"));
            return pipeline.renderLegacyParticleText(null, source);
        });
        assertEquals("3x " + ChatColor.stripColor(name) + " end", ChatColor.stripColor(rendered.text()));
        assertTrue(rendered.text().contains("§b<red>"));
        assertTrue(rendered.text().endsWith("§7 end"));
    }

    @Test
    void particleSpansExpandToTheResolvedNameWithoutParsingNameMarkup() {
        TextPipeline pipeline = new TextPipeline(null);
        DropLabelTemplate template = new DropLabelTemplate();
        String literal = "Long <particles:fake>item</particles>";
        String token = template.literal(literal);
        ParticleText.Rendered rendered = template.render("before <particles:item>" + token + "</particles> after",
            source -> pipeline.renderLegacyParticleText(null, source));
        assertEquals(1, rendered.spans().size());
        ParticleText.Span span = rendered.spans().getFirst();
        assertEquals("item", span.name());
        assertEquals(literal, ChatColor.stripColor(rendered.text().substring(span.start(), span.end())));
        assertEquals("before " + literal + " after", ChatColor.stripColor(rendered.text()));
    }

    @Test
    void identicalLiteralNamesKeepBundleAggregationAndDistinctNamesStaySeparate() {
        DropLabelTemplate template = new DropLabelTemplate();
        List<DropNameFormatter.BundleContent> aggregated = DropNameFormatter.aggregate(List.of(
            new DropNameFormatter.BundleContent(template.literal("Ruby"), 2),
            new DropNameFormatter.BundleContent(template.literal("Ruby"), 3),
            new DropNameFormatter.BundleContent(template.literal("Sapphire"), 1)));
        assertEquals(2, aggregated.size());
        assertEquals(5, aggregated.getFirst().amount());
    }

    @Test
    void bundleNamesSortAndMergeByResolvedNamesInsteadOfProtectedTokens() {
        TextPipeline pipeline = new TextPipeline(null);
        DropLabelTemplate template = new DropLabelTemplate();
        List<DropNameFormatter.BundleContent> contents = List.of(
            new DropNameFormatter.BundleContent(template.literal("Zircon"), 2),
            new DropNameFormatter.BundleContent("Copper", 2),
            new DropNameFormatter.BundleContent(template.literal("Amber"), 1),
            new DropNameFormatter.BundleContent("Amber", 1));
        String authored = DropNameFormatter.formatBundle("{contents}", contents, 5,
            remaining -> "+" + remaining, template::name);
        ParticleText.Rendered rendered = template.render(authored,
            source -> pipeline.renderLegacyParticleText(null, source));
        assertEquals("2x Amber, 2x Copper, 2x Zircon", ChatColor.stripColor(rendered.text()));
    }

    @Test
    void animationFramesAdvanceWithoutReevaluatingLiteralItemNames() {
        DropLabelTemplate names = new DropLabelTemplate();
        String literal = "<red>|animation.fast| {{ player.name }}";
        String authored = "|animation.fast| <particles:item>" + names.literal(literal) + "</particles>";
        AnimationClip clip = new AnimationClip("fast", 100.0D, AnimationMode.ASCEND, List.of("A", "BBBB"));
        AtomicInteger renders = new AtomicInteger();
        AnimationTemplate animation = AnimationTemplate.compile(ParticleText.parse(authored).marked(),
            name -> name.equals("animation.fast"), name -> name.equals("animation.fast") ? clip : null,
            source -> {
                renders.incrementAndGet();
                assertFalse(source.contains("player.name"));
                return source;
            });
        int compileRenders = renders.get();
        DropLabelTemplate.Frames frames = names.frames(animation);
        ParticleText.Rendered first = frames.render(0L);
        assertSame(first, frames.render(1L));
        ParticleText.Rendered second = frames.render(10L);
        assertEquals("A " + literal, ChatColor.stripColor(first.text()));
        assertEquals("BBBB " + literal, ChatColor.stripColor(second.text()));
        assertEquals(first.spans().getFirst().start() + 3, second.spans().getFirst().start());
        assertEquals(compileRenders, renders.get());
    }
}
