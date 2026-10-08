package art.arcane.gloss.board;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.condition.BoundedConditionErrorCallback;
import art.arcane.gloss.condition.GlossConditionScope;
import art.arcane.gloss.condition.ShowCondition;
import art.arcane.gloss.expr.Expr;
import art.arcane.gloss.expr.ExprEvaluator;
import art.arcane.gloss.expr.ExprParser;
import art.arcane.gloss.service.GlossTelemetry;
import art.arcane.gloss.util.common.TextUtils;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.netty.channel.ChannelHelper;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDisplayScoreboard;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerScoreboardObjective;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

final class BoardNativeObjectives extends PacketListenerAbstract {
    private static final long TICK_NANOS = 50_000_000L;
    private final Gloss plugin;
    private final BoundedConditionErrorCallback errors;
    private final Map<UUID, Viewer> viewers = new ConcurrentHashMap<>();
    private final Map<Source, Samples> sources = new ConcurrentHashMap<>();
    private volatile boolean enabled;

    BoardNativeObjectives(Gloss plugin, BoundedConditionErrorCallback errors) {
        super(PacketListenerPriority.MONITOR);
        this.plugin = plugin;
        this.errors = errors;
    }

    void enable() {
        enabled = true;
    }

    void publish(Player player, BoardObjectives definitions) {
        if (!enabled) {
            return;
        }
        Viewer existing = viewers.get(player.getUniqueId());
        if (definitions.isEmpty() && (existing == null || !existing.active)) {
            return;
        }
        Object channel = PacketEvents.getAPI().getPlayerManager().getChannel(player);
        if (channel == null) {
            return;
        }
        Viewer viewer = existing == null ? viewers.computeIfAbsent(player.getUniqueId(), ignored -> new Viewer()) : existing;
        viewer.channel = channel;
        viewer.active = !definitions.isEmpty();
        seed(player, viewer, 0, DisplaySlot.PLAYER_LIST);
        seed(player, viewer, 2, DisplaySlot.BELOW_NAME);
        GlossConditionScope scope = GlossConditionScope.viewer(plugin, player);
        long now = System.nanoTime();
        BoardObjectiveRenderer.Frame frame = new BoardObjectiveRenderer.Frame(
            prepare(player, viewer, definitions.playerList(), 0, scope, now),
            prepare(player, viewer, definitions.belowName(), 1, scope, now));
        synchronized (viewer) {
            viewer.requested = frame;
            if (viewer.queued) {
                return;
            }
            viewer.queued = true;
        }
        ChannelHelper.runInEventLoop(channel, () -> apply(player.getUniqueId(), viewer, channel));
    }

    void release(Player player) {
        publish(player, BoardObjectives.NONE);
    }

    void forget(UUID playerId) {
        viewers.remove(playerId);
        for (Samples samples : sources.values()) {
            samples.forget(playerId);
        }
    }

    void retain(Collection<GlossBoardMeta> boards) {
        Set<Source> retained = new HashSet<>();
        for (GlossBoardMeta board : boards) {
            BoardObjectives definitions = board.objectives();
            if (definitions.playerList() != null) {
                retained.add(Source.of(definitions.playerList()));
            }
            if (definitions.belowName() != null) {
                retained.add(Source.of(definitions.belowName()));
            }
        }
        sources.keySet().retainAll(retained);
    }

    void disable() {
        enabled = false;
        for (Viewer viewer : viewers.values()) {
            Object channel = viewer.channel;
            if (channel != null) {
                ChannelHelper.runInEventLoop(channel, () -> write(viewer, channel, null));
            }
        }
        viewers.clear();
        sources.clear();
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (!enabled || event.isCancelled() || event.getUser() == null || event.getUser().getUUID() == null) {
            return;
        }
        if (event.getPacketType() == PacketType.Play.Server.DISPLAY_SCOREBOARD) {
            WrapperPlayServerDisplayScoreboard packet = new WrapperPlayServerDisplayScoreboard(event);
            if (packet.getPosition() != 0 && packet.getPosition() != 2) {
                return;
            }
            viewers.computeIfAbsent(event.getUser().getUUID(), ignored -> new Viewer())
                .renderer.displayed(packet.getPosition(), packet.getScoreName());
        } else if (event.getPacketType() == PacketType.Play.Server.SCOREBOARD_OBJECTIVE) {
            Viewer viewer = viewers.get(event.getUser().getUUID());
            if (viewer == null) {
                return;
            }
            WrapperPlayServerScoreboardObjective packet = new WrapperPlayServerScoreboardObjective(event);
            if (packet.getMode() == WrapperPlayServerScoreboardObjective.ObjectiveMode.REMOVE) {
                viewer.renderer.removed(packet.getName());
            }
        }
    }

