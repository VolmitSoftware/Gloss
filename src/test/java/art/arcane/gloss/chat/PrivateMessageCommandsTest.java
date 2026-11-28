package art.arcane.gloss.chat;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.menu.CharacterizationSupport;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PrivateMessageCommandsTest {
    private static final List<String> LABELS = List.of("msg", "tell", "w", "r");

    private final Map<String, Command> known = new HashMap<>();
    private final CommandMap map = commandMap(known);

    @Test
    void withNoOtherPluginGlossRegistersTheLabelsAndReleasesThem() throws Exception {
        PrivateMessageCommands commands = commands();

        PrivateMessageOwnership ownership = commands.apply(true);

        assertEquals(LABELS, ownership.labels());
        for (String label : LABELS) {
            assertInstanceOf(PrivateMessageCommand.class, known.get(label), label);
            assertInstanceOf(PrivateMessageCommand.class, known.get("gloss:" + label), label);
        }

        commands.release();

        assertTrue(known.isEmpty(), known.toString());
    }

    @Test
    void glossReplacesVanillaLabelsAndHandsThemBackOnRelease() throws Exception {
        Command vanilla = vanilla("msg", List.of("tell", "w"));
        PrivateMessageCommands commands = commands();

        commands.apply(true);

        assertInstanceOf(PrivateMessageCommand.class, known.get("msg"));
        assertInstanceOf(PrivateMessageCommand.class, known.get("tell"));

        commands.release();

        assertSame(vanilla, known.get("msg"));
        assertSame(vanilla, known.get("tell"));
        assertSame(vanilla, known.get("w"));
    }

    @Test
    void autoLeavesAnotherPluginHoldingEveryLabelItHas() throws Exception {
        Command message = owned("Essentials", "msg", List.of("tell", "whisper", "w"));
        Command reply = owned("Essentials", "reply", List.of("r"));
        PrivateMessageCommands commands = commands();

        PrivateMessageOwnership ownership = commands.apply(true);

        assertEquals(List.of(), ownership.labels());
        assertSame(message, known.get("msg"));
        assertSame(message, known.get("tell"));
        assertSame(message, known.get("w"));
        assertSame(reply, known.get("r"));
        assertFalse(known.containsKey("gloss:msg"));
        assertFalse(known.containsKey("gloss:r"));
    }

    @Test
    void autoKeepsTheFreeLabelsButDropsReplyWithMessage() throws Exception {
        Command message = owned("Other", "msg", List.of());
        PrivateMessageCommands commands = commands();

        PrivateMessageOwnership ownership = commands.apply(true);

        assertEquals(List.of("tell", "w"), ownership.labels());
        assertSame(message, known.get("msg"));
        assertInstanceOf(PrivateMessageCommand.class, known.get("tell"));
        assertInstanceOf(PrivateMessageCommand.class, known.get("w"));
        assertFalse(known.containsKey("r"));
    }

    @Test
    void channelsOffRegistersNothingAndTurningThemOffHandsTheLabelsBack() throws Exception {
        Command vanilla = vanilla("msg", List.of("tell", "w"));
        PrivateMessageCommands commands = commands();

        PrivateMessageOwnership off = commands.apply(false);

        assertEquals(List.of(), off.labels());
        assertSame(vanilla, known.get("msg"));
        assertFalse(known.containsKey("r"));

        commands.apply(true);

        for (String label : LABELS) {
            assertInstanceOf(PrivateMessageCommand.class, known.get(label), label);
        }

        commands.apply(false);

        assertSame(vanilla, known.get("msg"));
        assertSame(vanilla, known.get("tell"));
        assertSame(vanilla, known.get("w"));
        assertFalse(known.containsKey("r"));
        assertFalse(known.containsKey("gloss:msg"));
    }

    private PrivateMessageCommands commands() throws ReflectiveOperationException {
        Server server = mock(Server.class);
        when(server.getLogger()).thenReturn(CharacterizationSupport.mutedLogger());
        Gloss gloss = CharacterizationSupport.bareGloss(server);
        return new PrivateMessageCommands(gloss, new ChatCommands(gloss, null), map, known);
    }

    private Command vanilla(String name, List<String> aliases) {
        Command command = new Command(name, "", "", aliases) {
            @Override
            public boolean execute(@NotNull CommandSender sender, @NotNull String label, @NotNull String[] args) {
                return true;
            }
        };
        map.register("minecraft", command);
        return command;
    }

    private Command owned(String owner, String name, List<String> aliases) {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn(owner);
        Command command = new OwnedCommand(name, aliases, plugin);
        map.register(owner.toLowerCase(Locale.ROOT), command);
        return command;
    }

    private static CommandMap commandMap(Map<String, Command> known) {
        CommandMap map = mock(CommandMap.class);
        doAnswer(invocation -> {
            String prefix = invocation.getArgument(0);
            Command command = invocation.getArgument(1);
            known.put(prefix + ":" + command.getName(), command);
            known.putIfAbsent(command.getName(), command);
            for (String alias : command.getAliases()) {
                known.putIfAbsent(alias, command);
            }
            command.register(map);
            return true;
        }).when(map).register(anyString(), any(Command.class));
        return map;
    }

    private static final class OwnedCommand extends Command implements PluginIdentifiableCommand {
        private final Plugin owner;

        private OwnedCommand(String name, List<String> aliases, Plugin owner) {
            super(name, "", "", aliases);
            this.owner = owner;
        }

        @Override
        public boolean execute(@NotNull CommandSender sender, @NotNull String label, @NotNull String[] args) {
            return true;
        }

        @Override
        public @NotNull Plugin getPlugin() {
            return owner;
        }
    }
}
