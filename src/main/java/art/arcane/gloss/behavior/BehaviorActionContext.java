package art.arcane.gloss.behavior;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.condition.GlossConditionContext;
import art.arcane.gloss.condition.GlossConditionScope;
import art.arcane.gloss.expr.ExprScope;
import art.arcane.gloss.menu.action.ActionContext;
import art.arcane.gloss.menu.action.NavigationRequest;
import art.arcane.gloss.menu.action.NavigationResult;
import org.bukkit.entity.Player;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The action context of one entry firing: the trigger's roles become the condition scope's
 * viewer, subject and source, and its arguments are readable as {@code args.*}. The viewer may be
 * null for global intervals, server start and API emits; player-targeting actions then skip.
 */
public final class BehaviorActionContext implements ActionContext, ArgsView.Source {
    private final Gloss plugin;
    private final BehaviorRuntime runtime;
    private final BehaviorRuntime.CompiledEntry entry;
    private final TriggerEvent event;
    private ExprScope scope;

    public BehaviorActionContext(Gloss plugin, BehaviorRuntime runtime, BehaviorRuntime.CompiledEntry entry,
                                 TriggerEvent event) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.entry = Objects.requireNonNull(entry, "entry");
        this.event = Objects.requireNonNull(event, "event");
    }

    @Override
    public Player player() {
        return event.viewer();
    }

    @Override
    public String menuId() {
        return runtime.menuId();
    }

    @Override
    public String componentId() {
        return entry.componentId();
    }

    @Override
    public HoloClickTrigger trigger() {
        return HoloClickTrigger.ANY;
    }

    @Override
    public NavigationResult navigate(NavigationRequest request) {
        return NavigationResult.DENIED;
    }

    @Override
    public ExprScope conditionScope() {
        ExprScope current = scope;
        if (current == null) {
            Map<String, Object> values = new LinkedHashMap<>(event.args().size());
            for (Map.Entry<String, Object> arg : event.args().entrySet()) {
                values.put(ArgsView.PREFIX + "." + arg.getKey(), arg.getValue());
            }
            current = new GlossConditionScope(plugin, new GlossConditionContext(event.viewer(), event.subject(),
                event.source(), event.location(), values));
            scope = current;
        }
        return current;
    }

    @Override
    public boolean viewerless() {
        return event.viewer() == null;
    }

    @Override
    public Map<String, Object> args() {
        return event.args();
    }

    public TriggerEvent event() {
        return event;
    }
}
