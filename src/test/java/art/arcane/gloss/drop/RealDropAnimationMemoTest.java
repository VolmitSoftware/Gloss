package art.arcane.gloss.drop;

import art.arcane.gloss.GlossConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The authored-animation timeline is sampled on every drop poll, so profile selection, clip
 * durations, and the neutral sample must not rebuild themselves on each of those polls.
 */
class RealDropAnimationMemoTest {

    @Test
    void profileSelectionIsStableAcrossRepeatedLookups() {
        RealDropAnimationPlan plan = plan(
            profile("blocks", 10, List.of("*_LOG", "STONE"), clip(4.0D)),
            profile("everything", 0, List.of("*"), clip(9.0D)));

        for (int repeat = 0; repeat < 4; repeat++) {
            assertEquals("blocks", plan.profileId("STONE"));
            assertEquals("blocks", plan.profileId("minecraft:oak_log"));
            assertEquals("everything", plan.profileId("DIAMOND"));
        }
    }

    @Test
    void clipDurationsArePrecompiledPerTriggerAsTheLongestClip() {
        RealDropAnimationPlan plan = plan(profile("default", 0, List.of("*"), clip(4.0D), clip(9.0D)));

        assertEquals(9.0D, plan.clipDurationTicks("STONE", GlossConfig.RealDrops.AnimationTrigger.SPAWN));
        assertEquals(-1.0D, plan.clipDurationTicks("STONE", GlossConfig.RealDrops.AnimationTrigger.BOUNCE));
        assertEquals(-1.0D, plan.clipDurationTicks("STONE", null));
    }

    @Test
    void materialsWithNoProfileReportNoClipDuration() {
        RealDropAnimationPlan plan = plan(profile("logs", 0, List.of("*_LOG"), clip(4.0D)));

        assertEquals(-1.0D, plan.clipDurationTicks("STONE", GlossConfig.RealDrops.AnimationTrigger.SPAWN));
        assertEquals(4.0D, plan.clipDurationTicks("OAK_LOG", GlossConfig.RealDrops.AnimationTrigger.SPAWN));
    }

    @Test
    void theNeutralSampleIsSharedInsteadOfAllocatedPerPoll() {
        assertSame(RealDropAnimationPlan.AnimationSample.neutral(""),
            RealDropAnimationPlan.AnimationSample.neutral(""));
        RealDropAnimationPlan disabled = RealDropAnimationPlan.compile(
            new GlossConfig.RealDrops.RealDropAnimation(false, Map.of(), List.of()));
        assertSame(RealDropAnimationPlan.AnimationSample.neutral(""), disabled.sample("STONE", List.of()));
    }

    private static RealDropAnimationPlan plan(GlossConfig.RealDrops.AnimationProfile... profiles) {
        return RealDropAnimationPlan.compile(
            new GlossConfig.RealDrops.RealDropAnimation(true, Map.of(), List.of(profiles)));
    }

    private static GlossConfig.RealDrops.AnimationProfile profile(String id, int priority,
                                                                  List<String> materials,
                                                                  GlossConfig.RealDrops.AnimationClip... clips) {
        return new GlossConfig.RealDrops.AnimationProfile(id, priority, materials, List.of(clips));
    }

    private static GlossConfig.RealDrops.AnimationClip clip(double durationTicks) {
        return new GlossConfig.RealDrops.AnimationClip(
            GlossConfig.RealDrops.AnimationTrigger.SPAWN, durationTicks, false,
            List.of(new GlossConfig.RealDrops.AnimationTrack(
                GlossConfig.RealDrops.AnimationTarget.OFFSET_X,
                GlossConfig.RealDrops.AnimationBlend.REPLACE,
                List.of(new GlossConfig.RealDrops.AnimationKeyframe(
                    0.0D, 0.0D, "", GlossConfig.RealDrops.AnimationEasing.LINEAR)))));
    }
}
