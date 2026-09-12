package art.arcane.gloss.rig;

import art.arcane.gloss.animation.clip.ClipSample;
import com.github.retrooper.packetevents.util.Vector3f;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class RigPose {
    private RigPose() {
    }

    public static Transform rootTransform(float yaw, float pitch, float scale) {
        return new Transform(new Vector3f(0.0F, 0.0F, 0.0F),
            Quaternions.multiply(Quaternions.aroundY(-yaw), Quaternions.aroundX(pitch)),
            new Vector3f(scale, scale, scale));
    }

    public static Map<String, Transform> boneOverrides(Map<String, ClipSample> samples) {
        if (samples == null || samples.isEmpty()) {
            return Map.of();
        }
        Map<String, Transform> overrides = new HashMap<>(samples.size() * 2);
        for (Map.Entry<String, ClipSample> entry : samples.entrySet()) {
            ClipSample sample = entry.getValue();
            if (sample == null || sample.isNeutral()) {
                continue;
            }
            overrides.put(entry.getKey(), new Transform(
                new Vector3f((float) sample.offsetX(), (float) sample.offsetY(), (float) sample.offsetZ()),
                Quaternions.fromEulerDegrees((float) sample.rotationX(), (float) sample.rotationY(), (float) sample.rotationZ()),
                new Vector3f((float) sample.scaleX(), (float) sample.scaleY(), (float) sample.scaleZ())));
        }
        return overrides;
    }

    public static Map<String, Transform> partTransforms(RigModel model, List<Part> parts, Transform root,
                                                        Map<String, ClipSample> samples) {
        Map<String, Transform> bones = model.worldTransforms(boneOverrides(samples));
        Map<String, Transform> placedBones = new HashMap<>(bones.size() * 2);
        for (Map.Entry<String, Transform> entry : bones.entrySet()) {
            placedBones.put(entry.getKey(), root.compose(entry.getValue()));
        }
        Map<String, Transform> pose = new HashMap<>(parts.size() * 2);
        for (Part part : parts) {
            pose.put(part.id(), placedBones.get(part.bone()).compose(part.local()));
        }
        return pose;
    }
}
