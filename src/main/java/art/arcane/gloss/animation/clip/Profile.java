package art.arcane.gloss.animation.clip;

import java.util.List;

public record Profile(String id, int priority, List<String> materials, List<Clip> clips) {
}
