package art.arcane.gloss.menu.action;

import art.arcane.gloss.config.action.SoundActionData;
import org.bukkit.Sound;

public class SoundMenuAction extends MenuAction<SoundActionData> {

  private final Sound sound;

  public SoundMenuAction(SoundActionData data) {
    super(data);
    this.sound = data.resolveSound();
  }

  public boolean hasSound() {
    return sound != null;
  }

  @Override
  public ActionOutcome execute(ActionContext context) {
    if (sound == null)
      return ActionOutcome.CONTINUE;
    context.player().playSound(context.player().getLocation(), sound, data.sourceOrDefault().getCategory(), data.volumeOrDefault(), data.pitchOrDefault());
    return ActionOutcome.CONTINUE;
  }
}
