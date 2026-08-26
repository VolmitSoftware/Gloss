package art.arcane.gloss.particle;

public record ParticleRect(double centerX, double centerY, double centerZ,
                           double width, double height, double depth) {
    public ParticleRect {
        requireFinite(centerX, "centerX");
        requireFinite(centerY, "centerY");
        requireFinite(centerZ, "centerZ");
        requireSize(width, "width");
        requireSize(height, "height");
        requireSize(depth, "depth");
    }

    public static ParticleRect plane(double width, double height) {
        return new ParticleRect(0.0D, 0.0D, 0.0D, width, height, 0.0D);
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("particle rectangle " + name + " must be finite");
        }
    }

    private static void requireSize(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0D || value > 256.0D) {
            throw new IllegalArgumentException("particle rectangle " + name + " must be between 0 and 256");
        }
    }
}
