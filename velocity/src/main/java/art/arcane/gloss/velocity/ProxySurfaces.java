package art.arcane.gloss.velocity;

import art.arcane.gloss.expr.Expr;
import art.arcane.gloss.expr.ExprEvaluator;
import art.arcane.gloss.expr.ExpressionScope;
import art.arcane.gloss.surface.SurfaceQueue;
import art.arcane.gloss.surface.SurfaceTrigger;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

public final class ProxySurfaces implements AutoCloseable {
    private static final long TICK_MILLIS = 50L;
    private final ProxyText text;
    private final Logger logger;
    private final LongSupplier clock;
    private final Map<UUID, ViewerState> viewers = new ConcurrentHashMap<>();
    private final Set<String> reportedProgressFailures = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    public ProxySurfaces(ProxyText text, Logger logger) {
        this(text, logger, () -> System.nanoTime() / 50_000_000L);
    }

    ProxySurfaces(ProxyText text, Logger logger, LongSupplier clock) {
        this.text = text;
        this.logger = logger;
        this.clock = clock;
    }

    public void render(Player viewer, ProxyDocuments.Snapshot snapshot) {
        if (closed) {
            return;
        }
        if (!snapshot.settings().surfaces()) {
            clear(viewer);
            return;
        }
        ExpressionScope scope = text.scope(viewer, viewer);
        ViewerState state = state(viewer, snapshot.surfaces());
        long tick = clock.getAsLong();
        schedule(state, snapshot.surfaces(), scope, tick);
        drainSignals(state, scope, tick);
        renderActionBar(viewer, state, snapshot.surfaces(), scope, tick);
        renderBossBars(viewer, state, snapshot.surfaces(), scope, tick, snapshot.settings().surfaceMaxBossBarsPerViewer());
        renderTitle(viewer, state, snapshot.surfaces(), scope, tick);
    }

    public void event(Player viewer, ProxyDocuments.Snapshot snapshot, String event) {
        if (closed || !snapshot.settings().surfaces()) {
            return;
        }
        ViewerState state = state(viewer, snapshot.surfaces());
        ExpressionScope scope = text.scope(viewer, viewer);
        long tick = clock.getAsLong();
        for (ProxySurfaceDocuments.Document document : snapshot.surfaces()) {
            for (ProxySurfaceDocuments.Trigger trigger : document.on()) {
                if (trigger.settings().trigger().equals(event) && selected(document, scope) && text.test(trigger.when(), scope)) {
                    signal(state, document, tick + trigger.settings().delayTicks());
                }
            }
        }
        render(viewer, snapshot);
    }

    public void clear(Player viewer) {
        ViewerState state = viewers.get(viewer.getUniqueId());
        if (state == null) {
            return;
        }
        clearActionBar(viewer, state);
        hideBossBars(viewer, state);
        state.queues.clear();
        state.signals.clear();
        state.intervals.clear();
        state.titleKey = null;
        state.sentTitle = null;
    }

    public void reset(Player viewer) {
        clear(viewer);
    }

    public void forget(UUID viewerId) {
        viewers.remove(viewerId);
    }

    @Override
    public void close() {
        closed = true;
        for (ViewerState state : viewers.values()) {
            clear(state.viewer);
        }
        viewers.clear();
        reportedProgressFailures.clear();
    }

    private ViewerState state(Player viewer, List<ProxySurfaceDocuments.Document> documents) {
        ViewerState state = viewers.computeIfAbsent(viewer.getUniqueId(), ignored -> new ViewerState(viewer));
        if (state.documents != documents) {
            state.documents = documents;
            Set<String> groups = new HashSet<>();
            for (ProxySurfaceDocuments.Document document : documents) {
                if (document.kind().equals("bossbar")) {
                    groups.add(document.group());
                }
            }
            state.cachedBars.keySet().retainAll(groups);
            state.intervals.clear();
            state.signals.clear();
            state.queues.clear();
            state.sentTitle = null;
        }
        return state;
    }

