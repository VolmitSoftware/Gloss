package art.arcane.gloss.state;

import java.util.Locale;

/**
 * The value shape of one state key. Values are the expression language's own types (a
 * {@code Double}, a {@code String}, or a {@code Boolean}) so a state read drops straight into
 * {@code {{ }}} and {@code when} without conversion.
 */
public enum StateType {
    NUMBER,
    STRING,
    BOOLEAN;

    public static StateType parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("state type must be number, string, or boolean");
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "number" -> NUMBER;
            case "string" -> STRING;
            case "boolean" -> BOOLEAN;
            default -> throw new IllegalArgumentException("state type must be number, string, or boolean: " + value);
        };
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public Object zero() {
        return switch (this) {
            case NUMBER -> 0.0D;
            case STRING -> "";
            case BOOLEAN -> Boolean.FALSE;
        };
    }

    /** Converts a document, command or API value to this type; null becomes {@link #zero()}. */
    public Object coerce(Object value) {
        if (value == null) {
            return zero();
        }
        return switch (this) {
            case NUMBER -> number(value);
            case STRING -> value instanceof Double number ? stringify(number) : String.valueOf(value);
            case BOOLEAN -> bool(value);
        };
    }

    private static Object number(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof Boolean flag) {
            return flag ? 1.0D : 0.0D;
        }
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("state value is not a number: " + value, failure);
        }
    }

    private static Object bool(Object value) {
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof Number number) {
            return number.doubleValue() != 0.0D;
        }
        String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        return switch (text) {
            case "true", "yes", "on", "1" -> Boolean.TRUE;
            case "false", "no", "off", "0" -> Boolean.FALSE;
            default -> throw new IllegalArgumentException("state value is not a boolean: " + value);
        };
    }

    private static String stringify(Double number) {
        double value = number;
        return value == Math.rint(value) && !Double.isInfinite(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}
