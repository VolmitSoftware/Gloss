package art.arcane.gloss.velocity;

import art.arcane.gloss.expr.ExpressionScope;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Broadcasts the network's join, server-switch and leave messages from {@code connections.json}.
 */
public final class ProxyConnections {
    private final ProxyServer proxy;
    private final ProxyText text;
    private final Logger logger;
    private final Map<UUID, String> backends = new ConcurrentHashMap<>();
    private volatile boolean failureLogged;

    public ProxyConnections(ProxyServer proxy, ProxyText text, Logger logger) {
        this.proxy = proxy;
        this.text = text;
        this.logger = logger;
    }

    public void joined(Player player, ProxyDocuments.Snapshot snapshot) {
        String destination = remember(player);
        announce(snapshot, snapshot.connections().join(), player, null, destination, true);
    }

    public void switched(Player player, String previousServer, ProxyDocuments.Snapshot snapshot) {
        String destination = remember(player);
        announce(snapshot, snapshot.connections().switched(), player, previousServer, destination, true);
    }

    /**
     * A disconnecting player no longer reports a backend, so the leave message names the last one
     * {@link #joined} or {@link #switched} recorded. Only a completed login is announced; a kicked
     * or cancelled connection never produced a join message either.
     */
    public void left(DisconnectEvent event, ProxyDocuments.Snapshot snapshot) {
        Player player = event.getPlayer();
        String origin = backends.remove(player.getUniqueId());
        if (event.getLoginStatus() != DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN) {
            return;
        }
        announce(snapshot, snapshot.connections().leave(), player, origin, null, false);
    }

    private String remember(Player player) {
        String server = backend(player);
        if (server == null) {
            backends.remove(player.getUniqueId());
            return null;
        }
        backends.put(player.getUniqueId(), server);
        return server;
    }

    private void announce(ProxyDocuments.Snapshot snapshot, ProxyConnectionDocuments.Section section,
                          Player subject, String origin, String destination, boolean includeSubject) {
        if (!snapshot.settings().connections() || !section.enabled()) {
            return;
        }
        ExpressionScope gate = new ConnectionScope(text.scope(subject, subject), origin, destination);
        if (!text.test(snapshot.connections().show(), gate) || !text.test(section.show(), gate)) {
            return;
        }
        for (Player recipient : proxy.getAllPlayers()) {
            if (!includeSubject && recipient.getUniqueId().equals(subject.getUniqueId())) {
                continue;
            }
            if (!inAudience(section.audience(), recipient, origin, destination)) {
                continue;
            }
            deliver(recipient, section, subject, origin, destination);
        }
    }

    private void deliver(Player recipient, ProxyConnectionDocuments.Section section, Player subject,
                         String origin, String destination) {
        try {
            ExpressionScope scope = new ConnectionScope(text.scope(recipient, subject), origin, destination);
            String template = presentation(section, scope).text();
            if (!template.isEmpty()) {
                recipient.sendMessage(text.render(template, scope));
            }
        } catch (RuntimeException failure) {
            if (!failureLogged) {
                failureLogged = true;
                logger.error("Gloss connection message failed for {}.", recipient.getUsername(), failure);
            }
        }
    }

    private ProxyConnectionDocuments.Presentation presentation(ProxyConnectionDocuments.Section section,
                                                               ExpressionScope scope) {
        for (ProxyDocuments.Variant<ProxyConnectionDocuments.Presentation> variant : section.variants()) {
            if (text.test(variant.when(), scope)) {
                return variant.presentation();
            }
        }
        return section.presentation();
    }

    private static boolean inAudience(String audience, Player recipient, String origin, String destination) {
        if (!ProxyConnectionDocuments.SERVER.equals(audience)) {
            return true;
        }
        String server = backend(recipient);
        return server != null && (server.equals(origin) || server.equals(destination));
    }

    private static String backend(Player player) {
        return player.getCurrentServer().map(connection -> connection.getServerInfo().getName()).orElse(null);
    }

    /** Answers the backend names a connection message needs; everything else falls to the base scope. */
    private record ConnectionScope(ExpressionScope delegate, String origin, String destination)
        implements ExpressionScope {
        @Override
        public Object variable(String name) {
            return switch (name) {
                case "connection.from" -> origin == null ? "" : origin;
                case "connection.to" -> destination == null ? "" : destination;
                default -> delegate.variable(name);
            };
        }

        @Override
        public Object call(String name, List<Object> arguments) {
            return delegate.call(name, arguments);
        }
    }
}
