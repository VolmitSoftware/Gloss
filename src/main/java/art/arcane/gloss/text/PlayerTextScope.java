package art.arcane.gloss.text;

import art.arcane.gloss.expr.ExprScope;
import art.arcane.gloss.expr.ExprVariableContext;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;

final class PlayerTextScope implements ExprScope {
    private final ExprScope delegate;
    private final Player viewer;
    private final BiFunction<Player, Player, String> names;

    PlayerTextScope(ExprScope delegate, Player viewer, BiFunction<Player, Player, String> names) {
        this.delegate = delegate;
        this.viewer = viewer;
        this.names = names;
    }

    @Override
    public Object variable(String name) {
        int separator = name.lastIndexOf('.');
        if (separator >= 0) {
            String property = name.substring(separator + 1);
            if (property.equals("name") || property.equals("displayName") || property.equals("username")) {
                Entity entity = role(name.substring(0, separator));
                if (entity == null && (name.startsWith("leaderboard.") || name.startsWith("source.")
                    || name.startsWith("subject."))) {
                    Object uuid = delegate.variable(name.substring(0, separator) + ".uuid");
                    if (uuid instanceof String id && !id.isBlank()) {
                        entity = Bukkit.getPlayer(UUID.fromString(id));
                    }
                }
                if (entity instanceof Player player) {
                    return property.equals("username") ? player.getName() : names.apply(viewer, player);
                }
            }
        }
        return delegate.variable(name);
    }

    @Override
    public Object call(String name, List<Object> args) {
        if (name.equals("papi") && !args.isEmpty()) {
            int keyIndex = args.size() >= 3 ? 1 : 0;
            if (args.get(keyIndex) instanceof String key
                && (key.equals("player_name") || key.equals("%player_name%")
                || key.equals("player_displayname") || key.equals("%player_displayname%"))) {
                Entity entity = role(keyIndex == 0 ? "viewer" : String.valueOf(args.getFirst()));
                if (entity instanceof Player player) {
                    return names.apply(viewer, player);
                }
            }
        }
        return delegate.call(name, args);
    }

    @Override
    public ExprVariableContext variableContext() {
        return delegate.variableContext();
    }

    private Entity role(String role) {
        ExprVariableContext context = delegate.variableContext();
        return switch (role) {
            case "player", "viewer" -> context.viewer() == null ? viewer : context.viewer();
            case "subject" -> context.subject();
            case "source", "sender" -> context.source();
            default -> null;
        };
    }
}
