package art.arcane.gloss.surface;

import art.arcane.gloss.expr.ExprParser;

import java.util.List;

public record SurfaceTrigger(String trigger, Integer everyTicks, Integer delayTicks, String when) {
    public SurfaceTrigger {
        if (!List.of("join", "world_change", "server_change", "interval").contains(trigger)) {
            throw new IllegalArgumentException("surface trigger must be join, world_change, server_change or interval");
        }
        if (trigger.equals("interval")) {
            if (everyTicks == null || everyTicks < 1 || everyTicks > 1728000) {
                throw new IllegalArgumentException("surface interval everyTicks must be between 1 and 1728000");
            }
        } else if (everyTicks != null) {
            throw new IllegalArgumentException("everyTicks is only supported for interval triggers");
        }
        delayTicks = delayTicks == null ? 0 : delayTicks;
        if (delayTicks < 0 || delayTicks > 72000) {
            throw new IllegalArgumentException("surface delayTicks must be between 0 and 72000");
        }
        when = when == null ? "true" : when;
        ExprParser.parse(when);
    }

    public void requirePlatform(boolean proxy) {
        if (proxy && trigger.equals("world_change") || !proxy && trigger.equals("server_change")) {
            throw new IllegalArgumentException("surface trigger " + trigger + " is unavailable on " + (proxy ? "Velocity" : "the backend"));
        }
    }
}
