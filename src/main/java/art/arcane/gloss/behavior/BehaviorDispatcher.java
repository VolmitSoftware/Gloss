package art.arcane.gloss.behavior;

import art.arcane.gloss.menu.action.ActionContext;
import art.arcane.gloss.menu.action.ActionOutcome;
import org.bukkit.entity.Player;

/** Filters a trigger event through the entry options, permission and {@code when}, then runs the program. */
public final class BehaviorDispatcher {
    @FunctionalInterface
    public interface ContextFactory {
        ActionContext create(BehaviorRuntime runtime, BehaviorRuntime.CompiledEntry entry, TriggerEvent event);
    }

    private BehaviorDispatcher() {
    }

    /** @return how many entries accepted the event (before permission and {@code when}) */
    public static int fire(BehaviorSubscriptions subscriptions, BehaviorTrigger trigger, TriggerEvent event,
                           ContextFactory contexts) {
        int matched = 0;
        for (BehaviorSubscriptions.Subscription subscription : subscriptions.subscribed(trigger)) {
            if (!accepts(subscription, event)) {
                continue;
            }
            matched++;
            run(subscription, event, contexts);
        }
        return matched;
    }

    public static ActionOutcome run(BehaviorSubscriptions.Subscription subscription, TriggerEvent event,
                                    ContextFactory contexts) {
        BehaviorRuntime.CompiledEntry compiled = subscription.entry();
        BehaviorEntry entry = compiled.entry();
        Player viewer = event.viewer();
        if (entry.permission() != null && (viewer == null || !viewer.hasPermission(entry.permission()))) {
            return ActionOutcome.CONTINUE;
        }
        ActionContext context = contexts.create(subscription.runtime(), compiled, event);
        if (compiled.when() != null && !compiled.when().matches(context.conditionScope(), compiled.errors())) {
            return ActionOutcome.CONTINUE;
        }
        return ActionProgram.run(compiled.actions(), 0, context);
    }

    static boolean accepts(BehaviorSubscriptions.Subscription subscription, TriggerEvent event) {
        BehaviorRuntime.CompiledEntry compiled = subscription.entry();
        BehaviorEntry entry = compiled.entry();
        return switch (entry.trigger()) {
            case REGION_ENTER, REGION_LEAVE -> entry.region().equalsIgnoreCase(event.selector());
            case BLOCK_BREAK, BLOCK_PLACE, PICKUP, DROP -> entry.material() == null
                || entry.material().equalsIgnoreCase(event.selector());
            case CHAT -> compiled.pattern() == null
                || (event.selector() != null && compiled.pattern().matcher(event.selector()).find());
            case COMMAND, EMIT -> entry.name().equals(event.selector());
            case MENU_OPEN, MENU_CLOSE, MENU_CLICK, INVENTORY_CLICK -> matches(entry.menu(), event.selector())
                && matches(entry.component(), event.secondary());
            case DIALOG_SUBMIT -> matches(entry.dialog(), event.selector());
            default -> true;
        };
    }

    private static boolean matches(String wanted, String actual) {
        return wanted == null || wanted.equalsIgnoreCase(actual);
    }
}
