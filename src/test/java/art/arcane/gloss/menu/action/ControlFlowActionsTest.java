package art.arcane.gloss.menu.action;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.behavior.EmitBus;
import art.arcane.gloss.config.action.ChanceActionData;
import art.arcane.gloss.config.action.CommandActionData;
import art.arcane.gloss.config.action.DelayActionData;
import art.arcane.gloss.config.action.MenuActionData;
import art.arcane.gloss.doc.DocumentParsers;
import art.arcane.gloss.expr.ExprFunctions;
import art.arcane.gloss.expr.ExprScope;
import art.arcane.gloss.expr.ExprVariableContext;
import art.arcane.gloss.state.StateSchema;
import art.arcane.gloss.state.StateScope;
import art.arcane.gloss.state.StateStore;
import art.arcane.gloss.state.StateStores;
import art.arcane.gloss.state.StateType;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlFlowActionsTest {
    private static final UUID VIEWER = UUID.nameUUIDFromBytes("flow-viewer".getBytes());
    private static final UUID SUBJECT = UUID.nameUUIDFromBytes("flow-subject".getBytes());
    private static final UUID WORLD = UUID.nameUUIDFromBytes("flow-world".getBytes());

    @TempDir
    Path folder;

    private StateStore store;

    @BeforeEach
    void setUp() {
        store = new StateStore(folder, Runnable::run, (player, task) -> task.run());
        store.declare(Map.of("flow", List.of(
            new StateSchema("visits", StateScope.PLAYER, StateType.NUMBER, 0),
            new StateSchema("tag", StateScope.PLAYER, StateType.STRING, ""),
            new StateSchema("weather", StateScope.WORLD, StateType.STRING, "clear"),
            new StateSchema("event", StateScope.GLOBAL, StateType.STRING, "none"))));
        store.beginLoad(VIEWER);
        store.beginLoad(SUBJECT);
        StateStores.install(store);
    }

    @AfterEach
    void cleanUp() {
        StateStores.install(null);
        ActionCooldowns.global().clear();
        EmitBus.install(null);
    }

    private static MenuActionData parse(String json) {
        return DocumentParsers.GSON.fromJson(json, MenuActionData.class);
    }

    private static ActionOutcome run(ActionContext context, MenuActionData... data) {
        return MenuAction.execute(MenuAction.resolve(List.of(data), "flow", "test"), context);
    }

    @Test
    void ifRunsThenOrElseAgainstTheConditionScope() {
        MenuActionData action = parse("{\"type\":\"if\",\"when\":\"level > 3\","
            + "\"then\":[{\"type\":\"addState\",\"key\":\"visits\",\"value\":\"1\"}],"
            + "\"else\":[{\"type\":\"setState\",\"key\":\"tag\",\"value\":\"'low'\"}]}");

        run(context(5.0D), action);
        run(context(1.0D), action);

        assertEquals(1.0D, store.get(StateScope.PLAYER, VIEWER, "visits"));
        assertEquals("low", store.get(StateScope.PLAYER, VIEWER, "tag"));
        assertEquals(2, action.nestedActions().size());
        assertNotNull(parse("{\"type\":\"if\",\"then\":[]}").invalidReason());
        assertNotNull(parse("{\"type\":\"if\",\"when\":\"level >\",\"then\":[]}").invalidReason());
    }

    @Test
    void switchPicksTheCaseMatchingTheEvaluatedValueOrTheDefault() {
        MenuActionData action = parse("{\"type\":\"switch\",\"on\":\"level\","
            + "\"cases\":{\"1\":[{\"type\":\"setState\",\"key\":\"tag\",\"value\":\"'one'\"}],"
            + "\"2\":[{\"type\":\"setState\",\"key\":\"tag\",\"value\":\"'two'\"}]},"
            + "\"default\":[{\"type\":\"setState\",\"key\":\"tag\",\"value\":\"'other'\"}]}");

        run(context(2.0D), action);
        assertEquals("two", store.get(StateScope.PLAYER, VIEWER, "tag"));
        run(context(7.0D), action);
        assertEquals("other", store.get(StateScope.PLAYER, VIEWER, "tag"));
        assertNotNull(parse("{\"type\":\"switch\",\"cases\":{\"a\":[]}}").invalidReason());
        assertNotNull(parse("{\"type\":\"switch\",\"on\":\"level\"}").invalidReason());
    }

    @Test
    void chanceUsesTheInjectedRandomAndValidatesPercent() {
        ChanceActionData data = (ChanceActionData) parse("{\"type\":\"chance\",\"percent\":50,"
            + "\"then\":[{\"type\":\"addState\",\"key\":\"visits\",\"value\":\"1\"}],"
            + "\"else\":[{\"type\":\"addState\",\"key\":\"visits\",\"value\":\"100\"}]}");
        ChanceMenuAction action = new ChanceMenuAction(data, new Random(7L));

        for (int run = 0; run < 40; run++) {
            MenuAction.execute(List.of(action), context(1.0D));
        }

        double visits = (Double) store.get(StateScope.PLAYER, VIEWER, "visits");
        assertTrue(visits > 100.0D && visits % 100.0D > 0.0D, String.valueOf(visits));
        assertNull(parse("{\"type\":\"chance\",\"percent\":0,\"then\":[]}").invalidReason());
        assertNotNull(parse("{\"type\":\"chance\",\"percent\":101,\"then\":[]}").invalidReason());
        assertNotNull(parse("{\"type\":\"chance\",\"then\":[]}").invalidReason());
    }

    @Test
    void cooldownClaimsOncePerPlayerAndKeyThenTakesTheElseBranch() {
        MenuActionData action = parse("{\"type\":\"cooldown\",\"key\":\"daily\",\"ticks\":1000,"
            + "\"then\":[{\"type\":\"addState\",\"key\":\"visits\",\"value\":\"1\"}],"
            + "\"else\":[{\"type\":\"setState\",\"key\":\"tag\",\"value\":\"'wait'\"}]}");

        run(context(1.0D), action);
        run(context(1.0D), action);

        assertEquals(1.0D, store.get(StateScope.PLAYER, VIEWER, "visits"));
        assertEquals("wait", store.get(StateScope.PLAYER, VIEWER, "tag"));
        assertNotNull(parse("{\"type\":\"cooldown\",\"ticks\":5,\"then\":[]}").invalidReason());
        assertNotNull(parse("{\"type\":\"cooldown\",\"key\":\"x\",\"ticks\":0,\"then\":[]}").invalidReason());
    }

    @Test
    void emitPassesEvaluatedArgsAndStopsRecursionAtDepthEight() {
        List<String> received = new ArrayList<>();
        AtomicInteger depth = new AtomicInteger();
        MenuActionData action = parse("{\"type\":\"emit\",\"name\":\"quest.complete\","
            + "\"args\":{\"quest\":\"mill\",\"level\":\"{{ level * 2 }}\",\"count\":3}}");
        EmitBus.install((name, args, viewer) -> {
            received.add(name + ":" + args.get("quest") + ":" + args.get("level") + ":" + args.get("count"));
            depth.incrementAndGet();
            run(context(2.0D), action);
        });

        run(context(2.0D), action);

        assertEquals(8, depth.get());
        assertEquals("quest.complete:mill:4.0:3.0", received.getFirst());
        assertNotNull(parse("{\"type\":\"emit\"}").invalidReason());
    }

    @Test
    void stateActionsWriteByDeclaredScopeAndTarget() {
        run(context(1.0D),
            parse("{\"type\":\"setState\",\"key\":\"visits\",\"value\":\"level + 4\"}"),
            parse("{\"type\":\"addState\",\"key\":\"visits\",\"value\":\"2\",\"target\":\"subject\"}"),
            parse("{\"type\":\"setState\",\"key\":\"weather\",\"value\":\"'rain'\"}"),
            parse("{\"type\":\"setState\",\"key\":\"event\",\"value\":\"'summer'\"}"),
            parse("{\"type\":\"setState\",\"key\":\"tag\",\"value\":\"'x'\",\"target\":\"subject\"}"),
            parse("{\"type\":\"clearState\",\"key\":\"tag\",\"target\":\"subject\"}"));

        assertEquals(5.0D, store.get(StateScope.PLAYER, VIEWER, "visits"));
        assertEquals(2.0D, store.get(StateScope.PLAYER, SUBJECT, "visits"));
        assertEquals("rain", store.get(StateScope.WORLD, WORLD, "weather"));
        assertEquals("summer", store.get(StateScope.GLOBAL, null, "event"));
        assertEquals("", store.get(StateScope.PLAYER, SUBJECT, "tag"));
        assertNotNull(parse("{\"type\":\"setState\",\"key\":\"visits\"}").invalidReason());
        assertNotNull(parse("{\"type\":\"setState\",\"key\":\"visits\",\"value\":\"1 +\"}").invalidReason());
        assertNotNull(parse("{\"type\":\"setState\",\"key\":\"visits\",\"value\":\"1\",\"target\":\"owner\"}").invalidReason());
        assertNotNull(parse("{\"type\":\"clearState\"}").invalidReason());
    }

    @Test
    void stopEndsTheListAndDelayValidatesItsTicks() {
        assertEquals(ActionOutcome.STOP, run(context(1.0D), parse("{\"type\":\"stop\"}"),
            parse("{\"type\":\"addState\",\"key\":\"visits\",\"value\":\"1\"}")));
        assertEquals(0.0D, store.get(StateScope.PLAYER, VIEWER, "visits"));
        DelayActionData delay = (DelayActionData) parse("{\"type\":\"delay\",\"ticks\":0}");
        assertNotNull(delay.invalidReason());
        assertNull(parse("{\"type\":\"delay\",\"ticks\":20}").invalidReason());
    }

    @Test
    void twoSequencesWithDifferentOnceKeysKeepBothDeclarations() {
        MenuActionData intro = parse("{\"type\":\"sequence\",\"once\":\"intro_seen\",\"steps\":["
            + "{\"type\":\"addState\",\"key\":\"visits\",\"value\":\"1\"}]}");
        MenuActionData tutorial = parse("{\"type\":\"sequence\",\"once\":\"tutorial_seen\",\"steps\":["
            + "{\"type\":\"addState\",\"key\":\"visits\",\"value\":\"1\"}]}");

        run(context(0.0D), intro);
        run(context(0.0D), tutorial);

        assertEquals(true, store.get(StateScope.PLAYER, VIEWER, "intro_seen"),
            "a second scene's once key must not undeclare the first");
        assertEquals(true, store.get(StateScope.PLAYER, VIEWER, "tutorial_seen"));

        run(context(0.0D), intro);
        run(context(0.0D), tutorial);
        assertEquals(2.0D, store.get(StateScope.PLAYER, VIEWER, "visits"),
            "a completed once scene never runs its steps again");
    }

    @Test
    void sequenceStepsCarryTheirCuesAndValidateTheirShape() {
        MenuActionData sequence = parse("{\"type\":\"sequence\",\"skippable\":true,\"once\":\"tag\",\"steps\":["
            + "{\"type\":\"setState\",\"key\":\"tag\",\"value\":\"'a'\"},"
            + "{\"type\":\"setState\",\"atTicks\":20,\"key\":\"tag\",\"value\":\"'b'\"}],"
            + "\"onSkip\":[{\"type\":\"setState\",\"key\":\"tag\",\"value\":\"'skipped'\"}]}");

        assertNull(sequence.invalidReason());
        assertEquals(3, sequence.nestedActions().size());
        assertNotNull(parse("{\"type\":\"sequence\"}").invalidReason());
        assertNotNull(parse("{\"type\":\"sequence\",\"steps\":[{\"type\":\"stop\",\"atTicks\":-1}]}").invalidReason());
        assertNotNull(parse("{\"type\":\"repeat\",\"steps\":[]}").invalidReason());
        assertNotNull(parse("{\"type\":\"repeat\",\"times\":2,\"while\":\"1 +\",\"steps\":[]}").invalidReason());
        assertNotNull(parse("{\"type\":\"parallel\"}").invalidReason());
        assertNotNull(parse("{\"type\":\"broadcast\"}").invalidReason());
        assertNotNull(parse("{\"type\":\"broadcast\",\"message\":\"hi\",\"scope\":\"planet\"}").invalidReason());
        assertNull(parse("{\"type\":\"broadcast\",\"message\":\"hi\",\"scope\":\"radius\",\"radius\":8}").invalidReason());
        assertNotNull(parse("{\"type\":\"effect\",\"ticks\":20}").invalidReason());
        assertNull(parse("{\"type\":\"effect\",\"effect\":\"minecraft:speed\",\"ticks\":20,\"amplifier\":1}").invalidReason());
        assertNotNull(parse("{\"type\":\"particle\",\"count\":5}").invalidReason());
        assertNotNull(parse("{\"type\":\"particle\",\"particle\":\"minecraft:flame\",\"at\":\"moon\"}").invalidReason());
        assertNull(parse("{\"type\":\"particle\",\"particle\":\"minecraft:flame\",\"count\":5,\"at\":\"subject\"}").invalidReason());
    }

    @Test
    void aViewerlessRunSkipsPlayerActionsAndKeepsGoing() {
        Recording needsPlayer = new Recording(true);
        Recording worksAlone = new Recording(false);
        List<MenuAction<?>> actions = List.of(needsPlayer, worksAlone);

        assertEquals(ActionOutcome.CONTINUE, MenuAction.execute(actions, playerless(true)));
        assertEquals(0, needsPlayer.executions);
        assertEquals(1, worksAlone.executions);

        assertEquals(ActionOutcome.CONTINUE, MenuAction.execute(actions, playerless(false)));
        assertEquals(1, needsPlayer.executions);
        assertEquals(2, worksAlone.executions);
    }

    private static ActionContext playerless(boolean viewerless) {
        return new ActionContext() {
            @Override
            public Player player() {
                return null;
            }

            @Override
            public String menuId() {
                return "flow";
            }

            @Override
            public String componentId() {
                return "test";
            }

            @Override
            public HoloClickTrigger trigger() {
                return HoloClickTrigger.ANY;
            }

            @Override
            public NavigationResult navigate(NavigationRequest request) {
                return NavigationResult.DENIED;
            }

            @Override
            public boolean viewerless() {
                return viewerless;
            }
        };
    }

    private static final class Recording extends MenuAction<CommandActionData> {
        private final boolean needsPlayer;
        private int executions;

        private Recording(boolean needsPlayer) {
            super(new CommandActionData(null, "test", HoloClickTrigger.ANY, null, null));
            this.needsPlayer = needsPlayer;
        }

        @Override
        public ActionOutcome execute(ActionContext context) {
            executions++;
            return ActionOutcome.CONTINUE;
        }

        @Override
        protected boolean requiresPlayer() {
            return needsPlayer;
        }
    }

    private static ActionContext context(double level) {
        World world = world();
        Player viewer = player(VIEWER, world);
        Player subject = player(SUBJECT, world);
        ExprScope scope = new ExprScope() {
            @Override
            public Object variable(String dottedName) {
                return dottedName.equals("level") ? level : null;
            }

            @Override
            public Object call(String name, List<Object> args) {
                return ExprFunctions.call(name, args);
            }

            @Override
            public ExprVariableContext variableContext() {
                return new ExprVariableContext(viewer, subject, null, viewer.getLocation());
            }
        };
        return new ActionContext() {
            @Override
            public Player player() {
                return viewer;
            }

            @Override
            public String menuId() {
                return "flow";
            }

            @Override
            public String componentId() {
                return "test";
            }

            @Override
            public HoloClickTrigger trigger() {
                return HoloClickTrigger.ANY;
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

    private static World world() {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getUID" -> WORLD;
                case "getName" -> "flow";
                case "hashCode" -> WORLD.hashCode();
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }

    private static Player player(UUID id, World world) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> id;
                case "getName" -> "flow";
                case "getWorld" -> world;
                case "getLocation" -> new Location(world, 0.0D, 64.0D, 0.0D);
                case "hashCode" -> id.hashCode();
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }
}
