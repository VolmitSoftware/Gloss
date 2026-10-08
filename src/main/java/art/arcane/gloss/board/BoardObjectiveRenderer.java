package art.arcane.gloss.board;

import com.github.retrooper.packetevents.protocol.score.ScoreFormat;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDisplayScoreboard;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerResetScore;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerScoreboardObjective;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateScore;
import net.kyori.adventure.text.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

final class BoardObjectiveRenderer {
    private final SlotState playerList;
    private final SlotState belowName;

    BoardObjectiveRenderer() {
        String token = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        playerList = new SlotState(0, "glp" + token);
        belowName = new SlotState(2, "glb" + token);
    }

    synchronized boolean known(int position) {
        SlotState state = slot(position);
        return state != null && state.known;
    }

    synchronized void seedIfUnknown(int position, String objective) {
        SlotState state = slot(position);
        if (state != null && !state.known) {
            displayed(position, objective);
        }
    }

    synchronized void displayed(int position, String objective) {
        SlotState state = slot(position);
        if (state == null) {
            return;
        }
        state.known = true;
        state.displayed = objective == null ? "" : objective;
        if (!state.name.equals(state.displayed)) {
            state.foreign = state.displayed;
        }
    }

    synchronized void removed(String objective) {
        for (SlotState state : new SlotState[]{playerList, belowName}) {
            if (state.displayed.equals(objective)) {
                state.displayed = "";
                state.known = true;
            }
            if (state.foreign.equals(objective)) {
                state.foreign = "";
            }
            if (state.name.equals(objective)) {
                state.created = false;
                state.values = Map.of();
                state.definition = null;
            }
        }
    }

    synchronized int apply(Frame frame, Consumer<PacketWrapper<?>> sink) {
        int[] sent = {0};
        Consumer<PacketWrapper<?>> counted = packet -> {
            sink.accept(packet);
            sent[0]++;
        };
        render(playerList, frame == null ? null : frame.playerList(), counted);
        render(belowName, frame == null ? null : frame.belowName(), counted);
        return sent[0];
    }

    private SlotState slot(int position) {
        return switch (position) {
            case 0 -> playerList;
            case 2 -> belowName;
            default -> null;
        };
    }

    private void render(SlotState state, Prepared desired, Consumer<PacketWrapper<?>> sink) {
        if (desired == null || desired.definition().conflict().equals("yield")
            && (!state.known || !state.foreign.isEmpty())) {
            release(state, sink);
            return;
        }
        BoardObjectives.Slot definition = desired.definition();
        if (!state.created || !desired.title().equals(state.title)
            || state.definition == null || !definition.renderType().equals(state.definition.renderType())) {
            sink.accept(new WrapperPlayServerScoreboardObjective(state.name,
                state.created ? WrapperPlayServerScoreboardObjective.ObjectiveMode.UPDATE
                    : WrapperPlayServerScoreboardObjective.ObjectiveMode.CREATE,
                desired.title(), definition.renderType().equals("hearts")
                    ? WrapperPlayServerScoreboardObjective.RenderType.HEARTS
                    : WrapperPlayServerScoreboardObjective.RenderType.INTEGER));
            state.created = true;
            state.title = desired.title();
            state.definition = definition;
        }
        if (state.values != desired.values()) {
            Map<String, Value> applied = new HashMap<>(state.values);
            for (String name : state.values.keySet()) {
                if (!desired.values().containsKey(name)) {
                    sink.accept(new WrapperPlayServerResetScore(name, state.name));
                    applied.remove(name);
                }
            }
            for (Map.Entry<String, Value> entry : desired.values().entrySet()) {
                if (!entry.getValue().equals(applied.get(entry.getKey()))) {
                    Value value = entry.getValue();
                    ScoreFormat format = value.format().scoreFormat(value.text());
                    sink.accept(new WrapperPlayServerUpdateScore(entry.getKey(),
                        WrapperPlayServerUpdateScore.Action.CREATE_OR_UPDATE_ITEM, state.name, value.score(), null, format));
                    applied.put(entry.getKey(), value);
                }
            }
            state.values = desired.values();
        }
        if (!state.name.equals(state.displayed)) {
            sink.accept(new WrapperPlayServerDisplayScoreboard(state.position, state.name));
            state.displayed = state.name;
            state.known = true;
        }
        state.definition = definition;
    }

    private void release(SlotState state, Consumer<PacketWrapper<?>> sink) {
        if (!state.created) {
            return;
        }
        if (state.name.equals(state.displayed)) {
            sink.accept(new WrapperPlayServerDisplayScoreboard(state.position, state.foreign));
            state.displayed = state.foreign;
        }
        sink.accept(new WrapperPlayServerScoreboardObjective(state.name,
            WrapperPlayServerScoreboardObjective.ObjectiveMode.REMOVE, Component.empty(), null));
        state.created = false;
        state.title = null;
        state.values = Map.of();
        state.definition = null;
    }

    record Value(int score, BoardLineFormat format, String text) {
    }

    record Prepared(BoardObjectives.Slot definition, Component title, Map<String, Value> values) {
    }

    record Frame(Prepared playerList, Prepared belowName) {
    }

    private static final class SlotState {
        private final int position;
        private final String name;
        private boolean known;
        private String displayed = "";
        private String foreign = "";
        private boolean created;
        private Component title;
        private BoardObjectives.Slot definition;
        private Map<String, Value> values = Map.of();

        private SlotState(int position, String name) {
            this.position = position;
            this.name = name;
        }
    }
}
