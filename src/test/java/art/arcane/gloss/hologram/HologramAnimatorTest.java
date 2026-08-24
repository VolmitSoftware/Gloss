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
import java.util.concurrent.atomic.AtomicInteger;
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

        private int recipientCount() {
            int recipients = 0;
            for (Sent packet : sent) {
                recipients += packet.viewers().size();
            }
            return recipients;
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
    void workerConsumesOwnerCapturedAudienceWithoutReadingPlayerState() {
        RecordingSender sender = new RecordingSender();
        HologramAnimator animator = animator(config(true), sender);
        AnimationTemplate template = animator.compileTemplate(List.of("|animation.fast|"), IDENTITY_RENDERER);
        AtomicInteger playerReads = new AtomicInteger();
        Player captured = (Player) Proxy.newProxyInstance(
            Player.class.getClassLoader(),
            new Class<?>[]{Player.class},
            (proxy, method, args) -> {
                playerReads.incrementAndGet();
                throw new AssertionError("animator worker read " + method.getName());
            });
        animator.publish("holo:test", HologramAnimator.SHARED_SUB,
            new HologramAnimator.Target(7, template, List.of(captured)));

        assertEquals(1, animator.pass(0L));
        assertEquals(0, playerReads.get());
        assertEquals(List.of(captured), sender.sent.getFirst().viewers());
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
    void packetBudgetIsSharedAcrossAnimatedDisplays() {
        RecordingSender sender = new RecordingSender();
        HologramAnimator animator = animator(config(true, 100), sender);
        AnimationTemplate template = animator.compileTemplate(List.of("|animation.fast|"), IDENTITY_RENDERER);
        Player first = player(UUID.randomUUID(), true);
        Player second = player(UUID.randomUUID(), true);
        animator.publish("holo:first", HologramAnimator.SHARED_SUB,
            new HologramAnimator.Target(7, template, List.of(first, second)));
        animator.publish("holo:second", HologramAnimator.SHARED_SUB,
            new HologramAnimator.Target(8, template, List.of(first, second)));

        assertEquals(2, animator.pass(0L));
        assertEquals(0, animator.pass(20L));
        assertEquals(2, animator.pass(50L));
        assertEquals(4, sender.sent.size());
    }

    @Test
    void packetBudgetCapsFirstPublishBurstAcrossManyTargets() {
        RecordingSender sender = new RecordingSender();
        HologramAnimator animator = animator(config(true, 100), sender);
        AnimationTemplate template = animator.compileTemplate(List.of("|animation.fast|"), IDENTITY_RENDERER);
        Player viewer = player(UUID.randomUUID(), true);
        for (int target = 0; target < 250; target++) {
            animator.publish("holo:" + target, HologramAnimator.SHARED_SUB,
                new HologramAnimator.Target(target, template, List.of(viewer)));
        }

        assertEquals(100, animator.pass(0L));
        assertEquals(100, sender.recipientCount());
        assertEquals(0, animator.pass(999L));
        assertEquals(100, sender.recipientCount());
        assertEquals(100, animator.pass(1000L));
        assertEquals(200, sender.recipientCount());
        assertEquals(50, animator.pass(2000L));
        assertEquals(250, sender.recipientCount());
    }

    @Test
    void packetBudgetRotatesFairlyAcrossLargeTargets() {
        RecordingSender sender = new RecordingSender();
        HologramAnimator animator = animator(config(true, 100), sender);
        AnimationTemplate template = animator.compileTemplate(List.of("|animation.fast|"), IDENTITY_RENDERER);
        List<Player> first = new ArrayList<>(100);
        List<Player> second = new ArrayList<>(100);
        for (int viewer = 0; viewer < 100; viewer++) {
            first.add(player(UUID.randomUUID(), true));
            second.add(player(UUID.randomUUID(), true));
        }
        animator.publish("holo:first", HologramAnimator.SHARED_SUB,
            new HologramAnimator.Target(7, template, first));
        animator.publish("holo:second", HologramAnimator.SHARED_SUB,
            new HologramAnimator.Target(8, template, second));

        assertEquals(1, animator.pass(0L));
        assertEquals(0, animator.pass(999L));
        assertEquals(1, animator.pass(1000L));
        assertEquals(200, sender.recipientCount());
        assertTrue(sender.sent.stream().anyMatch(sent -> sent.entityId() == 7));
        assertTrue(sender.sent.stream().anyMatch(sent -> sent.entityId() == 8));
    }

    @Test
    void directTextUpdatesAreLatestWinsAndBudgeted() {
        RecordingSender sender = new RecordingSender();
        HologramAnimator animator = animator(config(true, 100), sender);
        Player first = player(UUID.randomUUID(), true);
        UUID firstId = UUID.randomUUID();
        animator.sendText(first, firstId, 7, "old");
        animator.sendText(first, firstId, 7, "new");
        for (int viewer = 1; viewer < 150; viewer++) {
            UUID viewerId = UUID.randomUUID();
            animator.sendText(player(viewerId, true), viewerId, 7, "text-" + viewer);
        }

        assertEquals(150, animator.pendingTextUpdateCount());
        assertEquals(100, animator.pass(0L));
        assertEquals(100, sender.recipientCount());
        assertEquals(0, animator.pass(999L));
        assertEquals(50, animator.pass(1000L));
        assertEquals(150, sender.recipientCount());
        assertTrue(sender.sent.stream().anyMatch(sent -> sent.viewers().contains(first)
            && sent.text().equals("new")));
        assertTrue(sender.sent.stream().noneMatch(sent -> sent.text().equals("old")));
    }

    @Test
    void packetBudgetSlicesOneAudienceLargerThanTheCeiling() {
        RecordingSender sender = new RecordingSender();
        HologramAnimator animator = animator(config(true, 100), sender);
        AnimationTemplate template = animator.compileTemplate(List.of("|animation.fast|"), IDENTITY_RENDERER);
        List<Player> viewers = new ArrayList<>(150);
        for (int viewer = 0; viewer < 150; viewer++) {
            viewers.add(player(UUID.randomUUID(), true));
        }
        animator.publish("holo:test", HologramAnimator.SHARED_SUB,
            new HologramAnimator.Target(7, template, viewers));

        assertEquals(1, animator.pass(0L));
        assertEquals(100, sender.sent.get(0).viewers().size());
        assertEquals(0, animator.pass(999L));
        assertEquals(1, animator.pass(1000L));
        assertEquals(50, sender.sent.get(1).viewers().size());
        assertEquals(150, sender.recipientCount());
    }

    @Test
    void replacingAnEntityResetsItsSendState() {
        RecordingSender sender = new RecordingSender();
        HologramAnimator animator = animator(config(true), sender);
        AnimationTemplate template = animator.compileTemplate(List.of("|animation.fast|"), IDENTITY_RENDERER);
        Player viewer = player(UUID.randomUUID(), true);
        animator.publish("holo:test", HologramAnimator.SHARED_SUB,
            new HologramAnimator.Target(7, template, List.of(viewer)));

        assertEquals(1, animator.pass(0L));
        animator.publish("holo:test", HologramAnimator.SHARED_SUB,
            new HologramAnimator.Target(8, template, List.of(viewer)));

        assertEquals(1, animator.pass(0L));
        assertEquals(8, sender.sent.get(1).entityId());
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
