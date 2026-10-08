package art.arcane.gloss.surface;

import art.arcane.gloss.condition.BoundedConditionErrorCallback;
import art.arcane.gloss.expr.ExprScope;
import art.arcane.volmlib.util.hud.HudSlot;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarFlag;
import org.bukkit.boss.BarStyle;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SurfaceDispatchTest {
    private final RecordingDelivery delivery = new RecordingDelivery();
    private final SurfaceDriver driver = new SurfaceDriver(delivery, (viewer, raw, scope) -> raw);
    private final Player viewer = player();
    private final ExprScope scope = new ExprScope() {
        @Override
        public Object variable(String name) {
            return null;
        }

        @Override
        public Object call(String name, List<Object> args) {
            return null;
        }
    };
    private final BoundedConditionErrorCallback errors = BoundedConditionErrorCallback.silent();

    @Test
    void independentBossbarGroupsSelectOneDocumentEachAndCarryFlags() {
        SurfaceRuntime low = parse("low", "bossbar", "\"group\":\"health\",", "\"title\":\"Low\"");
        SurfaceRuntime other = parse("other", "bossbar", "\"group\":\"quests\",", "\"title\":\"Quests\",\"flags\":[\"create_fog\"]");
        driver.apply(viewer, scope, SurfaceDriver.byKind(List.of(low, other)), 1, 0, errors);
        assertEquals(2, delivery.bars.size());
        assertEquals(Set.of(Set.of(), Set.of(BarFlag.CREATE_FOG)), Set.of(delivery.bars.get(0).flags(), delivery.bars.get(1).flags()));
        driver.apply(viewer, scope, SurfaceDriver.byKind(List.of(low)), 1, 1, errors);
        assertEquals(List.of("gloss:surface:other"), delivery.hidden);
    }

    @Test
    void eventOnlyActionbarExpiresWithoutRefreshExtendingItsLifeThenFallbackReturns() {
        SurfaceRuntime fallback = parse("fallback", "actionbar", "", "\"text\":\"Fallback\"");
        SurfaceRuntime notice = parse("notice", "actionbar", "\"automatic\":false,", "\"text\":\"Notice\",\"ttlTicks\":4");
        List<SurfaceRuntime> documents = List.of(fallback, notice);
        driver.apply(viewer, scope, SurfaceDriver.byKind(documents), 1, 0, errors);
        assertEquals(List.of("Fallback"), delivery.actions.stream().map(Action::text).toList());
        assertEquals(SurfaceQueue.Outcome.STARTED, driver.submit(viewer, notice, scope, 1, errors));
        driver.apply(viewer, scope, SurfaceDriver.byKind(documents), 1, 1, errors);
        driver.apply(viewer, scope, SurfaceDriver.byKind(documents), 1, 4, errors);
        driver.apply(viewer, scope, SurfaceDriver.byKind(documents), 1, 5, errors);
        assertEquals(List.of("Fallback", "Notice", "Notice", "Fallback"), delivery.actions.stream().map(Action::text).toList());
        assertEquals(List.of(200L, 50L), delivery.actions.stream().filter(action -> action.text().equals("Notice")).map(Action::ttl).toList());
        assertEquals(List.of("gloss:surface:fallback", "gloss:surface-event:notice"), delivery.cleared);
    }

    @Test
    void unsupportedPlatformEventsAndNonBossbarFlagsFailAtLoad() {
        assertThrows(IllegalArgumentException.class, () -> parse("bad", "actionbar", "\"on\":[{\"trigger\":\"server_change\"}],", "\"text\":\"A\""));
        assertThrows(IllegalArgumentException.class, () -> parse("bad", "actionbar", "", "\"text\":\"A\",\"flags\":[\"create_fog\"]"));
    }

    private static SurfaceRuntime parse(String id, String kind, String fields, String presentation) {
        return SurfaceRuntime.compile(id, SurfaceDoc.parse(id + ".json", "{\"schemaVersion\":1,\"revision\":1,\"surface\":\"" + kind
            + "\",\"select\":{\"when\":\"true\"}," + fields + "\"presentation\":{" + presentation + "}}"));
    }

    private static Player player() {
        UUID id = UUID.randomUUID();
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> id;
                case "getName" -> "Viewer";
                case "hashCode" -> id.hashCode();
                case "equals" -> proxy == args[0];
                default -> null;
            });
    }

    private record Action(String text, long ttl) {
    }

    private static final class RecordingDelivery implements SurfaceDelivery {
        private final List<BossBarOptions> bars = new ArrayList<>();
        private final List<String> hidden = new ArrayList<>();
        private final List<String> cleared = new ArrayList<>();
        private final List<Action> actions = new ArrayList<>();

        @Override
        public void actionBar(Player viewer, String purpose, int priority, long ttlMillis, List<HudSlot> slots, String text) {
            actions.add(new Action(text, ttlMillis));
        }

        @Override
        public void clearActionBar(Player viewer, String purpose) {
            cleared.add(purpose);
        }

        @Override
        public boolean bossBar(Player viewer, String laneId, int priority, String title, double progress, BarColor color, BarStyle style, long staleMillis) {
            return true;
        }

        @Override
        public boolean bossBar(Player viewer, String laneId, BossBarOptions options) {
            bars.add(options);
            return true;
        }

        @Override
        public void hideBossBar(Player viewer, String laneId) {
            hidden.add(laneId);
        }

        @Override
        public void title(Player viewer, String purpose, int priority, String title, String subtitle, int fadeInTicks, int stayTicks, int fadeOutTicks) {
        }

        @Override
        public void clearTitle(Player viewer, String purpose) {
        }

        @Override
        public void forget(UUID viewerId) {
        }
    }
}
