package art.arcane.gloss.hologram;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.animation.AnimationClip;
import art.arcane.gloss.util.common.DisplayEntity;
import art.arcane.gloss.util.common.PacketUtils;
import art.arcane.gloss.util.common.TextUtils;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

public final class HologramAnimator {
    public static final String SHARED_SUB = "shared";
    public static final double FAST_THRESHOLD_FPS = 20.0D;
    private static final String FUNCTION_PREFIX = "animation.";
    private static final String TOKEN_MARKER = "|" + FUNCTION_PREFIX;
    private static final long IDLE_EXIT_MILLIS = 2000L;
    private static final long IDLE_POLL_MILLIS = 50L;
    private static final long REPORT_INTERVAL_MILLIS = 10_000L;
    private static final long STOP_JOIN_MILLIS = 1000L;

    public record Target(int entityId, AnimationTemplate template, List<Player> viewers) {
    }

    private record TargetKey(String group, String sub) {
    }

    private static final class SendState {
        private String lastText;
        private Set<UUID> lastViewers = Set.of();
        private long lastSentMs = Long.MIN_VALUE;
    }

    private final Supplier<GlossConfig> config;
    private final Predicate<String> isFunction;
    private final Function<String, AnimationClip> clipResolver;
    private final Function<AnimationClip, List<String>> sharedFrames;
    private final AnimationTextSender sender;
    private final Map<TargetKey, Target> targets;
    private final Map<TargetKey, SendState> states;
    private final Object workerLock;
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
        this.workerLock = new Object();
        this.stopped = true;
    }

    public void start() {
        stopped = false;
    }

    public void stop() {
        stopped = true;
        targets.clear();
        states.clear();
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
        targets.put(new TargetKey(group, sub), target);
        ensureWorker();
    }

    public void remove(String group, String sub) {
        TargetKey key = new TargetKey(group, sub);
        targets.remove(key);
        states.remove(key);
    }

    public void removeGroup(String group) {
        for (TargetKey key : targets.keySet()) {
            if (key.group().equals(group)) {
                targets.remove(key);
                states.remove(key);
            }
        }
        for (TargetKey key : states.keySet()) {
            if (key.group().equals(group)) {
                states.remove(key);
            }
        }
    }

    int pass(long nowMs) {
        int budget = config.get().holograms().animationPacketBudget();
        int sends = 0;
        for (Map.Entry<TargetKey, Target> entry : targets.entrySet()) {
            Target target = entry.getValue();
            List<Player> online = onlineViewers(target.viewers());
            if (online.isEmpty()) {
                continue;
            }

            SendState state = states.computeIfAbsent(entry.getKey(), key -> new SendState());
            long minIntervalMs = AnimatorLoopPolicy.minSendIntervalMillis(online.size(), budget);
            if (minIntervalMs > 0L && state.lastSentMs != Long.MIN_VALUE && state.lastSentMs + minIntervalMs > nowMs) {
                continue;
            }

            String text = target.template().compose(nowMs);
            if (!text.equals(state.lastText)) {
                sender.send(online, target.entityId(), text);
                state.lastText = text;
                state.lastViewers = viewerIds(online);
                state.lastSentMs = nowMs;
                sends++;
                continue;
            }

            List<Player> fresh = freshViewers(online, state.lastViewers);
            if (fresh.isEmpty()) {
                continue;
            }

            sender.send(fresh, target.entityId(), text);
            state.lastViewers = viewerIds(online);
            state.lastSentMs = nowMs;
            sends++;
        }

        return sends;
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
                Thread.sleep(targets.isEmpty() ? IDLE_POLL_MILLIS : interval);
            } catch (InterruptedException interrupted) {
                break;
            }
            if (stopped) {
                break;
            }
            if (targets.isEmpty()) {
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
                Gloss.logExceptionStack(false, failure, "Hologram animator pass failed; continuing.");
            }

            reportPasses++;
            double passMillis = (System.nanoTime() - startNanos) / 1.0E6D;
            interval = AnimatorLoopPolicy.nextIntervalMillis(interval, passMillis, floor);
            settledIntervalMillis = interval;
            if (config.get().debug().animator() && nowMs >= reportAtMs) {
                Gloss.info("animator interval=" + interval + "ms targets=" + targets.size()
                    + " sends=" + reportSends + " passes=" + reportPasses + " (10s window)");
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
        if (!stopped && !targets.isEmpty()) {
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

    private static void sendPacket(List<Player> viewers, int entityId, String legacyText) {
        List<PacketWrapper<?>> packets = List.of(DisplayEntity.textUpdate(entityId, TextUtils.parse(legacyText)));
        PacketUtils.send(viewers, packets);
    }

    private static List<Player> onlineViewers(List<Player> viewers) {
        List<Player> online = null;
        for (int index = 0; index < viewers.size(); index++) {
            Player viewer = viewers.get(index);
            if (viewer.isOnline()) {
                if (online != null) {
                    online.add(viewer);
                }

                continue;
            }
            if (online == null) {
                online = new ArrayList<>(viewers.size() - 1);
                online.addAll(viewers.subList(0, index));
            }
        }

        return online == null ? viewers : online;
    }

    private static List<Player> freshViewers(List<Player> online, Set<UUID> lastViewers) {
        List<Player> fresh = new ArrayList<>(0);
        for (Player viewer : online) {
            if (!lastViewers.contains(viewer.getUniqueId())) {
                fresh.add(viewer);
            }
        }

        return fresh;
    }

    private static Set<UUID> viewerIds(List<Player> viewers) {
        Set<UUID> ids = new HashSet<>(viewers.size() * 2);
        for (Player viewer : viewers) {
            ids.add(viewer.getUniqueId());
        }

        return ids;
    }
}
