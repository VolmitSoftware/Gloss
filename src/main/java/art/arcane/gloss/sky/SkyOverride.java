package art.arcane.gloss.sky;

import java.util.Objects;

/**
 * One owner's claim on a viewer's sky. Any component may be null, which means "leave that part of
 * the sky alone"; the service only sends what an override actually names.
 */
public record SkyOverride(String purpose, Long time, String weather, Border border, int fadeTicks) {
    public SkyOverride {
        purpose = Objects.requireNonNull(purpose, "purpose").trim();
        if (purpose.isEmpty() || purpose.length() > 64) {
            throw new IllegalArgumentException("a sky purpose must be 1 to 64 characters");
        }
        time = time == null ? null : Math.floorMod(time, SkyFade.DAY_TICKS);
        weather = weather == null || weather.isBlank() ? null : weather.trim();
        fadeTicks = Math.clamp(fadeTicks, 0, 72000);
    }

    public record Border(double centerX, double centerZ, double size, int warningBlocks) {
        public Border {
            size = Double.isFinite(size) ? Math.clamp(size, 1.0D, 59999968.0D) : 1.0D;
            warningBlocks = Math.clamp(warningBlocks, 0, 512);
        }
    }
}
