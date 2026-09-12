package art.arcane.gloss.rig;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.condition.GlossConditionScope;
import art.arcane.gloss.expr.ExprScope;
import art.arcane.gloss.menu.action.NavigationRequest;
import art.arcane.gloss.menu.action.NavigationResult;
import org.bukkit.entity.Player;

import java.util.List;

public final class RigClickContext implements RigActionContext {
    private static final String NAMESPACE_PREFIX = RigNamespace.PREFIX + ".";

    private final Gloss plugin;
    private final RigInstance instance;
    private final String componentId;
    private final Player player;
    private final HoloClickTrigger trigger;

    public RigClickContext(Gloss plugin, RigInstance instance, String componentId, Player player, HoloClickTrigger trigger) {
        this.plugin = plugin;
        this.instance = instance;
        this.componentId = componentId;
        this.player = player;
        this.trigger = trigger;
    }

    @Override
    public RigInstance instance() {
        return instance;
    }

    @Override
    public Player player() {
        return player;
    }

    @Override
    public String menuId() {
        return "rig:" + instance.id();
    }

    @Override
    public String componentId() {
        return componentId;
    }

    @Override
    public HoloClickTrigger trigger() {
        return trigger;
    }

    @Override
    public NavigationResult navigate(NavigationRequest request) {
        return NavigationResult.NOT_FOUND;
    }

    @Override
    public ExprScope conditionScope() {
        return new Scope(GlossConditionScope.viewer(plugin, player), instance.machine());
    }

    private record Scope(ExprScope viewer, RigScope rig) implements ExprScope {
        @Override
        public Object variable(String dottedName) {
            if (dottedName.startsWith(NAMESPACE_PREFIX)) {
                Object resolved = RigNamespace.resolveFor(rig, dottedName.substring(NAMESPACE_PREFIX.length()));
                if (resolved != null) {
                    return resolved;
                }
            }
            return viewer.variable(dottedName);
        }

        @Override
        public Object call(String name, List<Object> args) {
            return viewer.call(name, args);
        }
    }
}
