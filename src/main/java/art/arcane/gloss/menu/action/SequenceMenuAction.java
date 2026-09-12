package art.arcane.gloss.menu.action;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.behavior.ActionProgram;
import art.arcane.gloss.config.action.SequenceActionData;
import art.arcane.gloss.state.StateSchema;
import art.arcane.gloss.state.StateScope;
import art.arcane.gloss.state.StateStore;
import art.arcane.gloss.state.StateStores;
import art.arcane.gloss.state.StateType;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerToggleSneakEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Runs the steps in cue order inside a child frame with the cues as its timeline. A skippable
 * sequence registers one sneak listener for its lifetime; sneaking cancels the child, runs
 * {@code onSkip} and resumes the parent list. {@code once} is written on completion only, under an
 * owner of its own so one scene's key never replaces another's declaration.
 */
public final class SequenceMenuAction extends MenuAction<SequenceActionData> {
  private static final String ONCE_OWNER = "sequence:once";

  private final List<MenuAction<?>> steps;
  private final int[] cues;
  private final List<MenuAction<?>> onSkip;

  public SequenceMenuAction(SequenceActionData data) {
    super(data);
    List<SequenceActionData.Step> ordered = new ArrayList<>(data.steps());
    int[] resolvedCues = new int[ordered.size()];
    int previous = 0;
    for (int index = 0; index < ordered.size(); index++) {
      Integer cue = ordered.get(index).atTicks();
      previous = cue == null ? previous : cue;
      resolvedCues[index] = previous;
    }
    List<Integer> order = new ArrayList<>(ordered.size());
    for (int index = 0; index < ordered.size(); index++) {
      order.add(index);
    }
    order.sort(Comparator.comparingInt(index -> resolvedCues[index]));
    List<MenuAction<?>> resolvedSteps = new ArrayList<>(ordered.size());
    List<Integer> keptCues = new ArrayList<>(ordered.size());
    for (Integer index : order) {
      List<MenuAction<?>> one = MenuAction.resolve(List.of(ordered.get(index).action()), "sequence", "step:" + index);
      if (!one.isEmpty()) {
        resolvedSteps.add(one.getFirst());
        keptCues.add(resolvedCues[index]);
      }
    }
    this.steps = List.copyOf(resolvedSteps);
    this.cues = new int[keptCues.size()];
    for (int index = 0; index < keptCues.size(); index++) {
      this.cues[index] = keptCues.get(index);
    }
    this.onSkip = MenuAction.resolve(data.onSkip(), "sequence", "onSkip");
  }

  @Override
  public ActionOutcome execute(ActionContext context) {
    ActionProgram.Frame frame = ActionProgram.current();
    if (frame == null) {
      return ActionOutcome.CONTINUE;
    }
    Player player = context.player();
    StateStore store = onceStore(player);
    if (store != null && Boolean.TRUE.equals(store.get(StateScope.PLAYER, player.getUniqueId(), data.once()))) {
      return ActionOutcome.CONTINUE;
    }
    int at = frame.cursor();
    SkipListener skip = data.skippableOrDefault() && player != null ? new SkipListener(player.getUniqueId()) : null;
    ActionProgram.Frame[] childHolder = new ActionProgram.Frame[1];
    ActionProgram.Frame child = frame.child(steps, cues, () -> {
      finish(store, player, skip);
      frame.resumeAfter(at);
    });
    childHolder[0] = child;
    if (skip != null) {
      skip.arm(child, frame, at, context);
      child.onDeath(skip::disarm);
    }
    ActionOutcome outcome = child.start();
    if (outcome == ActionOutcome.CONTINUE) {
      finish(store, player, skip);
    } else if (outcome == ActionOutcome.STOP && skip != null) {
      skip.disarm();
    }
    return outcome;
  }

  private StateStore onceStore(Player player) {
    if (data.once() == null || player == null) {
      return null;
    }
    StateStore store = StateStores.active();
    if (store == null) {
      return null;
    }
    StateSchema schema = new StateSchema(data.once(), StateScope.PLAYER, StateType.BOOLEAN, false);
    if (!store.ensureDeclared(ONCE_OWNER + ":" + data.once(), schema)) {
      Gloss.warnThrottled("sequence-once:" + data.once(),
          "Sequence once key \"%s\" is declared with another scope or type; the sequence repeats.", data.once());
      return null;
    }
    return store;
  }

  private void finish(StateStore store, Player player, SkipListener skip) {
    if (skip != null) {
      skip.disarm();
    }
    if (store != null) {
      store.set(StateScope.PLAYER, player.getUniqueId(), data.once(), Boolean.TRUE);
    }
  }

  private final class SkipListener implements Listener {
    private final UUID player;
    private ActionProgram.Frame child;
    private ActionProgram.Frame parent;
    private int at;
    private ActionContext context;
    private boolean registered;

    private SkipListener(UUID player) {
      this.player = player;
    }

    private void arm(ActionProgram.Frame child, ActionProgram.Frame parent, int at, ActionContext context) {
      this.child = child;
      this.parent = parent;
      this.at = at;
      this.context = context;
      Gloss plugin = Gloss.instance;
      if (plugin == null || !plugin.isEnabled()) {
        return;
      }
      Bukkit.getPluginManager().registerEvents(this, plugin);
      registered = true;
    }

    private void disarm() {
      if (registered) {
        HandlerList.unregisterAll(this);
        registered = false;
      }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSneak(PlayerToggleSneakEvent event) {
      if (!event.isSneaking() || !event.getPlayer().getUniqueId().equals(player) || child.isCancelled()) {
        return;
      }
      child.cancel();
      disarm();
      ActionProgram.run(onSkip, 0, context, parent.continuation());
      parent.resumeAfter(at);
    }
  }

  @Override
  protected boolean requiresPlayer() {
    return false;
  }
}
