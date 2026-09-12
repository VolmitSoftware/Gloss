package art.arcane.gloss.surface;

import art.arcane.gloss.condition.BoundedConditionErrorCallback;
import art.arcane.gloss.condition.ShowCondition;
import art.arcane.gloss.doc.DocumentEnvelope;
import art.arcane.gloss.expr.ExprFunctions;
import art.arcane.gloss.expr.ExprScope;
import art.arcane.volmlib.util.hud.HudPriority;
import art.arcane.volmlib.util.hud.HudSlot;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The driver decides what each viewer sees per surface kind; a recording sink stands in for the
 * compositor so the routing, the deselection cleanup and the title triggers are pinned without a
 * server.
 */
class SurfaceDeliveryTest {
    private static final int DEFAULT_TTL_TICKS = 20;

    private final RecordingDelivery delivery = new RecordingDelivery();
    private final SurfaceDriver driver = new SurfaceDriver(delivery, (viewer, raw, scope) -> raw);
    private final Player viewer = player();

    @Test
    void aSelectedActionBarIsPublishedUnderItsSurfacePurpose() {
        apply(List.of(actionBar("welcome", 0, "true")), scope(), 0L);

        assertEquals(List.of("actionBar gloss:surface:welcome " + HudPriority.STATUS + " 1000 [CENTER] welcome-text"),
            delivery.calls);
    }

    @Test
    void anActionBarTtlFallsBackToTwiceTheRefreshInterval() {
        SurfaceRuntime runtime = SurfaceRuntime.compile("welcome", new SurfaceDoc(
            SurfaceDoc.CURRENT_SCHEMA_VERSION, DocumentEnvelope.INITIAL_REVISION, SurfaceKind.ACTIONBAR,
            ShowCondition.ALWAYS, new SurfaceDoc.Selection(0, "true"),
            new SurfaceDoc.Presentation("text", null, null, null, null, null, null, "notice", null, null,
                null, null, null, null), List.of()));

        apply(List.of(runtime), scope(), 0L);

        assertEquals(List.of("actionBar gloss:surface:welcome " + HudPriority.NOTICE + " 2000 [CENTER] text"),
            delivery.calls);
    }

    @Test
    void deselectingAnActionBarClearsItsPurposeExactlyOnce() {
        List<SurfaceRuntime> runtimes = List.of(actionBar("welcome", 0, "gate"));
        apply(runtimes, scope("gate", Boolean.TRUE), 0L);
        delivery.calls.clear();

        apply(runtimes, scope("gate", Boolean.FALSE), 20L);
        apply(runtimes, scope("gate", Boolean.FALSE), 40L);

        assertEquals(List.of("clearActionBar gloss:surface:welcome"), delivery.calls);
    }

    @Test
    void switchingActionBarDocumentsClearsTheOneItReplaced() {
        List<SurfaceRuntime> runtimes = List.of(actionBar("low", 0, "true"), actionBar("high", 50, "gate"));
        apply(runtimes, scope("gate", Boolean.FALSE), 0L);
        delivery.calls.clear();

        apply(runtimes, scope("gate", Boolean.TRUE), 20L);

        assertEquals(List.of("clearActionBar gloss:surface:low",
            "actionBar gloss:surface:high " + HudPriority.STATUS + " 1000 [CENTER] high-text"), delivery.calls);
    }

    @Test
    void aSelectedBossBarIsShownWithItsColourStyleAndProgress() {
        apply(List.of(bossBar("arena", "0.25")), scope(), 0L);

        assertEquals(List.of("bossBar gloss:surface:arena " + HudPriority.STATUS + " arena-title 0.25 RED SEGMENTED_10 1000"),
            delivery.calls);
    }

    @Test
    void deselectingABossBarHidesItsLane() {
        List<SurfaceRuntime> runtimes = List.of(bossBarGated("arena", "gate"));
        apply(runtimes, scope("gate", Boolean.TRUE), 0L);
        delivery.calls.clear();

        apply(runtimes, scope("gate", Boolean.FALSE), 20L);
        apply(runtimes, scope("gate", Boolean.FALSE), 40L);

        assertEquals(List.of("hideBossBar gloss:surface:arena"), delivery.calls);
    }

    @Test
    void aSelectTriggeredTitleFiresOnceOnTheTransition() {
        List<SurfaceRuntime> runtimes = List.of(title("hello", "select", null));

        apply(runtimes, scope(), 0L);
        apply(runtimes, scope(), 20L);
        apply(runtimes, scope(), 400L);

        assertEquals(List.of("title gloss:surface:hello " + HudPriority.STATUS + " hello-title hello-sub 10 40 10"),
            delivery.calls);
    }

    @Test
    void aSelectTriggeredTitleFiresAgainAfterTheDocumentWasDeselected() {
        List<SurfaceRuntime> runtimes = List.of(titleGated("hello", "gate"));
        apply(runtimes, scope("gate", Boolean.TRUE), 0L);
        apply(runtimes, scope("gate", Boolean.FALSE), 20L);
        apply(runtimes, scope("gate", Boolean.TRUE), 40L);

        assertEquals(2, delivery.calls.size());
        assertTrue(delivery.calls.get(0).startsWith("title gloss:surface:hello"));
        assertTrue(delivery.calls.get(1).startsWith("title gloss:surface:hello"));
    }

    @Test
    void anOnceTriggeredTitleNeverFiresTwiceForTheSameViewerSession() {
        List<SurfaceRuntime> runtimes = List.of(titleGatedTrigger("hello", "gate", "once", null));
        apply(runtimes, scope("gate", Boolean.TRUE), 0L);
        apply(runtimes, scope("gate", Boolean.FALSE), 20L);
        apply(runtimes, scope("gate", Boolean.TRUE), 40L);

        assertEquals(1, delivery.calls.size());
    }

