package art.arcane.gloss.api;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record HealthBarStyle(String glyph, String emptyGlyph, String healthyColor, String warningColor,
                             String criticalColor, String damageColor, String emptyColor,
                             Double warningThreshold, Double criticalThreshold, Integer decimals) {
    public static final HealthBarStyle DEFAULTS = new HealthBarStyle(null, null, null, null, null,
        null, null, null, null, null);

    public HealthBarStyle {
        glyph = glyph == null ? "|" : glyph;
        emptyGlyph = emptyGlyph == null ? glyph : emptyGlyph;
        healthyColor = healthyColor == null ? "&a" : healthyColor;
        warningColor = warningColor == null ? "&e" : warningColor;
        criticalColor = criticalColor == null ? "&c" : criticalColor;
        damageColor = damageColor == null ? "&c" : damageColor;
        emptyColor = emptyColor == null ? "&8" : emptyColor;
        warningThreshold = threshold(warningThreshold, 0.5D);
        criticalThreshold = Math.min(warningThreshold, threshold(criticalThreshold, 0.25D));
        decimals = decimals == null ? 1 : Math.clamp(decimals, 0, 6);
        if (glyph.length() > 64 || emptyGlyph.length() > 64) {
            throw new IllegalArgumentException("health bar glyphs must be at most 64 characters");
        }
    }

    public String render(int segments, double health, double maximum, double previous) {
        int count = Math.clamp(segments, 1, 40);
        double fraction = maximum > 0 ? Math.clamp(health / maximum, 0, 1) : 0;
        double priorFraction = maximum > 0 ? Math.clamp(previous / maximum, 0, 1) : fraction;
        int filled = (int) Math.ceil(fraction * count);
        int prior = Math.max(filled, (int) Math.ceil(priorFraction * count));
        String color = fraction >= warningThreshold ? healthyColor
            : fraction >= criticalThreshold ? warningColor : criticalColor;
        return color + glyph.repeat(filled) + damageColor + glyph.repeat(prior - filled)
            + emptyColor + emptyGlyph.repeat(count - prior);
    }

    public String number(double value) {
        return BigDecimal.valueOf(Double.isFinite(value) ? value : 0)
            .setScale(decimals, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private static double threshold(Double value, double fallback) {
        return value == null || !Double.isFinite(value) ? fallback : Math.clamp(value, 0, 1);
    }
}
