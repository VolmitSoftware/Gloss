package art.arcane.gloss.animation.clip;

public record Keyframe(double tick, double value, String materialMap, Easing.Curve easing) {
}
