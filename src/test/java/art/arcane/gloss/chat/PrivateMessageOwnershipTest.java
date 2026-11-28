package art.arcane.gloss.chat;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PrivateMessageOwnershipTest {
    private static final Function<String, Optional<String>> NOBODY = label -> Optional.empty();

    @Test
    void channelsOffRegistersNoPrivateMessageLabel() {
        PrivateMessageOwnership ownership = PrivateMessageOwnership.resolve(false, NOBODY);

        assertEquals(List.of(), ownership.labels());
        assertEquals("Chat channels are off, so Gloss does not register /msg, /tell, /w or /r.", ownership.describe());
    }

    @Test
    void channelsOnRegistersEveryLabelWhenNoOtherPluginHasThem() {
        PrivateMessageOwnership ownership = PrivateMessageOwnership.resolve(true, NOBODY);

        assertEquals(List.of("msg", "tell", "w", "r"), ownership.labels());
        assertEquals("Gloss registers /msg, /tell, /w and /r.", ownership.describe());
    }

    @Test
    void everyLabelAnotherPluginAlreadyHasIsLeftToIt() {
        PrivateMessageOwnership ownership = PrivateMessageOwnership.resolve(true,
            held(Map.of("msg", "Essentials", "tell", "Essentials", "w", "Essentials", "r", "Essentials")));

        assertEquals(List.of(), ownership.labels());
        assertEquals("Essentials already has /msg, /tell, /w and /r, so Gloss does not register them.",
            ownership.describe());
    }

    @Test
    void freeLabelsStayAndOnlyTheTakenOnesAreLeft() {
        PrivateMessageOwnership ownership = PrivateMessageOwnership.resolve(true, held(Map.of("tell", "Other")));

        assertEquals(List.of("msg", "w", "r"), ownership.labels());
        assertTrue(ownership.describe().endsWith("Gloss registers /msg, /w and /r."), ownership.describe());
    }

    @Test
    void replyIsLeftWheneverMessageIsLeft() {
        PrivateMessageOwnership ownership = PrivateMessageOwnership.resolve(true, held(Map.of("msg", "Other")));

        assertEquals(List.of("tell", "w"), ownership.labels());
        assertTrue(ownership.describe().contains("also leaves /r alone"), ownership.describe());
    }

    @Test
    void sameEffectComparesTheRegisteredLabels() {
        PrivateMessageOwnership free = PrivateMessageOwnership.resolve(true, NOBODY);
        PrivateMessageOwnership taken = PrivateMessageOwnership.resolve(true, held(Map.of("msg", "Other")));
        PrivateMessageOwnership off = PrivateMessageOwnership.resolve(false, NOBODY);
        PrivateMessageOwnership offTaken = PrivateMessageOwnership.resolve(false, held(Map.of("msg", "Other")));

        assertTrue(off.sameEffect(offTaken));
        assertFalse(free.sameEffect(taken));
        assertFalse(free.sameEffect(off));
        assertFalse(free.sameEffect(null));
    }

    @Test
    void pluginHoldersIgnoreGlossAndCommandsWithoutAPlugin() {
        Plugin gloss = plugin("Gloss");
        Map<String, Command> known = new HashMap<>();
        known.put("msg", new OwnedCommand("msg", plugin("Foundation")));
        known.put("tell", plain("tell"));
        known.put("w", new OwnedCommand("w", gloss));
        Function<String, Optional<String>> holders = PrivateMessageOwnership.pluginHolders(known, gloss);

        assertEquals(Optional.of("Foundation"), holders.apply("msg"));
        assertEquals(Optional.empty(), holders.apply("tell"));
        assertEquals(Optional.empty(), holders.apply("w"));
        assertEquals(Optional.empty(), holders.apply("r"));
    }

    private static Function<String, Optional<String>> held(Map<String, String> holders) {
        return label -> Optional.ofNullable(holders.get(label));
    }

    private static Plugin plugin(String name) {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn(name);
        return plugin;
    }

    private static Command plain(String name) {
        return new Command(name) {
            @Override
            public boolean execute(@NotNull CommandSender sender, @NotNull String label, @NotNull String[] args) {
                return true;
            }
        };
    }

    private static final class OwnedCommand extends Command implements PluginIdentifiableCommand {
        private final Plugin owner;

        private OwnedCommand(String name, Plugin owner) {
            super(name);
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
