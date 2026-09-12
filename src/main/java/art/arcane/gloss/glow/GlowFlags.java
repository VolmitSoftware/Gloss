package art.arcane.gloss.glow;

import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

/**
 * Rebuilds an entity's shared-flags byte. The glow bit cannot be sent alone: index 0 is one byte
 * of packed booleans, so a packet that only sets glowing would also tell the client the entity
 * stopped burning, sneaking and sprinting. Invisibility is only readable on living entities in the
 * Bukkit API, which is the only kind a glow tag is ever put on in practice.
 */
public final class GlowFlags {
    public static final int INDEX = 0;
    public static final byte ON_FIRE = 0x01;
    public static final byte SNEAKING = 0x02;
    public static final byte SPRINTING = 0x08;
    public static final byte SWIMMING = 0x10;
    public static final byte INVISIBLE = 0x20;
    public static final byte GLOWING = 0x40;
    public static final byte GLIDING = (byte) 0x80;

    private GlowFlags() {
    }

    public static byte of(Entity entity, boolean glowing) {
        byte flags = 0;
        if (entity.getFireTicks() > 0 || entity.isVisualFire()) {
            flags |= ON_FIRE;
        }
        if (entity.isGlowing() || glowing) {
            flags |= GLOWING;
        }
        if (entity instanceof Player player) {
            if (player.isSneaking()) {
                flags |= SNEAKING;
            }
            if (player.isSprinting()) {
                flags |= SPRINTING;
            }
        }
        if (entity instanceof LivingEntity living) {
            if (living.isInvisible()) {
                flags |= INVISIBLE;
            }
            if (living.isSwimming()) {
                flags |= SWIMMING;
            }
            if (living.isGliding()) {
                flags |= GLIDING;
            }
        }
        return flags;
    }
}
