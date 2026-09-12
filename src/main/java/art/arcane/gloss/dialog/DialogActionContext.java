package art.arcane.gloss.dialog;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.expr.ExprScope;
import art.arcane.gloss.menu.action.ActionContext;
import art.arcane.gloss.menu.action.NavigationRequest;
import art.arcane.gloss.menu.action.NavigationResult;
import org.bukkit.entity.Player;

import java.util.Map;

/**
 * The context a dialog button's actions run in. A dialog is not a navigable surface: it has no
 * history and nothing to go back to, so {@code navigate} is denied and an author is told through
 * the result rather than silently doing nothing.
 */
public final class DialogActionContext implements ActionContext {
    private final Player player;
    private final String dialogId;
    private final int buttonIndex;
    private final Map<String, Object> args;

    public DialogActionContext(Player player, String dialogId, int buttonIndex, Map<String, Object> args) {
        this.player = player;
        this.dialogId = dialogId;
        this.buttonIndex = buttonIndex;
        this.args = args == null ? Map.of() : Map.copyOf(args);
    }

    @Override
    public Player player() {
        return player;
    }

    @Override
    public String menuId() {
        return "dialog:" + dialogId;
    }

    @Override
    public String componentId() {
        return "button:" + buttonIndex;
    }

    @Override
    public HoloClickTrigger trigger() {
        return HoloClickTrigger.LEFT_CLICK;
    }

    @Override
    public NavigationResult navigate(NavigationRequest request) {
        return NavigationResult.DENIED;
    }

    @Override
    public ExprScope conditionScope() {
        return new DialogScope(player, args);
    }

    @Override
    public void closeSurface(Player viewer) {
        DialogService service = DialogService.active();
        if (service != null) {
            service.close(viewer == null ? player : viewer);
        }
    }

}
