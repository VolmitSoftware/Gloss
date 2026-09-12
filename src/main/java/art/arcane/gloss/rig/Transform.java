package art.arcane.gloss.rig;

import com.github.retrooper.packetevents.util.Quaternion4f;
import com.github.retrooper.packetevents.util.Vector3f;

import java.util.Objects;

public record Transform(Vector3f translation, Quaternion4f rotation, Vector3f scale) {
    private static final Vector3f ZERO = new Vector3f(0.0F, 0.0F, 0.0F);
    private static final Vector3f ONE = new Vector3f(1.0F, 1.0F, 1.0F);

    public Transform {
        translation = Objects.requireNonNull(translation, "translation");
        rotation = Objects.requireNonNull(rotation, "rotation");
        scale = Objects.requireNonNull(scale, "scale");
    }

    public static Transform identity() {
        return new Transform(ZERO, Quaternions.identity(), ONE);
    }

    public static Transform of(float x, float y, float z) {
        return new Transform(new Vector3f(x, y, z), Quaternions.identity(), ONE);
    }

    /**
     * Value equality, which the generated record equals does not give: packetevents'
     * {@code Quaternion4f} has no {@code equals}, so a rotation would compare by identity and
     * every frame of a rig standing still would look like a new one.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Transform transform)) {
            return false;
        }
        return translation.equals(transform.translation) && scale.equals(transform.scale)
            && sameRotation(rotation, transform.rotation);
    }

    @Override
    public int hashCode() {
        return Objects.hash(translation, scale, rotation.getX(), rotation.getY(), rotation.getZ(),
            rotation.getW());
    }

    private static boolean sameRotation(Quaternion4f left, Quaternion4f right) {
        return left == right || (Float.compare(left.getX(), right.getX()) == 0
            && Float.compare(left.getY(), right.getY()) == 0
            && Float.compare(left.getZ(), right.getZ()) == 0
            && Float.compare(left.getW(), right.getW()) == 0);
    }

    public Transform compose(Transform child) {
        Vector3f scaledChild = child.translation.multiply(scale);
        Vector3f translated = translation.add(Quaternions.rotate(rotation, scaledChild));
        return new Transform(translated, Quaternions.multiply(rotation, child.rotation), scale.multiply(child.scale));
    }
}
