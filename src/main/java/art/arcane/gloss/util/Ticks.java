package art.arcane.gloss.util;

public final class Ticks {
    private Ticks() {
    }

    public static int fromMs(long ms) {
        return (int) Math.max(1L, ms / 50L);
    }
}
