package art.arcane.gloss.surface;

import art.arcane.gloss.Gloss;
import art.arcane.volmlib.util.hud.HudBossBarLane;
import art.arcane.volmlib.util.hud.HudSegment;
import art.arcane.volmlib.util.hud.HudSlot;
import art.arcane.volmlib.util.hud.HudTitleClaim;
import art.arcane.volmlib.util.hud.HudTitleService;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Routes surfaces onto the VolmLib HUD compositor so Iris, Wormholes, React, Adapt and Gloss share
 * the action bar, the boss bar stack and the title slot instead of overwriting each other.
 */
public final class CompositorDelivery implements SurfaceDelivery {
    private final Gloss plugin;
    private final HudBossBarLane bossBars;
    private final HudTitleService titles;
    private final Map<UUID, TitleQueue> queues = new ConcurrentHashMap<>();
    private final Map<UUID, HudTitleClaim> titleClaims = new ConcurrentHashMap<>();

    public CompositorDelivery(Gloss plugin) {
        this.plugin = plugin;
        this.bossBars = new HudBossBarLane(plugin);
        this.titles = new HudTitleService(plugin);
    }

    public void start(long sweepPeriodTicks) {
        bossBars.startSweeper(sweepPeriodTicks);
    }

    public void shutdown() {
        bossBars.shutdown();
        titles.shutdown();
        queues.clear();
        titleClaims.clear();
    }

    @Override
    public void actionBar(Player viewer, String purpose, int priority, long ttlMillis, List<HudSlot> slots,
                          String text) {
        plugin.getHudBar().publish(viewer, new HudSegment(purpose, priority, ttlMillis, slots, text));
    }

    @Override
    public void clearActionBar(Player viewer, String purpose) {
        plugin.getHudBar().clear(viewer, purpose);
    }

    @Override
    public boolean bossBar(Player viewer, String laneId, int priority, String title, double progress, BarColor color,
                           BarStyle style, long staleMillis) {
        return bossBars.show(viewer, laneId, priority, title, progress, color, style, staleMillis,
            plugin.cfg().modules().surfaces().maxBossBarsPerViewer());
    }

    @Override
    public void hideBossBar(Player viewer, String laneId) {
        bossBars.hide(viewer, laneId);
    }

    @Override
    public void title(Player viewer, String purpose, int priority, String title, String subtitle, int fadeInTicks,
                      int stayTicks, int fadeOutTicks) {
        TitleQueue queue = queues.computeIfAbsent(viewer.getUniqueId(),
            key -> new TitleQueue(plugin.cfg().modules().surfaces().titleQueueLimit()));
        queue.offer(new TitleQueue.TitleRequest(purpose, priority, title, subtitle, fadeInTicks, stayTicks,
            fadeOutTicks));
        drain(viewer, queue);
    }

    /**
     * Hands the title slot back. A claim is only stood down by releasing it: bidding again at the
     * same priority loses to the live claim, because the compositor breaks that tie in favour of
     * whoever asked first.
     */
    @Override
    public void clearTitle(Player viewer, String purpose) {
        TitleQueue queue = queues.get(viewer.getUniqueId());
        if (queue != null) {
            queue.drop(purpose);
        }
        HudTitleClaim held = titleClaims.get(viewer.getUniqueId());
        if (held != null && held.purpose().equals(purpose)
            && titleClaims.remove(viewer.getUniqueId(), held)) {
            held.release();
        }
        if (queue != null) {
            drain(viewer, queue);
        }
    }

    @Override
    public void forget(UUID viewerId) {
        TitleQueue queue = queues.remove(viewerId);
        if (queue != null) {
            queue.clear();
        }
        HudTitleClaim held = titleClaims.remove(viewerId);
        if (held != null) {
            held.retire();
        }
    }

    public void retire(Player viewer) {
        forget(viewer.getUniqueId());
        bossBars.hideAll(viewer);
        titles.clear(viewer);
    }

    private void drain(Player viewer, TitleQueue queue) {
        TitleQueue.TitleRequest head = queue.peek();
        if (head == null) {
            return;
        }
        HudTitleClaim held = titleClaims.remove(viewer.getUniqueId());
        if (held != null) {
            held.release();
        }
        HudTitleClaim claim = titles.open(viewer, head.purpose(), head.priority(), head.ttlMillis(),
            preempted -> {
            });
        if (claim.show(head.title(), head.subtitle(), head.fadeInTicks(), head.stayTicks(), head.fadeOutTicks())) {
            queue.poll();
            titleClaims.put(viewer.getUniqueId(), claim);
            return;
        }
        queue.dropStaleHead();
    }
}
