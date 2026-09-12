package art.arcane.gloss.behavior;

import art.arcane.gloss.Gloss;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Objects;

/**
 * Routes {@code emit} events to the behavior subscriptions. The sink is installed by the behavior
 * service while it is enabled; an emit with no sink is dropped. A chain of emits that re-enters
 * itself stops at depth {@value #MAX_DEPTH} with a warning naming the emitter and the event.
 */
public final class EmitBus {
    public static final int MAX_DEPTH = 8;

    @FunctionalInterface
    public interface Sink {
        void emit(String name, Map<String, Object> args, Player viewer);
    }

    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);
    private static volatile Sink sink;

    private EmitBus() {
    }

    public static void install(Sink value) {
        sink = value;
    }

    public static void emit(String name, Map<String, Object> args, Player viewer) {
        emit(name, args, viewer, "api");
    }

    public static void emit(String name, Map<String, Object> args, Player viewer, String source) {
        Objects.requireNonNull(name, "name");
        Sink current = sink;
        if (current == null) {
            return;
        }
        int depth = DEPTH.get();
        if (depth >= MAX_DEPTH) {
            Gloss.warnThrottled("behavior-emit-depth",
                "emit \"%s\" from %s re-entered itself %d deep; dropping it.", name, source, depth);
            return;
        }
        DEPTH.set(depth + 1);
        try {
            current.emit(name, args == null ? Map.of() : args, viewer);
        } finally {
            DEPTH.set(depth);
        }
    }
}
