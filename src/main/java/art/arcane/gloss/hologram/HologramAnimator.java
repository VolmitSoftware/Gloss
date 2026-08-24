package art.arcane.gloss.hologram;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.animation.AnimationClip;
import art.arcane.gloss.util.common.DisplayEntity;
import art.arcane.gloss.util.common.PacketUtils;
import art.arcane.gloss.util.common.TextUtils;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

public final class HologramAnimator {
    private static final TargetKey[] EMPTY_TARGET_KEYS = new TargetKey[0];
    private static final DirectKey[] EMPTY_DIRECT_KEYS = new DirectKey[0];
    public static final String SHARED_SUB = "shared";
    public static final double FAST_THRESHOLD_FPS = 20.0D;
    private static final String FUNCTION_PREFIX = "animation.";
    private static final String TOKEN_MARKER = "|" + FUNCTION_PREFIX;
    private static final long IDLE_EXIT_MILLIS = 2000L;
    private static final long IDLE_POLL_MILLIS = 50L;
    private static final long PACKET_BUDGET_WINDOW_MILLIS = 1000L;
    private static final long REPORT_INTERVAL_MILLIS = 10_000L;
    private static final long STOP_JOIN_MILLIS = 1000L;

    public record Target(int entityId, TextFrameSource frames, List<Player> viewers, TextCodec codec) {
        public Target(int entityId, TextFrameSource frames, List<Player> viewers) {
            this(entityId, frames, viewers, TextCodec.AUTHORED);
        }
    }

    private record TargetKey(String group, String sub) {
    }

    private record DirectKey(UUID viewerId, int entityId) {
    }

    private record DirectUpdate(List<Player> viewers, String text) {
    }

    private record BudgetSample(long atMs, int recipients) {
    }

    private record RecipientBatch(List<Player> viewers, boolean hasMore) {
    }

    private static final class SendState {
        private String lastText;
        private Set<Player> lastViewers = Set.of();
        private long lastEvaluatedMs = Long.MIN_VALUE;
        private boolean pendingRecipients;
    }

    private final Supplier<GlossConfig> config;
    private final Predicate<String> isFunction;
    private final Function<String, AnimationClip> clipResolver;
    private final Function<AnimationClip, List<String>> sharedFrames;
    private final AnimationTextSender sender;
    private final Map<TargetKey, Target> targets;
    private final Map<TargetKey, SendState> states;
    private final Map<DirectKey, DirectUpdate> directUpdates;
    private final ArrayDeque<BudgetSample> budgetSamples;
    private final Object workerLock;
    private final Object targetOrderLock;
    private final Object directOrderLock;
    private final AtomicLong targetMembershipGeneration;
    private final AtomicLong directMembershipGeneration;
    private long budgetRecipients;
    private long budgetTimeMs;
    private volatile TargetKey[] targetOrder;
    private volatile DirectKey[] directOrder;
    private volatile long targetOrderGeneration;
    private volatile long directOrderGeneration;
    private int targetStartOffset;
    private int directStartOffset;
    private boolean preferDirectUpdates;
    private Thread worker;
    private volatile boolean stopped;
    private volatile long settledIntervalMillis;

    public HologramAnimator(Gloss plugin) {
        this(plugin::cfg,
            name -> plugin.text().hasFunction(name),
            name -> resolveClip(plugin, name),
            clip -> plugin.animations().staticFrames(clip),
            HologramAnimator::sendPacket);
    }

    HologramAnimator(Supplier<GlossConfig> config, Predicate<String> isFunction,
                     Function<String, AnimationClip> clipResolver, AnimationTextSender sender) {
        this(config, isFunction, clipResolver, clip -> null, sender);
    }

