package art.arcane.gloss.api;

/**
 * One state key a plugin declares: {@code scope} is {@code player}, {@code world} or {@code global};
 * {@code type} is {@code number}, {@code string} or {@code boolean}; the default is coerced to it.
 */
public record GlossStateSpec(String key, String scope, String type, Object defaultValue) {
}
