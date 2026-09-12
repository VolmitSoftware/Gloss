package art.arcane.gloss.menu.components;

import art.arcane.gloss.config.components.ButtonComponentData;
import art.arcane.gloss.config.icon.TextIconData;
import art.arcane.gloss.menu.MenuSession;
import art.arcane.gloss.menu.icon.MenuIcon;
import org.bukkit.Location;
import org.bukkit.util.Vector;

import java.util.List;

/**
 * The secondary pane a button shows once the viewer has held their aim on it. It is spawned late
 * and torn down the moment the aim leaves, so a menu full of tooltips costs nothing until one is
 * actually being read.
 */
public final class TooltipPane {
    /** How far in front of the button the pane sits, in menu-local units. */
    public static final double FORWARD_OFFSET = 0.05D;
    /** How far above the button the pane sits, so it does not cover what is being hovered. */
    public static final double VERTICAL_OFFSET = 0.35D;

    private final MenuSession session;
    private final ButtonComponentData.TooltipData data;
    private MenuIcon<?> icon;
    private int hoveredTicks;

    public TooltipPane(MenuSession session, ButtonComponentData.TooltipData data) {
        this.session = session;
        this.data = data;
    }

    /**
     * Advances the hover clock for one tick.
     *
     * @param hovered whether the viewer is aiming at the owning button this tick
     * @param anchor  where the owning button is drawn
     */
    public void tick(boolean hovered, Location anchor) {
        if (!hovered) {
            hoveredTicks = 0;
            hide();
            return;
        }
        hoveredTicks++;
        if (hoveredTicks < data.delayTicks() || icon != null || data.lines().isEmpty()) {
            return;
        }
        show(anchor);
    }

    public void hide() {
        if (icon != null) {
            icon.remove();
            icon = null;
        }
    }

    private void show(Location anchor) {
        Location at = session.getTransform().localPosition(anchor,
            new Vector(0D, VERTICAL_OFFSET, FORWARD_OFFSET));
        icon = MenuIcon.createIcon(session, at, new TextIconData(text(data.lines()), null, null, null), null);
        if (icon != null) {
            icon.spawn();
        }
    }

    private static String text(List<String> lines) {
        return String.join("\n", lines);
    }
}
