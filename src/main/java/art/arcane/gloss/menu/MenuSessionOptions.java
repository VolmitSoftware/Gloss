package art.arcane.gloss.menu;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.api.internal.ApiMenuHandle;
import art.arcane.gloss.config.MenuDefinitionData;
import art.arcane.gloss.menu.action.MenuNavigator;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Objects;

public record MenuSessionOptions(ApiMenuHandle apiHandle, MenuTransform transform,
                                 boolean faceViewerOnOpen, MenuNavigator navigator,
                                 float scaleMultiplier) {
  public MenuSessionOptions {
    transform = Objects.requireNonNull(transform, "transform");
    navigator = Objects.requireNonNull(navigator, "navigator");
    if (!Float.isFinite(scaleMultiplier) || scaleMultiplier <= 0F) {
      throw new IllegalArgumentException("scaleMultiplier must be finite and greater than zero");
    }
  }

  public static MenuSessionOptions personal(MenuDefinitionData data, Player player, ApiMenuHandle apiHandle) {
    Objects.requireNonNull(data, "data");
    Player viewer = Objects.requireNonNull(player, "player");
    Location anchor = viewer.getLocation();
    MenuTransform transform = new MenuTransform(
        anchor,
        data.getOffset(),
        anchor.getYaw(),
        0F,
        0F,
        GlossConfig.current().menus().uiScale()
    );
    return new MenuSessionOptions(
        apiHandle,
        transform,
        true,
        request -> Gloss.instance.getSessionManager().navigateSession(viewer, request),
        1F
    );
  }

  public static MenuSessionOptions positioned(MenuTransform transform, MenuNavigator navigator,
                                               float scaleMultiplier) {
    return new MenuSessionOptions(null, transform, false, navigator, scaleMultiplier);
  }
}
