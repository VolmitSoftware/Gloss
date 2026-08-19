package art.arcane.gloss.util.common;

import art.arcane.volmlib.util.bukkit.registry.RegistryUtil;
import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.util.Vector;

public final class ParticleUtils {
  private static final Particle REDSTONE = RegistryUtil.find(Particle.class, "redstone", "dust");

  public static void playParticle(World w, Vector v, Color c) {
    w.spawnParticle(REDSTONE, v.getX(), v.getY(), v.getZ(), 5, new Particle.DustOptions(c, 1));
  }

}
