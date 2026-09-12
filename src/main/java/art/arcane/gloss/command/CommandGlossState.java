package art.arcane.gloss.command;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.locale.GlossMessages;
import art.arcane.gloss.state.StateSchema;
import art.arcane.gloss.state.StateScope;
import art.arcane.gloss.state.StateStore;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.director.help.DirectorMiniMenu;
import art.arcane.volmlib.util.localization.MessageArgument;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * {@code /gloss state}: reads and writes the same values the {@code state} namespace exposes, so an
 * operator can reproduce a conditional branch without editing a document. Every key must already be
 * declared by a behavior document; the store has no untyped slots.
 */
@Director(name = "state", descriptionKey = "command.help.state.root",
    description = "Read and write persisted behavior state")
public final class CommandGlossState {
    private final Gloss plugin;

    public CommandGlossState(Gloss plugin) {
        this.plugin = plugin;
    }

    @Director(name = "get", descriptionKey = "command.help.state.get", description = "Show one state value")
    public void get(@Param(name = "sender", contextual = true) CommandSender sender,
                    @Param(name = "key", descriptionKey = "command.help.state.key",
                        description = "Declared state key") String key,
                    @Param(name = "player", defaultValue = "*", descriptionKey = "command.help.state.player",
                        description = "Player the value belongs to, or * for yourself") String player) {
        Target target = target(sender, key, player);
        if (target == null) {
            return;
        }
        GlossCommandMessages.send(sender, GlossMessages.BEHAVIORS_STATE_VALUE,
            MessageArgument.untrusted("key", key),
            MessageArgument.untrusted("value", String.valueOf(target.store().get(
                target.schema().scope(), target.owner(), key))));
    }

    @Director(name = "set", sync = true, descriptionKey = "command.help.state.set",
        description = "Write one state value")
    public void set(@Param(name = "sender", contextual = true) CommandSender sender,
                    @Param(name = "key", descriptionKey = "command.help.state.key",
                        description = "Declared state key") String key,
                    @Param(name = "value", descriptionKey = "command.help.state.value",
                        description = "New value; quote it to include spaces") String value,
                    @Param(name = "player", defaultValue = "*", descriptionKey = "command.help.state.player",
                        description = "Player the value belongs to, or * for yourself") String player) {
        Target target = target(sender, key, player);
        if (target == null) {
            return;
        }
        Object typed;
        try {
            typed = target.schema().type().coerce(value);
        } catch (IllegalArgumentException invalid) {
            GlossCommandMessages.send(sender, GlossMessages.BEHAVIORS_STATE_INVALID,
                MessageArgument.untrusted("value", value),
                MessageArgument.untrusted("type", target.schema().type().key()));
            return;
        }
        target.store().set(target.schema().scope(), target.owner(), key, typed);
        GlossCommandMessages.send(sender, GlossMessages.BEHAVIORS_STATE_SET,
            MessageArgument.untrusted("key", key),
            MessageArgument.untrusted("value", String.valueOf(typed)));
    }

    @Director(name = "clear", sync = true, descriptionKey = "command.help.state.clear",
        description = "Reset one state value to its default")
    public void clear(@Param(name = "sender", contextual = true) CommandSender sender,
                      @Param(name = "key", descriptionKey = "command.help.state.key",
                          description = "Declared state key") String key,
                      @Param(name = "player", defaultValue = "*", descriptionKey = "command.help.state.player",
                          description = "Player the value belongs to, or * for yourself") String player) {
        Target target = target(sender, key, player);
        if (target == null) {
            return;
        }
        target.store().clear(target.schema().scope(), target.owner(), key);
        GlossCommandMessages.send(sender, GlossMessages.BEHAVIORS_STATE_CLEARED,
            MessageArgument.untrusted("key", key));
    }

    @Director(name = "dump", descriptionKey = "command.help.state.dump",
        description = "List every declared state key and its value")
    public void dump(@Param(name = "sender", contextual = true) CommandSender sender,
                     @Param(name = "player", defaultValue = "*", descriptionKey = "command.help.state.player",
                         description = "Player the value belongs to, or * for yourself") String player,
                     @Param(name = "page", defaultValue = "1", descriptionKey = "command.help.arg.list_page",
                         description = "One-based list page") int page) {
        if (GlossCommandMessages.denied(sender, "gloss.state")) {
            return;
        }
        StateStore store = store(sender);
        if (store == null) {
            return;
        }
        List<String> keys = new ArrayList<>(store.declarations().keys());
        keys.sort(String::compareTo);
        if (keys.isEmpty()) {
            GlossCommandMessages.send(sender, GlossMessages.BEHAVIORS_STATE_EMPTY);
            return;
        }
        Player viewer = viewer(sender, player);
        DirectorMiniMenu.ContentPage window = GlossCommandPager.window(
            keys.size(), page, GlossCommandPager.TEXT_PAGE_SIZE);
        for (String key : keys.subList(window.startIndex(), window.endIndex())) {
            StateSchema schema = store.declarations().get(key);
            UUID owner = owner(schema.scope(), viewer);
            String value = schema.scope() == StateScope.PLAYER && viewer == null
                ? "-" : String.valueOf(store.get(schema.scope(), owner, key));
            GlossCommandMessages.send(sender, GlossMessages.BEHAVIORS_STATE_VALUE,
                MessageArgument.untrusted("key", key),
                MessageArgument.untrusted("value", value));
        }
        GlossCommandPager.sendPageFooter(sender, window, "/gloss state dump " + player);
    }

    private Target target(CommandSender sender, String key, String player) {
        if (GlossCommandMessages.denied(sender, "gloss.state")) {
            return null;
        }
        StateStore store = store(sender);
        if (store == null) {
            return null;
        }
        StateSchema schema = store.declarations().get(key);
        if (schema == null) {
            GlossCommandMessages.send(sender, GlossMessages.BEHAVIORS_STATE_UNKNOWN,
                MessageArgument.untrusted("key", key));
            return null;
        }
        Player viewer = viewer(sender, player);
        if (viewer == null && !"*".equals(player)) {
            GlossCommandMessages.send(sender, GlossMessages.BEHAVIORS_PLAYER_MISSING,
                MessageArgument.untrusted("player", player));
            return null;
        }
        if (viewer == null && schema.scope() != StateScope.GLOBAL) {
            GlossCommandMessages.send(sender, GlossMessages.BEHAVIORS_STATE_PLAYER,
                MessageArgument.untrusted("key", key));
            return null;
        }
        return new Target(store, schema, owner(schema.scope(), viewer));
    }

    private StateStore store(CommandSender sender) {
        StateStore store = plugin == null ? null : plugin.service(StateStore.class);
        if (store == null) {
            GlossCommandMessages.send(sender, GlossMessages.BEHAVIORS_OFF);
        }
        return store;
    }

    private static UUID owner(StateScope scope, Player viewer) {
        return switch (scope) {
            case PLAYER -> viewer == null ? null : viewer.getUniqueId();
            case WORLD -> viewer == null ? null : viewer.getWorld().getUID();
            case GLOBAL -> null;
        };
    }

    private static Player viewer(CommandSender sender, String player) {
        if ("*".equals(player)) {
            return sender instanceof Player self ? self : null;
        }
        return Bukkit.getPlayerExact(player);
    }

    private record Target(StateStore store, StateSchema schema, UUID owner) {
    }
}
