package art.arcane.gloss.drop;

import art.arcane.gloss.GlossConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealDropAnimationPlanTest {
    private static GlossConfig.RealDrops.AnimationKeyframe keyframe(
        double tick,
        double value,
        GlossConfig.RealDrops.AnimationEasing easing
    ) {
        return new GlossConfig.RealDrops.AnimationKeyframe(tick, value, "", easing);
    }

    private static GlossConfig.RealDrops.AnimationTrack track(
        GlossConfig.RealDrops.AnimationTarget target,
        GlossConfig.RealDrops.AnimationBlend blend,
        GlossConfig.RealDrops.AnimationKeyframe... keyframes
    ) {
        return new GlossConfig.RealDrops.AnimationTrack(target, blend, List.of(keyframes));
    }

    private static GlossConfig.RealDrops.AnimationClip clip(
        GlossConfig.RealDrops.AnimationTrigger trigger,
        double duration,
        boolean loop,
        GlossConfig.RealDrops.AnimationTrack... tracks
    ) {
        return new GlossConfig.RealDrops.AnimationClip(trigger, duration, loop, List.of(tracks));
    }

    private static GlossConfig.RealDrops.AnimationProfile profile(
        String id,
        int priority,
        List<String> materials,
        GlossConfig.RealDrops.AnimationClip... clips
    ) {
        return new GlossConfig.RealDrops.AnimationProfile(id, priority, materials, List.of(clips));
    }

    private static GlossConfig.RealDrops.RealDropAnimation animation(
        List<GlossConfig.RealDrops.AnimationProfile> profiles
    ) {
        return new GlossConfig.RealDrops.RealDropAnimation(true, Map.of(), profiles);
    }

    private static RealDropAnimationPlan.AnimationSample sample(
        GlossConfig.RealDrops.AnimationEasing easing,
        double tick
    ) {
        GlossConfig.RealDrops.AnimationTrack track = track(
            GlossConfig.RealDrops.AnimationTarget.OFFSET_X,
            GlossConfig.RealDrops.AnimationBlend.REPLACE,
            keyframe(0.0D, 0.0D, GlossConfig.RealDrops.AnimationEasing.LINEAR),
            keyframe(10.0D, 10.0D, easing));
        RealDropAnimationPlan plan = RealDropAnimationPlan.compile(animation(List.of(
            profile("default", 0, List.of("*"),
                clip(GlossConfig.RealDrops.AnimationTrigger.SPAWN, 10.0D, false, track)))));
        return plan.sample("STONE", GlossConfig.RealDrops.AnimationTrigger.SPAWN, tick);
    }

    @Test
    void easingPresetsProduceDeterministicSegments() {
        assertEquals(5.0D, sample(GlossConfig.RealDrops.AnimationEasing.LINEAR, 5.0D).offsetX(), 0.000001D);
        assertEquals(0.0D, sample(GlossConfig.RealDrops.AnimationEasing.HOLD, 5.0D).offsetX(), 0.000001D);
        assertEquals(1.25D, sample(GlossConfig.RealDrops.AnimationEasing.EASE_IN, 5.0D).offsetX(), 0.000001D);
        assertEquals(8.75D, sample(GlossConfig.RealDrops.AnimationEasing.EASE_OUT, 5.0D).offsetX(), 0.000001D);
        assertEquals(5.0D, sample(GlossConfig.RealDrops.AnimationEasing.EASE_IN_OUT, 5.0D).offsetX(), 0.000001D);
        assertTrue(sample(GlossConfig.RealDrops.AnimationEasing.BACK_OUT, 8.0D).offsetX() > 10.0D);
    }

    @Test
    void orderedTracksBlendAndMultipleActiveClipsCompose() {
        GlossConfig.RealDrops.AnimationClip spawn = clip(
            GlossConfig.RealDrops.AnimationTrigger.SPAWN,
            20.0D,
            false,
            track(
                GlossConfig.RealDrops.AnimationTarget.OFFSET_X,
                GlossConfig.RealDrops.AnimationBlend.REPLACE,
                keyframe(0.0D, 4.0D, GlossConfig.RealDrops.AnimationEasing.LINEAR)),
            track(
                GlossConfig.RealDrops.AnimationTarget.OFFSET_X,
                GlossConfig.RealDrops.AnimationBlend.ADD,
                keyframe(0.0D, 2.0D, GlossConfig.RealDrops.AnimationEasing.LINEAR)),
            track(
                GlossConfig.RealDrops.AnimationTarget.SCALE_X,
                GlossConfig.RealDrops.AnimationBlend.REPLACE,
                keyframe(0.0D, 2.0D, GlossConfig.RealDrops.AnimationEasing.LINEAR)),
            track(
                GlossConfig.RealDrops.AnimationTarget.SCALE_X,
                GlossConfig.RealDrops.AnimationBlend.MULTIPLY,
                keyframe(0.0D, 3.0D, GlossConfig.RealDrops.AnimationEasing.LINEAR)));
        GlossConfig.RealDrops.AnimationClip airborne = clip(
            GlossConfig.RealDrops.AnimationTrigger.AIRBORNE,
            10.0D,
            true,
            track(
                GlossConfig.RealDrops.AnimationTarget.OFFSET_Y,
                GlossConfig.RealDrops.AnimationBlend.ADD,
                keyframe(0.0D, 0.0D, GlossConfig.RealDrops.AnimationEasing.LINEAR),
                keyframe(10.0D, 10.0D, GlossConfig.RealDrops.AnimationEasing.LINEAR)));
        RealDropAnimationPlan plan = RealDropAnimationPlan.compile(animation(List.of(
            profile("default", 0, List.of("*"), spawn, airborne))));

        RealDropAnimationPlan.AnimationSample value = plan.sample(
            "STONE",
            List.of(
                new RealDropAnimationPlan.ActiveClip(GlossConfig.RealDrops.AnimationTrigger.SPAWN, 0.0D),
                new RealDropAnimationPlan.ActiveClip(GlossConfig.RealDrops.AnimationTrigger.AIRBORNE, 15.0D)));

        assertEquals(6.0D, value.offsetX(), 0.000001D);
        assertEquals(5.0D, value.offsetY(), 0.000001D);
        assertEquals(6.0D, value.scaleX(), 0.000001D);
    }

    @Test
    void priorityProfilesAndMaterialPropertyMapsResolveByMaterial() {
        Map<String, GlossConfig.RealDrops.MaterialProperties> lights = new LinkedHashMap<>();
        lights.put("SOUL_TORCH", new GlossConfig.RealDrops.MaterialProperties(0xFF55FFFFL, 10.0D));
        lights.put("TORCH", new GlossConfig.RealDrops.MaterialProperties(0xFFFF8800L, 14.0D));
        lights.put("*_TORCH", new GlossConfig.RealDrops.MaterialProperties(0xFFFF8800L, 14.0D));
        Map<String, Map<String, GlossConfig.RealDrops.MaterialProperties>> properties =
            Map.of("lightSources", lights);
        GlossConfig.RealDrops.AnimationKeyframe mapped = new GlossConfig.RealDrops.AnimationKeyframe(
            0.0D,
            0.0D,
            "lightSources",
            GlossConfig.RealDrops.AnimationEasing.HOLD);
        GlossConfig.RealDrops.AnimationClip lightClip = clip(
            GlossConfig.RealDrops.AnimationTrigger.SPAWN,
            20.0D,
            false,
            track(
                GlossConfig.RealDrops.AnimationTarget.GLOW,
                GlossConfig.RealDrops.AnimationBlend.REPLACE,
                mapped),
            track(
                GlossConfig.RealDrops.AnimationTarget.LIGHT_LEVEL,
                GlossConfig.RealDrops.AnimationBlend.REPLACE,
                mapped));
        GlossConfig.RealDrops.AnimationProfile fallback = profile(
            "fallback",
            0,
            List.of("*"),
            clip(
                GlossConfig.RealDrops.AnimationTrigger.SPAWN,
                1.0D,
                false,
                track(
                    GlossConfig.RealDrops.AnimationTarget.GLOW,
                    GlossConfig.RealDrops.AnimationBlend.REPLACE,
                    keyframe(0.0D, 7.0D, GlossConfig.RealDrops.AnimationEasing.HOLD))));
        GlossConfig.RealDrops.RealDropAnimation animation = new GlossConfig.RealDrops.RealDropAnimation(
            true,
            properties,
            List.of(fallback, profile("lights", 100, List.of("TORCH", "*_TORCH"), lightClip)));
        RealDropAnimationPlan plan = RealDropAnimationPlan.compile(animation);

        RealDropAnimationPlan.AnimationSample soul = plan.sample(
            "minecraft:soul_torch",
            GlossConfig.RealDrops.AnimationTrigger.SPAWN,
            0.0D);
        RealDropAnimationPlan.AnimationSample torch = plan.sample(
            "TORCH",
            GlossConfig.RealDrops.AnimationTrigger.SPAWN,
            0.0D);
        RealDropAnimationPlan.AnimationSample stone = plan.sample(
            "STONE",
            GlossConfig.RealDrops.AnimationTrigger.SPAWN,
            0.0D);

        assertEquals("lights", soul.profileId());
        assertEquals(0xFF55FFFFL, soul.glowArgb());
        assertEquals(10, soul.lightLevel());
        assertEquals(0xFFFF8800L, torch.glowArgb());
        assertEquals(14, torch.lightLevel());
        assertEquals("fallback", stone.profileId());
        assertEquals(7L, stone.glowArgb());
    }

    @Test
    void physicsAndVisibilityAreSampledAsBooleanTracks() {
        RealDropAnimationPlan plan = RealDropAnimationPlan.compile(animation(List.of(
            profile(
                "default",
                0,
                List.of("*"),
                clip(
                    GlossConfig.RealDrops.AnimationTrigger.SPAWN,
                    10.0D,
                    false,
                    track(
                        GlossConfig.RealDrops.AnimationTarget.PHYSICS,
                        GlossConfig.RealDrops.AnimationBlend.REPLACE,
                        keyframe(0.0D, 0.0D, GlossConfig.RealDrops.AnimationEasing.HOLD),
                        keyframe(5.0D, 1.0D, GlossConfig.RealDrops.AnimationEasing.LINEAR)),
                    track(
                        GlossConfig.RealDrops.AnimationTarget.VISIBLE,
                        GlossConfig.RealDrops.AnimationBlend.REPLACE,
                        keyframe(0.0D, 1.0D, GlossConfig.RealDrops.AnimationEasing.HOLD),
                        keyframe(5.0D, 0.0D, GlossConfig.RealDrops.AnimationEasing.LINEAR)))))));

        RealDropAnimationPlan.AnimationSample held = plan.sample(
            "STONE",
            GlossConfig.RealDrops.AnimationTrigger.SPAWN,
            0.0D);
        RealDropAnimationPlan.AnimationSample released = plan.sample(
            "STONE",
            GlossConfig.RealDrops.AnimationTrigger.SPAWN,
            5.0D);

        assertFalse(held.physics());
        assertTrue(held.visible());
        assertTrue(released.physics());
        assertFalse(released.visible());
    }

    @Test
    void activityMetadataStopsSettledPollingFromSamplingStaticClips() {
        RealDropAnimationPlan plan = RealDropAnimationPlan.compile(animation(List.of(
            profile(
                "default",
                0,
                List.of("*"),
                clip(
                    GlossConfig.RealDrops.AnimationTrigger.SETTLED,
                    20.0D,
                    false,
                    track(
                        GlossConfig.RealDrops.AnimationTarget.OFFSET_Y,
                        GlossConfig.RealDrops.AnimationBlend.ADD,
                        keyframe(0.0D, 0.0D, GlossConfig.RealDrops.AnimationEasing.LINEAR),
                        keyframe(5.0D, 1.0D, GlossConfig.RealDrops.AnimationEasing.EASE_OUT),
                        keyframe(15.0D, 1.0D, GlossConfig.RealDrops.AnimationEasing.HOLD),
                        keyframe(20.0D, 0.0D, GlossConfig.RealDrops.AnimationEasing.EASE_IN)))))));

        assertEquals(20.0D, plan.clipDurationTicks(
            "STONE",
            GlossConfig.RealDrops.AnimationTrigger.SETTLED));
        assertTrue(plan.requiresContinuousUpdates(
            "STONE",
            GlossConfig.RealDrops.AnimationTrigger.SETTLED,
            2.0D));
        assertFalse(plan.requiresContinuousUpdates(
            "STONE",
            GlossConfig.RealDrops.AnimationTrigger.SETTLED,
            10.0D));
        assertTrue(plan.requiresContinuousUpdates(
            "STONE",
            GlossConfig.RealDrops.AnimationTrigger.SETTLED,
            18.0D));
        assertFalse(plan.requiresContinuousUpdates(
            "STONE",
            GlossConfig.RealDrops.AnimationTrigger.SETTLED,
            20.0D));
        assertEquals(-1.0D, plan.clipDurationTicks(
            "STONE",
            GlossConfig.RealDrops.AnimationTrigger.BOUNCE));
    }

    @Test
    void invalidBlendsAndMissingMaterialMapsAreRejected() {
        GlossConfig.RealDrops.AnimationTrack badBlend = track(
            GlossConfig.RealDrops.AnimationTarget.GLOW,
            GlossConfig.RealDrops.AnimationBlend.ADD,
            keyframe(0.0D, 1.0D, GlossConfig.RealDrops.AnimationEasing.LINEAR));
        assertThrows(IllegalArgumentException.class, () -> RealDropAnimationPlan.compile(animation(List.of(
            profile("default", 0, List.of("*"),
                clip(GlossConfig.RealDrops.AnimationTrigger.SPAWN, 1.0D, false, badBlend))))));

        GlossConfig.RealDrops.AnimationTrack missingMap = track(
            GlossConfig.RealDrops.AnimationTarget.GLOW,
            GlossConfig.RealDrops.AnimationBlend.REPLACE,
            new GlossConfig.RealDrops.AnimationKeyframe(
                0.0D,
                1.0D,
                "missing",
                GlossConfig.RealDrops.AnimationEasing.LINEAR));
        assertThrows(IllegalArgumentException.class, () -> RealDropAnimationPlan.compile(animation(List.of(
            profile("default", 0, List.of("*"),
                clip(GlossConfig.RealDrops.AnimationTrigger.SPAWN, 1.0D, false, missingMap))))));
    }

    @Test
    void exaggeratedExampleParsesAndExpressesItsFullSequence() throws IOException {
        InputStream input = RealDropAnimationPlanTest.class.getResourceAsStream(
            "/examples/real-drops/exaggerated-animation.json");
        assertTrue(input != null);
        String raw;
        try (InputStream closeable = input) {
            raw = new String(closeable.readAllBytes(), StandardCharsets.UTF_8);
        }
        RealDropSettingsDoc document = RealDropSettingsDoc.parse("exaggerated-animation.json", raw);
        RealDropAnimationPlan plan = RealDropAnimationPlan.compile(document.toConfig(true).animation());

        RealDropAnimationPlan.AnimationSample huge = plan.sample(
            "TORCH",
            GlossConfig.RealDrops.AnimationTrigger.SPAWN,
            0.0D);
        RealDropAnimationPlan.AnimationSample small = plan.sample(
            "TORCH",
            GlossConfig.RealDrops.AnimationTrigger.SPAWN,
            6.0D);
        RealDropAnimationPlan.AnimationSample hugeAgain = plan.sample(
            "TORCH",
            GlossConfig.RealDrops.AnimationTrigger.SPAWN,
            12.0D);
        RealDropAnimationPlan.AnimationSample hover = plan.sample(
            "TORCH",
            GlossConfig.RealDrops.AnimationTrigger.SPAWN,
            30.0D);
        RealDropAnimationPlan.AnimationSample released = plan.sample(
            "TORCH",
            GlossConfig.RealDrops.AnimationTrigger.SPAWN,
            40.0D);
        RealDropAnimationPlan.AnimationSample spiral = plan.sample(
            "TORCH",
            List.of(
                new RealDropAnimationPlan.ActiveClip(GlossConfig.RealDrops.AnimationTrigger.SPAWN, 40.0D),
                new RealDropAnimationPlan.ActiveClip(GlossConfig.RealDrops.AnimationTrigger.AIRBORNE, 5.0D)));

        assertEquals(4.0D, huge.scaleX(), 0.000001D);
        assertEquals(0.15D, small.scaleX(), 0.000001D);
        assertEquals(4.0D, hugeAgain.scaleX(), 0.000001D);
        assertTrue(hover.offsetY() > 1.9D);
        assertFalse(hover.physics());
        assertTrue(released.physics());
        assertEquals(0xFFFF8800L, huge.glowArgb());
        assertEquals(14, huge.lightLevel());
        assertTrue(spiral.rotationZ() > 0.0D);
        assertTrue(released.offsetY() < hover.offsetY());
    }
}
