package art.arcane.gloss.menu;

import art.arcane.gloss.config.MenuComponentData;
import art.arcane.gloss.expr.ExprScope;

public record ExpandedMenuComponent(MenuComponentData data, ExprScope scope) {
}