    private void schedule(ViewerState state, List<ProxySurfaceDocuments.Document> documents, ExpressionScope scope, long tick) {
        for (ProxySurfaceDocuments.Document document : documents) {
            for (int index = 0; index < document.on().size(); index++) {
                ProxySurfaceDocuments.Trigger trigger = document.on().get(index);
                SurfaceTrigger settings = trigger.settings();
                if (!settings.trigger().equals("interval")) {
                    continue;
                }
                String key = document.id() + "/" + index;
                Long due = state.intervals.putIfAbsent(key, tick + settings.everyTicks());
                if (due == null || tick < due) {
                    continue;
                }
                state.intervals.put(key, tick + settings.everyTicks());
                if (selected(document, scope) && text.test(trigger.when(), scope)) {
                    signal(state, document, tick + settings.delayTicks());
                }
            }
        }
    }

    private void signal(ViewerState state, ProxySurfaceDocuments.Document document, long due) {
        if (state.signals.size() >= 256) {
            if (reportedProgressFailures.add("pending:" + document.id())) {
                logger.warn("Surface {} trigger rejected: viewer pending trigger limit is 256.", document.id());
            }
            return;
        }
        state.signals.add(new Signal(document, due));
    }

    private void drainSignals(ViewerState state, ExpressionScope scope, long tick) {
        Iterator<Signal> iterator = state.signals.iterator();
        while (iterator.hasNext()) {
            Signal signal = iterator.next();
            if (tick < signal.due()) {
                continue;
            }
            iterator.remove();
            if (text.test(signal.document().show(), scope)) {
                submit(state, emission(signal.document(), scope), tick);
            }
        }
    }

    private void submit(ViewerState state, Emission emission, long tick) {
        ProxySurfaceDocuments.Document document = emission.document();
        ProxySurfaceDocuments.Presentation presentation = emission.presentation();
        int duration = document.kind().equals("title")
            ? Math.max(1, presentation.fadeInTicks() + presentation.stayTicks() + presentation.fadeOutTicks())
            : presentation.ttlTicks() == null ? 100 : presentation.ttlTicks();
        SurfaceQueue<Emission> queue = state.queues.computeIfAbsent(lane(document), ignored -> new SurfaceQueue<>());
        SurfaceQueue.Outcome outcome = queue.offer(new SurfaceQueue.Request<>(document.id(), emission.content(),
            presentation.priority(), duration, document.delivery(), emission), tick);
        if (outcome == SurfaceQueue.Outcome.REJECTED && reportedProgressFailures.add("queue:" + document.id())) {
            logger.warn("Surface {} delivery rejected by its queue policy.", document.id());
        }
    }

    private void renderActionBar(Player viewer, ViewerState state, List<ProxySurfaceDocuments.Document> documents,
                                 ExpressionScope scope, long tick) {
        SurfaceQueue.Active<Emission> active = active(state, "actionbar", tick);
        ProxySurfaceDocuments.Document automatic = select(documents, "actionbar", scope);
        Emission emission = active != null ? active.request().value() : automatic == null ? null : emission(automatic, scope);
        if (emission == null) {
            clearActionBar(viewer, state);
            return;
        }
        state.actionBarId = emission.document().id();
        viewer.sendActionBar(emission.text());
    }

    private void clearActionBar(Player viewer, ViewerState state) {
        if (state.actionBarId != null) {
            state.actionBarId = null;
            viewer.sendActionBar(Component.empty());
        }
    }

