package art.arcane.gloss.menu.action;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.config.action.ActionEnvelope;
import art.arcane.gloss.config.action.MenuActionData;
import art.arcane.gloss.enums.MenuActionType;
import art.arcane.gloss.expr.ExprScope;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionEnvelopeTest {
    private static final UUID VIEWER = UUID.nameUUIDFromBytes("envelope-viewer".getBytes());

    @AfterEach
    void cleanUp() {
        ActionCooldowns.global().clear();
    }

    @Test
    void envelopeOfCollapsesBlankAndNonPositiveValuesToNone() {
        assertSame(ActionEnvelope.NONE, ActionEnvelope.of(null, null));
        assertSame(ActionEnvelope.NONE, ActionEnvelope.of("  ", 0));
        ActionEnvelope gated = ActionEnvelope.of(" viewer.level > 3 ", 20);
        assertEquals("viewer.level > 3", gated.when());
        assertEquals(20, gated.cooldownTicks());
    }

    @Test
    void whenGateSkipsTheActionWithoutStoppingTheList() {
        AtomicInteger ran = new AtomicInteger();
        List<MenuAction<?>> actions = List.of(
            new CountingAction(new CountingData("false", null), ran),
            new CountingAction(new CountingData("true", null), ran),
            new CountingAction(new CountingData("level > 3", null), ran));

        ActionOutcome outcome = MenuAction.execute(actions, context(5.0D));

        assertEquals(ActionOutcome.CONTINUE, outcome);
        assertEquals(2, ran.get());
    }

    @Test
    void throwingGateCountsAsClosed() {
        AtomicInteger ran = new AtomicInteger();
        List<MenuAction<?>> actions = List.of(new CountingAction(new CountingData("missing > 1", null), ran));

        MenuAction.execute(actions, context(5.0D));

        assertEquals(0, ran.get());
    }

    @Test
    void cooldownBlocksRepeatRunsPerPlayerAndPosition() {
        AtomicInteger ran = new AtomicInteger();
        List<MenuAction<?>> actions = List.of(new CountingAction(new CountingData(null, 100), ran));

        MenuAction.execute(actions, context(1.0D));
        MenuAction.execute(actions, context(1.0D));

        assertEquals(1, ran.get());
        ActionCooldowns.global().forget(VIEWER);
        MenuAction.execute(actions, context(1.0D));
        assertEquals(2, ran.get());
    }

    @Test
    void invalidGateIsReportedAtResolveTimeAndDropped() {
        List<MenuAction<?>> resolved = MenuAction.resolve(
            List.of(new CountingData("level >", null), new CountingData("true", null)), "shop", "buy");
        assertEquals(1, resolved.size());
        assertTrue(resolved.get(0) instanceof CountingAction);
    }

    private static ActionContext context(double level) {
        Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> VIEWER;
                case "getName" -> "envelope";
                case "hashCode" -> VIEWER.hashCode();
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.getName());
            });
        ExprScope scope = new ExprScope() {
            @Override
            public Object variable(String dottedName) {
                return dottedName.equals("level") ? level : null;
            }

            @Override
            public Object call(String name, List<Object> args) {
                return null;
            }
        };
        return new ActionContext() {
            @Override
            public Player player() {
                return player;
            }

            @Override
            public String menuId() {
                return "shop";
            }

            @Override
            public String componentId() {
                return "buy";
            }

            @Override
            public HoloClickTrigger trigger() {
                return HoloClickTrigger.RIGHT_CLICK;
            }

            @Override
            public NavigationResult navigate(NavigationRequest request) {
                return NavigationResult.DENIED;
            }

            @Override
            public ExprScope conditionScope() {
                return scope;
            }
        };
    }

    private record CountingData(String when, Integer cooldownTicks) implements MenuActionData {
        @Override
        public MenuActionType getType() {
            return MenuActionType.MESSAGE;
        }

        @Override
        public HoloClickTrigger trigger() {
            return null;
        }

        @Override
        public MenuAction<?> createAction() {
            return new CountingAction(this, new AtomicInteger());
        }

        @Override
        public ActionEnvelope envelope() {
            return ActionEnvelope.of(when, cooldownTicks);
        }
    }

    private static final class CountingAction extends MenuAction<CountingData> {
        private final AtomicInteger counter;

        private CountingAction(CountingData data, AtomicInteger counter) {
            super(data);
            this.counter = counter;
        }

        @Override
        public ActionOutcome execute(ActionContext context) {
            counter.incrementAndGet();
            return ActionOutcome.CONTINUE;
        }
    }
}
