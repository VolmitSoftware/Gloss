package art.arcane.gloss.menu;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The {@code key=value} argument list a menu or inventory is opened with. Arguments are
 * operator or player input that ends up inside rendered text and, for a {@code server} command,
 * inside console authority, so the shape is strict and refusals are loud.
 */
public final class MenuArguments {
    public static final int MAX_ARGUMENTS = 32;
    public static final int MAX_VALUE_LENGTH = 256;

    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9_]*");

    private MenuArguments() {
    }

    public static Map<String, Object> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        Map<String, Object> parsed = new LinkedHashMap<>();
        for (String token : raw.trim().split("\\s+")) {
            int split = token.indexOf('=');
            if (split <= 0) {
                throw new IllegalArgumentException("menu argument must be key=value: " + token);
            }
            String key = token.substring(0, split);
            if (!KEY.matcher(key).matches()) {
                throw new IllegalArgumentException("menu argument key must match [a-z][a-z0-9_]*: " + key);
            }
            String value = token.substring(split + 1);
            if (value.length() > MAX_VALUE_LENGTH) {
                throw new IllegalArgumentException("menu argument " + key + " is longer than "
                    + MAX_VALUE_LENGTH + " characters");
            }
            if (parsed.size() >= MAX_ARGUMENTS && !parsed.containsKey(key)) {
                throw new IllegalArgumentException("a menu takes at most " + MAX_ARGUMENTS + " arguments");
            }
            parsed.put(key, value);
        }
        return Map.copyOf(parsed);
    }
}
