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
    private final Map<UUID, SurfaceQueue<TitleRequest>> queues = new ConcurrentHashMap<>();
    private final Map<UUID, HudTitleClaim> titleClaims = new ConcurrentHashMap<>();
    private final Map<UUID, SurfaceQueue.Active<TitleRequest>> presented = new ConcurrentHashMap<>();

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
        presented.clear();
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
    public boolean bossBar(Player viewer, String laneId, BossBarOptions options) {
        return bossBars.show(viewer, laneId, new HudBossBarLane.Options(options.priority(), options.title(),
            options.progress(), options.color(), options.style(), options.staleMillis(),
            plugin.cfg().modules().surfaces().maxBossBarsPerViewer(), options.flags()));
    }

    @Override
    public void hideBossBar(Player viewer, String laneId) {
        bossBars.hide(viewer, laneId);
    }

    @Override
    public void title(Player viewer, String purpose, int priority, String title, String subtitle, int fadeInTicks,
                      int stayTicks, int fadeOutTicks) {
        SurfaceQueue<TitleRequest> queue = queues.computeIfAbsent(viewer.getUniqueId(), key -> new SurfaceQueue<>());
        TitleRequest request = new TitleRequest(purpose, priority, title, subtitle, fadeInTicks, stayTicks, fadeOutTicks);
        SurfaceDispatchPolicy policy = new SurfaceDispatchPolicy("queue", "higher",
            plugin.cfg().modules().surfaces().titleQueueLimit(), "drop-oldest", 0, "content", Math.min(72000, request.durationTicks()));
        queue.offer(new SurfaceQueue.Request<>(purpose, title + "\u0000" + subtitle, priority,
            request.durationTicks(), policy, request), now());
        drain(viewer, queue);
    }

    /**
     * Hands the title slot back. A claim is only stood down by releasing it: bidding again at the
     * same priority loses to the live claim, because the compositor breaks that tie in favour of
     * whoever asked first.
     */
    @Override
    public void clearTitle(Player viewer, String purpose) {
        SurfaceQueue<TitleRequest> queue = queues.get(viewer.getUniqueId());
        if (queue != null) {
            queue.remove(request -> request.purpose().equals(purpose));
        }
        HudTitleClaim held = titleClaims.get(viewer.getUniqueId());
        if (held != null && held.purpose().equals(purpose)
            && titleClaims.remove(viewer.getUniqueId(), held)) {
            held.release();
            presented.remove(viewer.getUniqueId());
        }
        if (queue != null) {
            drain(viewer, queue);
        }
    }

    @Override
    public void forget(UUID viewerId) {
        SurfaceQueue<TitleRequest> queue = queues.remove(viewerId);
        if (queue != null) {
            queue.clear();
        }
        presented.remove(viewerId);
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

    public void tick(Player viewer) {
        SurfaceQueue<TitleRequest> queue = queues.get(viewer.getUniqueId());
        if (queue != null) {
            drain(viewer, queue);
        }
    }

    private void drain(Player viewer, SurfaceQueue<TitleRequest> queue) {
        SurfaceQueue.Active<TitleRequest> active = queue.advance(now());
        UUID id = viewer.getUniqueId();
        if (active == presented.get(id)) {
            return;
        }
        HudTitleClaim held = titleClaims.remove(id);
        if (held != null) {
            held.release();
        }
        presented.remove(id);
        if (active == null) {
            return;
        }
        TitleRequest head = active.request().value();
        HudTitleClaim claim = titles.open(viewer, head.purpose(), head.priority(),
            Math.max(1, active.endsAt() - now()) * 50L, preempted -> {
                titleClaims.remove(id, preempted);
                presented.remove(id, active);
            });
        TitleTiming timing = TitleTiming.remaining(new TitleTiming(head.fadeInTicks(), head.stayTicks(), head.fadeOutTicks()),
            Math.max(0, now() - active.startedAt()));
        if (claim.show(head.title(), head.subtitle(), timing.fadeIn(), timing.stay(), timing.fadeOut())) {
            titleClaims.put(id, claim);
            presented.put(id, active);
        } else {
            claim.release();
        }
    }

    private static long now() {
        return System.nanoTime() / 50_000_000L;
    }

    record TitleTiming(int fadeIn, int stay, int fadeOut) {
        static TitleTiming remaining(TitleTiming original, long elapsed) {
            int fadeIn = (int) Math.max(0, original.fadeIn() - elapsed);
            long afterFade = Math.max(0, elapsed - original.fadeIn());
            int stay = (int) Math.max(0, original.stay() - afterFade);
            int fadeOut = (int) Math.max(0, original.fadeOut() - Math.max(0, afterFade - original.stay()));
            return new TitleTiming(fadeIn, stay, fadeOut);
        }
    }

    private record TitleRequest(String purpose, int priority, String title, String subtitle,
                                int fadeInTicks, int stayTicks, int fadeOutTicks) {
        private int durationTicks() {
            return Math.max(1, fadeInTicks + stayTicks + fadeOutTicks);
        }
    }
}