    HologramAnimator(Supplier<GlossConfig> config, Predicate<String> isFunction,
                     Function<String, AnimationClip> clipResolver,
                     Function<AnimationClip, List<String>> sharedFrames, AnimationTextSender sender) {
        this.config = config;
        this.isFunction = isFunction;
        this.clipResolver = clipResolver;
        this.sharedFrames = sharedFrames;
        this.sender = sender;
        this.targets = new ConcurrentHashMap<>();
        this.states = new ConcurrentHashMap<>();
        this.directUpdates = new ConcurrentHashMap<>();
        this.budgetSamples = new ArrayDeque<>();
        this.workerLock = new Object();
        this.targetOrderLock = new Object();
        this.directOrderLock = new Object();
        this.targetMembershipGeneration = new AtomicLong();
        this.directMembershipGeneration = new AtomicLong();
        this.targetOrder = EMPTY_TARGET_KEYS;
        this.directOrder = EMPTY_DIRECT_KEYS;
        this.targetOrderGeneration = -1L;
        this.directOrderGeneration = -1L;
        this.preferDirectUpdates = true;
        this.budgetTimeMs = Long.MIN_VALUE;
        this.stopped = true;
    }

    public void start() {
        stopped = false;
        if (hasWork()) {
            ensureWorker();
        }
    }

