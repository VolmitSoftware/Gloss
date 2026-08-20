package art.arcane.gloss.hologram;

/**
 * How an animator frame becomes a component on the packet path.
 *
 * <p>{@link #AUTHORED} text is written by an operator in a document, so it keeps the MiniMessage
 * pass the render pipeline gives it everywhere else. {@link #LEGACY} text carries player typed
 * content — chat bubbles — and is deserialized with section codes only, so a message containing
 * {@code <red>} or {@code <rainbow>} stays literal instead of becoming markup.
 */
public enum TextCodec {
    AUTHORED,
    LEGACY
}
