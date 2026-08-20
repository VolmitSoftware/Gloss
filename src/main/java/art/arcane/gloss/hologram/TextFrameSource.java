package art.arcane.gloss.hologram;

/**
 * Composes one animator frame of legacy coded text for a wall clock instant. The animator only
 * sends when the composed text changes, so a source is free to return the same string for as long
 * as its effect has not moved.
 */
public interface TextFrameSource {
    String compose(long nowMs);
}