    private void renderBossBars(Player viewer, ViewerState state, List<ProxySurfaceDocuments.Document> documents,
                                ExpressionScope scope, long tick, int maxBars) {
        Map<String, Emission> selected = new HashMap<>();
        for (ProxySurfaceDocuments.Document document : documents) {
            if (document.kind().equals("bossbar") && document.automatic() && selected(document, scope)) {
                selected.putIfAbsent(document.group(), emission(document, scope));
            }
        }
        for (Map.Entry<String, SurfaceQueue<Emission>> entry : state.queues.entrySet()) {
            if (!entry.getKey().startsWith("bossbar:")) {
                continue;
            }
            SurfaceQueue.Active<Emission> active = entry.getValue().advance(tick);
            if (active != null) {
                Emission emission = active.request().value();
                selected.put(emission.document().group(), emission);
            }
        }
        List<Emission> ranked = new ArrayList<>(selected.values());
        ranked.sort(Comparator.comparingInt((Emission emission) -> emission.presentation().priority()).reversed()
            .thenComparing(emission -> emission.document().group()));
        if (ranked.size() > maxBars) {
            ranked.subList(maxBars, ranked.size()).clear();
        }
        Set<String> visible = new HashSet<>();
        for (Emission emission : ranked) {
            visible.add(emission.document().group());
        }
        Iterator<Map.Entry<String, BossBar>> previous = state.bossBars.entrySet().iterator();
        while (previous.hasNext()) {
            Map.Entry<String, BossBar> entry = previous.next();
            if (!visible.contains(entry.getKey())) {
                viewer.hideBossBar(entry.getValue());
                previous.remove();
            }
        }
        for (Emission emission : ranked) {
            ProxySurfaceDocuments.Presentation presentation = emission.presentation();
            String group = emission.document().group();
            BossBar bar = state.bossBars.get(group);
            Set<BossBar.Flag> flags = flags(presentation.flags());
            boolean show = bar == null;
            if (bar == null) {
                bar = state.cachedBars.get(group);
            }
            if (bar == null) {
                bar = BossBar.bossBar(emission.title(), emission.progress(), color(presentation.color()), overlay(presentation.style()), flags);
                state.cachedBars.put(group, bar);
            } else {
                bar.name(emission.title()).progress(emission.progress()).color(color(presentation.color()))
                    .overlay(overlay(presentation.style())).flags(flags);
            }
            if (show) {
                state.bossBars.put(group, bar);
                viewer.showBossBar(bar);
            }
        }
    }

    private void hideBossBars(Player viewer, ViewerState state) {
        for (BossBar bar : state.bossBars.values()) {
            viewer.hideBossBar(bar);
        }
        state.bossBars.clear();
    }

    private void renderTitle(Player viewer, ViewerState state, List<ProxySurfaceDocuments.Document> documents,
                             ExpressionScope scope, long tick) {
        SurfaceQueue.Active<Emission> active = active(state, "title", tick);
        ProxySurfaceDocuments.Document document = select(documents, "title", scope);
        if (document == null) {
            state.titleKey = null;
        } else if (active == null || active.request().value().document().id().equals(document.id())) {
            Profile profile = profile(document, scope);
            ProxySurfaceDocuments.Presentation presentation = profile.presentation();
            int repeat = presentation.repeatTicks() == null ? presentation.stayTicks() : presentation.repeatTicks();
            if (state.armTitle(document.id(), profile.id(), presentation.trigger(), repeat, tick)) {
                submit(state, emission(document, scope), tick);
                active = active(state, "title", tick);
            }
        } else if (active != state.sentTitle) {
            state.titleKey = null;
        }
        if (active == state.sentTitle) {
            return;
        }
        state.sentTitle = active;
        if (active == null) {
            return;
        }
        Emission emission = active.request().value();
        ProxySurfaceDocuments.Presentation presentation = emission.presentation();
        viewer.showTitle(Title.title(emission.title(), emission.subtitle(), Title.Times.times(
            duration(presentation.fadeInTicks()), duration(presentation.stayTicks()), duration(presentation.fadeOutTicks()))));
    }

    private SurfaceQueue.Active<Emission> active(ViewerState state, String lane, long tick) {
        SurfaceQueue<Emission> queue = state.queues.get(lane);
        return queue == null ? null : queue.advance(tick);
    }

    private ProxySurfaceDocuments.Document select(List<ProxySurfaceDocuments.Document> documents, String kind, ExpressionScope scope) {
        for (ProxySurfaceDocuments.Document document : documents) {
            if (document.automatic() && document.kind().equals(kind) && selected(document, scope)) {
                return document;
            }
        }
        return null;
    }

    private boolean selected(ProxySurfaceDocuments.Document document, ExpressionScope scope) {
        return text.test(document.show(), scope) && text.test(document.when(), scope);
    }

    private Profile profile(ProxySurfaceDocuments.Document document, ExpressionScope scope) {
        for (ProxySurfaceDocuments.Variant variant : document.variants()) {
            if (text.test(variant.when(), scope)) {
                return new Profile(variant.id(), variant.presentation());
            }
        }
        return new Profile("base", document.presentation());
    }

    private Emission emission(ProxySurfaceDocuments.Document document, ExpressionScope scope) {
        ProxySurfaceDocuments.Presentation presentation = profile(document, scope).presentation();
        return new Emission(document, presentation, render(presentation.text(), scope), render(presentation.title(), scope),
            render(presentation.subtitle(), scope), presentation.progress() == null ? 1 : progress(document.id(), presentation.progress(), scope));
    }

