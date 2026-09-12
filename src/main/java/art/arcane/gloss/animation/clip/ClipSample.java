package art.arcane.gloss.animation.clip;

public record ClipSample(
    String profileId,
    double offsetX,
    double offsetY,
    double offsetZ,
    double rotationX,
    double rotationY,
    double rotationZ,
    double scaleX,
    double scaleY,
    double scaleZ,
    long glowArgb,
    boolean visible,
    boolean physics,
    int lightLevel,
    double opacity,
    double brightness
) {
    public static final double NEUTRAL_OPACITY = 1.0D;
    public static final double UNSET_BRIGHTNESS = -1.0D;

    public static final ClipSample NEUTRAL = new ClipSample(
        "", 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D, 0L, true, true, 0,
        NEUTRAL_OPACITY, UNSET_BRIGHTNESS);

    public static ClipSample neutral(String profileId) {
        if (profileId.isEmpty()) {
            return NEUTRAL;
        }
        return new ClipSample(profileId, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D, 0L,
            true, true, 0, NEUTRAL_OPACITY, UNSET_BRIGHTNESS);
    }

    public boolean isNeutral() {
        return this == NEUTRAL;
    }
}
