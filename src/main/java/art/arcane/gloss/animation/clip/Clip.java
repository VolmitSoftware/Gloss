package art.arcane.gloss.animation.clip;

import java.util.List;

public record Clip(Trigger trigger, double durationTicks, LoopMode loopMode, List<Track> tracks) {
    public boolean loop() {
        return loopMode == LoopMode.LOOP;
    }
}
