package art.arcane.gloss.surface;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.condition.BoundedConditionErrorCallback;
import art.arcane.gloss.expr.ExprScope;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decides what one viewer sees on each native screen slot and hands it to the compositor. The
 * service sweeps a slice of the fleet per tick with the same arithmetic the sidebar driver uses, so
 * a thousand viewers cost one interval's worth of work spread evenly instead of one burst.
 */
public final class SurfaceDriver {
    public static final String PURPOSE_PREFIX = "gloss:surface:";
    private static final long TICK_MILLIS = 50L;

    private final SurfaceDelivery delivery;
    private final SurfaceRenderer renderer;
    private final Map<UUID, ViewerState> states = new ConcurrentHashMap<>();

    public SurfaceDriver(SurfaceDelivery delivery, SurfaceRenderer renderer) {
        this.delivery = delivery;
        this.renderer = renderer;
    }

    public static Map<SurfaceKind, List<SurfaceRuntime>> byKind(List<SurfaceRuntime> runtimes) {
        Map<SurfaceKind, List<SurfaceRuntime>> grouped = new EnumMap<>(SurfaceKind.class);
        for (SurfaceKind kind : SurfaceKind.values()) {
            grouped.put(kind, new ArrayList<>());
        }
        for (SurfaceRuntime runtime : runtimes) {
            grouped.get(runtime.kind()).add(runtime);
        }
        Map<SurfaceKind, List<SurfaceRuntime>> published = new EnumMap<>(SurfaceKind.class);
        for (Map.Entry<SurfaceKind, List<SurfaceRuntime>> entry : grouped.entrySet()) {
            published.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return published;
    }

    /** At a one-tick interval every tick is a whole cycle, so the roster snapshot is pure waste. */
    public static boolean sweepsWholeFleet(int intervalTicks) {
        return intervalTicks <= 1;
    }

    /** Sweep slice for this tick: ceil(remaining / remaining stripes), so the cycle always closes. */
    public static int stripeSize(int remaining, int remainingStripes) {
        if (remaining <= 0) {
            return 0;
        }
        int stripes = Math.max(1, remainingStripes);
        return (remaining + stripes - 1) / stripes;
    }

    public void apply(Player viewer, ExprScope scope, Map<SurfaceKind, List<SurfaceRuntime>> byKind,
                      int defaultTtlTicks, long nowTicks, BoundedConditionErrorCallback errors) {
        ViewerState state = states.computeIfAbsent(viewer.getUniqueId(), key -> new ViewerState());
        applyActionBar(viewer, scope, byKind.get(SurfaceKind.ACTIONBAR), state, defaultTtlTicks, errors);
        applyBossBar(viewer, scope, byKind.get(SurfaceKind.BOSSBAR), state, defaultTtlTicks, errors);
        applyTitle(viewer, scope, byKind.get(SurfaceKind.TITLE), state, nowTicks, errors);
    }

    public void forget(UUID viewerId) {
        states.remove(viewerId);
        delivery.forget(viewerId);
    }

    /** Stands every surface this viewer currently holds down and forgets what was selected. */
    public void clear(Player viewer) {
        ViewerState state = states.remove(viewer.getUniqueId());
        if (state == null) {
            return;
        }
        String actionBar = state.selected.get(SurfaceKind.ACTIONBAR);
        if (actionBar != null) {
            delivery.clearActionBar(viewer, PURPOSE_PREFIX + actionBar);
        }
        String bossBar = state.selected.get(SurfaceKind.BOSSBAR);
        if (bossBar != null) {
            delivery.hideBossBar(viewer, PURPOSE_PREFIX + bossBar);
        }
        String title = state.selected.get(SurfaceKind.TITLE);
        if (title != null) {
            delivery.clearTitle(viewer, PURPOSE_PREFIX + title);
        }
    }

    public void clearAll() {
        states.clear();
    }

    private void applyActionBar(Player viewer, ExprScope scope, List<SurfaceRuntime> candidates, ViewerState state,
                                int defaultTtlTicks, BoundedConditionErrorCallback errors) {
        Optional<SurfaceRuntime> picked = SurfaceRuntime.pick(candidates, scope, errors);
        String previous = state.selected.get(SurfaceKind.ACTIONBAR);
        if (picked.isEmpty()) {
            if (previous != null) {
                state.selected.remove(SurfaceKind.ACTIONBAR);
                delivery.clearActionBar(viewer, PURPOSE_PREFIX + previous);
            }
            return;
        }
        SurfaceRuntime runtime = picked.get();
        if (previous != null && !previous.equals(runtime.id())) {
            delivery.clearActionBar(viewer, PURPOSE_PREFIX + previous);
        }
        state.selected.put(SurfaceKind.ACTIONBAR, runtime.id());
        ExprScope surfaceScope = new SurfaceScope(scope, runtime);
        SurfaceRuntime.SurfaceProfile profile = runtime.profile(surfaceScope, errors);
        int ttlTicks = profile.presentation().ttlTicks() == null
            ? Math.max(1, defaultTtlTicks * 2) : profile.presentation().ttlTicks();
        delivery.actionBar(viewer, PURPOSE_PREFIX + runtime.id(), profile.priorityValue(),
            ttlTicks * TICK_MILLIS, profile.slots(),
            renderer.render(viewer, profile.presentation().text(), surfaceScope));
    }

    private void applyBossBar(Player viewer, ExprScope scope, List<SurfaceRuntime> candidates, ViewerState state,
                              int defaultTtlTicks, BoundedConditionErrorCallback errors) {
        Optional<SurfaceRuntime> picked = SurfaceRuntime.pick(candidates, scope, errors);
        String previous = state.selected.get(SurfaceKind.BOSSBAR);
        if (picked.isEmpty()) {
            if (previous != null) {
                state.selected.remove(SurfaceKind.BOSSBAR);
                delivery.hideBossBar(viewer, PURPOSE_PREFIX + previous);
            }
            return;
        }
        SurfaceRuntime runtime = picked.get();
        if (previous != null && !previous.equals(runtime.id())) {
            delivery.hideBossBar(viewer, PURPOSE_PREFIX + previous);
        }
        state.selected.put(SurfaceKind.BOSSBAR, runtime.id());
        ExprScope surfaceScope = new SurfaceScope(scope, runtime);
        SurfaceRuntime.SurfaceProfile profile = runtime.profile(surfaceScope, errors);
        int ttlTicks = profile.presentation().ttlTicks() == null
            ? Math.max(1, defaultTtlTicks * 2) : profile.presentation().ttlTicks();
        boolean shown = delivery.bossBar(viewer, PURPOSE_PREFIX + runtime.id(), profile.priorityValue(),
            renderer.render(viewer, profile.presentation().title(), surfaceScope), profile.progress(surfaceScope),
            barColor(profile.presentation().color()), barStyle(profile.presentation().style()),
            ttlTicks * TICK_MILLIS);
        if (!shown) {
            Gloss.warnThrottled("surface-bossbar-denied:" + runtime.id(),
                "Boss bar surface \"%s\" did not get a slot for %s: the shared boss bar stack is full at "
                    + "[surfaces] maxBossBarsPerViewer, and every plugin's bars count against it.",
                runtime.id(), viewer.getName());
        }
    }

    private void applyTitle(Player viewer, ExprScope scope, List<SurfaceRuntime> candidates, ViewerState state,
                            long nowTicks, BoundedConditionErrorCallback errors) {
        Optional<SurfaceRuntime> picked = SurfaceRuntime.pick(candidates, scope, errors);
        if (picked.isEmpty()) {
            state.selected.remove(SurfaceKind.TITLE);
            state.lastTitleKey = null;
            return;
        }
        SurfaceRuntime runtime = picked.get();
        state.selected.put(SurfaceKind.TITLE, runtime.id());
        ExprScope surfaceScope = new SurfaceScope(scope, runtime);
        SurfaceRuntime.SurfaceProfile profile = runtime.profile(surfaceScope, errors);
        SurfaceDoc.Presentation presentation = profile.presentation();
        int stayTicks = presentation.stayTicks() == null ? SurfaceDoc.DEFAULT_STAY_TICKS : presentation.stayTicks();
        int repeatTicks = presentation.repeatTicks() == null ? stayTicks
            : Math.max(stayTicks, presentation.repeatTicks());
        if (!state.armTitle(runtime.id(), profile.id(), presentation.trigger(), repeatTicks, nowTicks)) {
            return;
        }
        delivery.title(viewer, PURPOSE_PREFIX + runtime.id(), profile.priorityValue(),
            renderer.render(viewer, presentation.title(), surfaceScope),
            renderer.render(viewer, presentation.subtitle(), surfaceScope),
            presentation.fadeInTicks() == null ? SurfaceDoc.DEFAULT_FADE_IN_TICKS : presentation.fadeInTicks(),
            stayTicks,
            presentation.fadeOutTicks() == null ? SurfaceDoc.DEFAULT_FADE_OUT_TICKS : presentation.fadeOutTicks());
    }

    private static BarColor barColor(String name) {
        return name == null ? BarColor.WHITE : BarColor.valueOf(name.toUpperCase(Locale.ROOT));
    }

    private static BarStyle barStyle(String name) {
        return name == null ? BarStyle.SOLID : BarStyle.valueOf(name.toUpperCase(Locale.ROOT));
    }

    /** Adds {@code surface.id} and {@code surface.kind} without rebuilding the viewer scope. */
    private record SurfaceScope(ExprScope delegate, SurfaceRuntime runtime) implements ExprScope {
        @Override
        public Object variable(String dottedName) {
            return switch (dottedName) {
                case "surface.id" -> runtime.id();
                case "surface.kind" -> runtime.kind().wireName();
                default -> delegate.variable(dottedName);
            };
        }

        @Override
        public Object call(String name, List<Object> args) {
            return delegate.call(name, args);
        }
    }

    @FunctionalInterface
    public interface SurfaceRenderer {
        String render(Player viewer, String raw, ExprScope scope);
    }

    private static final class ViewerState {
        private final Map<SurfaceKind, String> selected = new EnumMap<>(SurfaceKind.class);
        private final Set<String> firedOnce = new HashSet<>();
        private String lastTitleKey;
        private long lastTitleTick;

        private boolean armTitle(String surfaceId, String profileId, String trigger, int repeatTicks, long nowTicks) {
            String key = surfaceId + "/" + profileId;
            if ("once".equals(trigger)) {
                if (!firedOnce.add(surfaceId)) {
                    return false;
                }
                lastTitleKey = key;
                lastTitleTick = nowTicks;
                return true;
            }
            if (!key.equals(lastTitleKey)) {
                lastTitleKey = key;
                lastTitleTick = nowTicks;
                return true;
            }
            if ("repeat".equals(trigger) && nowTicks - lastTitleTick >= repeatTicks) {
                lastTitleTick = nowTicks;
                return true;
            }
            return false;
        }
    }
}
