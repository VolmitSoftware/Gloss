package art.arcane.gloss.chat;

import art.arcane.gloss.Gloss;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.entity.Player;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registers the private message labels in the server command map on servers that loaded Gloss from
 * {@code plugin.yml}, so each label exists only while Gloss owns it.
 */
final class PrivateMessageCommands {
    static final String NAMESPACE = "gloss";

    private final Gloss plugin;
    private final ChatCommands executor;
    private final CommandMap commandMap;
    private final Map<String, Command> knownCommands;
    private final List<PrivateMessageCommand> registered = new ArrayList<>(PrivateMessageOwnership.LABELS.size());
    private final Map<String, Command> displaced = new LinkedHashMap<>();
    private boolean applied;

    PrivateMessageCommands(Gloss plugin, ChatCommands executor, CommandMap commandMap,
                           Map<String, Command> knownCommands) {
        this.plugin = plugin;
        this.executor = executor;
        this.commandMap = commandMap;
        this.knownCommands = knownCommands;
    }

    PrivateMessageOwnership apply(boolean channels) {
        PrivateMessageOwnership ownership;
        synchronized (commandMap) {
            unregisterOwned();
            ownership = PrivateMessageOwnership.resolve(channels, PrivateMessageOwnership.pluginHolders(knownCommands, plugin));
            claim(ownership.labels());
        }
        applied = true;
        refresh();
        return ownership;
    }

    void release() {
        if (!applied) {
            return;
        }
        synchronized (commandMap) {
            unregisterOwned();
        }
        applied = false;
        refresh();
    }

    private void claim(List<String> labels) {
        for (String label : labels) {
            PrivateMessageCommand command = new PrivateMessageCommand(plugin, executor, label);
            Command holder = knownCommands.get(label);
            if (holder != null) {
                displaced.putIfAbsent(label, holder);
            }
            commandMap.register(NAMESPACE, command);
            knownCommands.put(label, command);
            registered.add(command);
        }
    }

    private void unregisterOwned() {
        for (PrivateMessageCommand command : registered) {
            knownCommands.remove(command.getName(), command);
            knownCommands.remove(NAMESPACE + ":" + command.getName(), command);
            command.unregister(commandMap);
        }
        registered.clear();
        for (Map.Entry<String, Command> entry : displaced.entrySet()) {
            knownCommands.putIfAbsent(entry.getKey(), entry.getValue());
        }
        displaced.clear();
    }

    private void refresh() {
        if (!plugin.isEnabled()) {
            return;
        }
        Server server = plugin.getServer();
        if (!FoliaScheduler.isFoliaThreading(server)) {
            if (!FoliaScheduler.runGlobal(plugin, () -> synchronizeCommands(server), 1L)) {
                Gloss.warn("The command tree refresh after a private message ownership change could not be scheduled.");
            }
            return;
        }
        for (Player player : List.copyOf(server.getOnlinePlayers())) {
            if (!FoliaScheduler.runEntity(plugin, player, player::updateCommands)) {
                Gloss.warn("The command tree refresh for %s could not be scheduled.", player.getName());
            }
        }
    }

    private static void synchronizeCommands(Server server) {
        try {
            server.getClass().getMethod("syncCommands").invoke(server);
        } catch (NoSuchMethodException missing) {
            for (Player player : List.copyOf(server.getOnlinePlayers())) {
                player.updateCommands();
            }
        } catch (IllegalAccessException | InvocationTargetException failure) {
            Gloss.logExceptionStack(false, failure, "The server command tree could not be synchronized.");
        }
    }
}
