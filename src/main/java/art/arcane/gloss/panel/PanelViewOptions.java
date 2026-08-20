package art.arcane.gloss.panel;

import art.arcane.gloss.config.menu.MenuCatalog;
import org.bukkit.entity.Player;

import java.util.Objects;
import java.util.function.Consumer;

public record PanelViewOptions(PanelDefinition definition, PanelTransform effectiveTransform,
                               Player viewer, MenuCatalog menus,
                               Consumer<PanelViewSession> closeRequester) {
  public PanelViewOptions {
    definition = Objects.requireNonNull(definition, "definition");
    effectiveTransform = Objects.requireNonNull(effectiveTransform, "effectiveTransform");
    viewer = Objects.requireNonNull(viewer, "viewer");
    menus = Objects.requireNonNull(menus, "menus");
    closeRequester = Objects.requireNonNull(closeRequester, "closeRequester");
  }
}
