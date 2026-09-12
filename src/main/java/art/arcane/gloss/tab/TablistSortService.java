package art.arcane.gloss.tab;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.condition.BoundedConditionErrorCallback;
import art.arcane.gloss.expr.Expr;
import art.arcane.gloss.expr.ExprEvaluator;
import art.arcane.gloss.expr.ExprScope;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import art.arcane.gloss.util.common.PacketUtils;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orders the tablist by an authored weight. A weight that never reads {@code viewer.*} is computed
 * once per subject and broadcast; one that does is computed per viewer. Either way only the entries
 * whose order actually changed are sent.
 */
public final class TablistSortService {
    private final ListOrderSink sink;
    private final Map<UUID, Integer> lastOrder = new ConcurrentHashMap<>();
    private final Map<UUID, Map<UUID, Integer>> lastViewerOrder = new ConcurrentHashMap<>();

    public TablistSortService() {
        this(TablistSortService::sendListOrder);
    }

    TablistSortService(ListOrderSink sink) {
        this.sink = sink;
    }

    /** Rounds a weight into the protocol's signed 32-bit list order; a broken weight sorts as zero. */
    public static int listOrder(double weight) {
        if (Double.isNaN(weight)) {
            return 0;
        }
        double rounded = Math.round(weight);
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, rounded));
    }

    public void pass(TablistRuntime runtime, List<Player> players, ScopeFactory scopes,
                     BoundedConditionErrorCallback errors) {
        Expr weight = runtime.sortWeight();
        if (weight == null || players.isEmpty()) {
            return;
        }
        if (runtime.sortViewerDependent()) {
            passPerViewer(weight, players, scopes, errors);
            return;
        }
        Map<UUID, Integer> changed = new LinkedHashMap<>();
        for (Player subject : players) {
            int order = evaluate(weight, scopes.scope(subject, subject), errors);
            Integer previous = lastOrder.put(subject.getUniqueId(), order);
            if (previous == null || previous.intValue() != order) {
                changed.put(subject.getUniqueId(), order);
            }
        }
        if (!changed.isEmpty()) {
            sink.order(players, changed);
        }
    }

    public void forget(UUID playerId) {
        lastOrder.remove(playerId);
        lastViewerOrder.remove(playerId);
        for (Map<UUID, Integer> perViewer : lastViewerOrder.values()) {
            perViewer.remove(playerId);
        }
    }

    public void clear() {
        lastOrder.clear();
        lastViewerOrder.clear();
    }

    private void passPerViewer(Expr weight, List<Player> players, ScopeFactory scopes,
                               BoundedConditionErrorCallback errors) {
        for (Player viewer : players) {
            Map<UUID, Integer> seen = lastViewerOrder.computeIfAbsent(viewer.getUniqueId(),
                key -> new ConcurrentHashMap<>());
            Map<UUID, Integer> changed = new LinkedHashMap<>();
            for (Player subject : players) {
                int order = evaluate(weight, scopes.scope(viewer, subject), errors);
                Integer previous = seen.put(subject.getUniqueId(), order);
                if (previous == null || previous.intValue() != order) {
                    changed.put(subject.getUniqueId(), order);
                }
            }
            if (!changed.isEmpty()) {
                sink.order(List.of(viewer), changed);
            }
        }
    }

    private static int evaluate(Expr weight, ExprScope scope, BoundedConditionErrorCallback errors) {
        try {
            return listOrder(ExprEvaluator.number(weight, scope));
        } catch (RuntimeException failure) {
            Gloss.logExceptionStackThrottled(false, "tablist-sort-weight", failure,
                "Tablist sort weight failed and was treated as 0.");
            return 0;
        }
    }

    private static void sendListOrder(List<Player> viewers, Map<UUID, Integer> orders) {
        List<WrapperPlayServerPlayerInfoUpdate.PlayerInfo> entries = new ArrayList<>(orders.size());
        for (Map.Entry<UUID, Integer> entry : orders.entrySet()) {
            UserProfile profile = new UserProfile(entry.getKey(), null);
            WrapperPlayServerPlayerInfoUpdate.PlayerInfo info =
                new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(profile);
            info.setListOrder(entry.getValue());
            entries.add(info);
        }
        WrapperPlayServerPlayerInfoUpdate packet = new WrapperPlayServerPlayerInfoUpdate(
            EnumSet.of(WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LIST_ORDER), entries);
        if (PacketEvents.getAPI() == null) {
            return;
        }
        PacketUtils.broadcast(viewers, packet, () -> true);
    }

    /** The viewer/subject pair a weight is evaluated against. */
    @FunctionalInterface
    public interface ScopeFactory {
        ExprScope scope(Player viewer, Player subject);
    }

    @FunctionalInterface
    interface ListOrderSink {
        void order(List<Player> viewers, Map<UUID, Integer> orders);
    }
}
