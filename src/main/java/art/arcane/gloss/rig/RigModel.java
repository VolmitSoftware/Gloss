package art.arcane.gloss.rig;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class RigModel {
    private final Map<String, Bone> bones;
    private final List<Bone> topological;

    private RigModel(Map<String, Bone> bones, List<Bone> topological) {
        this.bones = bones;
        this.topological = topological;
    }

    public static RigModel of(List<Bone> declared) {
        Objects.requireNonNull(declared, "bones");
        if (declared.isEmpty()) {
            throw new IllegalArgumentException("rig needs at least one bone");
        }
        Map<String, Bone> byId = new LinkedHashMap<>(declared.size() * 2);
        for (Bone bone : declared) {
            Objects.requireNonNull(bone, "bones must not contain null entries");
            if (byId.putIfAbsent(bone.id(), bone) != null) {
                throw new IllegalArgumentException("rig bone " + bone.id() + " is declared twice");
            }
        }
        for (Bone bone : byId.values()) {
            if (bone.parent() != null && !byId.containsKey(bone.parent())) {
                throw new IllegalArgumentException("rig bone " + bone.id() + " names unknown parent " + bone.parent());
            }
        }
        return new RigModel(Map.copyOf(byId), order(byId));
    }

    public Map<String, Bone> bones() {
        return bones;
    }

    public boolean hasBone(String id) {
        return id != null && bones.containsKey(id);
    }

    public List<Bone> topological() {
        return topological;
    }

    public Transform worldTransform(Map<String, Transform> boneOverrides, String boneId) {
        Bone bone = bones.get(boneId);
        if (bone == null) {
            throw new IllegalArgumentException("unknown rig bone " + boneId);
        }
        Transform local = local(bone, boneOverrides);
        if (bone.parent() == null) {
            return local;
        }
        return worldTransform(boneOverrides, bone.parent()).compose(local);
    }

    public Map<String, Transform> worldTransforms(Map<String, Transform> boneOverrides) {
        Map<String, Transform> world = new HashMap<>(topological.size() * 2);
        for (Bone bone : topological) {
            Transform local = local(bone, boneOverrides);
            world.put(bone.id(), bone.parent() == null ? local : world.get(bone.parent()).compose(local));
        }
        return world;
    }

    private static Transform local(Bone bone, Map<String, Transform> overrides) {
        Transform override = overrides == null ? null : overrides.get(bone.id());
        return override == null ? bone.rest() : bone.rest().compose(override);
    }

    private static List<Bone> order(Map<String, Bone> byId) {
        List<Bone> ordered = new ArrayList<>(byId.size());
        Set<String> placed = new HashSet<>(byId.size() * 2);
        Set<String> visiting = new HashSet<>();
        for (Bone bone : byId.values()) {
            place(bone, byId, placed, visiting, ordered);
        }
        return List.copyOf(ordered);
    }

    private static void place(Bone bone, Map<String, Bone> byId, Set<String> placed, Set<String> visiting,
                              List<Bone> ordered) {
        if (placed.contains(bone.id())) {
            return;
        }
        Deque<Bone> chain = new ArrayDeque<>();
        Bone current = bone;
        while (current != null && !placed.contains(current.id())) {
            if (!visiting.add(current.id())) {
                throw new IllegalArgumentException("rig bones form a cycle through " + current.id());
            }
            chain.push(current);
            current = current.parent() == null ? null : byId.get(current.parent());
        }
        while (!chain.isEmpty()) {
            Bone next = chain.pop();
            visiting.remove(next.id());
            placed.add(next.id());
            ordered.add(next);
        }
    }
}