    public void stop() {
        stopped = true;
        if (!targets.isEmpty()) {
            targets.clear();
            targetMembershipGeneration.incrementAndGet();
        }
        states.clear();
        if (!directUpdates.isEmpty()) {
            directUpdates.clear();
            directMembershipGeneration.incrementAndGet();
        }
        Thread active;
        synchronized (workerLock) {
            active = worker;
        }
        if (active == null) {
            return;
        }

        active.interrupt();
        try {
            active.join(STOP_JOIN_MILLIS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    public long settledIntervalMillis() {
        return settledIntervalMillis;
    }

    public int targetCount() {
        return targets.size();
    }

    int pendingTextUpdateCount() {
        return directUpdates.size();
    }

    void sendText(Player viewer, UUID viewerId, int entityId, String text) {
        DirectKey key = new DirectKey(viewerId, entityId);
        DirectUpdate previous = directUpdates.put(key, new DirectUpdate(List.of(viewer), text));
        if (previous == null) {
            directMembershipGeneration.incrementAndGet();
        }
        ensureWorker();
    }

    void discardText(UUID viewerId, int entityId) {
        if (directUpdates.remove(new DirectKey(viewerId, entityId)) != null) {
            directMembershipGeneration.incrementAndGet();
        }
    }

    public AnimationTemplate compileTemplate(List<String> rawLines, UnaryOperator<String> renderer) {
        if (!containsAnimationTokens(rawLines)) {
            return null;
        }

        GlossConfig active = config.get();
        if (!active.holograms().highFrequencyAnimations() || !active.text().functions()) {
            return null;
        }

        AnimationTemplate template = AnimationTemplate.compile(String.join("\n", rawLines),
            isFunction, this::fastClip, renderer, sharedFrames);
        return template.hasSlots() ? template : null;
    }

    private static boolean containsAnimationTokens(List<String> rawLines) {
        for (String line : rawLines) {
            if (line.contains(TOKEN_MARKER)) {
                return true;
            }
        }

        return false;
    }

    public void publish(String group, String sub, Target target) {
        TargetKey key = new TargetKey(group, sub);
        Target previous = targets.put(key, target);
        if (previous == null) {
            targetMembershipGeneration.incrementAndGet();
        }
        if (previous != null
            && (previous.entityId() != target.entityId() || previous.codec() != target.codec())) {
            states.remove(key);
        }
        ensureWorker();
    }

    public void remove(String group, String sub) {
        TargetKey key = new TargetKey(group, sub);
        if (targets.remove(key) != null) {
            targetMembershipGeneration.incrementAndGet();
        }
        states.remove(key);
    }

    public void removeGroup(String group) {
        boolean removed = false;
        for (TargetKey key : targets.keySet()) {
            if (key.group().equals(group)) {
                removed |= targets.remove(key) != null;
                states.remove(key);
            }
        }
        if (removed) {
            targetMembershipGeneration.incrementAndGet();
        }
        for (TargetKey key : states.keySet()) {
            if (key.group().equals(group)) {
                states.remove(key);
            }
        }
    }

    int pass(long nowMs) {
        int budget = Math.max(1, config.get().holograms().animationPacketBudget());
        long currentBudgetTimeMs = advanceBudgetTime(nowMs);
        discardExpiredBudgetSamples(currentBudgetTimeMs);
        int remainingRecipients = (int) Math.max(0L, (long) budget - budgetRecipients);
        if (remainingRecipients == 0) {
            return 0;
        }

        long minIntervalMs = AnimatorLoopPolicy.minSendIntervalMillis(audienceRecipients(), budget);
        int sends = 0;
        int reservedRecipients = 0;
        boolean directAvailable = !directUpdates.isEmpty();
        boolean animationAvailable = !targets.isEmpty();
        if (preferDirectUpdates && directAvailable) {
            int directSends = sendDirectUpdates(remainingRecipients);
            sends += directSends;
            reservedRecipients += directSends;
            remainingRecipients -= directSends;
        }

        TargetKey[] order = targetOrder();
        int start = order.length == 0 ? 0 : Math.floorMod(targetStartOffset, order.length);
        int visited = 0;
        try {
            while (visited < order.length && remainingRecipients > 0) {
                TargetKey key = order[(start + visited) % order.length];
                visited++;
                Target target = targets.get(key);
                if (target == null) {
                    continue;
                }
                SendState state = states.computeIfAbsent(key, ignored -> new SendState());
                boolean evaluationDue = minIntervalMs <= 0L || state.lastEvaluatedMs == Long.MIN_VALUE
                    || state.lastEvaluatedMs + minIntervalMs <= nowMs;
                if (!state.pendingRecipients && !evaluationDue) {
                    continue;
                }

                List<Player> viewers = target.viewers();
                if (viewers.isEmpty()) {
                    state.lastViewers = Set.of();
                    state.pendingRecipients = false;
                    if (evaluationDue) {
                        state.lastEvaluatedMs = nowMs;
                    }
                    continue;
                }

                if (!state.pendingRecipients) {
                    state.lastEvaluatedMs = nowMs;
                    String text = target.frames().compose(nowMs);
                    if (!text.equals(state.lastText)) {
                        state.lastText = text;
                        state.lastViewers = Set.of();
                    }
                }

                RecipientBatch batch = unsentViewers(viewers, state.lastViewers, remainingRecipients);
                if (batch.viewers().isEmpty()) {
                    state.lastViewers = viewerSet(viewers);
                    state.pendingRecipients = false;
                    continue;
                }

                int recipients = batch.viewers().size();
                reservedRecipients += recipients;
                remainingRecipients -= recipients;
                state.pendingRecipients = true;
                sender.send(batch.viewers(), target.entityId(), state.lastText, target.codec());
                state.lastViewers = batch.hasMore()
                    ? combinedViewerSet(state.lastViewers, batch.viewers())
                    : viewerSet(viewers);
                state.pendingRecipients = batch.hasMore();
                sends++;
            }
        } finally {
            if (order.length > 0 && visited > 0) {
                targetStartOffset = (start + visited) % order.length;
            }
            try {
                if (!preferDirectUpdates && directAvailable && remainingRecipients > 0) {
                    int directSends = sendDirectUpdates(remainingRecipients);
                    sends += directSends;
                    reservedRecipients += directSends;
                    remainingRecipients -= directSends;
                }
                if (directAvailable && animationAvailable && remainingRecipients == 0) {
                    preferDirectUpdates = !preferDirectUpdates;
                }
            } finally {
                recordBudgetSample(currentBudgetTimeMs, reservedRecipients);
            }
        }

        return sends;
    }

    private int sendDirectUpdates(int limit) {
        DirectKey[] order = directOrder();
        if (limit <= 0 || order.length == 0) {
            return 0;
        }
        int start = Math.floorMod(directStartOffset, order.length);
        int visited = 0;
        int sends = 0;
        while (visited < order.length && sends < limit) {
            DirectKey key = order[(start + visited) % order.length];
            visited++;
            DirectUpdate update = directUpdates.get(key);
            if (update == null || !directUpdates.remove(key, update)) {
                continue;
            }
            directMembershipGeneration.incrementAndGet();
            try {
                sender.send(update.viewers(), key.entityId(), update.text(), TextCodec.AUTHORED);
                sends++;
            } catch (Throwable failure) {
                if (directUpdates.putIfAbsent(key, update) == null) {
                    directMembershipGeneration.incrementAndGet();
                }
                throw failure;
            }
        }
        if (visited > 0) {
            directStartOffset = (start + visited) % order.length;
        }
        return sends;
    }

    private long advanceBudgetTime(long nowMs) {
        if (budgetTimeMs == Long.MIN_VALUE || nowMs > budgetTimeMs) {
            budgetTimeMs = nowMs;
        }
        return budgetTimeMs;
    }

    private void discardExpiredBudgetSamples(long nowMs) {
        long cutoffMs = nowMs - PACKET_BUDGET_WINDOW_MILLIS;
        while (!budgetSamples.isEmpty() && budgetSamples.peekFirst().atMs() <= cutoffMs) {
            budgetRecipients -= budgetSamples.removeFirst().recipients();
        }
    }

    private void recordBudgetSample(long nowMs, int recipients) {
        if (recipients <= 0) {
            return;
        }

        budgetSamples.addLast(new BudgetSample(nowMs, recipients));
        budgetRecipients += recipients;
    }

    private long audienceRecipients() {
        long recipients = 0L;
        for (Target target : targets.values()) {
            recipients = Math.min(Long.MAX_VALUE / 1000L, recipients + target.viewers().size());
        }
        return recipients;
    }

    private TargetKey[] targetOrder() {
        long generation = targetMembershipGeneration.get();
        TargetKey[] cached = targetOrder;
        if (targetOrderGeneration == generation) {
            return cached;
        }
        synchronized (targetOrderLock) {
            generation = targetMembershipGeneration.get();
            if (targetOrderGeneration != generation) {
                cached = targets.keySet().toArray(EMPTY_TARGET_KEYS);
                targetOrder = cached;
                targetOrderGeneration = generation;
                targetStartOffset = cached.length == 0 ? 0 : Math.floorMod(targetStartOffset, cached.length);
            }
            return targetOrder;
        }
    }

    private DirectKey[] directOrder() {
        long generation = directMembershipGeneration.get();
        DirectKey[] cached = directOrder;
        if (directOrderGeneration == generation) {
            return cached;
        }
        synchronized (directOrderLock) {
            generation = directMembershipGeneration.get();
            if (directOrderGeneration != generation) {
                cached = directUpdates.keySet().toArray(EMPTY_DIRECT_KEYS);
                directOrder = cached;
                directOrderGeneration = generation;
                directStartOffset = cached.length == 0 ? 0 : Math.floorMod(directStartOffset, cached.length);
            }
            return directOrder;
        }
    }

    private boolean hasWork() {
        return !targets.isEmpty() || !directUpdates.isEmpty();
    }

    private void ensureWorker() {
        synchronized (workerLock) {
            if (stopped || worker != null) {
                return;
            }

            Thread thread = new Thread(this::runLoop, "Gloss Animator");
            thread.setDaemon(true);
            worker = thread;
            thread.start();
        }
    }

    private void runLoop() {
        long floor = AnimatorLoopPolicy.floorMillis(config.get().holograms().maxAnimationFps());
        long interval = floor;
        long idleSinceMs = -1L;
        long reportAtMs = System.currentTimeMillis() + REPORT_INTERVAL_MILLIS;
        long reportSends = 0L;
        long reportPasses = 0L;
        settledIntervalMillis = interval;
        while (!stopped && !Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(hasWork() ? interval : IDLE_POLL_MILLIS);
            } catch (InterruptedException interrupted) {
                break;
            }
            if (stopped) {
                break;
            }
            if (!hasWork()) {
                long nowMs = System.currentTimeMillis();
                if (idleSinceMs < 0L) {
                    idleSinceMs = nowMs;
                }
                if (nowMs - idleSinceMs >= IDLE_EXIT_MILLIS) {
                    break;
                }

                continue;
            }

            idleSinceMs = -1L;
            floor = AnimatorLoopPolicy.floorMillis(config.get().holograms().maxAnimationFps());
            long startNanos = System.nanoTime();
            long nowMs = System.currentTimeMillis();
            try {
                reportSends += pass(nowMs);
            } catch (Throwable failure) {
                Gloss.logExceptionStackThrottled(false, "hologram-animator-pass", failure,
                    "Hologram animator pass failed; continuing.");
            }

            reportPasses++;
            double passMillis = (System.nanoTime() - startNanos) / 1.0E6D;
            interval = AnimatorLoopPolicy.nextIntervalMillis(interval, passMillis, floor);
            settledIntervalMillis = interval;
            if (config.get().debug().animator() && nowMs >= reportAtMs) {
                Gloss.verbose("Animator interval=%dms targets=%d sends=%d passes=%d (10s window).",
                    interval, targets.size(), reportSends, reportPasses);
                reportAtMs = nowMs + REPORT_INTERVAL_MILLIS;
                reportSends = 0L;
                reportPasses = 0L;
            }
        }

        synchronized (workerLock) {
            if (worker == Thread.currentThread()) {
                worker = null;
            }
        }
        if (!stopped && hasWork()) {
            ensureWorker();
        }
    }

    private AnimationClip fastClip(String name) {
        AnimationClip clip = clipResolver.apply(name);
        if (clip == null || clip.targetFramerate() <= FAST_THRESHOLD_FPS) {
            return null;
        }

        return clip;
    }

    private static AnimationClip resolveClip(Gloss plugin, String name) {
        if (!name.startsWith(FUNCTION_PREFIX)) {
            return null;
        }

        return plugin.animations().clip(name.substring(FUNCTION_PREFIX.length()));
    }

    private static void sendPacket(List<Player> viewers, int entityId, String legacyText, TextCodec codec) {
        List<PacketWrapper<?>> packets = List.of(DisplayEntity.textUpdate(entityId,
            codec == TextCodec.LEGACY ? TextUtils.parseLegacy(legacyText) : TextUtils.parse(legacyText)));
        PacketUtils.send(viewers, packets);
    }

    private static RecipientBatch unsentViewers(List<Player> viewers, Set<Player> lastViewers, int limit) {
        List<Player> fresh = new ArrayList<>(Math.min(viewers.size(), limit));
        boolean hasMore = false;
        for (Player viewer : viewers) {
            if (!lastViewers.contains(viewer)) {
                if (fresh.size() < limit) {
                    fresh.add(viewer);
                } else {
                    hasMore = true;
                    break;
                }
            }
        }

        return new RecipientBatch(fresh, hasMore);
    }

    private static Set<Player> combinedViewerSet(Set<Player> existing, List<Player> viewers) {
        Set<Player> combined = identityPlayerSet(existing.size() + viewers.size());
        combined.addAll(existing);
        combined.addAll(viewers);
        return combined;
    }

    private static Set<Player> viewerSet(List<Player> viewers) {
        Set<Player> captured = identityPlayerSet(viewers.size());
        captured.addAll(viewers);
        return captured;
    }

    private static Set<Player> identityPlayerSet(int expectedSize) {
        return Collections.newSetFromMap(new IdentityHashMap<>(Math.max(1, expectedSize)));
    }
}
