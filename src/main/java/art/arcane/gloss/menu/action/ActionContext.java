package art.arcane.gloss.menu.action;

import art.arcane.gloss.api.HoloClickTrigger;
import org.bukkit.entity.Player;

public interface ActionContext {
  Player player();

  String menuId();

  String componentId();

  HoloClickTrigger trigger();

  NavigationResult navigate(NavigationRequest request);
}
