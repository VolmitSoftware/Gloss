package art.arcane.gloss.config;

import art.arcane.gloss.config.components.ComponentData;
import art.arcane.gloss.condition.ShowCondition;
import art.arcane.gloss.menu.MenuSession;
import art.arcane.gloss.menu.components.MenuComponent;
import org.bukkit.util.Vector;

public record MenuComponentData(String id, Vector offset, ComponentData data, ShowCondition show) {
  public MenuComponentData {
    show = show == null ? ShowCondition.ALWAYS : show;
  }

  public MenuComponent<?> createComponent(MenuSession session) {
    return data.createComponent(session, this);
  }
}
