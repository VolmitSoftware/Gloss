package art.arcane.gloss.velocity;

import art.arcane.gloss.expr.ExprEvaluator;
import art.arcane.gloss.expr.ExpressionScope;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.netty.channel.ChannelHelper;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.protocol.player.User;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.player.TabList;
import com.velocitypowered.api.proxy.player.TabListEntry;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ProxyTablists implements AutoCloseable {
    private final ProxyServer proxy;
    private final ProxyText text;
    private final Logger logger;
    private final Map<UUID, ViewerState> viewers = new ConcurrentHashMap<>();
    private final Set<UUID> failedPlayers = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    public ProxyTablists(ProxyServer proxy, ProxyText text, Logger logger) {
        this.proxy = proxy;
        this.text = text;
        this.logger = logger;
    }

    @Override
    public void close() {
        closed = true;
        for (ViewerState state : viewers.values()) {
            execute(state.player, state.user, () -> clearNow(state.player, state.user));
        }
    }

    public void render(Player viewer, ProxyDocuments.Snapshot snapshot) {
        if (closed) {
            return;
        }
        User user = PacketEvents.getAPI().getPlayerManager().getUser(viewer);
        if (user != null) {
            execute(viewer, user, () -> {
                if (!closed && ready(viewer, user)) {
                    renderNow(viewer, user, snapshot);
                }
            });
        }
    }

    public void clear(Player viewer) {
        User user = PacketEvents.getAPI().getPlayerManager().getUser(viewer);
        if (user != null) {
            execute(viewer, user, () -> clearNow(viewer, user));
        }
    }

    public void forget(UUID id) {
        ViewerState state = viewers.get(id);
        Player viewer = state == null ? proxy.getPlayer(id).orElse(null) : state.player;
        User user = state == null && viewer != null
            ? PacketEvents.getAPI().getPlayerManager().getUser(viewer) : state == null ? null : state.user;
        if (user == null || viewer == null) {
            viewers.remove(id);
            failedPlayers.remove(id);
            return;
        }
        execute(viewer, user, () -> {
            clearNow(viewer, user);
            viewers.remove(id);
            failedPlayers.remove(id);
        });
    }

    private void renderNow(Player viewer, User user, ProxyDocuments.Snapshot snapshot) {
        ProxyDocuments.Tablist document = snapshot.tablist();
        ExpressionScope scope = text.scope(viewer, viewer);
        if (!snapshot.settings().tablist() || !text.test(document.show(), scope)) {
            clearNow(viewer, user);
            return;
        }
        ViewerState state = viewers.computeIfAbsent(viewer.getUniqueId(), ignored -> new ViewerState(viewer, user));
        ProxyDocuments.HeaderFooter presentation = text.select(document.headerFooter(), scope);
        if (presentation == null) {
            if (state.header != null) {
                viewer.sendPlayerListHeaderAndFooter(Component.empty(), Component.empty());
                state.header = null;
                state.footer = null;
            }
        } else {
            Component header = text.render(presentation.header(), scope);
            Component footer = text.render(presentation.footer(), scope);
            if (!header.equals(state.header) || !footer.equals(state.footer)) {
                viewer.sendPlayerListHeaderAndFooter(header, footer);
                state.header = header;
                state.footer = footer;
            }
        }
        TabList tab = viewer.getTabList();
        Set<UUID> desired = new HashSet<>();
        for (Player subject : proxy.getAllPlayers()) {
            if (!subject.isActive() || subject.getCurrentServer().isEmpty()) {
                continue;
            }
            if (!snapshot.settings().networkTablist() && !sameServer(viewer, subject)) {
                continue;
            }
            desired.add(subject.getUniqueId());
            ExpressionScope subjectScope = text.scope(viewer, subject);
            ProxyDocuments.ListName name = text.select(document.listNames(), subjectScope);
            TabListEntry entry = tab.getEntry(subject.getUniqueId()).orElse(null);
            if (entry == null && !snapshot.settings().networkTablist()) {
                continue;
            }
            boolean added = entry == null;
            if (added) {
                entry = TabListEntry.builder().tabList(tab).profile(subject.getGameProfile())
                    .latency((int) Math.clamp(subject.getPing(), 0, Integer.MAX_VALUE)).gameMode(0).build();
                tab.addEntry(entry);
            }
            Original original = state.entries.get(subject.getUniqueId());
            if (original == null || original.entry != entry) {
                original = new Original(added, entry);
                state.entries.put(subject.getUniqueId(), original);
            }
            if (name != null) {
                if (!original.nameOwned) {
                    original.name = entry.getDisplayNameComponent().orElse(null);
                    original.nameOwned = true;
                }
                Component display = text.render(name.format(), subjectScope);
                if (!Objects.equals(entry.getDisplayNameComponent().orElse(null), display)) {
                    entry.setDisplayName(display);
                }
            } else if (original.nameOwned) {
                entry.setDisplayName(original.name);
                original.nameOwned = false;
            }
            if (document.sortWeight() != null) {
                if (!original.orderOwned) {
                    original.order = entry.getListOrder();
                    original.orderOwned = true;
                }
                int order = (int) Math.clamp(ExprEvaluator.number(document.sortWeight(), subjectScope),
                    Integer.MIN_VALUE, Integer.MAX_VALUE);
                if (entry.getListOrder() != order) {
                    entry.setListOrder(order);
                }
            } else if (original.orderOwned) {
                entry.setListOrder(original.order);
                original.orderOwned = false;
            }
            if (original.added) {
                int ping = (int) Math.clamp(subject.getPing(), 0, Integer.MAX_VALUE);
                if (entry.getLatency() != ping) {
                    entry.setLatency(ping);
                }
            }
        }
        List<UUID> removed = new ArrayList<>();
        for (Map.Entry<UUID, Original> entry : state.entries.entrySet()) {
            if (!desired.contains(entry.getKey())) {
                restore(viewer, entry.getKey(), entry.getValue());
                removed.add(entry.getKey());
            }
        }
        for (UUID id : removed) {
            state.entries.remove(id);
        }
    }

    private void clearNow(Player viewer, User user) {
        if (viewer.isActive() && user.getEncoderState() != ConnectionState.PLAY) {
            return;
        }
        ViewerState state = viewers.remove(viewer.getUniqueId());
        if (state == null || !ready(viewer, user)) {
            return;
        }
        if (state.header != null) {
            viewer.sendPlayerListHeaderAndFooter(Component.empty(), Component.empty());
        }
        for (Map.Entry<UUID, Original> entry : state.entries.entrySet()) {
            restore(viewer, entry.getKey(), entry.getValue());
        }
    }

    private void execute(Player viewer, User user, Runnable action) {
        try {
            ChannelHelper.runInEventLoop(user.getChannel(), () -> {
                try {
                    action.run();
                } catch (RuntimeException failure) {
                    report(viewer, failure);
                }
            });
        } catch (RuntimeException failure) {
            report(viewer, failure);
        }
    }

    private void report(Player viewer, RuntimeException failure) {
        if (failedPlayers.add(viewer.getUniqueId())) {
            logger.error("Could not update proxy tablist for " + viewer.getUsername(), failure);
        }
    }

    private static boolean ready(Player viewer, User user) {
        return viewer.isActive() && user.getEncoderState() == ConnectionState.PLAY
            && ChannelHelper.isOpen(user.getChannel());
    }

    private static boolean sameServer(Player viewer, Player subject) {
        return viewer.getCurrentServer().map(connection -> connection.getServerInfo())
            .equals(subject.getCurrentServer().map(connection -> connection.getServerInfo()));
    }

    private void restore(Player viewer, UUID id, Original original) {
        TabList tab = viewer.getTabList();
        if (tab.getEntry(id).orElse(null) != original.entry) {
            return;
        }
        boolean local = proxy.getPlayer(id).map(subject -> sameServer(viewer, subject)).orElse(false);
        if (original.added && !local) {
            tab.removeEntry(id);
        } else {
            tab.getEntry(id).ifPresent(entry -> {
                if (original.nameOwned) {
                    entry.setDisplayName(original.name);
                }
                if (original.orderOwned) {
                    entry.setListOrder(original.order);
                }
            });
        }
    }

    private static final class Original {
        private final boolean added;
        private final TabListEntry entry;
        private Component name;
        private int order;
        private boolean nameOwned;
        private boolean orderOwned;

        private Original(boolean added, TabListEntry entry) {
            this.added = added;
            this.entry = entry;
        }
    }

    private static final class ViewerState {
        private final Player player;
        private final User user;
        private final Map<UUID, Original> entries = new HashMap<>();
        private Component header;
        private Component footer;

        private ViewerState(Player player, User user) {
            this.player = player;
            this.user = user;
        }
    }
}
