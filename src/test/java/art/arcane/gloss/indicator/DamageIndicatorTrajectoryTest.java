package art.arcane.gloss.indicator;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DamageIndicatorTrajectoryTest {
    private static final double EPSILON = 1.0E-9D;

    @Test
    void sampleUsesClosedFormBallisticMotion() {
        DamageIndicatorSettingsDoc.Motion motion =
            new DamageIndicatorSettingsDoc.Motion(2.0D, 3.0D, -4.0D, 90.0D);
        DamageIndicatorSettingsDoc.Transform presentation =
            new DamageIndicatorSettingsDoc.Transform(1.0D, 2.0D, 0.5D);

        DamageIndicatorTrajectory.Frame frame = DamageIndicatorTrajectory.sample(
            new Vector(1.0D, 2.0D, 3.0D), motion, presentation,
            0.0D, 1.0D, 2.0D);

        assertEquals(3.0D, frame.x(), EPSILON);
        assertEquals(3.0D, frame.y(), EPSILON);
        assertEquals(3.0D, frame.z(), EPSILON);
        assertEquals(90.0D, frame.spinDegrees(), EPSILON);
        assertEquals(1.5D, frame.scale(), EPSILON);
        assertEquals(1.0D, frame.opacity(), EPSILON);
    }

    @Test
    void fadeIsLinearAfterItsConfiguredLifetimeFraction() {
        DamageIndicatorSettingsDoc.Style style = new DamageIndicatorSettingsDoc.Style(
            "true",
            new DamageIndicatorSettingsDoc.IndicatorPresentation(
                "{amount}",
                new Vector(),
                new DamageIndicatorSettingsDoc.Motion(0.0D, 0.0D, 0.0D, 0.0D),
                new DamageIndicatorSettingsDoc.Transform(1.0D, 1.0D, 0.5D), List.of()),
            List.of());

        assertEquals(1.0D, DamageIndicatorTrajectory.sample(style, 0.0D, 1.0D, 2.0D).opacity(), EPSILON);
        assertEquals(0.5D, DamageIndicatorTrajectory.sample(style, 0.0D, 1.5D, 2.0D).opacity(), EPSILON);
        assertEquals(0.0D, DamageIndicatorTrajectory.sample(style, 0.0D, 2.0D, 2.0D).opacity(), EPSILON);
    }

    @Test
    void elapsedTimeClampsToTheLifetime() {
        DamageIndicatorSettingsDoc.Style style = DamageIndicatorSettingsDoc.DEFAULTS.damage();

        assertEquals(
            DamageIndicatorTrajectory.sample(style, 1.0D, 3.0D, 3.0D),
            DamageIndicatorTrajectory.sample(style, 1.0D, 300.0D, 3.0D));
    }

    @Test
    void trajectoryDoesNotDependOnSamplingCadence() {
        DamageIndicatorSettingsDoc.Style style = DamageIndicatorSettingsDoc.DEFAULTS.damage();
        DamageIndicatorTrajectory.sample(style, 0.7D, 0.25D, 3.0D);
        DamageIndicatorTrajectory.sample(style, 0.7D, 0.9D, 3.0D);

        DamageIndicatorTrajectory.Frame sampledAfterIntermediates =
            DamageIndicatorTrajectory.sample(style, 0.7D, 1.4D, 3.0D);
        DamageIndicatorTrajectory.Frame sampledDirectly =
            DamageIndicatorTrajectory.sample(style, 0.7D, 1.4D, 3.0D);

        assertEquals(sampledDirectly, sampledAfterIntermediates);
    }
}
