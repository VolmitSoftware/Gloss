package art.arcane.gloss.animation.clip;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClipPlanBezierTest {
    private static ClipPlan plan(Easing.Curve easing) {
        Track track = new Track(null, Target.OFFSET_X, Blend.REPLACE, List.of(
            new Keyframe(0.0D, 0.0D, "", Easing.Curve.of(Easing.LINEAR)),
            new Keyframe(10.0D, 1.0D, "", easing)));
        Clip clip = new Clip(Trigger.SPAWN, 10.0D, LoopMode.ONCE, List.of(track));
        Profile profile = new Profile("default", 0, List.of("*"), List.of(clip));
        return ClipPlan.compile(new ClipSet(true, Map.of(), List.of(profile)));
    }

    @Test
    void symmetricBezierSamplesHalfAtTheMidpointAndTheEndsExactly() {
        ClipPlan plan = plan(Easing.parse("bezier(0.42,0,0.58,1)"));

        assertEquals(0.0D, plan.sample("STONE", Trigger.SPAWN, 0.0D).offsetX(), 1.0E-9D);
        assertEquals(0.5D, plan.sample("STONE", Trigger.SPAWN, 5.0D).offsetX(), 1.0E-3D);
        assertEquals(1.0D, plan.sample("STONE", Trigger.SPAWN, 10.0D).offsetX(), 1.0E-9D);
    }

    @Test
    void easeInBezierStaysBelowLinearBeforeTheMidpoint() {
        ClipPlan plan = plan(Easing.parse("bezier(0.42,0,1,1)"));

        assertTrue(plan.sample("STONE", Trigger.SPAWN, 2.5D).offsetX() < 0.25D);
    }

    @Test
    void parseYieldsTheCurveForBezierAndTheEnumForNamedEasings() {
        Easing.Curve bezier = Easing.parse("bezier(0.42,0,0.58,1)");
        assertEquals(Easing.BEZIER, bezier.easing());
        assertEquals(0.42F, bezier.x1(), 1.0E-6F);
        assertEquals(0.0F, bezier.y1(), 1.0E-6F);
        assertEquals(0.58F, bezier.x2(), 1.0E-6F);
        assertEquals(1.0F, bezier.y2(), 1.0E-6F);

        assertEquals(Easing.EASE_IN, Easing.parse("ease_in").easing());
        assertEquals(Easing.EASE_IN_OUT, Easing.parse("EASE_IN_OUT").easing());
        assertEquals(Easing.LINEAR, Easing.parse(" linear ").easing());
    }

    @Test
    void malformedEasingsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> Easing.parse("bezier(0.42,0,0.58)"));
        assertThrows(IllegalArgumentException.class, () -> Easing.parse("bezier(1.5,0,0.58,1)"));
        assertThrows(IllegalArgumentException.class, () -> Easing.parse("bounce"));
        assertThrows(IllegalArgumentException.class, () -> Easing.parse(""));
    }

    @Test
    void openTriggersCarryTheDropVocabularyAndNormalizeKeys() {
        assertEquals(Trigger.SPAWN, Trigger.of("spawn"));
        assertEquals(Trigger.of("state:open"), Trigger.of(" STATE:open "));
        assertEquals("wake", Trigger.WAKE.key());
    }

    @Test
    void translationTargetsShareTheOffsetChannels() {
        assertEquals(Target.OFFSET_X.channel(), Target.TRANSLATION_X.channel());
        assertEquals(Target.OFFSET_Y.channel(), Target.TRANSLATION_Y.channel());
        assertEquals(Target.OFFSET_Z.channel(), Target.TRANSLATION_Z.channel());
        Track track = new Track("root", Target.TRANSLATION_Y, Blend.ADD, List.of(
            new Keyframe(0.0D, 0.0D, "", null),
            new Keyframe(10.0D, 2.0D, "", null)));
        Clip clip = new Clip(Trigger.of("play"), 10.0D, LoopMode.ONCE, List.of(track));
        ClipPlan plan = ClipPlan.compile(new ClipSet(true, Map.of(),
            List.of(new Profile("motion", 0, List.of("*"), List.of(clip)))));

        Map<String, ClipSample> bones = plan.sampleBones(List.of("root", "lid"),
            List.of(new ClipPlan.ActiveClip(Trigger.of("play"), 5.0D)));
        assertEquals(1.0D, bones.get("root").offsetY(), 1.0E-9D);
        assertEquals(0.0D, bones.get("lid").offsetY(), 1.0E-9D);
    }
}
