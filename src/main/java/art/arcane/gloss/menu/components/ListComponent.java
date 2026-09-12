package art.arcane.gloss.menu.components;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.config.MenuComponentData;
import art.arcane.gloss.config.components.ListComponentData;
import art.arcane.gloss.expr.ExprEvaluator;
import art.arcane.gloss.expr.ExprParser;
import art.arcane.gloss.expr.ExprScope;
import art.arcane.gloss.menu.MenuSession;
import art.arcane.gloss.menu.icon.MenuIcon;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * The list in a hologram menu. A list is an expansion, not a drawn thing: at session build its
 * source is evaluated, one page of entries is taken, and each entry becomes a real component with
 * the loop variable bound. The component itself exists only so the document can carry a list where
 * a component goes; it draws nothing and is never interactable.
 */
public final class ListComponent extends MenuComponent<ListComponentData> {

  public ListComponent(MenuSession session, MenuComponentData data) {
    super(session, data);
  }

  /**
   * Expands one list into the components its current page shows.
   *
   * @param page the zero-based page; a page past the end expands to nothing
   * @param cap  the hard ceiling on entries, so a runaway source cannot spawn a runaway menu
   */
  public static List<MenuComponentData> expand(MenuComponentData data, ExprScope scope, int page, int cap) {
    ListComponentData list = (ListComponentData) data.data();
    List<Object> entries = evaluate(data.id(), list, scope);
    if (entries.isEmpty()) {
      return List.of();
    }
    int pageSize = Math.min(list.resolvedPageSize(), Math.max(1, cap));
    int from = Math.max(0, page) * pageSize;
    if (from >= entries.size()) {
      return List.of();
    }
    int to = Math.min(entries.size(), from + pageSize);
    ListComponentData.Flow flow = list.flow();
    List<MenuComponentData> expanded = new ArrayList<>(to - from);
    for (int index = from; index < to; index++) {
      int position = index - from;
      expanded.add(new MenuComponentData(data.id() + "[" + index + "]",
          offset(data.offset(), flow, position), list.template(), data.show()));
    }
    return List.copyOf(expanded);
  }

  /** The entries a viewer's scope currently yields, capped to what the menu is allowed to spawn. */
  private static List<Object> evaluate(String id, ListComponentData list, ExprScope scope) {
    try {
      Object value = ExprEvaluator.eval(ExprParser.parse(list.source()), scope);
      return value instanceof List<?> entries ? List.copyOf(entries) : List.of();
    } catch (RuntimeException failure) {
      Gloss.logThrottled(Level.WARNING, "menu-list-" + id,
          "Menu list \"%s\" source failed to evaluate: %s", id, failure.getMessage());
      return List.of();
    }
  }

  /** The hard ceiling on entries one list may spawn, from {@code [menus] maxListEntries}. */
  public static int cap() {
    return GlossConfig.current().menus().maxListEntries();
  }

  private static Vector offset(Vector origin, ListComponentData.Flow flow, int position) {
    int column = position % flow.columns();
    int row = position / flow.columns();
    return origin.clone().add(new Vector(
        flow.x() + column * flow.spacingX(),
        flow.y() - row * flow.spacingY(),
        0D));
  }

  @Override
  protected void onTick(Vector eyeOrigin, Vector eyeDirection) {
  }

  @Override
  protected MenuIcon<?> createIcon() {
    return null;
  }

  @Override
  protected void onOpen() {
  }

  @Override
  protected void onClose() {
  }

  @Override
  public void open() {
  }

  @Override
  public boolean isInteractable() {
    return false;
  }
}
