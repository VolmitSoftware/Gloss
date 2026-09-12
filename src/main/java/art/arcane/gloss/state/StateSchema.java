package art.arcane.gloss.state;

import java.util.Objects;
import java.util.regex.Pattern;

/** One declared state key: its scope, its value type and the value a read returns before any write. */
public record StateSchema(String key, StateScope scope, StateType type, Object defaultValue) {
    public static final Pattern KEY = Pattern.compile("[a-z][a-z0-9_.]*");

    public StateSchema {
        key = requireKey(key);
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(type, "type");
        defaultValue = type.coerce(defaultValue);
    }

    public static String requireKey(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("state key must match [a-z][a-z0-9_.]*: " + key);
        }
        return key;
    }

    public boolean agreesWith(StateSchema other) {
        return scope == other.scope && type == other.type && defaultValue.equals(other.defaultValue);
    }
}
