package art.arcane.gloss.menu.action;

import art.arcane.gloss.config.action.ChanceActionData;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

public final class ChanceMenuAction extends MenuAction<ChanceActionData> {
  private final RandomGenerator random;
  private final List<MenuAction<?>> then;
  private final List<MenuAction<?>> otherwise;

  public ChanceMenuAction(ChanceActionData data) {
    this(data, null);
  }

  public ChanceMenuAction(ChanceActionData data, RandomGenerator random) {
    super(data);
    this.random = random;
    this.then = MenuAction.resolve(data.then(), "chance", "then");
    this.otherwise = MenuAction.resolve(data.otherwise(), "chance", "else");
  }

  @Override
  public ActionOutcome execute(ActionContext context) {
    RandomGenerator generator = random == null ? ThreadLocalRandom.current() : random;
    boolean hit = generator.nextDouble() * 100.0D < data.percent();
    return Branches.run(hit ? then : otherwise, context);
  }

  @Override
  protected boolean requiresPlayer() {
    return false;
  }
}
