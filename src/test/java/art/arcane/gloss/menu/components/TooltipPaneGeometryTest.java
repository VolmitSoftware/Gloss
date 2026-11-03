package art.arcane.gloss.menu.components;

import art.arcane.gloss.menu.MenuTransform;
import art.arcane.gloss.util.common.math.CollisionPlane;
import org.bukkit.Location;
import org.bukkit.util.Vector;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class TooltipPaneGeometryTest {

    @Test
    public void thePaneSitsBetweenTheViewerAndItsButton() {
        MenuTransform transform = new MenuTransform(new Location(null, 0D, 0D, 0D), new Vector(), 0F, 0F, 0F, 1F);

        Location pane = transform.localPosition(new Location(null, 0D, 0D, 3D), TooltipPane.forward());

        assertEquals(3D - TooltipPane.FORWARD_OFFSET, pane.getZ(), 0.000001D);
    }

    @Test
    public void thePaneBottomSitsAGapAboveTheIconTop() {
        CollisionPlane icon = new CollisionPlane(new Vector(0D, -0.05D, 3D), 0.4F, 0.4F);
        CollisionPlane pane = new CollisionPlane(new Vector(0D, -0.325D, 3D), 1F, 0.25F);

        Vector offset = TooltipPane.aboveIcon(icon, pane, 0.08D);

        assertEquals(0D, offset.getX(), 0.000001D);
        assertEquals(0.68D, offset.getY(), 0.000001D);
        assertEquals(0D, offset.getZ(), 0.000001D);
    }

    @Test
    public void aTallerIconLiftsThePaneFurther() {
        CollisionPlane small = new CollisionPlane(new Vector(0D, 0D, 3D), 0.4F, 0.4F);
        CollisionPlane tall = new CollisionPlane(new Vector(0D, 0D, 3D), 0.4F, 1F);
        CollisionPlane pane = new CollisionPlane(new Vector(0D, 0D, 3D), 1F, 0.25F);

        double difference = TooltipPane.aboveIcon(tall, pane, 0.08D).getY() - TooltipPane.aboveIcon(small, pane, 0.08D).getY();

        assertEquals(0.3D, difference, 0.000001D);
    }

    @Test
    public void theOffsetFollowsARotatedMenu() {
        CollisionPlane icon = new CollisionPlane(new Vector(0D, 0D, 3D), 0.4F, 0.4F);
        CollisionPlane pane = new CollisionPlane(new Vector(0D, 0D, 3D), 1F, 0.2F);
        icon.rotate(0F, 0F, 90F);
        pane.rotate(0F, 0F, 90F);

        Vector offset = TooltipPane.aboveIcon(icon, pane, 0.1D);

        assertEquals(0.4D, offset.length(), 0.000001D);
        assertEquals(0D, offset.getY(), 0.000001D);
    }
}