    @Test
    void aRepeatTriggeredTitleNeverRefiresFasterThanItsStayTicks() {
        List<SurfaceRuntime> runtimes = List.of(title("hello", "repeat", 5));

        apply(runtimes, scope(), 0L);
        apply(runtimes, scope(), 20L);
        apply(runtimes, scope(), 39L);
        apply(runtimes, scope(), 40L);

        assertEquals(2, delivery.calls.size(), "repeatTicks below stayTicks is clamped to stayTicks");
    }

    @Test
    void forgettingAViewerDropsItsSurfaceState() {
        List<SurfaceRuntime> runtimes = List.of(actionBar("welcome", 0, "true"));
        apply(runtimes, scope(), 0L);
        driver.forget(viewerId(viewer));
        delivery.calls.clear();

        apply(runtimes, scope(), 20L);

        assertEquals(List.of("actionBar gloss:surface:welcome " + HudPriority.STATUS + " 1000 [CENTER] welcome-text"),
            delivery.calls);
    }

    private void apply(List<SurfaceRuntime> runtimes, ExprScope scope, long nowTicks) {
        driver.apply(viewer, scope, SurfaceDriver.byKind(runtimes), DEFAULT_TTL_TICKS, nowTicks,
            BoundedConditionErrorCallback.silent());
    }

    private static SurfaceRuntime actionBar(String id, int priority, String when) {
        return SurfaceRuntime.compile(id, new SurfaceDoc(SurfaceDoc.CURRENT_SCHEMA_VERSION,
            DocumentEnvelope.INITIAL_REVISION, SurfaceKind.ACTIONBAR, ShowCondition.ALWAYS,
            new SurfaceDoc.Selection(priority, when),
            new SurfaceDoc.Presentation(id + "-text", null, null, null, null, null, null, null, 20, null,
                null, null, null, null), List.of()));
    }

    private static SurfaceRuntime bossBar(String id, String progress) {
        return bossBar(id, progress, "true");
    }

    private static SurfaceRuntime bossBarGated(String id, String when) {
        return bossBar(id, "0.25", when);
    }

    private static SurfaceRuntime bossBar(String id, String progress, String when) {
        return SurfaceRuntime.compile(id, new SurfaceDoc(SurfaceDoc.CURRENT_SCHEMA_VERSION,
            DocumentEnvelope.INITIAL_REVISION, SurfaceKind.BOSSBAR, ShowCondition.ALWAYS,
            new SurfaceDoc.Selection(0, when),
            new SurfaceDoc.Presentation(null, null, id + "-title", null, progress, "red", "segmented_10",
                null, 20, null, null, null, null, null), List.of()));
    }

    private static SurfaceRuntime title(String id, String trigger, Integer repeatTicks) {
        return titleGatedTrigger(id, "true", trigger, repeatTicks);
    }

    private static SurfaceRuntime titleGated(String id, String when) {
        return titleGatedTrigger(id, when, "select", null);
    }

    private static SurfaceRuntime titleGatedTrigger(String id, String when, String trigger, Integer repeatTicks) {
        return SurfaceRuntime.compile(id, new SurfaceDoc(SurfaceDoc.CURRENT_SCHEMA_VERSION,
            DocumentEnvelope.INITIAL_REVISION, SurfaceKind.TITLE, ShowCondition.ALWAYS,
            new SurfaceDoc.Selection(0, when),
            new SurfaceDoc.Presentation(null, null, id + "-title", id + "-sub", null, null, null, null,
                null, null, null, null, trigger, repeatTicks), List.of()));
    }

    private static ExprScope scope() {
        return new TestScope(Map.of());
    }

    private static ExprScope scope(String name, Object value) {
        return new TestScope(Map.of(name, value));
    }

    private static UUID viewerId(Player player) {
        return player.getUniqueId();
    }

    private static Player player() {
        UUID id = UUID.randomUUID();
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> id;
                case "getName" -> "Viewer";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "Player[" + id + "]";
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }

    private record TestScope(Map<String, Object> variables) implements ExprScope {
        @Override
        public Object variable(String dottedName) {
            return variables.get(dottedName);
        }

        @Override
        public Object call(String name, List<Object> args) {
            return ExprFunctions.call(name, args);
        }
    }

    private static final class RecordingDelivery implements SurfaceDelivery {
        private final List<String> calls = new ArrayList<>();

        @Override
        public void actionBar(Player viewer, String purpose, int priority, long ttlMillis, List<HudSlot> slots,
                              String text) {
            calls.add("actionBar " + purpose + " " + priority + " " + ttlMillis + " " + slots + " " + text);
        }

        @Override
        public void clearActionBar(Player viewer, String purpose) {
            calls.add("clearActionBar " + purpose);
        }

        @Override
        public void clearTitle(Player viewer, String purpose) {
            calls.add("clearTitle " + purpose);
        }

        @Override
        public boolean bossBar(Player viewer, String laneId, int priority, String title, double progress,
                               BarColor color, BarStyle style, long staleMillis) {
            calls.add("bossBar " + laneId + " " + priority + " " + title + " " + progress + " " + color + " "
                + style + " " + staleMillis);
            return true;
        }

        @Override
        public void hideBossBar(Player viewer, String laneId) {
            calls.add("hideBossBar " + laneId);
        }

        @Override
        public void title(Player viewer, String purpose, int priority, String title, String subtitle, int fadeInTicks,
                          int stayTicks, int fadeOutTicks) {
            calls.add("title " + purpose + " " + priority + " " + title + " " + subtitle + " " + fadeInTicks + " "
                + stayTicks + " " + fadeOutTicks);
        }

        @Override
        public void forget(UUID viewerId) {
        }
    }
}
