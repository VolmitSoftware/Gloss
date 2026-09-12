package art.arcane.gloss.rig;

import com.github.retrooper.packetevents.util.Quaternion4f;
import com.github.retrooper.packetevents.util.Vector3f;

public final class Quaternions {
    private static final float DEGREES_TO_RADIANS = (float) (Math.PI / 180.0D);
    private static final float EPSILON = 1.0E-8F;

    private Quaternions() {
    }

    public static Quaternion4f identity() {
        return new Quaternion4f(0.0F, 0.0F, 0.0F, 1.0F);
    }

    public static Quaternion4f fromEulerDegrees(float xDegrees, float yDegrees, float zDegrees) {
        return multiply(multiply(aroundX(xDegrees), aroundY(yDegrees)), aroundZ(zDegrees));
    }

    public static Quaternion4f aroundX(float degrees) {
        float half = degrees * DEGREES_TO_RADIANS * 0.5F;
        return new Quaternion4f((float) Math.sin(half), 0.0F, 0.0F, (float) Math.cos(half));
    }

    public static Quaternion4f aroundY(float degrees) {
        float half = degrees * DEGREES_TO_RADIANS * 0.5F;
        return new Quaternion4f(0.0F, (float) Math.sin(half), 0.0F, (float) Math.cos(half));
    }

    public static Quaternion4f aroundZ(float degrees) {
        float half = degrees * DEGREES_TO_RADIANS * 0.5F;
        return new Quaternion4f(0.0F, 0.0F, (float) Math.sin(half), (float) Math.cos(half));
    }

    public static Quaternion4f multiply(Quaternion4f left, Quaternion4f right) {
        float ax = left.getX();
        float ay = left.getY();
        float az = left.getZ();
        float aw = left.getW();
        float bx = right.getX();
        float by = right.getY();
        float bz = right.getZ();
        float bw = right.getW();
        return new Quaternion4f(
            aw * bx + ax * bw + ay * bz - az * by,
            aw * by - ax * bz + ay * bw + az * bx,
            aw * bz + ax * by - ay * bx + az * bw,
            aw * bw - ax * bx - ay * by - az * bz);
    }

    public static Vector3f rotate(Quaternion4f rotation, Vector3f vector) {
        float qx = rotation.getX();
        float qy = rotation.getY();
        float qz = rotation.getZ();
        float qw = rotation.getW();
        float vx = vector.getX();
        float vy = vector.getY();
        float vz = vector.getZ();
        float tx = 2.0F * (qy * vz - qz * vy);
        float ty = 2.0F * (qz * vx - qx * vz);
        float tz = 2.0F * (qx * vy - qy * vx);
        return new Vector3f(
            vx + qw * tx + (qy * tz - qz * ty),
            vy + qw * ty + (qz * tx - qx * tz),
            vz + qw * tz + (qx * ty - qy * tx));
    }

    public static Quaternion4f lookAt(Vector3f from, Vector3f to, Vector3f up) {
        Vector3f forward = normalize(to.subtract(from));
        if (forward == null) {
            return identity();
        }
        Vector3f right = normalize(up.crossProduct(forward));
        if (right == null) {
            Vector3f fallbackUp = Math.abs(forward.getY()) < 0.99F
                ? new Vector3f(0.0F, 1.0F, 0.0F)
                : new Vector3f(0.0F, 0.0F, 1.0F);
            right = normalize(fallbackUp.crossProduct(forward));
            if (right == null) {
                return identity();
            }
        }
        Vector3f trueUp = forward.crossProduct(right);
        return fromBasis(right, trueUp, forward);
    }

    public static Quaternion4f normalize(Quaternion4f rotation) {
        float x = rotation.getX();
        float y = rotation.getY();
        float z = rotation.getZ();
        float w = rotation.getW();
        float lengthSquared = x * x + y * y + z * z + w * w;
        if (lengthSquared < EPSILON) {
            return identity();
        }
        float inverse = (float) (1.0D / Math.sqrt(lengthSquared));
        return new Quaternion4f(x * inverse, y * inverse, z * inverse, w * inverse);
    }

    private static Quaternion4f fromBasis(Vector3f right, Vector3f up, Vector3f forward) {
        float m00 = right.getX();
        float m10 = right.getY();
        float m20 = right.getZ();
        float m01 = up.getX();
        float m11 = up.getY();
        float m21 = up.getZ();
        float m02 = forward.getX();
        float m12 = forward.getY();
        float m22 = forward.getZ();
        float trace = m00 + m11 + m22;
        float x;
        float y;
        float z;
        float w;
        if (trace >= 0.0F) {
            float s = (float) Math.sqrt(trace + 1.0F) * 2.0F;
            w = 0.25F * s;
            x = (m21 - m12) / s;
            y = (m02 - m20) / s;
            z = (m10 - m01) / s;
        } else if (m00 > m11 && m00 > m22) {
            float s = (float) Math.sqrt(1.0F + m00 - m11 - m22) * 2.0F;
            w = (m21 - m12) / s;
            x = 0.25F * s;
            y = (m01 + m10) / s;
            z = (m02 + m20) / s;
        } else if (m11 > m22) {
            float s = (float) Math.sqrt(1.0F + m11 - m00 - m22) * 2.0F;
            w = (m02 - m20) / s;
            x = (m01 + m10) / s;
            y = 0.25F * s;
            z = (m12 + m21) / s;
        } else {
            float s = (float) Math.sqrt(1.0F + m22 - m00 - m11) * 2.0F;
            w = (m10 - m01) / s;
            x = (m02 + m20) / s;
            y = (m12 + m21) / s;
            z = 0.25F * s;
        }
        return normalize(new Quaternion4f(x, y, z, w));
    }

    private static Vector3f normalize(Vector3f vector) {
        float lengthSquared = vector.dot(vector);
        if (lengthSquared < EPSILON) {
            return null;
        }
        return vector.multiply((float) (1.0D / Math.sqrt(lengthSquared)));
    }
}
