package art.arcane.gloss.api;

public record HologramPresentation(double scaleX, double scaleY, double scaleZ,
                                  double rotationXDegrees, double rotationYDegrees, double rotationZDegrees,
                                  double opacity) {
    private static final HologramPresentation IDENTITY = new HologramPresentation(
        1.0D, 1.0D, 1.0D, 0.0D, 0.0D, 0.0D, 1.0D);

    public HologramPresentation {
        scaleX = scale(scaleX);
        scaleY = scale(scaleY);
        scaleZ = scale(scaleZ);
        rotationXDegrees = rotation(rotationXDegrees);
        rotationYDegrees = rotation(rotationYDegrees);
        rotationZDegrees = rotation(rotationZDegrees);
        opacity = opacity(opacity);
    }

    public static HologramPresentation identity() {
        return IDENTITY;
    }

    private static double scale(double value) {
        return Double.isFinite(value) ? Math.max(0.0D, Math.min(16.0D, value)) : 1.0D;
    }

    private static double rotation(double value) {
        if (!Double.isFinite(value)) {
            return 0.0D;
        }
        double normalized = value % 360.0D;
        return normalized < 0.0D ? normalized + 360.0D : normalized;
    }

    private static double opacity(double value) {
        return Double.isFinite(value) ? Math.max(0.0D, Math.min(1.0D, value)) : 1.0D;
    }
}
