package art.arcane.gloss.inventory;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

final class InventoryToggleState {
    private final Map<Key, State> states = new HashMap<>();

    boolean resolve(boolean list, int slot, Object binding, boolean dynamic, BooleanSupplier initial) {
        Key key = new Key(list, slot);
        State previous = states.get(key);
        if (previous == null || !Objects.equals(previous.binding(), binding) || dynamic) {
            previous = new State(binding, initial.getAsBoolean());
            states.put(key, previous);
        }
        return previous.value();
    }

    boolean current(boolean list, int slot) {
        State state = states.get(new Key(list, slot));
        return state != null && state.value();
    }

    void flip(boolean list, int slot) {
        Key key = new Key(list, slot);
        State state = states.get(key);
        if (state != null) {
            states.put(key, new State(state.binding(), !state.value()));
        }
    }

    void clear() {
        states.clear();
    }

    private record Key(boolean list, int slot) {
    }

    private record State(Object binding, boolean value) {
    }
}
