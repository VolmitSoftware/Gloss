package art.arcane.gloss.surface;

import art.arcane.gloss.text.TextPipeline;
import art.arcane.volmlib.util.hud.HudPriority;
import org.bukkit.entity.Player;

/**
 * The black bars a camera ride asks for, drawn on the title slot. They claim at ambient priority so
 * a scene's own {@code title} cue takes the slot from them rather than queueing behind them, and
 * their stay window is the longest ride the config allows so an abandoned ride cannot pin the slot.
 */
final class SurfaceLetterbox {
    static final String PURPOSE = "gloss:camera:letterbox";
    static final int PRIORITY = HudPriority.AMBIENT;
    static final String BAR = "&0█████████████"
        + "████████████";
    private static final int FADE_TICKS = 10;

    private SurfaceLetterbox() {
    }

    static void apply(SurfaceDelivery delivery, Player viewer, boolean visible, int maxRideSeconds) {
        if (delivery == null || viewer == null) {
            return;
        }
        if (!visible) {
            delivery.clearTitle(viewer, PURPOSE);
            return;
        }
        int stayTicks = Math.max(1, maxRideSeconds * 20);
        String bar = TextPipeline.menuText(viewer, BAR);
        delivery.title(viewer, PURPOSE, PRIORITY, bar, bar, FADE_TICKS, stayTicks, FADE_TICKS);
    }
}
