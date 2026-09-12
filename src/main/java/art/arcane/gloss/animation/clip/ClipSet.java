package art.arcane.gloss.animation.clip;

import java.util.List;
import java.util.Map;

public record ClipSet(
    boolean enabled,
    Map<String, Map<String, MaterialProperties>> materialProperties,
    List<Profile> profiles
) {
    public static final ClipSet DISABLED = new ClipSet(false, Map.of(), List.of());
}
