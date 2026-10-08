package art.arcane.gloss.surface;

import java.util.List;

public record SurfaceDispatchPolicy(String mode, String preempt, Integer maxPending, String overflow,
                                    Integer cooldownTicks, String deduplicate, Integer expireTicks) {
    public static final SurfaceDispatchPolicy DEFAULTS = new SurfaceDispatchPolicy(null, null, null, null, null, null, null);

    public SurfaceDispatchPolicy {
        mode = choice(mode, "replace", List.of("queue", "replace", "drop"), "mode");
        preempt = choice(preempt, "always", List.of("higher", "always", "never"), "preempt");
        maxPending = bounded(maxPending, 32, 1, 256, "maxPending");
        overflow = choice(overflow, "reject", List.of("drop-oldest", "reject"), "overflow");
        cooldownTicks = bounded(cooldownTicks, 0, 0, 72000, "cooldownTicks");
        deduplicate = choice(deduplicate, "none", List.of("none", "purpose", "content"), "deduplicate");
        expireTicks = bounded(expireTicks, 1200, 1, 72000, "expireTicks");
    }

    private static String choice(String value, String fallback, List<String> allowed, String field) {
        String selected = value == null ? fallback : value;
        if (!allowed.contains(selected)) {
            throw new IllegalArgumentException("surface delivery." + field + " must be one of " + allowed);
        }
        return selected;
    }

    private static Integer bounded(Integer value, int fallback, int min, int max, String field) {
        int selected = value == null ? fallback : value;
        if (selected < min || selected > max) {
            throw new IllegalArgumentException("surface delivery." + field + " must be between " + min + " and " + max);
        }
        return selected;
    }
}
