package art.arcane.gloss.menu.action;

import art.arcane.gloss.config.action.CooldownActionData;

import java.util.List;
import java.util.UUID;

/** Claims the per-player window {@code behavior:<key>}; the first run in the window takes {@code then}, later runs {@code else}. */
public final class CooldownMenuAction extends MenuAction<CooldownActionData> {
  private static final UUID GLOBAL_OWNER = new UUID(0L, 0L);

  private final List<MenuAction<?>> then;
  private final List<MenuAction<?>> otherwise;

  public CooldownMenuAction(CooldownActionData data) {
    super(data);
    this.then = MenuAction.resolve(data.then(), "cooldown", "then");
    this.otherwise = MenuAction.resolve(data.otherwise(), "cooldown", "else");
  }

  @Override
  public ActionOutcome execute(ActionContext context) {
    UUID owner = context.player() == null ? GLOBAL_OWNER : context.player().getUniqueId();
    boolean free = ActionCooldowns.global().claim(owner, "behavior:" + data.key(), data.ticks());
    return Branches.run(free ? then : otherwise, context);
  }

  @Override
  protected boolean requiresPlayer() {
    return false;
  }
}
