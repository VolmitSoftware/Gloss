package art.arcane.gloss.integrate;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class MetricFormat {
    private static final String[] SUFFIXES = {"K", "M", "B", "T"};
    private static final double THOUSAND = 1000.0D;
    private static final int SMALL_DECIMALS = 2;
    private static final int SCALED_DECIMALS = 1;

    private MetricFormat() {
    }

    public static String compact(Double value) {
        if (value == null || !Double.isFinite(value)) {
            return "";
        }

        double raw = value;
        if (Math.abs(raw) < THOUSAND) {
            return plain(raw, SMALL_DECIMALS);
        }

        double scaled = raw;
        int index = -1;
        while (Math.abs(scaled) >= THOUSAND && index < SUFFIXES.length - 1) {
            scaled = scaled / THOUSAND;
            index++;
        }
        return plain(scaled, SCALED_DECIMALS) + SUFFIXES[index];
    }

    private static String plain(double value, int decimals) {
        BigDecimal rounded = BigDecimal.valueOf(value)
            .setScale(decimals, RoundingMode.HALF_UP)
            .stripTrailingZeros();
        return rounded.scale() <= 0 ? rounded.toBigInteger().toString() : rounded.toPlainString();
    }
}
