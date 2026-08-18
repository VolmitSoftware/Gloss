package art.arcane.gloss.indicator;

import java.util.Locale;

public final class IndicatorTextFormat {
    private IndicatorTextFormat() {
    }

    public static String format(double amount, int decimals) {
        if (decimals <= 0) {
            return Long.toString(Math.round(amount));
        }
        return String.format(Locale.ROOT, "%." + decimals + "f", amount);
    }
}
