package art.arcane.gloss.prompt;

import art.arcane.gloss.menu.action.ActionContext;
import art.arcane.gloss.menu.action.MenuAction;

import java.util.List;
import java.util.Locale;

/**
 * One request for a line of text from a player: which editor to open, where the answer goes, and
 * what runs once it lands.
 */
public record PromptRequest(String kind, String variable, String label, String initial,
                            List<MenuAction<?>> then, int timeoutTicks, ActionContext origin) {
    public static final String SIGN = "sign";
    public static final String ANVIL = "anvil";
    public static final String CHAT = "chat";
    public static final int DEFAULT_TIMEOUT_TICKS = 600;
    public static final int MAX_TIMEOUT_TICKS = 12000;

    public PromptRequest {
        kind = kind == null ? SIGN : kind.trim().toLowerCase(Locale.ROOT);
        if (!kind.equals(SIGN) && !kind.equals(ANVIL) && !kind.equals(CHAT)) {
            throw new IllegalArgumentException("prompt kind must be sign, anvil or chat: " + kind);
        }
        variable = variable == null ? "" : variable.trim();
        label = label == null ? "" : label;
        initial = initial == null ? "" : initial;
        then = then == null ? List.of() : List.copyOf(then);
        timeoutTicks = timeoutTicks <= 0 ? DEFAULT_TIMEOUT_TICKS : Math.min(timeoutTicks, MAX_TIMEOUT_TICKS);
    }
}
