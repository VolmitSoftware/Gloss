package art.arcane.gloss.panel;

import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.config.MenuDefinitionData;
import art.arcane.gloss.menu.MenuTransform;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.Objects;

public final class PanelPlacement {
  private PanelPlacement() {
  }

  public static World resolveWorld(PanelDefinition board) {
    PanelTransform transform = Objects.requireNonNull(board, "board").transform();
    World world = Bukkit.getWorld(transform.worldUuid());
    if (world == null || !world.getKey().toString().equals(transform.worldKey())) {
      return null;
    }
    return world;
  }

  public static MenuTransform menuTransform(PanelDefinition board, MenuDefinitionData menu) {
    return menuTransform(board.transform(), menu);
  }

  public static MenuTransform menuTransform(PanelTransform transform, MenuDefinitionData menu) {
    World world = Bukkit.getWorld(Objects.requireNonNull(transform, "transform").worldUuid());
    if (world == null || !world.getKey().toString().equals(transform.worldKey())) {
      return null;
    }
    Location anchor = new Location(
        world,
        transform.x(),
        transform.y(),
        transform.z(),
        (float) transform.yaw(),
        (float) transform.pitch()
    );
    return new MenuTransform(
        anchor,
        Objects.requireNonNull(menu, "menu").getOffset(),
        (float) transform.yaw(),
        (float) transform.pitch(),
        (float) transform.roll(),
        (float) (transform.scale() * GlossConfig.current().menus().uiScale())
    );
  }

  public static double distanceSquared(PanelDefinition board, Location location) {
    return distanceSquared(Objects.requireNonNull(board, "board").transform(), location);
  }

  public static double distanceSquared(PanelTransform transform, Location location) {
    PanelTransform requiredTransform = Objects.requireNonNull(transform, "transform");
    Location point = Objects.requireNonNull(location, "location");
    if (point.getWorld() == null || !point.getWorld().getUID().equals(requiredTransform.worldUuid())) {
      return Double.POSITIVE_INFINITY;
    }
    double x = point.getX() - requiredTransform.x();
    double y = point.getY() - requiredTransform.y();
    double z = point.getZ() - requiredTransform.z();
    return x * x + y * y + z * z;
  }
}