    private BoardObjectiveRenderer.Prepared prepare(Player player, Viewer viewer, BoardObjectives.Slot definition,
                                                     int index, GlossConditionScope scope, long now) {
        if (definition == null || !definition.show().matches(scope, errors)) {
            viewer.titles[index] = null;
            return null;
        }
        Source source = Source.of(definition);
        Samples samples = sources.computeIfAbsent(source, Samples::new);
        sample(samples, now);
        Title cached = viewer.titles[index];
        if (cached == null || !cached.definition().equals(definition) || now - cached.deadline() >= 0) {
            String rendered = plugin.text().render(player, definition.title());
            Component title = TextUtils.parse(rendered == null ? "" : rendered);
            cached = new Title(definition, title, now + definition.refreshTicks() * TICK_NANOS);
            viewer.titles[index] = cached;
        }
        return new BoardObjectiveRenderer.Prepared(definition, cached.component(), samples.snapshot());
    }

    private void sample(Samples samples, long now) {
        long due = samples.next.get();
        if (due != 0 && now - due < 0 || !samples.next.compareAndSet(due, now + samples.source.refreshTicks() * TICK_NANOS)) {
            return;
        }
        for (Player subject : Bukkit.getOnlinePlayers()) {
            UUID subjectId = subject.getUniqueId();
            SampleTicket token = new SampleTicket(subject);
            SampleTicket claimed = samples.pending.compute(subjectId, (id, previous) ->
                previous == null || previous.subject != subject ? token : previous);
            if (claimed != token) {
                continue;
            }
            boolean scheduled = plugin.scheduler().runEntity(subject, () -> sampleSubject(samples, subject, token));
            if (!scheduled) {
                samples.pending.remove(subjectId, token);
            }
        }
    }

    private void sampleSubject(Samples samples, Player subject, SampleTicket token) {
        UUID subjectId = subject.getUniqueId();
        try {
            if (!enabled || sources.get(samples.source) != samples || !subject.isOnline()
                || samples.pending.get(subjectId) != token) {
                return;
            }
            GlossConditionScope scope = GlossConditionScope.viewer(plugin, subject);
            if (!samples.source.subjects().matches(scope, errors)) {
                samples.remove(subjectId, token);
                return;
            }
            double raw = ExprEvaluator.number(samples.expression, scope);
            int score = score(raw);
            String text = samples.source.valueText().isEmpty() ? ""
                : plugin.text().render(subject, samples.source.valueText());
            samples.update(subjectId, token, new SubjectValue(subject.getName(),
                new BoardObjectiveRenderer.Value(score, samples.source.format(), text == null ? "" : text)));
        } catch (RuntimeException failure) {
            samples.remove(subjectId, token);
            Gloss.logExceptionStackThrottled(false, "board-native-sample-" + samples.source.hashCode(), failure,
                "Native scoreboard value %s failed for %s.", samples.source.value(), subjectId);
        } finally {
            samples.pending.remove(subjectId, token);
        }
    }

