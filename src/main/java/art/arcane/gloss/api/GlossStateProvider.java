package art.arcane.gloss.api;

import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Gloss behavior state for other plugins: read and write per-player and global keys, fire an
 * {@code emit} trigger, and declare keys of your own. Keys are shared with behavior documents; a
 * declaration that disagrees with an existing one is refused with {@link IllegalStateException},
 * and writing an undeclared key or the wrong scope is refused with {@link IllegalArgumentException}.
 * Values are a {@code Double}, a {@code String} or a {@code Boolean}.
 */
public interface GlossStateProvider {
    Object get(UUID player, String key);

    void set(UUID player, String key, Object value);

    Object global(String key);

    void setGlobal(String key, Object value);

    void emit(String name, Map<String, Object> args);

    void declare(Plugin plugin, List<GlossStateSpec> specs);
}
