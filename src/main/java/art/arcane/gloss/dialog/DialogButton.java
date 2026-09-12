package art.arcane.gloss.dialog;

import art.arcane.gloss.config.action.MenuActionData;

import java.util.List;

/**
 * One clickable button on a dialog. The action list is the same list every other Gloss surface
 * takes, so {@code when} and {@code cooldownTicks} apply per action exactly as they do in a menu.
 */
public record DialogButton(String label, String tooltip, Integer width, List<MenuActionData> actions) {
    public static final int DEFAULT_WIDTH = 150;

    public DialogButton {
        label = label == null ? "" : label;
        if (label.isBlank()) {
            throw new IllegalArgumentException("dialog button requires a label");
        }
        tooltip = tooltip == null || tooltip.isBlank() ? null : tooltip;
        width = DialogWidths.clamp(width, DEFAULT_WIDTH);
        actions = actions == null ? List.of() : List.copyOf(actions);
    }
}
