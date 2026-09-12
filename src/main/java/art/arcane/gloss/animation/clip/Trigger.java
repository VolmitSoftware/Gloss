package art.arcane.gloss.animation.clip;

import java.util.Locale;
import java.util.Objects;

public record Trigger(String key) {
    public static final Trigger SPAWN = of("spawn");
    public static final Trigger AIRBORNE = of("airborne");
    public static final Trigger REBOUNDING = of("rebounding");
    public static final Trigger ROLLING = of("rolling");
    public static final Trigger SLIDING = of("sliding");
    public static final Trigger SETTLING = of("settling");
    public static final Trigger SETTLED = of("settled");
    public static final Trigger SUBMERGED = of("submerged");
    public static final Trigger FLOATING = of("floating");
    public static final Trigger IMPACT = of("impact");
    public static final Trigger BOUNCE = of("bounce");
    public static final Trigger ENTER_FLUID = of("enter_fluid");
    public static final Trigger EXIT_FLUID = of("exit_fluid");
    public static final Trigger START_ROLL = of("start_roll");
    public static final Trigger SETTLE = of("settle");
    public static final Trigger WAKE = of("wake");

    public Trigger {
        key = Objects.requireNonNull(key, "trigger key").trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty()) {
            throw new IllegalArgumentException("trigger key must not be blank");
        }
    }

    public static Trigger of(String key) {
        return new Trigger(key);
    }

    @Override
    public String toString() {
        return key;
    }
}
