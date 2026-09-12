package art.arcane.gloss.surface;

import art.arcane.volmlib.util.hud.HudSlot;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/**
 * The three native screen slots as the surface driver and the surface actions see them. The
 * production implementation is {@link CompositorDelivery}, which arbitrates with every other
 * VolmLib-aware plugin through the shared HUD compositor.
 */
public interface SurfaceDelivery {
    void actionBar(Player viewer, String purpose, int priority, long ttlMillis, List<HudSlot> slots, String text);

    void clearActionBar(Player viewer, String purpose);

    boolean bossBar(Player viewer, String laneId, int priority, String title, double progress, BarColor color,
                    BarStyle style, long staleMillis);

    void hideBossBar(Player viewer, String laneId);

    void title(Player viewer, String purpose, int priority, String title, String subtitle, int fadeInTicks,
               int stayTicks, int fadeOutTicks);

    /** Stands one purpose down and hands the title slot back to whoever bids for it next. */
    void clearTitle(Player viewer, String purpose);

    void forget(UUID viewerId);
}
