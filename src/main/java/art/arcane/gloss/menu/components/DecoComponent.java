package art.arcane.gloss.menu.components;

import art.arcane.gloss.config.MenuComponentData;
import art.arcane.gloss.config.components.DecoComponentData;
import art.arcane.gloss.menu.MenuSession;
import art.arcane.gloss.menu.icon.MenuIcon;
import org.bukkit.util.Vector;

public class DecoComponent extends MenuComponent<DecoComponentData> {

  public DecoComponent(MenuSession session, MenuComponentData data) {
    super(session, data);
  }

  protected MenuIcon<?> createIcon() {
    return MenuIcon.createIcon(session, location, data.iconData(), this);
  }

  protected void onOpen() {
  }

  protected void onTick(Vector eyeOrigin, Vector eyeDirection) {
  }

  protected void onClose() {
  }
}
