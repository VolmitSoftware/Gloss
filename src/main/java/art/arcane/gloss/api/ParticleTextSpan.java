package art.arcane.gloss.api;

import java.util.Locale;

public record ParticleTextSpan(String name, int start, int end) {
    public ParticleTextSpan {
        name = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        if (name.isEmpty() || name.length() > 64 || !name.matches("[a-z0-9][a-z0-9._-]*")) {
            throw new IllegalArgumentException(
                "particle text span name must match [a-z0-9][a-z0-9._-]* and be at most 64 characters");
        }
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("particle text span range is invalid");
        }
    }
}
