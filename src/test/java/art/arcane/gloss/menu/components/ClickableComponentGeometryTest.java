package art.arcane.gloss.menu.components;

import art.arcane.gloss.config.components.HitboxAnchor;
import art.arcane.gloss.config.components.HitboxData;
import art.arcane.gloss.menu.MenuTransform;
import org.bukkit.Location;
import org.bukkit.util.Vector;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ClickableComponentGeometryTest {

  @Test
  public void hitboxOffsetUsesMenuLocalAxesAndScale() {
    HitboxData hitbox = new HitboxData(null, null, new Vector(0.5, -0.25, 0.75), null);

    MenuTransform transform = transform(2F, 0F);
    Vector translation = transform.localVector(hitbox.offset());

    assertEquals(new Vector(-1, -0.5, 1.5), translation);
  }

  @Test
  public void hitboxOffsetRotatesWithPlaneAxes() {
    HitboxData hitbox = new HitboxData(null, null, new Vector(1, 2, 3), null);

    MenuTransform transform = transform(1F, 90F);
    Vector translation = transform.localVector(hitbox.offset());

    assertEquals(new Vector(-3, 2, -1), translation);
  }

  @Test
  public void buttonAnchorMovesWithButtonOrigin() {
    HitboxData hitbox = new HitboxData(null, null, new Vector(0.5, 0, 0), HitboxAnchor.BUTTON);

    Vector first = ClickableComponent.hitboxCenter(
        hitbox,
        transform(1F, 0F),
        new Vector(2, 3, 4)
    );
    Vector moved = ClickableComponent.hitboxCenter(
        hitbox,
        transform(1F, 0F),
        new Vector(7, 9, 11)
    );

    assertEquals(new Vector(1.5, 3, 4), first);
    assertEquals(new Vector(6.5, 9, 11), moved);
  }

  @Test
  public void menuAnchorIgnoresButtonOrigin() {
    HitboxData hitbox = new HitboxData(null, null, null, HitboxAnchor.MENU);

    Vector first = ClickableComponent.hitboxCenter(
        hitbox,
        transform(1F, 0F),
        new Vector(2, 3, 4)
    );
    Vector moved = ClickableComponent.hitboxCenter(
        hitbox,
        transform(1F, 0F),
        new Vector(7, 9, 11)
    );

    assertEquals(new Vector(10, 20, 30), first);
    assertEquals(first, moved);
  }

  private static MenuTransform transform(float scale, float yaw) {
    return new MenuTransform(new Location(null, 10D, 20D, 30D), new Vector(), yaw, 0F, 0F, scale);
  }
}