    private Component render(String raw, ExpressionScope scope) {
        return raw == null ? Component.empty() : text.render(raw, scope);
    }

    private float progress(String id, Expr expression, ExpressionScope scope) {
        try {
            double value = ExprEvaluator.number(expression, scope);
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("Bossbar progress must be finite");
            }
            return (float) Math.clamp(value, BossBar.MIN_PROGRESS, BossBar.MAX_PROGRESS);
        } catch (RuntimeException failure) {
            if (reportedProgressFailures.add(id)) {
                logger.error("Surface {} progress failed and was treated as empty.", id, failure);
            }
            return BossBar.MIN_PROGRESS;
        }
    }

    private static String lane(ProxySurfaceDocuments.Document document) {
        return document.kind().equals("bossbar") ? "bossbar:" + document.group() : document.kind();
    }

    private static Set<BossBar.Flag> flags(List<String> names) {
        if (names.isEmpty()) {
            return Set.of();
        }
        Set<BossBar.Flag> flags = new HashSet<>();
        for (String name : names) {
            flags.add(switch (name) {
                case "darken_sky" -> BossBar.Flag.DARKEN_SCREEN;
                case "play_boss_music" -> BossBar.Flag.PLAY_BOSS_MUSIC;
                default -> BossBar.Flag.CREATE_WORLD_FOG;
            });
        }
        return Set.copyOf(flags);
    }

    private static BossBar.Color color(String name) {
        return switch (name) {
            case "pink" -> BossBar.Color.PINK;
            case "blue" -> BossBar.Color.BLUE;
            case "red" -> BossBar.Color.RED;
            case "green" -> BossBar.Color.GREEN;
            case "yellow" -> BossBar.Color.YELLOW;
            case "purple" -> BossBar.Color.PURPLE;
            default -> BossBar.Color.WHITE;
        };
    }

    private static BossBar.Overlay overlay(String style) {
        return switch (style) {
            case "segmented_6" -> BossBar.Overlay.NOTCHED_6;
            case "segmented_10" -> BossBar.Overlay.NOTCHED_10;
            case "segmented_12" -> BossBar.Overlay.NOTCHED_12;
            case "segmented_20" -> BossBar.Overlay.NOTCHED_20;
            default -> BossBar.Overlay.PROGRESS;
        };
    }

    private static Duration duration(int ticks) {
        return Duration.ofMillis(ticks * TICK_MILLIS);
    }

    private record Signal(ProxySurfaceDocuments.Document document, long due) {
    }

    private record Profile(String id, ProxySurfaceDocuments.Presentation presentation) {
    }

    private record Emission(ProxySurfaceDocuments.Document document, ProxySurfaceDocuments.Presentation presentation,
                             Component text, Component title, Component subtitle, float progress) {
        private String content() {
            return text.toString() + "\u0000" + title + "\u0000" + subtitle;
        }
    }

    private static final class ViewerState {
        private final Player viewer;
        private final Set<String> firedOnce = new HashSet<>();
        private final Map<String, BossBar> bossBars = new HashMap<>();
        private final Map<String, BossBar> cachedBars = new HashMap<>();
        private final Map<String, SurfaceQueue<Emission>> queues = new HashMap<>();
        private final Map<String, Long> intervals = new HashMap<>();
        private final List<Signal> signals = new ArrayList<>();
        private List<ProxySurfaceDocuments.Document> documents;
        private SurfaceQueue.Active<Emission> sentTitle;
        private String actionBarId;
        private String titleKey;
        private long titleTick;

        private ViewerState(Player viewer) {
            this.viewer = viewer;
        }

        private boolean armTitle(String documentId, String profileId, String trigger, int repeatTicks, long tick) {
            String key = documentId + "/" + profileId;
            if ("once".equals(trigger)) {
                if (!firedOnce.add(documentId)) {
                    return false;
                }
                titleKey = key;
                titleTick = tick;
                return true;
            }
            if (!key.equals(titleKey)) {
                titleKey = key;
                titleTick = tick;
                return true;
            }
            if ("repeat".equals(trigger) && tick - titleTick >= repeatTicks) {
                titleTick = tick;
                return true;
            }
            return false;
        }
    }
}
