package art.arcane.gloss.indicator;

import org.bukkit.util.Vector;

public final class DamageIndicatorTrajectory {
    private DamageIndicatorTrajectory() {
    }

    public static Frame sample(DamageIndicatorSettingsDoc.Style style, double angleRadians,
                               double elapsedSeconds, double lifetimeSeconds) {
        DamageIndicatorSettingsDoc.IndicatorPresentation presentation = style.presentation();
        return sample(presentation.offset(), presentation.motion(), presentation.transform(), angleRadians,
            elapsedSeconds, lifetimeSeconds);
    }

    public static Frame sample(Vector offset, DamageIndicatorSettingsDoc.Motion motion,
                               DamageIndicatorSettingsDoc.Transform presentation,
                               double angleRadians, double elapsedSeconds, double lifetimeSeconds) {
        double lifetime = Math.max(0.001D, lifetimeSeconds);
        double elapsed = Math.min(lifetime, Math.max(0.0D, elapsedSeconds));
        double progress = Math.max(0.0D, Math.min(1.0D, elapsed / lifetime));
        double horizontalDistance = motion.horizontalSpeed() * elapsed;
        double x = offset.getX() + Math.cos(angleRadians) * horizontalDistance;
        double y = offset.getY() + motion.verticalSpeed() * elapsed
            + 0.5D * motion.verticalAcceleration() * elapsed * elapsed;
        double z = offset.getZ() + Math.sin(angleRadians) * horizontalDistance;
        double scale = presentation.startScale()
            + (presentation.endScale() - presentation.startScale()) * progress;
        double opacity = opacity(progress, presentation.fadeStartFraction());
        double spin = motion.spinDegreesPerSecond() * elapsed;
        return new Frame(x, y, z, spin, scale, opacity);
    }

    private static double opacity(double progress, double fadeStartFraction) {
        if (progress <= fadeStartFraction || fadeStartFraction >= 1.0D) {
            return 1.0D;
        }
        return Math.max(0.0D, (1.0D - progress) / (1.0D - fadeStartFraction));
    }

    public record Frame(double x, double y, double z, double spinDegrees, double scale,
                        double opacity) {
    }
}
