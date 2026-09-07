package art.arcane.gloss.hologram;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.animation.AnimationClip;
import art.arcane.gloss.hologram.CharacterizationHarness.PlayerHandle;
import art.arcane.gloss.hologram.CharacterizationHarness.RecordingSender;
import art.arcane.gloss.hologram.CharacterizationHarness.WorldState;
import art.arcane.gloss.util.common.TextUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A personalized animated temporary used to push every clip frame through the viewer's renderer
 * on every refresh; frames that do not depend on the viewer must come from the shared cache.
 */
class TemporaryHologramSharedFramesTest {
    @TempDir
    File directory;

    @Test
    void personalizedAnimatedTemporariesReuseTheSharedClipFrames() throws Exception {
        try (CharacterizationHarness harness = new CharacterizationHarness(directory)) {
            AtomicInteger sharedLookups = new AtomicInteger();
            RecordingSender sender = new RecordingSender();
            HologramAnimator animator = sharedFrameAnimator(harness, sender, sharedLookups);
            installAnimator(harness, animator);

            WorldState world = harness.world("world");
            PlayerHandle viewer = harness.join("Viewer", world, 0, 64, 0);
            TemporaryHologramDisplay temporary = harness.temporary("shared-frames",
                harness.at(world, 0, 64, 0), 60_000L);
            temporary.setLines(List.of("{{ player.name }} |animation.fast|"));
            temporary.drive(true);
            temporary.drive(true);

            assertEquals(1, animator.pass(0L));
            assertTrue(sharedLookups.get() > 0, "the shared frame cache must be consulted");
            assertEquals("Viewer SHARED-0", firstText(sender, viewer),
                "viewer independent clip frames must come from the shared cache");
        }
    }

    @Test
    void viewerDependentClipFramesStillRenderPerViewer() throws Exception {
        try (CharacterizationHarness harness = new CharacterizationHarness(directory)) {
            AtomicInteger sharedLookups = new AtomicInteger();
            RecordingSender sender = new RecordingSender();
            HologramAnimator animator = new HologramAnimator(() -> harness.config,
                name -> harness.animationClips.containsKey(name) || harness.text.hasFunction(name),
                harness.animationClips::get,
                clip -> {
                    sharedLookups.incrementAndGet();
                    return null;
                },
                sender);
            animator.stop();
            installAnimator(harness, animator);

            WorldState world = harness.world("world");
            PlayerHandle viewer = harness.join("Viewer", world, 0, 64, 0);
            TemporaryHologramDisplay temporary = harness.temporary("personal-frames",
                harness.at(world, 0, 64, 0), 60_000L);
            temporary.setLines(List.of("{{ player.name }} |animation.fast|"));
            temporary.drive(true);
            temporary.drive(true);

            assertEquals(1, animator.pass(0L));
            assertEquals("Viewer A", firstText(sender, viewer),
                "a clip the shared cache refuses must still render through the viewer");
        }
    }

    private static HologramAnimator sharedFrameAnimator(CharacterizationHarness harness,
                                                        RecordingSender sender,
                                                        AtomicInteger sharedLookups) {
        Function<AnimationClip, List<String>> shared = clip -> {
            sharedLookups.incrementAndGet();
            List<String> frames = new java.util.ArrayList<>(clip.frames().size());
            for (int index = 0; index < clip.frames().size(); index++) {
                frames.add("SHARED-" + index);
            }
            return List.copyOf(frames);
        };
        HologramAnimator animator = new HologramAnimator(() -> harness.config,
            name -> harness.animationClips.containsKey(name) || harness.text.hasFunction(name),
            harness.animationClips::get, shared, sender);
        animator.stop();
        return animator;
    }

    private static void installAnimator(CharacterizationHarness harness, HologramAnimator animator)
        throws ReflectiveOperationException {
        Field field = Gloss.class.getDeclaredField("animator");
        field.setAccessible(true);
        field.set(harness.gloss, animator);
    }

    private static String firstText(RecordingSender sender, PlayerHandle viewer) {
        for (CharacterizationHarness.Sent sent : sender.sent) {
            if (sent.viewers().contains(viewer.proxy)) {
                return TextUtils.content(TextUtils.parse(sent.text()));
            }
        }
        throw new AssertionError("Viewer received no text");
    }
}
