package art.arcane.gloss.behavior;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.menu.action.ActionContext;
import art.arcane.gloss.menu.action.ActionOutcome;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

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
        long remaining = trigger == BehaviorTrigger.CHAT
            ? GlossConfig.current().modules().behaviors().chatMaxWorkUnits() : 0;
        Map<String, Long> documentWork = trigger == BehaviorTrigger.CHAT ? new HashMap<>() : Map.of();
        for (BehaviorSubscriptions.Subscription subscription : subscriptions.subscribed(trigger)) {
            if (trigger == BehaviorTrigger.CHAT) {
                if (remaining-- <= 0) {
                    warn("shared", "shared work limit");
                    break;
                }
                BehaviorDoc.Matching limits = subscription.runtime().doc().matching();
                String input = event.selector();
                if (input == null || input.length() > limits.maxInputCharacters()) {
                    warn(subscription.runtime().id(), "input size");
                    continue;
                }
                long requested = subscription.entry().pattern() == null ? 0
                    : (long) subscription.entry().pattern().programSize() * (input.length() + 1L);
                if (requested > remaining) {
                    warn("shared", "shared work limit");
                    break;
                }
                String id = subscription.runtime().id();
                long used = documentWork.getOrDefault(id, 0L);
                if (requested + 1 > limits.maxWorkUnits() - used) {
                    warn(id, "document work limit");
                    continue;
                }
                documentWork.put(id, used + requested + 1);
                remaining -= requested;
            }
            if (!accepts(subscription, event)) {
                continue;
            }
            matched++;
            run(subscription, event, contexts);
        }
        return matched;
    }

    private static void warn(String id, String reason) {
        if (Gloss.instance != null) {
            Gloss.logThrottled(Level.WARNING, "behavior-chat-limit:" + id,
                "Behavior %s reached its chat %s; matching was skipped and ordinary chat was retained.", id, reason);
        }
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
            default -> true;
        };
    }

    private static boolean matches(String wanted, String actual) {
        return wanted == null || wanted.equalsIgnoreCase(actual);
    }
}
