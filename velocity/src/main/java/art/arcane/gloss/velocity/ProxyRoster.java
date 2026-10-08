package art.arcane.gloss.velocity;

import art.arcane.gloss.expr.ExpressionScope;
import art.arcane.gloss.tab.TablistLayoutDefinition;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.util.GameProfile;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ProxyRoster(Map<UUID, Subject> subjects, ProxyTablistVisibility visibility,
                          Map<String, TablistLayoutDefinition.Skin> textures) {
    public ProxyRoster {
        subjects = Map.copyOf(subjects);
        textures = Map.copyOf(textures);
    }

    public static ProxyRoster capture(Collection<Player> players, ProxyTablistVisibility visibility) {
        Map<UUID, Subject> subjects = new LinkedHashMap<>(players.size());
        Map<String, TablistLayoutDefinition.Skin> textures = new LinkedHashMap<>();
        for (Player player : players) {
            Subject subject = capture(player);
            if (subject != null) {
                subjects.put(player.getUniqueId(), subject);
                for (GameProfile.Property property : subject.profile().getProperties()) {
                    if (property.getName().equals("textures")) {
                        textures.put(subject.name(), new TablistLayoutDefinition.Skin(property.getValue(), property.getSignature()));
                        break;
                    }
                }
            }
        }
        return new ProxyRoster(subjects, visibility, textures);
    }

    public static Subject capture(Player player) {
        String server = player.getCurrentServer().map(connection -> connection.getServerInfo().getName()).orElse(null);
        return !player.isActive() || server == null ? null : new Subject(player, player.getUniqueId(),
            player.getUsername(), server, (int) Math.clamp(player.getPing(), 0, Integer.MAX_VALUE), player.getGameProfile());
    }

    public ExpressionScope scope(ProxyText text, Subject viewer, Subject subject) {
        ExpressionScope fallback = text.scope(viewer.player(), subject.player());
        return new ExpressionScope() {
            @Override
            public Object variable(String name) {
                if (name.startsWith("viewer.")) {
                    return value(viewer, viewer, name.substring(7));
                }
                if (name.startsWith("subject.") || name.startsWith("player.")) {
                    return value(viewer, subject, name.substring(name.indexOf('.') + 1));
                }
                return name.equals("server.online") ? (double) subjects.size() : fallback.variable(name);
            }

            @Override
            public Object call(String name, List<Object> arguments) {
                return fallback.call(name, arguments);
            }
        };
    }

    private Object value(Subject viewer, Subject subject, String field) {
        return switch (field) {
            case "present" -> true;
            case "bedrock" -> visibility.bedrock().contains(subject.id());
            case "npc" -> visibility.npcs().contains(subject.id());
            case "visible" -> visibility.visible(viewer.id(), subject.id());
            case "name" -> subject.name();
            case "uuid" -> subject.id().toString();
            case "ping" -> (double) subject.ping();
            case "server" -> subject.server();
            default -> null;
        };
    }

    public record Subject(Player player, UUID id, String name, String server, int ping, GameProfile profile) {
    }
}
