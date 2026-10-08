package art.arcane.gloss.prompt;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.behavior.ArgsView;
import art.arcane.gloss.expr.ExprScope;
import art.arcane.gloss.menu.SessionVariables;
import art.arcane.gloss.menu.action.ActionContext;
import art.arcane.gloss.menu.action.NavigationRequest;
import art.arcane.gloss.menu.action.NavigationResult;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;

public final class DialogActionContext implements ActionContext, InputNamespace.Source, ArgsView.Source {
    private final ActionContext origin;
    private final Map<String, Object> inputs;
    private final ExprScope scope;

    public DialogActionContext(ActionContext origin, Map<String, Object> inputs) {
        this.origin = origin;
        this.inputs = Map.copyOf(inputs);
        this.scope = new InputScope(origin.conditionScope(), this.inputs);
    }

    @Override
    public Player player() {
        return origin.player();
    }

    @Override
    public String menuId() {
        return origin.menuId();
    }

    @Override
    public String componentId() {
        return origin.componentId();
    }

    @Override
    public HoloClickTrigger trigger() {
        return origin.trigger();
    }

    @Override
    public boolean current() {
        return origin.current();
    }

    @Override
    public NavigationResult navigate(NavigationRequest request) {
        return origin.navigate(request);
    }

    @Override
    public ExprScope conditionScope() {
        return scope;
    }

    @Override
    public SessionVariables sessionVariables() {
        return origin.sessionVariables();
    }

    @Override
    public void closeSurface(Player player) {
        origin.closeSurface(player);
    }

    @Override
    public Map<String, Object> inputs() {
        return inputs;
    }

    @Override
    public Map<String, Object> args() {
        return origin instanceof ArgsView.Source source ? source.args() : Map.of();
    }

    private record InputScope(ExprScope parent, Map<String, Object> inputs) implements ExprScope {
        @Override
        public Object variable(String name) {
            return name.startsWith("input.") ? inputs.get(name.substring(6)) : parent.variable(name);
        }

        @Override
        public Object call(String name, List<Object> args) {
            return parent.call(name, args);
        }
    }
}
