package art.arcane.gloss.velocity;

import art.arcane.gloss.expr.Expr;
import art.arcane.gloss.expr.ExprEvaluator;
import art.arcane.gloss.expr.ExpressionScope;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Drives action bar, boss bar and title surfaces through the viewer's Adventure audience on every
 * refresh tick, selecting the highest-priority matching document per surface kind. Each viewer owns
 * at most one boss bar, which is updated in place instead of being resent.
 */
public final class ProxySurfaces implements AutoCloseable {
    private static final long TICK_MILLIS = 50L;
    private static final String ACTION_BAR = "actionbar";
    private static final String BOSS_BAR = "bossbar";
    private static final String TITLE = "title";

    private final ProxyText text;
    private final Logger logger;
    private final Map<UUID, ViewerState> viewers = new ConcurrentHashMap<>();
    private final Set<String> reportedProgressFailures = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    public ProxySurfaces(ProxyText text, Logger logger) {
        this.text = text;
        this.logger = logger;
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
        ViewerState state = viewers.computeIfAbsent(viewer.getUniqueId(), ignored -> new ViewerState(viewer));
        List<ProxySurfaceDocuments.Document> documents = snapshot.surfaces();
        renderActionBar(viewer, state, select(documents, ACTION_BAR, scope), scope);
        renderBossBar(viewer, state, select(documents, BOSS_BAR, scope), scope);
        renderTitle(viewer, state, select(documents, TITLE, scope), scope);
    }

    public void clear(Player viewer) {
        ViewerState state = viewers.get(viewer.getUniqueId());
        if (state == null) {
            return;
        }
        clearActionBar(viewer, state);
        hideBossBar(viewer, state);
        state.titleKey = null;
    }

    /** Drops per-viewer selection state after a backend switch or an ownership restore. */
    public void reset(Player viewer) {
        ViewerState state = viewers.get(viewer.getUniqueId());
        if (state == null) {
            return;
        }
        state.actionBarId = null;
        state.titleKey = null;
        hideBossBar(viewer, state);
    }

    public void forget(UUID viewerId) {
        viewers.remove(viewerId);
    }

    @Override
    public void close() {
        closed = true;
        for (ViewerState state : viewers.values()) {
            hideBossBar(state.viewer, state);
        }
        viewers.clear();
        reportedProgressFailures.clear();
    }

    private void renderActionBar(Player viewer, ViewerState state, ProxySurfaceDocuments.Document document,
                                 ExpressionScope scope) {
        if (document == null) {
            clearActionBar(viewer, state);
            return;
        }
        state.actionBarId = document.id();
        viewer.sendActionBar(text.render(profile(document, scope).presentation().text(), scope));
    }

    private void clearActionBar(Player viewer, ViewerState state) {
        if (state.actionBarId == null) {
            return;
        }
        state.actionBarId = null;
        viewer.sendActionBar(Component.empty());
    }

    private void renderBossBar(Player viewer, ViewerState state, ProxySurfaceDocuments.Document document,
                               ExpressionScope scope) {
        if (document == null) {
            hideBossBar(viewer, state);
            return;
        }
        ProxySurfaceDocuments.Presentation presentation = profile(document, scope).presentation();
        Component name = text.render(presentation.title(), scope);
        float progress = progress(document.id(), presentation.progress(), scope);
        BossBar.Color color = color(presentation.color());
        BossBar.Overlay overlay = overlay(presentation.style());
        if (state.bossBar == null) {
            state.bossBar = BossBar.bossBar(name, progress, color, overlay);
        } else {
            state.bossBar.name(name).progress(progress).color(color).overlay(overlay);
        }
        if (!state.bossBarShown) {
            state.bossBarShown = true;
            viewer.showBossBar(state.bossBar);
        }
    }

    private void hideBossBar(Player viewer, ViewerState state) {
        if (!state.bossBarShown) {
            return;
        }
        state.bossBarShown = false;
        viewer.hideBossBar(state.bossBar);
    }

    private void renderTitle(Player viewer, ViewerState state, ProxySurfaceDocuments.Document document,
                             ExpressionScope scope) {
        if (document == null) {
            state.titleKey = null;
            return;
        }
        Profile profile = profile(document, scope);
        ProxySurfaceDocuments.Presentation presentation = profile.presentation();
        int stayTicks = presentation.stayTicks().intValue();
        int repeatTicks = presentation.repeatTicks() == null ? stayTicks
            : Math.max(stayTicks, presentation.repeatTicks().intValue());
        if (!state.armTitle(document.id(), profile.id(), presentation.trigger(), repeatTicks,
            System.currentTimeMillis() / TICK_MILLIS)) {
            return;
        }
        viewer.showTitle(Title.title(text.render(presentation.title(), scope),
            text.render(presentation.subtitle(), scope),
            Title.Times.times(duration(presentation.fadeInTicks().intValue()), duration(stayTicks),
                duration(presentation.fadeOutTicks().intValue()))));
    }

    private ProxySurfaceDocuments.Document select(List<ProxySurfaceDocuments.Document> documents, String kind,
                                                  ExpressionScope scope) {
        for (ProxySurfaceDocuments.Document document : documents) {
            if (document.kind().equals(kind) && text.test(document.show(), scope)
                && text.test(document.when(), scope)) {
                return document;
            }
        }
        return null;
    }

    private Profile profile(ProxySurfaceDocuments.Document document, ExpressionScope scope) {
        for (ProxySurfaceDocuments.Variant variant : document.variants()) {
            if (text.test(variant.when(), scope)) {
                return new Profile(variant.id(), variant.presentation());
            }
        }
        return new Profile("base", document.presentation());
    }

    private float progress(String id, Expr expression, ExpressionScope scope) {
        try {
            return (float) Math.clamp(ExprEvaluator.number(expression, scope), BossBar.MIN_PROGRESS,
                BossBar.MAX_PROGRESS);
        } catch (RuntimeException failure) {
            if (reportedProgressFailures.add(id)) {
                logger.error("Surface {} progress failed and was treated as empty.", id, failure);
            }
            return BossBar.MIN_PROGRESS;
        }
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

    private record Profile(String id, ProxySurfaceDocuments.Presentation presentation) {
    }

    private static final class ViewerState {
        private final Player viewer;
        private final Set<String> firedOnce = new HashSet<>();
        private String actionBarId;
        private BossBar bossBar;
        private boolean bossBarShown;
        private String titleKey;
        private long titleTick;

        private ViewerState(Player viewer) {
            this.viewer = viewer;
        }

        private boolean armTitle(String documentId, String profileId, String trigger, int repeatTicks,
                                 long nowTicks) {
            String key = documentId + "/" + profileId;
            if ("once".equals(trigger)) {
                if (!firedOnce.add(documentId)) {
                    return false;
                }
                titleKey = key;
                titleTick = nowTicks;
                return true;
            }
            if (!key.equals(titleKey)) {
                titleKey = key;
                titleTick = nowTicks;
                return true;
            }
            if ("repeat".equals(trigger) && nowTicks - titleTick >= repeatTicks) {
                titleTick = nowTicks;
                return true;
            }
            return false;
        }
    }
}
