package art.arcane.gloss.hologram;

public record DisplayRefresh(Integer contentTicks, Integer visibilityTicks, Integer motionTicks) {
    public static final DisplayRefresh DEFAULTS = new DisplayRefresh(null, null, null);

    public DisplayRefresh {
        validate(contentTicks, "contentTicks");
        validate(visibilityTicks, "visibilityTicks");
        validate(motionTicks, "motionTicks");
    }

    public static DisplayRefresh resolve(DisplayRefresh refresh) {
        return refresh == null ? DEFAULTS : refresh;
    }

    public int contentInterval(int fallback) {
        return contentTicks == null ? fallback : contentTicks;
    }

    public int visibilityInterval(int fallback) {
        return visibilityTicks == null ? fallback : visibilityTicks;
    }

    private static void validate(Integer ticks, String field) {
        if (ticks != null && (ticks < 1 || ticks > 1200)) {
            throw new IllegalArgumentException("display refresh " + field + " must be within 1..1200");
        }
    }
}
