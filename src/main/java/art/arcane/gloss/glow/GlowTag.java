package art.arcane.gloss.glow;

import java.util.Locale;
import java.util.Objects;

/**
 * One claim on an entity's outline for one viewer. Priority decides which claim the viewer
 * actually sees, so a quest target's colour beats a party member's without either owner knowing
 * about the other.
 */
public record GlowTag(String purpose, int priority, String color, long expiresAtMs) {
    public GlowTag {
        purpose = Objects.requireNonNull(purpose, "purpose").trim();
        if (purpose.isEmpty() || purpose.length() > 64) {
            throw new IllegalArgumentException("a glow purpose must be 1 to 64 characters");
        }
        color = color == null || color.isBlank() ? "white" : color.trim().toLowerCase(Locale.ROOT);
    }

    public boolean expired(long nowMs) {
        return expiresAtMs > 0L && nowMs >= expiresAtMs;
    }
}
