package art.arcane.gloss.menu;

import art.arcane.gloss.config.MenuComponentData;
import art.arcane.gloss.config.components.ComponentData;
import art.arcane.gloss.config.components.ListComponentData;
import art.arcane.gloss.config.components.TabsComponentData;
import art.arcane.gloss.expr.ExprScope;
import art.arcane.gloss.menu.components.ListComponent;
import art.arcane.gloss.menu.components.TabsComponent;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the two container component types into the plain components a session actually holds. A
 * list becomes one component per visible entry, a tab row becomes one clickable per tab; everything
 * downstream — hitboxes, hover, clicks, particles — then works on them without knowing they were
 * ever a container.
 */
public final class MenuComponentExpansion {
    private MenuComponentExpansion() {
    }

    public static List<MenuComponentData> expand(List<MenuComponentData> declared, ExprScope scope) {
        if (declared == null || declared.isEmpty()) {
            return List.of();
        }
        List<MenuComponentData> expanded = new ArrayList<>(declared.size());
        for (MenuComponentData component : declared) {
            ComponentData data = component == null ? null : component.data();
            if (data instanceof ListComponentData) {
                expanded.addAll(ListComponent.expand(component, scope, 0, ListComponent.cap()));
            } else if (data instanceof TabsComponentData tabs) {
                expanded.addAll(expandTabs(component, tabs));
            } else if (component != null) {
                expanded.add(component);
            }
        }
        return List.copyOf(expanded);
    }

    private static List<MenuComponentData> expandTabs(MenuComponentData component, TabsComponentData tabs) {
        List<MenuComponentData> expanded = new ArrayList<>(tabs.tabs().size());
        double span = tabs.spacing() * (tabs.tabs().size() - 1) / 2D;
        for (int index = 0; index < tabs.tabs().size(); index++) {
            Vector offset = component.offset().clone().add(new Vector(index * tabs.spacing() - span, 0D, 0D));
            expanded.add(new MenuComponentData(TabsComponent.tabId(component.id(), index), offset, tabs,
                component.show()));
        }
        return expanded;
    }
}
