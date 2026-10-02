package art.arcane.gloss.indicator;

import java.util.Locale;
import java.util.Map;

public final class IndicatorTextFormat {
    private IndicatorTextFormat() {
    }

    static String template(String format, String amount) {
        return format.replace("{amount}", amount).replace("{cause}", "\uE100")
            .replace("{source}", "\uE101").replace("{target}", "\uE102");
    }

    static String names(String rendered, Map<String, Object> values) {
        return rendered.replace("\uE100", String.valueOf(values.getOrDefault("event.causeName", "")))
            .replace("\uE101", entityName(values, "source."))
            .replace("\uE102", entityName(values, "subject."));
    }

    private static String entityName(Map<String, Object> values, String prefix) {
        String name = String.valueOf(values.getOrDefault(prefix + "name", ""));
        return name.isBlank() ? String.valueOf(values.getOrDefault(prefix + "typeName", "")) : name;
    }

    public static String format(double amount, int decimals) {
        if (decimals <= 0) {
            return Long.toString(Math.round(amount));
        }
        return String.format(Locale.ROOT, "%." + decimals + "f", amount);
    }
}
