package art.arcane.gloss.menu.action;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.behavior.ActionProgram;
import art.arcane.gloss.condition.CompiledCondition;
import art.arcane.gloss.condition.ConditionCompiler;
import art.arcane.gloss.condition.ConditionValidationException;
import art.arcane.gloss.config.action.ActionEnvelope;
import art.arcane.gloss.config.action.MenuActionData;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public abstract class MenuAction<E extends MenuActionData> {

  private static final String UNKNOWN_MENU = "unknown";
  private static final UUID GLOBAL_OWNER = new UUID(0L, 0L);
  private static final Set<ComponentWarning> WARNINGS = ConcurrentHashMap.newKeySet();

  protected final E data;
  private final CompiledCondition gate;

  public MenuAction(E data) {
    this.data = data;
    ActionEnvelope envelope = data == null ? ActionEnvelope.NONE : data.envelope();
    this.gate = envelope.hasWhen() ? ConditionCompiler.compile(envelope.when()) : null;
  }

  public static List<MenuAction<?>> resolve(List<MenuActionData> data, String menuId, String componentId) {
    List<MenuAction<?>> actions = new ArrayList<>(data == null ? 0 : data.size());
    if (data == null) {
      return actions;
    }

    for (MenuActionData entry : data) {
      MenuAction<?> action;
      try {
        action = entry == null ? null : entry.createAction();
      } catch (ConditionValidationException invalidGate) {
        String owner = menuId == null ? UNKNOWN_MENU : menuId;
        if (WARNINGS.add(new ComponentWarning(owner, componentId, invalidGate.getMessage()))) {
          Gloss.log(Level.WARNING, "Menu \"%s\" component \"%s\" declares an invalid when condition: %s; that action does nothing.",
              owner, componentId, invalidGate.getMessage());
        }
        continue;
      }
      if (action == null) {
        Gloss.log(Level.WARNING, "Component \"%s\" declares an unsupported action \"%s\"; skipping it.",
            componentId, entry == null ? "null" : entry.getClass().getSimpleName());
        continue;
      }

      String reason = entry.invalidReason();
      if (reason != null) {
        String owner = menuId == null ? UNKNOWN_MENU : menuId;
        if (WARNINGS.add(new ComponentWarning(owner, componentId, reason))) {
          Gloss.log(Level.WARNING, "Menu \"%s\" component \"%s\" %s; that action does nothing.",
              owner, componentId, reason);
        }
        continue;
      }

      actions.add(action);
    }

    return actions;
  }

  /** Runs the list as an action program; a suspended run reads as {@link ActionOutcome#STOP} to the caller. */
  public static ActionOutcome execute(List<MenuAction<?>> actions, ActionContext context) {
    ActionOutcome outcome = ActionProgram.run(actions, 0, context);
    return outcome == ActionOutcome.SUSPENDED ? ActionOutcome.STOP : outcome;
  }

  /** One step of the runner: trigger filter, then the gate and cooldown, then the action; a filtered action continues. */
  public static ActionOutcome executeAt(List<MenuAction<?>> actions, int index, ActionContext context) {
    MenuAction<?> action = actions.get(index);
    if (!action.data.triggerOrDefault().matches(context.trigger())) {
      return ActionOutcome.CONTINUE;
    }
    if (!action.gateOpen(context, index)) {
      return ActionOutcome.CONTINUE;
    }
    if (context.viewerless() && action.requiresPlayer()) {
      Gloss.warnThrottled("action-no-player:" + context.menuId(),
          "%s runs a %s action without a player; skipping it.", context.menuId(), action.data.getType().getSerializedName());
      return ActionOutcome.CONTINUE;
    }
    return action.execute(context);
  }

  /** True when the {@code when} gate passes (a throwing gate counts as closed) and the cooldown window is free. */
  boolean gateOpen(ActionContext context, int index) {
    if (gate != null && !gate.matches(context.conditionScope())) {
      return false;
    }
    ActionEnvelope envelope = data.envelope();
    if (!envelope.hasCooldown()) {
      return true;
    }
    String key = context.menuId() + "/" + context.componentId() + "/" + index;
    UUID owner = context.player() == null ? GLOBAL_OWNER : context.player().getUniqueId();
    return ActionCooldowns.global().claim(owner, key, envelope.cooldownTicks());
  }

  public abstract ActionOutcome execute(ActionContext context);

  /** False for actions that work without a viewer (control flow, state, broadcast, emit, effects on other roles). */
  protected boolean requiresPlayer() {
    return true;
  }

  private record ComponentWarning(String menuId, String componentId, String reason) {
  }
}
