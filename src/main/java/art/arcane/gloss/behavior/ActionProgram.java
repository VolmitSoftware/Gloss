package art.arcane.gloss.behavior;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.menu.action.ActionContext;
import art.arcane.gloss.menu.action.ActionOutcome;
import art.arcane.gloss.menu.action.MenuAction;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The continuation engine behind every action list. A run walks its list like a plain loop until
 * an action suspends it ({@code delay}, a {@code sequence} cue, a {@code repeat} interval); the
 * frame then reschedules itself through the run's {@link Continuation} and re-enters the list at
 * the next index on the same thread family (the player's region, or the global region for runs
 * without a player). Nested lists (branches, steps) are child frames: a child that suspends
 * suspends its parent, and resumes it after the current action when it completes.
 */
public final class ActionProgram {
    private static final ThreadLocal<Frame> CURRENT = new ThreadLocal<>();

    /** Schedules a resume; false when the run must die (the player left, the timer cap is hit, no plugin). */
    @FunctionalInterface
    public interface Continuation {
        boolean later(int delayTicks, Runnable task);

        /**
         * Same, plus a hook for the resume that is accepted and then never runs - on Folia the
         * entity's scheduler retires the task when the player leaves, which is the one death a
         * refused schedule does not cover.
         */
        default boolean later(int delayTicks, Runnable task, Runnable onDropped) {
            return later(delayTicks, task);
        }
    }

    private ActionProgram() {
    }

    public static ActionOutcome run(List<MenuAction<?>> actions, int fromIndex, ActionContext context) {
        return run(actions, fromIndex, context, new DefaultContinuation(context));
    }

    public static ActionOutcome run(List<MenuAction<?>> actions, int fromIndex, ActionContext context,
                                    Continuation continuation) {
        return new Frame(null, actions, null, context, continuation, null).walk(fromIndex, false);
    }

    /** The frame whose action is executing on this thread, or null outside a run. */
    public static Frame current() {
        return CURRENT.get();
    }

    public static final class Frame {
        private final Frame parent;
        private final List<MenuAction<?>> actions;
        private final int[] cues;
        private final ActionContext context;
        private final Continuation continuation;
        private final Runnable afterAsyncComplete;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicReference<Runnable> onDeath = new AtomicReference<>();
        private int cursor = -1;
        private int elapsed;

        private Frame(Frame parent, List<MenuAction<?>> actions, int[] cues, ActionContext context,
                      Continuation continuation, Runnable afterAsyncComplete) {
            this.parent = parent;
            this.actions = Objects.requireNonNull(actions, "actions");
            this.cues = cues;
            this.context = Objects.requireNonNull(context, "context");
            this.continuation = Objects.requireNonNull(continuation, "continuation");
            this.afterAsyncComplete = afterAsyncComplete;
        }

        public ActionContext context() {
            return context;
        }

        public Continuation continuation() {
            return continuation;
        }

        public int cursor() {
            return cursor;
        }

        public int elapsed() {
            return elapsed;
        }

        public boolean isCancelled() {
            return cancelled.get() || (parent != null && parent.isCancelled());
        }

        public void cancel() {
            cancelled.set(true);
        }

        /**
         * Runs once when this frame can no longer continue: the continuation refused the next
         * resume, the entity retired, or the run was cancelled under it. Without it a suspended
         * frame that dies takes its listeners and its context with it for the life of the server.
         */
        public void onDeath(Runnable cleanup) {
            onDeath.set(cleanup);
        }

        private void die() {
            cancelled.set(true);
            Runnable cleanup = onDeath.getAndSet(null);
            if (cleanup != null) {
                cleanup.run();
            }
        }

        /** Continue after the current action in {@code delayTicks}; false when the run cannot be scheduled. */
        public boolean suspendAfterCurrent(int delayTicks) {
            return suspend(cursor + 1, delayTicks);
        }

        /** Re-enters this frame after the action at {@code index}; for a child's completion callback. */
        public void resumeAfter(int index) {
            walk(index + 1, true);
        }

