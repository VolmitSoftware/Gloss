package art.arcane.gloss.behavior;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.config.GlossConfigFile;
import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.config.action.ActionEnvelope;
import art.arcane.gloss.config.action.DelayActionData;
import art.arcane.gloss.config.action.IfActionData;
import art.arcane.gloss.config.action.MenuActionData;
import art.arcane.gloss.config.action.ParallelActionData;
import art.arcane.gloss.config.action.RepeatActionData;
import art.arcane.gloss.config.action.SequenceActionData;
import art.arcane.gloss.config.action.StopActionData;
import art.arcane.gloss.enums.MenuActionType;
import art.arcane.gloss.expr.ExprFunctions;
import art.arcane.gloss.expr.ExprScope;
import art.arcane.gloss.menu.action.ActionContext;
import art.arcane.gloss.menu.CharacterizationSupport;
import art.arcane.gloss.menu.action.ActionOutcome;
import art.arcane.gloss.menu.action.MenuAction;
import art.arcane.gloss.menu.action.NavigationRequest;
import art.arcane.gloss.menu.action.NavigationResult;
import org.bukkit.entity.Player;
import org.bukkit.Server;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ActionProgramTest {
    private static final UUID VIEWER = UUID.nameUUIDFromBytes("program-viewer".getBytes());

    private final List<String> log = new ArrayList<>();
    private final FakeContinuation clock = new FakeContinuation();

    @AfterEach
    void cleanUp() {
        ActionBudget.global().configure(0);
        PendingTimers.global().resetAfterShutdown();
    }

    @Test
    void delaySuspendsTheListAndResumesAtTheNextIndexAfterTheTicks() {
        List<MenuAction<?>> actions = resolve(record("a"), delay(5), record("b"));

        ActionOutcome outcome = ActionProgram.run(actions, 0, context(), clock);

        assertEquals(ActionOutcome.SUSPENDED, outcome);
        assertEquals(List.of("a"), log);
        clock.advance(4);
        assertEquals(List.of("a"), log);
        clock.advance(1);
        assertEquals(List.of("a", "b"), log);
        assertEquals(0, clock.pending());
    }

    @Test
    void clickCallersSeeSuspendedAsStopAndAPlainListAsContinue() {
        assertEquals(ActionOutcome.STOP, MenuAction.execute(resolve(record("a"), delay(1), record("b")), context()));
        assertEquals(ActionOutcome.CONTINUE, MenuAction.execute(resolve(record("c")), context()));
        assertEquals(List.of("a", "c"), log);
    }

    @Test
    void sequenceCuesFireAtTheirTicksRelativeToTheStart() {
        SequenceActionData sequence = new SequenceActionData(List.of(
            new SequenceActionData.Step(null, record("s0")),
            new SequenceActionData.Step(20, record("s1")),
            new SequenceActionData.Step(20, record("s2")),
            new SequenceActionData.Step(40, record("s3"))), null, null, null, null, null, null);
        List<MenuAction<?>> actions = resolve(sequence, record("after"));

        assertEquals(ActionOutcome.SUSPENDED, ActionProgram.run(actions, 0, context(), clock));
        assertEquals(List.of("s0"), log);
        clock.advance(19);
        assertEquals(List.of("s0"), log);
        clock.advance(1);
        assertEquals(List.of("s0", "s1", "s2"), log);
        clock.advance(20);
        assertEquals(List.of("s0", "s1", "s2", "s3", "after"), log);
    }

    @Test
    void repeatRunsUntilTimesOrTheWhileConditionFails() {
        AtomicInteger counter = new AtomicInteger();
        RepeatActionData repeat = new RepeatActionData(5, 10, List.of(record("tick")), "counter < 3", null, null, null);
        List<MenuAction<?>> actions = resolve(repeat, record("done"));

        assertEquals(ActionOutcome.SUSPENDED, ActionProgram.run(actions, 0, context(counter), clock));
        assertEquals(List.of("tick"), log);
        counter.incrementAndGet();
        clock.advance(10);
        assertEquals(List.of("tick", "tick"), log);
        counter.incrementAndGet();
        clock.advance(10);
        assertEquals(List.of("tick", "tick", "tick"), log);
        counter.incrementAndGet();
        clock.advance(10);
        assertEquals(List.of("tick", "tick", "tick", "done"), log);
    }

    @Test
    void repeatWithoutADelayLoopsSynchronously() {
        RepeatActionData repeat = new RepeatActionData(3, null, List.of(record("x")), null, null, null, null);

        assertEquals(ActionOutcome.CONTINUE, ActionProgram.run(resolve(repeat, record("done")), 0, context(), clock));
        assertEquals(List.of("x", "x", "x", "done"), log);
    }

    @Test
    void parallelBranchesRunIndependentlyAndTheListContinues() {
        ParallelActionData parallel = new ParallelActionData(List.of(
            List.of(record("a1"), delay(2), record("a2")),
            List.of(record("b1"), delay(1), record("b2"))), null, null, null);

        assertEquals(ActionOutcome.CONTINUE, ActionProgram.run(resolve(parallel, record("after")), 0, context(), clock));
        assertEquals(List.of("a1", "b1", "after"), log);
        clock.advance(1);
        assertEquals(List.of("a1", "b1", "after", "b2"), log);
        clock.advance(1);
        assertEquals(List.of("a1", "b1", "after", "b2", "a2"), log);
    }

    @Test
    void aSuspendedBranchResumesItsParentWhenItCompletes() {
        IfActionData branch = new IfActionData("true", List.of(record("a"), delay(3), record("b")), List.of(), null, null);

        assertEquals(ActionOutcome.SUSPENDED, ActionProgram.run(resolve(branch, record("c")), 0, context(), clock));
        assertEquals(List.of("a"), log);
        clock.advance(3);
        assertEquals(List.of("a", "b", "c"), log);
    }

    @Test
    void stopInsideABranchEndsTheWholeProgram() {
        IfActionData branch = new IfActionData("true", List.of(record("a"), new StopActionData(null, null, null)), List.of(), null, null);

        assertEquals(ActionOutcome.STOP, ActionProgram.run(resolve(branch, record("c")), 0, context(), clock));
        assertEquals(List.of("a"), log);
    }

    @Test
    void anExhaustedTokenBucketDefersAResumeByOneTick() {
        ActionBudget.global().configure(1);
        ActionProgram.run(resolve(delay(1), record("first")), 0, context(), clock);
        ActionProgram.run(resolve(delay(1), record("second")), 0, context(), clock);

        clock.advance(1);
        assertEquals(List.of("first"), log);
        clock.advance(1);
        assertEquals(List.of("first"), log);
        ActionBudget.global().refill();
        clock.advance(1);
        assertEquals(List.of("first", "second"), log);
    }

    @Test
    void thePerPlayerTimerCapKillsARunThatCannotSchedule() {
        FakeContinuation capped = new FakeContinuation(VIEWER, 1);

        assertEquals(ActionOutcome.SUSPENDED, ActionProgram.run(resolve(delay(1), record("one")), 0, context(), capped));
        assertEquals(ActionOutcome.STOP, ActionProgram.run(resolve(delay(1), record("two")), 0, context(), capped));
        capped.advance(1);
        assertEquals(List.of("one"), log);
        assertEquals(ActionOutcome.SUSPENDED, ActionProgram.run(resolve(delay(1), record("three")), 0, context(), capped));
    }

    @Test
    void aRetiredPlayerNeverResumesAndFreesItsTimers() {
        FakeContinuation capped = new FakeContinuation(VIEWER, 1);
        ActionProgram.run(resolve(delay(1), record("never")), 0, context(), capped);

        capped.retire();
        capped.advance(5);

        assertTrue(log.isEmpty());
        assertEquals(0, PendingTimers.global().pending(VIEWER));
    }

    @Test
    void aSuspendedRunRunsItsDeathHookWhenTheEntityRetires() {
        AtomicBoolean died = new AtomicBoolean();
        List<MenuAction<?>> actions = resolve(hook(died), delay(5), record("b"));

        assertEquals(ActionOutcome.SUSPENDED, ActionProgram.run(actions, 0, context(), clock));
        assertFalse(died.get());

        clock.retire();

        assertTrue(died.get(), "a run the scheduler retires must release what it armed");
        assertEquals(List.of(), log);
    }

    @Test
    void aRunRefusedItsNextResumeRunsItsDeathHook() {
        AtomicBoolean died = new AtomicBoolean();
        List<MenuAction<?>> actions = resolve(hook(died), delay(5), record("b"), delay(5), record("c"));

        assertEquals(ActionOutcome.SUSPENDED, ActionProgram.run(actions, 0, context(), clock));
        clock.refuse();
        clock.advance(5);

        assertEquals(List.of("b"), log);
        assertTrue(died.get(), "a refused reschedule after the first suspension must not be silent");
    }

    @Test
    void schedulerRefusalReleasesTheProductionTimerReservation() throws ReflectiveOperationException {
        Gloss previous = CharacterizationSupport.installGloss(
            CharacterizationSupport.bareGloss(CharacterizationSupport.server(Map.of())));
        try {
            AtomicBoolean died = new AtomicBoolean();
            assertEquals(ActionOutcome.STOP,
                ActionProgram.run(resolve(hook(died), delay(5)), 0, context()));
            assertTrue(died.get());
            assertEquals(0, PendingTimers.global().pending(VIEWER));
        } finally {
            CharacterizationSupport.restoreGloss(previous);
        }
    }

    @Test
    void schedulingExceptionReleasesTheProductionLeaseAndRunsTheDeathHook() throws ReflectiveOperationException {
        Gloss previous = CharacterizationSupport.installGloss(
            CharacterizationSupport.bareGloss(CharacterizationSupport.server(Map.of())));
        IllegalStateException failure = new IllegalStateException("entity scheduling failed");
        Player player = (Player) CharacterizationSupport.proxy(new Class<?>[]{Player.class},
            (proxy, method, arguments) -> {
                if (method.getName().equals("getUniqueId")) {
                    if (PendingTimers.global().pending(VIEWER) > 0) {
                        throw failure;
                    }
                    return VIEWER;
                }
                return CharacterizationSupport.identity(proxy, method, arguments);
            });
        try {
            AtomicBoolean died = new AtomicBoolean();
            assertSame(failure, assertThrows(IllegalStateException.class,
                () -> ActionProgram.run(resolve(hook(died), delay(5)), 0,
                    context(new AtomicInteger(), player))));
            assertTrue(died.get());
            assertEquals(0, PendingTimers.global().pending(VIEWER));
        } finally {
            CharacterizationSupport.restoreGloss(previous);
        }
    }

    @Test
    void retiringAnOldContinuationDoesNotReleaseARejoinedPlayersTimer() {
        FakeContinuation old = new FakeContinuation(VIEWER, 1);
        FakeContinuation replacement = new FakeContinuation(VIEWER, 1);
        ActionProgram.run(resolve(delay(5), record("old")), 0, context(), old);
        PendingTimers.global().forget(VIEWER);
        ActionProgram.run(resolve(delay(5), record("new")), 0, context(), replacement);

        old.retire();

        assertEquals(1, PendingTimers.global().pending(VIEWER));
        assertEquals(ActionOutcome.STOP,
            ActionProgram.run(resolve(delay(5), record("overflow")), 0, context(), replacement));
        replacement.advance(5);
        assertEquals(List.of("new"), log);
        assertEquals(0, PendingTimers.global().pending(VIEWER));
    }

    @Test
    void playerlessProductionTimersRefuseBeforeSchedulingAndReadmitAfterCompletion() throws Exception {
        try (GlobalScheduler scheduler = new GlobalScheduler()) {
            ActionContext playerless = context(new AtomicInteger(), null);
            assertEquals(ActionOutcome.SUSPENDED,
                ActionProgram.run(resolve(delay(5), record("first")), 0, playerless));
            AtomicBoolean rejected = new AtomicBoolean();
            assertEquals(ActionOutcome.STOP,
                ActionProgram.run(resolve(hook(rejected), delay(5), record("refused")), 0, playerless));
            assertTrue(rejected.get());
            assertEquals(1, scheduler.tasks.size());
            assertEquals(1, PendingTimers.global().pending(null));
            assertEquals(1, PendingTimers.global().total());

            scheduler.tasks.removeFirst().run();

            assertEquals(List.of("first"), log);
            assertEquals(0, PendingTimers.global().total());
            assertEquals(ActionOutcome.SUSPENDED,
                ActionProgram.run(resolve(delay(5), record("next")), 0, playerless));
            scheduler.tasks.removeFirst().run();
            assertEquals(List.of("first", "next"), log);
            assertEquals(0, PendingTimers.global().total());
        }
    }

    @Test
    void playerlessSchedulingFailuresReleaseAllAdmissionCounters() throws Exception {
        try (GlobalScheduler scheduler = new GlobalScheduler()) {
            scheduler.failure = new IllegalStateException("global scheduler failed");
            AtomicBoolean died = new AtomicBoolean();
            assertSame(scheduler.failure, assertThrows(IllegalStateException.class, () ->
                ActionProgram.run(resolve(hook(died), delay(5)), 0, context(new AtomicInteger(), null))));
            assertTrue(died.get());
            assertEquals(0, PendingTimers.global().total());
            assertEquals(0, PendingTimers.global().pending(null));
            scheduler.failure = null;
            scheduler.refuse = true;
            assertEquals(ActionOutcome.STOP,
                ActionProgram.run(resolve(delay(5)), 0, context(new AtomicInteger(), null)));
            assertEquals(0, PendingTimers.global().total());
            assertTrue(scheduler.tasks.isEmpty());
        }
    }

    @Test
    void clearingPlayerlessTimersDropsOldActionsWithoutReleasingANewReservation() throws Exception {
        try (GlobalScheduler scheduler = new GlobalScheduler()) {
            ActionContext playerless = context(new AtomicInteger(), null);
            AtomicBoolean retired = new AtomicBoolean();
            ActionProgram.run(resolve(hook(retired), delay(5), record("old")), 0, playerless);
            Runnable oldCallback = scheduler.tasks.getFirst();
            PendingTimers.global().clear();

            assertTrue(retired.get());
            assertTrue(log.isEmpty());
            assertTrue(scheduler.tasks.isEmpty());
            assertEquals(0, PendingTimers.global().total());
            assertEquals(ActionOutcome.SUSPENDED,
                ActionProgram.run(resolve(delay(5), record("new")), 0, playerless));
            oldCallback.run();
            assertEquals(1, PendingTimers.global().total());
            assertEquals(ActionOutcome.STOP, ActionProgram.run(resolve(delay(5)), 0, playerless));
            assertEquals(1, scheduler.tasks.size());
            scheduler.tasks.removeFirst().run();
            assertEquals(List.of("new"), log);
            assertEquals(0, PendingTimers.global().total());
        }
    }

    @Test
    void budgetTakesUntilEmptyAndRefillsToCapacity() {
        ActionBudget budget = new ActionBudget();
        assertTrue(budget.take());
        budget.configure(2);
        assertTrue(budget.take());
        assertTrue(budget.take());
        assertFalse(budget.take());
        budget.refill();
        assertTrue(budget.take());
        budget.configure(0);
        assertTrue(budget.take());
    }

    private static DelayActionData delay(int ticks) {
        return new DelayActionData(ticks, null, null, null);
    }

    private RecordingData record(String label) {
        return new RecordingData(label, log);
    }

    private static HookData hook(AtomicBoolean died) {
        return new HookData(died);
    }

    private static List<MenuAction<?>> resolve(MenuActionData... data) {
        return MenuAction.resolve(List.of(data), "test", "list");
    }

    private ActionContext context() {
        return context(new AtomicInteger());
    }

    private static ActionContext context(AtomicInteger counter) {
        Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> VIEWER;
                case "getName" -> "program";
                case "hashCode" -> VIEWER.hashCode();
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.getName());
            });
        return context(counter, player);
    }

    private static ActionContext context(AtomicInteger counter, Player player) {
        ExprScope scope = new ExprScope() {
            @Override
            public Object variable(String dottedName) {
                return dottedName.equals("counter") ? (double) counter.get() : null;
            }

            @Override
            public Object call(String name, List<Object> args) {
                return ExprFunctions.call(name, args);
            }
        };
        return new ActionContext() {
            @Override
            public Player player() {
                return player;
            }

            @Override
            public String menuId() {
                return "test";
            }

            @Override
            public String componentId() {
                return "list";
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

    /** A continuation on a fake tick clock; with a player it also honours the per-player timer cap. */
    static final class FakeContinuation implements ActionProgram.Continuation {
        private final List<Map.Entry<Long, Runnable>> tasks = new ArrayList<>();
        private final List<Runnable> dropHooks = new ArrayList<>();
        private final UUID player;
        private final int cap;
        private long now;
        private boolean retired;
        private boolean refusing;

        FakeContinuation() {
            this(null, 0);
        }

        FakeContinuation(UUID player, int cap) {
            this.player = player;
            this.cap = cap;
        }

        @Override
        public boolean later(int delayTicks, Runnable task) {
            return later(delayTicks, task, () -> {
            });
        }

        @Override
        public boolean later(int delayTicks, Runnable task, Runnable onDropped) {
            if (retired || refusing) {
                return false;
            }
            PendingTimers.Lease lease = PendingTimers.global().acquire(player, new PendingTimers.Limits(cap, 0, 0));
            if (lease == null) {
                return false;
            }
            tasks.add(Map.entry(now + Math.max(1, delayTicks), () -> {
                if (lease.resume()) {
                    task.run();
                } else {
                    onDropped.run();
                }
            }));
            dropHooks.add(() -> {
                lease.close();
                onDropped.run();
            });
            return true;
        }

        /** Every later schedule is refused, as the server does once the player is gone. */
        void refuse() {
            refusing = true;
        }

        void retire() {
            retired = true;
            tasks.clear();
            List<Runnable> dropped = new ArrayList<>(dropHooks);
            dropHooks.clear();
            for (Runnable hook : dropped) {
                hook.run();
            }
        }

        int pending() {
            return tasks.size();
        }

        void advance(int ticks) {
            for (int tick = 0; tick < ticks; tick++) {
                now++;
                List<Map.Entry<Long, Runnable>> due = new ArrayList<>();
                for (Map.Entry<Long, Runnable> task : tasks) {
                    if (task.getKey() <= now) {
                        due.add(task);
                    }
                }
                for (Map.Entry<Long, Runnable> task : due) {
                    dropHooks.remove(tasks.indexOf(task));
                    tasks.remove(task);
                }
                for (Map.Entry<Long, Runnable> task : due) {
                    task.getValue().run();
                }
            }
        }
    }

    private static final class GlobalScheduler implements AutoCloseable {
        private final List<Runnable> tasks = new ArrayList<>();
        private final Object previousServer;
        private final Gloss previousPlugin;
        private RuntimeException failure;
        private boolean refuse;

        private GlobalScheduler() throws ReflectiveOperationException {
            BukkitScheduler scheduler = (BukkitScheduler) CharacterizationSupport.proxy(
                new Class<?>[]{BukkitScheduler.class}, (proxy, method, args) -> {
                    if (method.getName().equals("runTaskLater")) {
                        if (failure != null) {
                            throw failure;
                        }
                        if (refuse) {
                            return null;
                        }
                        Runnable callback = (Runnable) args[1];
                        tasks.add(callback);
                        AtomicBoolean cancelled = new AtomicBoolean();
                        return CharacterizationSupport.proxy(new Class<?>[]{BukkitTask.class},
                            (taskProxy, taskMethod, taskArguments) -> switch (taskMethod.getName()) {
                                case "cancel" -> {
                                    cancelled.set(true);
                                    tasks.remove(callback);
                                    yield null;
                                }
                                case "isCancelled" -> cancelled.get();
                                default -> CharacterizationSupport.identity(taskProxy, taskMethod, taskArguments);
                            });
                    }
                    return CharacterizationSupport.identity(proxy, method, args);
                });
            Server server = (Server) CharacterizationSupport.proxy(new Class<?>[]{Server.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getScheduler" -> scheduler;
                    case "getGlobalRegionScheduler", "getRegionScheduler" -> null;
                    case "getLogger" -> CharacterizationSupport.mutedLogger();
                    case "getName", "getVersion", "getBukkitVersion" -> "Paper";
                    default -> CharacterizationSupport.identity(proxy, method, args);
                });
            Gloss plugin = CharacterizationSupport.bareGloss(server);
            CharacterizationSupport.setField(plugin, "isEnabled", true);
            GlossConfigFile config = new GlossConfigFile();
            config.behaviors.maxTimersGlobal = 1;
            config.behaviors.maxTimersWithoutPlayer = 1;
            config.normalize();
            CharacterizationSupport.setField(plugin, "config", GlossConfig.from(config));
            previousServer = CharacterizationSupport.installServer(server);
            previousPlugin = CharacterizationSupport.installGloss(plugin);
        }

        @Override
        public void close() throws ReflectiveOperationException {
            CharacterizationSupport.restoreGloss(previousPlugin);
            CharacterizationSupport.restoreServer(previousServer);
        }
    }

    private record RecordingData(String label, List<String> log) implements MenuActionData {
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
            return new RecordingAction(this);
        }

        @Override
        public ActionEnvelope envelope() {
            return ActionEnvelope.NONE;
        }
    }

    /** Registers a death hook on the frame it runs in, the way a skippable sequence disarms its listener. */
    private record HookData(AtomicBoolean died) implements MenuActionData {
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
            return new HookAction(this);
        }

        @Override
        public ActionEnvelope envelope() {
            return ActionEnvelope.NONE;
        }
    }

    private static final class HookAction extends MenuAction<HookData> {
        private HookAction(HookData data) {
            super(data);
        }

        @Override
        public ActionOutcome execute(ActionContext context) {
            ActionProgram.Frame frame = ActionProgram.current();
            if (frame != null) {
                frame.onDeath(() -> data.died().set(true));
            }
            return ActionOutcome.CONTINUE;
        }
    }

    private static final class RecordingAction extends MenuAction<RecordingData> {
        private RecordingAction(RecordingData data) {
            super(data);
        }

        @Override
        public ActionOutcome execute(ActionContext context) {
            data.log().add(data.label());
            return ActionOutcome.CONTINUE;
        }
    }
}
