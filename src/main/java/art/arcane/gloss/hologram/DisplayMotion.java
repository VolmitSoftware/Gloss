package art.arcane.gloss.hologram;

import org.bukkit.entity.Display;

import java.lang.reflect.Method;

public final class DisplayMotion {
    private static final int MAX_TELEPORT_DURATION_TICKS = 59;
    private static final Method SET_TELEPORT_DURATION = probe();

    private DisplayMotion() {
    }

    public static boolean canInterpolate() {
        return SET_TELEPORT_DURATION != null;
    }

    public static int clampDuration(int ticks) {
        if (ticks < 0) {
            return 0;
        }

        return Math.min(MAX_TELEPORT_DURATION_TICKS, ticks);
    }

    public static boolean applyTeleportDuration(Display display, int ticks) {
        if (display == null || SET_TELEPORT_DURATION == null) {
            return false;
        }

        try {
            display.setTeleportDuration(clampDuration(ticks));
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Method probe() {
        try {
            return Display.class.getMethod("setTeleportDuration", int.class);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