    static int score(double raw) {
        if (!Double.isFinite(raw)) {
            throw new IllegalArgumentException("Native scoreboard values must be finite numbers");
        }
        return (int) Math.clamp(Math.round(raw), Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    private void seed(Player player, Viewer viewer, int position, DisplaySlot slot) {
        if (viewer.renderer.known(position) || viewer.seedAttempted[position == 0 ? 0 : 1]) {
            return;
        }
        viewer.seedAttempted[position == 0 ? 0 : 1] = true;
        try {
            Objective objective = player.getScoreboard().getObjective(slot);
            viewer.renderer.seedIfUnknown(position, objective == null ? "" : objective.getName());
        } catch (UnsupportedOperationException | IllegalStateException failure) {
            Gloss.logExceptionStackThrottled(false, "board-native-ownership-seed", failure,
                "Native scoreboard ownership could not be read; yield mode waits for a display-slot packet.");
        }
    }

    private void apply(UUID viewerId, Viewer viewer, Object channel) {
        BoardObjectiveRenderer.Frame requested;
        synchronized (viewer) {
            requested = viewer.requested;
            viewer.requested = null;
            viewer.queued = false;
        }
        if (!enabled || viewers.get(viewerId) != viewer) {
            return;
        }
        write(viewer, channel, requested);
    }

    private void write(Viewer viewer, Object channel, BoardObjectiveRenderer.Frame frame) {
        if (!ChannelHelper.isOpen(channel)) {
            return;
        }
        int[] accepted = {0};
        try {
            viewer.renderer.apply(frame, packet -> {
                PacketEvents.getAPI().getProtocolManager().writePacketSilently(channel, packet);
                accepted[0]++;
            });
        } catch (RuntimeException failure) {
            viewer.active = true;
            Gloss.logExceptionStackThrottled(false, "board-native-write", failure,
                "Native scoreboard packets could not be written completely.");
        } finally {
            if (accepted[0] > 0) {
                GlossTelemetry.countPackets(accepted[0]);
                ChannelHelper.flush(channel);
            }
        }
    }

    record Source(String value, BoardLineFormat format, String valueText,
                          ShowCondition subjects, int refreshTicks) {
        static Source of(BoardObjectives.Slot slot) {
            return new Source(slot.value(), slot.format(), slot.valueText(), slot.subjects(), slot.refreshTicks());
        }
    }

    record SubjectValue(String name, BoardObjectiveRenderer.Value value) {
    }

    private record Title(BoardObjectives.Slot definition, Component component, long deadline) {
    }

    private static final class Viewer {
        private final BoardObjectiveRenderer renderer = new BoardObjectiveRenderer();
        private final Title[] titles = new Title[2];
        private final boolean[] seedAttempted = new boolean[2];
        private volatile Object channel;
        private volatile boolean active;
        private boolean queued;
        private BoardObjectiveRenderer.Frame requested;
    }

    static final class SampleTicket {
        private final Player subject;

        SampleTicket(Player subject) {
            this.subject = subject;
        }
    }

    static final class Samples {
        private final Source source;
        private final Expr expression;
        private final AtomicLong next = new AtomicLong();
        final Map<UUID, SampleTicket> pending = new ConcurrentHashMap<>();
        private final Map<UUID, SubjectValue> values = new HashMap<>();
        private Map<String, BoardObjectiveRenderer.Value> snapshot = Map.of();
        private boolean dirty;

        Samples(Source source) {
            this.source = source;
            this.expression = ExprParser.parse(source.value());
        }

        synchronized void update(UUID id, SampleTicket token, SubjectValue value) {
            if (pending.get(id) != token) {
                return;
            }
            if (!value.equals(values.put(id, value))) {
                dirty = true;
            }
        }

        private synchronized void remove(UUID id, SampleTicket token) {
            if (pending.get(id) == token) {
                dirty |= values.remove(id) != null;
            }
        }

        synchronized void forget(UUID id) {
            pending.remove(id);
            dirty |= values.remove(id) != null;
        }

        synchronized Map<String, BoardObjectiveRenderer.Value> snapshot() {
            if (dirty) {
                Map<String, BoardObjectiveRenderer.Value> copied = new HashMap<>(values.size());
                for (SubjectValue value : values.values()) {
                    copied.put(value.name(), value.value());
                }
                snapshot = Map.copyOf(copied);
                dirty = false;
            }
            return snapshot;
        }
    }
}
