package art.arcane.gloss.config.action;

/**
 * The two fields every action may carry beside its own: a {@code when} condition that gates the
 * action per run, and a per-player {@code cooldownTicks} window after a successful run. Every
 * action data record declares both as its last two components so the JSON shape is flat.
 */
public record ActionEnvelope(String when, Integer cooldownTicks) {
    public static final ActionEnvelope NONE = new ActionEnvelope(null, null);

    public static ActionEnvelope of(String when, Integer cooldownTicks) {
        boolean blankWhen = when == null || when.isBlank();
        boolean noCooldown = cooldownTicks == null || cooldownTicks <= 0;
        return blankWhen && noCooldown ? NONE : new ActionEnvelope(blankWhen ? null : when.trim(), noCooldown ? null : cooldownTicks);
    }

    public boolean hasWhen() {
        return when != null;
    }

    public boolean hasCooldown() {
        return cooldownTicks != null;
    }
}
