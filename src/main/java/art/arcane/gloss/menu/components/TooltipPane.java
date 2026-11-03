package art.arcane.gloss.menu.components;

import art.arcane.gloss.config.components.ButtonComponentData;
import art.arcane.gloss.config.icon.TextIconData;
import art.arcane.gloss.menu.MenuSession;
import art.arcane.gloss.menu.icon.MenuIcon;
import art.arcane.gloss.util.common.math.CollisionPlane;
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
    /** The space between the top of the button's icon and the pane, so it does not cover what is being hovered. */
    public static final double ICON_GAP = 0.08D;

    private final MenuSession session;
    private final MenuComponent<?> owner;
    private final ButtonComponentData.TooltipData data;
    private MenuIcon<?> icon;
    private int hoveredTicks;

    public TooltipPane(MenuComponent<?> owner, ButtonComponentData.TooltipData data) {
        this.owner = owner;
        this.session = owner.session;
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
        Location at = session.getTransform().localPosition(anchor, forward());
        icon = MenuIcon.createIcon(session, at, new TextIconData(text(data.lines()), data.style(), null, data.box()), owner);
        if (icon == null) {
            return;
        }
        if (owner.currentIcon != null) {
            icon.move(aboveIcon(owner.currentIcon.createBoundingBox(anchor), icon.createBoundingBox(at),
                ICON_GAP * session.getTransform().scale() + owner.currentIcon.frameMargin() + icon.frameMargin()));
        }
        icon.spawn();
    }

    /** Menu-local z grows away from the viewer, so a pane in front of its button has a negative z. */
    static Vector forward() {
        return new Vector(0D, 0D, -FORWARD_OFFSET);
    }

    static Vector aboveIcon(CollisionPlane ownerIcon, CollisionPlane pane, double gap) {
        Vector up = pane.getUp();
        double iconTop = ownerIcon.getCenter().dot(up) + ownerIcon.getHeight() / 2D;
        double paneBottom = pane.getCenter().dot(up) - pane.getHeight() / 2D;
        return up.clone().multiply(iconTop + gap - paneBottom);
    }

    private static String text(List<String> lines) {
        return String.join("\n", lines);
    }
}
