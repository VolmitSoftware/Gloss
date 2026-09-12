package art.arcane.gloss.state;

import art.arcane.gloss.api.GlossStateProvider;
import art.arcane.gloss.api.GlossStateSpec;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;

/** The {@link GlossStateProvider} other plugins reach through {@code GlossStateAccess}. */
public final class GlossStateBridge implements GlossStateProvider {
    private final StateStore store;
    private final BiConsumer<String, Map<String, Object>> emitter;

    public GlossStateBridge(StateStore store, BiConsumer<String, Map<String, Object>> emitter) {
        this.store = Objects.requireNonNull(store, "store");
        this.emitter = Objects.requireNonNull(emitter, "emitter");
    }

    @Override
    public Object get(UUID player, String key) {
        return store.get(StateScope.PLAYER, Objects.requireNonNull(player, "player"), key);
    }

    @Override
    public void set(UUID player, String key, Object value) {
        store.set(StateScope.PLAYER, Objects.requireNonNull(player, "player"), key, value);
    }

    @Override
    public Object global(String key) {
        return store.get(StateScope.GLOBAL, null, key);
    }

    @Override
    public void setGlobal(String key, Object value) {
        store.set(StateScope.GLOBAL, null, key, value);
    }

    @Override
    public void emit(String name, Map<String, Object> args) {
        emitter.accept(Objects.requireNonNull(name, "name"), args == null ? Map.of() : Map.copyOf(args));
    }

    @Override
    public void declare(Plugin plugin, List<GlossStateSpec> specs) {
        Objects.requireNonNull(plugin, "plugin");
        List<StateSchema> schemas = new ArrayList<>(specs.size());
        for (GlossStateSpec spec : specs) {
            schemas.add(new StateSchema(spec.key(), StateScope.parse(spec.scope()), StateType.parse(spec.type()),
                spec.defaultValue()));
        }
        store.declareExternal("plugin:" + plugin.getName(), schemas);
    }
}
