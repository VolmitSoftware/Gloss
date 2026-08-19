package art.arcane.gloss.panel;

import art.arcane.gloss.config.ConfigManager;
import org.bukkit.entity.Player;

import java.util.Objects;
import java.util.function.Consumer;

public record PanelViewOptions(PanelDefinition definition, PanelTransform effectiveTransform,
                               Player viewer, ConfigManager menus,
                               Consumer<PanelViewSession> closeRequester) {
  public PanelViewOptions {
    definition = Objects.requireNonNull(definition, "definition");
    effectiveTransform = Objects.requireNonNull(effectiveTransform, "effectiveTransform");
    viewer = Objects.requireNonNull(viewer, "viewer");
    menus = Objects.requireNonNull(menus, "menus");
    closeRequester = Objects.requireNonNull(closeRequester, "closeRequester");
  }
}
