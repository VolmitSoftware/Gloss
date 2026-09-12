package art.arcane.gloss.config.components;

import art.arcane.gloss.config.MenuComponentData;
import art.arcane.gloss.enums.MenuComponentType;
import art.arcane.gloss.menu.MenuSession;
import art.arcane.gloss.menu.components.ListComponent;
import art.arcane.gloss.menu.components.MenuComponent;
import com.google.gson.annotations.SerializedName;

/**
 * A template repeated once per entry of a source expression, flowed on a grid. The list itself
 * draws nothing; the components it expands into are ordinary menu components, so everything that
 * works on a button works on a list entry.
 */
public record ListComponentData(
    @SerializedName("var")
    String variable,
    String source,
    Integer pageSize,
    Flow flow,
    ComponentData template
) implements ComponentData {

  public static final int DEFAULT_PAGE_SIZE = 6;

  public ListComponentData {
    variable = variable == null ? "" : variable.trim();
    if (!variable.matches("[a-z][a-z0-9_]*")) {
      throw new IllegalArgumentException("list var must match [a-z][a-z0-9_]*: " + variable);
    }
    if (source == null || source.isBlank()) {
      throw new IllegalArgumentException("list " + variable + " requires a source expression");
    }
    source = source.trim();
    if (template == null) {
      throw new IllegalArgumentException("list " + variable + " requires a template component");
    }
    if (pageSize != null && pageSize < 1) {
      throw new IllegalArgumentException("list pageSize must be at least 1");
    }
    flow = flow == null ? Flow.DEFAULT : flow;
  }

  public int resolvedPageSize() {
    return pageSize == null ? DEFAULT_PAGE_SIZE : pageSize;
  }

  @Override
  public MenuComponentType getType() {
    return MenuComponentType.LIST;
  }

  @Override
  public MenuComponent<?> createComponent(MenuSession session, MenuComponentData data) {
    return new ListComponent(session, data);
  }

  /** Grid geometry in menu-local units: entries run across {@code columns}, then wrap downward. */
  public record Flow(Integer columns, Float spacingX, Float spacingY, Float x, Float y) {
    public static final Flow DEFAULT = new Flow(3, 1.0F, 0.5F, 0F, 0F);

    public Flow {
      columns = columns == null || columns < 1 ? 3 : columns;
      spacingX = spacingX == null ? 1.0F : spacingX;
      spacingY = spacingY == null ? 0.5F : spacingY;
      x = x == null ? 0F : x;
      y = y == null ? 0F : y;
    }
  }
}
