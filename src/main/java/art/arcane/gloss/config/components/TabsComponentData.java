package art.arcane.gloss.config.components;

import art.arcane.gloss.api.IconDisplayStyle;
import art.arcane.gloss.config.MenuComponentData;
import art.arcane.gloss.enums.MenuComponentType;
import art.arcane.gloss.menu.MenuSession;
import art.arcane.gloss.menu.components.MenuComponent;
import art.arcane.gloss.menu.components.TabsComponent;
import com.google.gson.annotations.SerializedName;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A row of buttons that writes one session variable; other components gate on it with {@code show}. */
public record TabsComponentData(
    @SerializedName("var")
    String variable,
    List<Tab> tabs,
    Float spacing,
    IconDisplayStyle style
) implements ComponentData {

  public TabsComponentData {
    variable = variable == null ? "" : variable.trim();
    if (!variable.matches("[a-z][a-z0-9_]*")) {
      throw new IllegalArgumentException("tabs var must match [a-z][a-z0-9_]*: " + variable);
    }
    tabs = copyTabs(tabs, variable);
    spacing = spacing == null || spacing <= 0F ? 1.0F : spacing;
  }

  private static List<Tab> copyTabs(List<Tab> tabs, String variable) {
    if (tabs == null || tabs.isEmpty()) {
      throw new IllegalArgumentException("tabs " + variable + " requires at least one tab");
    }
    Set<String> ids = new HashSet<>(tabs.size());
    for (Tab tab : tabs) {
      if (tab == null) {
        throw new IllegalArgumentException("tabs " + variable + " may not contain null entries");
      }
      if (!ids.add(tab.id())) {
        throw new IllegalArgumentException("tabs " + variable + " duplicates tab id " + tab.id());
      }
    }
    return List.copyOf(tabs);
  }

  @Override
  public MenuComponentType getType() {
    return MenuComponentType.TABS;
  }

  @Override
  public MenuComponent<?> createComponent(MenuSession session, MenuComponentData data) {
    return new TabsComponent(session, data);
  }

  /** One tab: the value written to the variable, and the label drawn for it. */
  public record Tab(String id, String label) {
    public Tab {
      id = id == null ? "" : id.trim();
      if (id.isEmpty()) {
        throw new IllegalArgumentException("a tab requires an id");
      }
      label = label == null ? id : label;
    }
  }
}
