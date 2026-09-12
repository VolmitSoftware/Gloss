package art.arcane.gloss.menu.components;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.config.MenuComponentData;
import art.arcane.gloss.config.components.TabsComponentData;
import art.arcane.gloss.config.icon.TextIconData;
import art.arcane.gloss.menu.MenuSession;
import art.arcane.gloss.menu.SessionVariables;
import art.arcane.gloss.menu.icon.MenuIcon;

/**
 * One tab of a row. The row is expanded into one component per tab at session build, exactly as a
 * list is, so every tab is an ordinary clickable with its own hitbox and hover.
 */
public final class TabsComponent extends ClickableComponent<TabsComponentData> {

  private final int index;

  public TabsComponent(MenuSession session, MenuComponentData data) {
    super(session, data, 0F, null, 0, art.arcane.gloss.config.components.HoverEasing.resolve(null));
    this.index = indexOf(data.id());
  }

  /** Writes the tab at this index; an index the row does not have writes nothing. */
  public static void select(TabsComponentData data, SessionVariables variables, int index) {
    if (index < 0 || index >= data.tabs().size()) {
      return;
    }
    variables.set(data.variable(), data.tabs().get(index).id());
  }

  /** The expanded id of one tab, which is also how the clicked index is recovered. */
  public static String tabId(String componentId, int index) {
    return componentId + "#" + index;
  }

  private static int indexOf(String componentId) {
    int marker = componentId.lastIndexOf('#');
    if (marker < 0) {
      return 0;
    }
    try {
      return Integer.parseInt(componentId.substring(marker + 1));
    } catch (NumberFormatException notATab) {
      return 0;
    }
  }

  @Override
  public MenuIcon<?> createIcon() {
    String label = index < data.tabs().size() ? data.tabs().get(index).label() : "";
    return MenuIcon.createIcon(session, location, new TextIconData(label, data.style(), null, null), this);
  }

  @Override
  public void onClick(HoloClickTrigger trigger) {
    if (!isInteractable()) {
      return;
    }
    select(data, session.variables(), index);
  }
}
