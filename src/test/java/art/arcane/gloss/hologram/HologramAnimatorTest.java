package art.arcane.gloss.hologram;

import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.animation.AnimationClip;
import art.arcane.gloss.animation.AnimationMode;
import art.arcane.gloss.config.GlossConfigFile;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HologramAnimatorTest {
    private record Sent(List<Player> viewers, int entityId, String text, TextCodec codec) {
    }

    private static final class RecordingSender implements AnimationTextSender {
        private final List<Sent> sent = new ArrayList<>();

        @Override
        public void send(List<Player> viewers, int entityId, String legacyText, TextCodec codec) {
            sent.add(new Sent(List.copyOf(viewers), entityId, legacyText, codec));
        }
    }

    private static final AnimationClip FAST = new AnimationClip("fast", 100.0D, AnimationMode.ASCEND, List.of("A", "B", "C", "D"));
    private static final AnimationClip SLOW = new AnimationClip("rainbow", 2.0D, AnimationMode.ASCEND, List.of("R", "G"));
    private static final UnaryOperator<String> IDENTITY_RENDERER = raw -> raw;

    private static GlossConfig config(boolean highFrequency) {
        return config(highFrequency, 20_000);
    }

    private static GlossConfig config(boolean highFrequency, int packetBudget) {
        GlossConfigFile file = new GlossConfigFile();
        file.holograms.highFrequencyAnimations = highFrequency;
        file.holograms.animationPacketBudget = packetBudget;
        file.normalize();
        return GlossConfig.from(file);
    }

    private static HologramAnimator animator(GlossConfig config, RecordingSender sender) {
        Map<String, AnimationClip> clips = Map.of("animation.fast", FAST, "animation.rainbow", SLOW);
        Predicate<String> isFunction = clips::containsKey;
        Function<String, AnimationClip> resolver = clips::get;
        return new HologramAnimator(() -> config, isFunction, resolver, sender);
    }

    private static Player player(UUID id, boolean online) {
        return (Player) Proxy.newProxyInstance(
            Player.class.getClassLoader(),
            new Class<?>[]{Player.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> id;
                case "isOnline" -> online;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "Player[" + id + "]";
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }

    @Test
    void fastClipLinesCompileToTemplates() {
        HologramAnimator animator = animator(config(true), new RecordingSender());
        AnimationTemplate template = animator.compileTemplate(List.of("hi |animation.fast|"), IDENTITY_RENDERER);

        assertNotNull(template);
        assertTrue(template.hasSlots());
    }

    @Test
    void slowClipLinesStayOnTheTickPath() {
        HologramAnimator animator = animator(config(true), new RecordingSender());

        assertNull(animator.compileTemplate(List.of("hi |animation.rainbow|"), IDENTITY_RENDERER));
    }

    @Test
    void disabledKnobRoutesEverythingToTheTickPath() {
        HologramAnimator animator = animator(config(false), new RecordingSender());

        assertNull(animator.compileTemplate(List.of("hi |animation.fast|"), IDENTITY_RENDERER));
    }

    @Test
    void linesWithoutAnimationTokensCompileToNothing() {
        HologramAnimator animator = animator(config(true), new RecordingSender());

        assertNull(animator.compileTemplate(List.of("plain", "%papi% only"), IDENTITY_RENDERER));
    }

    @Test
    void passSendsOnlyWhenComposedTextChanges() {
        RecordingSender sender = new RecordingSender();
        HologramAnimator animator = animator(config(true), sender);
        AnimationTemplate template = animator.compileTemplate(List.of("|animation.fast|"), IDENTITY_RENDERER);
        Player viewer = player(UUID.randomUUID(), true);
        animator.publish("holo:test", HologramAnimator.SHARED_SUB, new HologramAnimator.Target(7, template, List.of(viewer)));

        assertEquals(1, animator.pass(0L));
        assertEquals(0, animator.pass(0L));
        assertEquals(0, animator.pass(5L));
        assertEquals(1, animator.pass(10L));
        assertEquals(2, sender.sent.size());
        assertEquals("A", sender.sent.get(0).text());
        assertEquals("B", sender.sent.get(1).text());
        assertEquals(7, sender.sent.get(0).entityId());
        assertEquals(List.of(viewer), sender.sent.get(0).viewers());
    }

    @Test
    void newViewersReceiveTheCurrentTextWithoutAFrameChange() {
        RecordingSender sender = new RecordingSender();
        HologramAnimator animator = animator(config(true), sender);
        AnimationTemplate template = animator.compileTemplate(List.of("|animation.fast|"), IDENTITY_RENDERER);
        Player first = player(UUID.randomUUID(), true);
        Player second = player(UUID.randomUUID(), true);
        animator.publish("holo:test", HologramAnimator.SHARED_SUB, new HologramAnimator.Target(7, template, List.of(first)));

        assertEquals(1, animator.pass(0L));
        animator.publish("holo:test", HologramAnimator.SHARED_SUB, new HologramAnimator.Target(7, template, List.of(first, second)));
        assertEquals(1, animator.pass(0L));
        assertEquals(List.of(second), sender.sent.get(1).viewers());
        assertEquals("A", sender.sent.get(1).text());
        assertEquals(0, animator.pass(0L));
    }

    @Test
    void offlineViewersAreSkipped() {
        RecordingSender sender = new RecordingSender();
        HologramAnimator animator = animator(config(true), sender);
        AnimationTemplate template = animator.compileTemplate(List.of("|animation.fast|"), IDENTITY_RENDERER);
        Player offline = player(UUID.randomUUID(), false);
        animator.publish("holo:test", HologramAnimator.SHARED_SUB, new HologramAnimator.Target(7, template, List.of(offline)));

        assertEquals(0, animator.pass(0L));
        assertTrue(sender.sent.isEmpty());
    }

    @Test
    void removeGroupRetractsAllTargets() {
        RecordingSender sender = new RecordingSender();
        HologramAnimator animator = animator(config(true), sender);
        AnimationTemplate template = animator.compileTemplate(List.of("|animation.fast|"), IDENTITY_RENDERER);
        Player viewer = player(UUID.randomUUID(), true);
        animator.publish("holo:a", HologramAnimator.SHARED_SUB, new HologramAnimator.Target(1, template, List.of(viewer)));
        animator.publish("holo:a", viewer.getUniqueId().toString(), new HologramAnimator.Target(2, template, List.of(viewer)));
        animator.publish("holo:b", HologramAnimator.SHARED_SUB, new HologramAnimator.Target(3, template, List.of(viewer)));

        assertEquals(3, animator.targetCount());
        animator.removeGroup("holo:a");
        assertEquals(1, animator.targetCount());
        assertEquals(1, animator.pass(0L));
        assertEquals(3, sender.sent.get(0).entityId());
    }

    @Test
    void packetBudgetThrottlesLargeAudienceSends() {
        RecordingSender sender = new RecordingSender();
        HologramAnimator animator = animator(config(true, 100), sender);
        AnimationTemplate template = animator.compileTemplate(List.of("|animation.fast|"), IDENTITY_RENDERER);
        Player first = player(UUID.randomUUID(), true);
        Player second = player(UUID.randomUUID(), true);
        animator.publish("holo:test", HologramAnimator.SHARED_SUB, new HologramAnimator.Target(7, template, List.of(first, second)));

        assertEquals(1, animator.pass(0L));
        assertEquals(0, animator.pass(10L));
        assertEquals(1, animator.pass(20L));
        assertEquals("A", sender.sent.get(0).text());
        assertEquals("C", sender.sent.get(1).text());
    }

    @Test
    void mixedFastAndSlowLinesRefreshSlowContentThroughTheTemplate() {
        RecordingSender sender = new RecordingSender();
        HologramAnimator animator = animator(config(true), sender);
        AnimationTemplate template = animator.compileTemplate(List.of("|animation.rainbow| |animation.fast|"), IDENTITY_RENDERER);
        Player viewer = player(UUID.randomUUID(), true);
        animator.publish("holo:test", HologramAnimator.SHARED_SUB, new HologramAnimator.Target(7, template, List.of(viewer)));

        assertNotNull(template);
        assertEquals(1, animator.pass(0L));
        assertEquals("|animation.rainbow| A", sender.sent.get(0).text());
    }
}