        /**
         * Schedules {@code task} through the continuation with the cancel check and the token bucket
         * applied: an exhausted bucket pushes the task one tick, a cancelled run drops it.
         */
        public boolean defer(int delayTicks, Runnable task) {
            boolean scheduled = continuation.later(delayTicks, () -> {
                if (isCancelled()) {
                    die();
                    return;
                }
                if (!ActionBudget.global().take()) {
                    defer(1, task);
                    return;
                }
                task.run();
            }, this::die);
            if (!scheduled) {
                die();
            }
            return scheduled;
        }

        /** A nested list under the current action; {@code afterAsyncComplete} runs when it completes after a suspension. */
        public Frame child(List<MenuAction<?>> nested, int[] cues, Runnable afterAsyncComplete) {
            return new Frame(this, nested, cues, context, continuation, afterAsyncComplete);
        }

        /** Runs a branch inside the current action; when it suspends, this frame resumes after the action once it completes. */
        public ActionOutcome runBranch(List<MenuAction<?>> branch) {
            int at = cursor;
            return child(branch, null, () -> resumeAfter(at)).start();
        }

        /**
         * True when this frame is a cue list: a timed sequence owning its own timeline. An action
         * that takes over the viewer's surface ends a click list, but inside a scene it is one cue
         * among several, so the cue list keeps running.
         */
        public boolean cued() {
            return cues != null;
        }

        /** Walks this frame from its first action on the calling thread. */
        public ActionOutcome start() {
            return walk(0, false);
        }

        ActionOutcome walk(int from, boolean resumed) {
            Frame previous = CURRENT.get();
            CURRENT.set(this);
            Map<String, Object> previousArgs = ArgsView.enter(context);
            try {
                for (int index = from; index < actions.size(); index++) {
                    if (isCancelled()) {
                        return ActionOutcome.STOP;
                    }
                    if (cues != null && cues[index] > elapsed) {
                        int wait = cues[index] - elapsed;
                        elapsed = cues[index];
                        return suspend(index, wait) ? ActionOutcome.SUSPENDED : ActionOutcome.STOP;
                    }
                    cursor = index;
                    ActionOutcome outcome = MenuAction.executeAt(actions, index, context);
                    if (outcome != ActionOutcome.CONTINUE) {
                        return outcome;
                    }
                }
            } finally {
                ArgsView.exit(previousArgs);
                CURRENT.set(previous);
            }
            if (resumed && afterAsyncComplete != null) {
                afterAsyncComplete.run();
            }
            return ActionOutcome.CONTINUE;
        }

        private boolean suspend(int nextIndex, int delayTicks) {
            return defer(delayTicks, () -> walk(nextIndex, true));
        }
    }

    /**
     * The production continuation: a player's run rides {@code FoliaScheduler.runEntity} and dies
     * with the entity; a run without a player rides the global region. Player runs count against
     * {@code maxTimersPerPlayer}.
     */
    private static final class DefaultContinuation implements Continuation {
        private final ActionContext context;

        private DefaultContinuation(ActionContext context) {
            this.context = context;
        }

        @Override
        public boolean later(int delayTicks, Runnable task) {
            return later(delayTicks, task, () -> {
            });
        }

        @Override
        public boolean later(int delayTicks, Runnable task, Runnable onDropped) {
            Gloss plugin = Gloss.instance;
            if (plugin == null) {
                return false;
            }
            int delay = Math.max(1, delayTicks);
            Player player = context.player();
            if (player == null) {
                plugin.scheduler().s(task, delay);
                return true;
            }
            UUID id = player.getUniqueId();
            if (!PendingTimers.global().acquire(id, timerCap(plugin))) {
                Gloss.warnThrottled("behavior-timer-cap",
                    "%s has too many pending behavior timers; a delayed run in %s was dropped.",
                    player.getName(), context.menuId());
                return false;
            }
            boolean scheduled = FoliaScheduler.runEntity(plugin, player, () -> {
                PendingTimers.global().release(id);
                task.run();
            }, delay, () -> {
                PendingTimers.global().release(id);
                onDropped.run();
            });
            if (!scheduled) {
                PendingTimers.global().release(id);
            }
            return scheduled;
        }

        private static int timerCap(Gloss plugin) {
            GlossConfig config = plugin.cfg();
            return config == null ? 0 : config.modules().behaviors().maxTimersPerPlayer();
        }
    }
}
