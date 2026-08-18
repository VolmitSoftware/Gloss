package art.arcane.gloss.text;

import org.bukkit.entity.Player;

import java.util.function.Function;

public interface TextRenderer {
    String render(Player viewer, String raw);

    String renderStatic(String raw);

    String chat(Player sender, String message);

    void registerFunction(String name, Function<Player, String> resolver);

    void unregisterFunction(String name);
}
