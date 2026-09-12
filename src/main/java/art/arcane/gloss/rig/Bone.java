package art.arcane.gloss.rig;

import java.util.Objects;

public record Bone(String id, String parent, Transform rest) {
    public Bone {
        id = Objects.requireNonNull(id, "bone id").trim();
        if (id.isEmpty()) {
            throw new IllegalArgumentException("bone id must not be blank");
        }
        parent = parent == null || parent.isBlank() ? null : parent.trim();
        rest = rest == null ? Transform.identity() : rest;
    }

    public boolean isRoot() {
        return parent == null;
    }
}
