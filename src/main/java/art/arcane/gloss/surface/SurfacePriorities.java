package art.arcane.gloss.surface;

import art.arcane.volmlib.util.hud.HudPriority;

import java.util.List;
import java.util.Locale;

public final class SurfacePriorities {
    public static final String DEFAULT_NAME = "status";
    public static final List<String> NAMES = List.of(
        "ambient", "notice", "status", "progress", "interactive", "modal", "pinned");

    private SurfacePriorities() {
    }

    public static int of(String name) {
        String normalized = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return HudPriority.STATUS;
        }
        return switch (normalized) {
            case "ambient" -> HudPriority.AMBIENT;
            case "notice" -> HudPriority.NOTICE;
            case "status" -> HudPriority.STATUS;
            case "progress" -> HudPriority.PROGRESS;
            case "interactive" -> HudPriority.INTERACTIVE;
            case "modal" -> HudPriority.MODAL;
            case "pinned" -> HudPriority.PINNED;
            default -> throw new IllegalArgumentException(
                "surface priority must be one of " + String.join(", ", NAMES) + ": " + name);
        };
    }

    public static String name(String configured) {
        String normalized = configured == null ? "" : configured.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? DEFAULT_NAME : normalized;
    }
}
