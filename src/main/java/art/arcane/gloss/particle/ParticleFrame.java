package art.arcane.gloss.particle;

import art.arcane.gloss.api.ParticleLayer;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.Vector;

import java.util.Objects;

public record ParticleFrame(Location origin, Vector right, Vector up, Vector back) {
    public ParticleFrame {
        origin = Objects.requireNonNull(origin, "particle frame requires an origin").clone();
        Objects.requireNonNull(origin.getWorld(), "particle frame requires a world");
        right = normalize(right, "right");
        up = normalize(up, "up");
        back = normalize(back, "back");
    }

    @Override
    public Location origin() {
        return origin.clone();
    }

    @Override
    public Vector right() {
        return right.clone();
    }

    @Override
    public Vector up() {
        return up.clone();
    }

    @Override
    public Vector back() {
        return back.clone();
    }

    public Location world(Vector local, ParticleLayer.Placement placement) {
        Vector offset = placement.offset();
        double z = local.getZ() + offset.getZ() + placement.signedDepth();
        Vector translated = origin.toVector()
            .add(right.clone().multiply(local.getX() + offset.getX()))
            .add(up.clone().multiply(local.getY() + offset.getY()))
            .add(back.clone().multiply(z));
        World world = origin.getWorld();
        return translated.toLocation(world);
    }

    private static Vector normalize(Vector vector, String name) {
        Vector normalized = Objects.requireNonNull(vector, "particle frame requires a " + name + " axis").clone();
        if (!Double.isFinite(normalized.getX()) || !Double.isFinite(normalized.getY())
            || !Double.isFinite(normalized.getZ()) || normalized.lengthSquared() < 1.0E-12D) {
            throw new IllegalArgumentException("particle frame " + name + " axis must be finite and non-zero");
        }
        return normalized.normalize();
    }
}
